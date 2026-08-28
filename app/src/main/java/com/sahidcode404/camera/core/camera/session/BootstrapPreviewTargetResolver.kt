package com.sahidcode404.camera.core.camera.session

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.sahidcode404.camera.core.camera.discovery.HotPreviewCacheStore
import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.FloatSizeValue
import com.sahidcode404.camera.core.camera.model.IntRectValue
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import com.sahidcode404.camera.core.camera.model.StreamKind
import com.sahidcode404.camera.core.camera.model.StreamSpec
import com.sahidcode404.camera.core.camera.preview.PreviewStreamSelector
import com.sahidcode404.camera.core.camera.topology.EnvironmentFingerprint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max

internal class BootstrapPreviewTargetResolver(
    context: Context,
    private val cameraManager: CameraManager,
) {
    private val hotCacheStore = HotPreviewCacheStore(context.applicationContext)

    fun resolve(): PreviewTarget? {
        val allIds = advertisedIdsOrNull() ?: return null
        val boundedIds = allIds.take(MAX_CAMERA_IDS)
        if (boundedIds.isEmpty()) return null

        if (allIds.size <= MAX_CAMERA_IDS) {
            val fingerprint = environmentFingerprint(boundedIds)
            val cached = hotCacheStore.loadOrNull(fingerprint)?.toPreviewTarget()
            if (cached != null) {
                val validated = validateCachedTarget(cached, boundedIds)
                if (validated != null) return validated
                hotCacheStore.invalidate()
            }
        }

        return boundedIds.mapNotNull(::candidateOrNull)
            .minWithOrNull(
                compareBy<Candidate>(
                    { facingRank(it.target.facing) },
                    { if (it.logicalDefault) 0 else 1 },
                    { it.standardFieldOfViewPenalty },
                    { -it.sensorAreaMm2 },
                    { it.target.streamSize.area },
                    { it.target.stableId },
                ),
            )
            ?.target
    }

    private fun validateCachedTarget(
        cached: PreviewTarget,
        advertisedIds: List<CameraTransportId>,
    ): PreviewTarget? {
        if (advertisedIds.none { it.value == cached.route.logicalCameraId.value }) return null

        val logicalCharacteristics = characteristicsOrNull(cached.route.logicalCameraId.value) ?: return null
        val physicalId = cached.route.physicalCameraId
        val sourceCharacteristics = if (physicalId != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            if (cached.route.routingMethod != RoutingMethod.LOGICAL_PHYSICAL_MEMBER) return null
            if (logicalCharacteristics.physicalCameraIds.none { it == physicalId.value }) return null
            characteristicsOrNull(physicalId.value) ?: return null
        } else {
            logicalCharacteristics
        }

        if (cached.route.routingMethod == RoutingMethod.LOGICAL_PHYSICAL_MEMBER && physicalId == null) return null
        if (cached.route.routingMethod == RoutingMethod.LOGICAL_DEFAULT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val caps = logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) return null
        }

        val streamMap = sourceCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val supported = try {
            streamMap.getOutputSizes(SurfaceTexture::class.java)
        } catch (_: IllegalArgumentException) {
            null
        }.orEmpty().any { size ->
            size.width == cached.streamSize.width && size.height == cached.streamSize.height
        }
        if (!supported) return null

        val active = sourceCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixel = sourceCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val rawAspect = when {
            active != null && active.width() > 0 && active.height() > 0 ->
                active.width().toDouble() / active.height().toDouble()
            pixel != null && pixel.width > 0 && pixel.height > 0 ->
                pixel.width.toDouble() / pixel.height.toDouble()
            else -> cached.sensorLandscapeAspect
        }
        if (!rawAspect.isFinite() || rawAspect <= 0.0) return null

        return cached.copy(
            facing = facing(sourceCharacteristics.get(CameraCharacteristics.LENS_FACING)),
            sensorOrientationDegrees = sourceCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cached.sensorOrientationDegrees,
            sensorLandscapeAspect = max(rawAspect, 1.0 / rawAspect),
        )
    }

    private fun candidateOrNull(id: CameraTransportId): Candidate? {
        val characteristics = characteristicsOrNull(id.value) ?: return null
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = try {
            streamMap.getOutputSizes(SurfaceTexture::class.java)
        } catch (_: IllegalArgumentException) {
            null
        }.orEmpty().filter { it.width > 0 && it.height > 0 }.take(MAX_PREVIEW_SIZES)
        if (sizes.isEmpty()) return null

        val streams = sizes.map { size ->
            val minFrameDurationNs = try {
                streamMap.getOutputMinFrameDuration(SurfaceTexture::class.java, size).takeIf { it > 0L }
            } catch (_: IllegalArgumentException) {
                null
            }
            StreamSpec(
                kind = StreamKind.PRIVATE_PREVIEW,
                size = IntSizeValue(size.width, size.height),
                minFrameDurationNs = minFrameDurationNs,
            )
        }

        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
            if (rect.width() > 0 && rect.height() > 0) {
                IntRectValue(rect.left, rect.top, rect.right, rect.bottom)
            } else {
                null
            }
        }
        val pixel = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { size ->
            if (size.width > 0 && size.height > 0) IntSizeValue(size.width, size.height) else null
        }
        val sensorPhysicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { size ->
            if (size.width > 0f && size.height > 0f) FloatSizeValue(size.width, size.height) else null
        }
        val cameraFacing = facing(characteristics.get(CameraCharacteristics.LENS_FACING))
        val availableCapabilities = (
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        ).toList()
        val logicalDefault = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val cameraCapabilities = CameraCapabilities(
            facing = cameraFacing,
            focalLengthsMm = (
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            ).filter { it > 0f }.take(MAX_FOCAL_LENGTHS),
            sensorPhysicalSizeMm = sensorPhysicalSize,
            pixelArraySize = pixel,
            activeArray = active,
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
            availableCapabilities = availableCapabilities,
            isLogicalMultiCamera = logicalDefault,
            streams = streams,
        )
        val selected = PreviewStreamSelector.select(cameraCapabilities) ?: return null
        val rawSensorAspect = PreviewStreamSelector.sensorAspect(cameraCapabilities)
            ?: selected.size.width.toDouble() / selected.size.height.toDouble()
        val route = CameraRoute(
            logicalCameraId = id,
            physicalCameraId = null,
            routingMethod = if (logicalDefault) RoutingMethod.LOGICAL_DEFAULT else RoutingMethod.DIRECT,
        )
        val target = PreviewTarget(
            stableId = "bootstrap:${id.value}",
            route = route,
            streamSize = selected.size,
            facing = cameraFacing,
            sensorOrientationDegrees = cameraCapabilities.sensorOrientationDegrees ?: 0,
            sensorLandscapeAspect = max(rawSensorAspect, 1.0 / rawSensorAspect),
        )
        return Candidate(
            target = target,
            logicalDefault = logicalDefault,
            standardFieldOfViewPenalty = standardFieldOfViewPenalty(cameraCapabilities),
            sensorAreaMm2 = sensorPhysicalSize?.let { it.width.toDouble() * it.height.toDouble() } ?: 0.0,
        )
    }

    private fun advertisedIdsOrNull(): List<CameraTransportId>? = try {
        cameraManager.cameraIdList
            .filter { it.isNotBlank() }
            .distinct()
            .map(::CameraTransportId)
    } catch (_: CameraAccessException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun characteristicsOrNull(cameraId: String): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(cameraId)
    } catch (_: CameraAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun environmentFingerprint(ids: Collection<CameraTransportId>): String =
        EnvironmentFingerprint.create(Build.FINGERPRINT, Build.VERSION.SDK_INT, ids)

    private fun standardFieldOfViewPenalty(capabilities: CameraCapabilities): Double {
        val sensor = capabilities.sensorPhysicalSizeMm ?: return UNKNOWN_FIELD_OF_VIEW_PENALTY
        val sensorDiagonal = hypot(sensor.width.toDouble(), sensor.height.toDouble())
        if (sensorDiagonal <= 0.0) return UNKNOWN_FIELD_OF_VIEW_PENALTY
        val focalLengths = capabilities.focalLengthsMm.filter { it > 0f }
        if (focalLengths.isEmpty()) return UNKNOWN_FIELD_OF_VIEW_PENALTY
        return focalLengths.minOf { focal ->
            val diagonalFovDegrees = 2.0 * atan(sensorDiagonal / (2.0 * focal.toDouble())) * 180.0 / PI
            abs(diagonalFovDegrees - TARGET_STANDARD_DIAGONAL_FOV_DEGREES)
        }
    }

    private fun facing(cameraFacing: Int?): CameraFacing = when (cameraFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    private fun facingRank(cameraFacing: CameraFacing): Int = when (cameraFacing) {
        CameraFacing.BACK -> 0
        CameraFacing.EXTERNAL -> 1
        CameraFacing.FRONT -> 2
        CameraFacing.UNKNOWN -> 3
    }

    private data class Candidate(
        val target: PreviewTarget,
        val logicalDefault: Boolean,
        val standardFieldOfViewPenalty: Double,
        val sensorAreaMm2: Double,
    )

    private companion object {
        const val MAX_CAMERA_IDS = 32
        const val MAX_PREVIEW_SIZES = 64
        const val MAX_FOCAL_LENGTHS = 16
        const val TARGET_STANDARD_DIAGONAL_FOV_DEGREES = 75.0
        const val UNKNOWN_FIELD_OF_VIEW_PENALTY = 10_000.0
    }
}
