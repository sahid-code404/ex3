package com.sahidcode404.camera.core.camera.session

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max

internal class BootstrapPreviewTargetResolver(private val cameraManager: CameraManager) {
    fun resolve(): PreviewTarget? {
        val ids = try {
            cameraManager.cameraIdList.filter { it.isNotBlank() }.distinct().take(MAX_CAMERA_IDS)
        } catch (_: CameraAccessException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        return ids.mapNotNull(::candidateOrNull)
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

    private fun candidateOrNull(cameraId: String): Candidate? {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: CameraAccessException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

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
        val facing = facing(characteristics.get(CameraCharacteristics.LENS_FACING))
        val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty().toList()
        val logicalDefault = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val cameraCapabilities = CameraCapabilities(
            facing = facing,
            focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                .orEmpty().filter { it > 0f }.take(MAX_FOCAL_LENGTHS),
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
            logicalCameraId = CameraTransportId(cameraId),
            physicalCameraId = null,
            routingMethod = if (logicalDefault) RoutingMethod.LOGICAL_DEFAULT else RoutingMethod.DIRECT,
        )
        val target = PreviewTarget(
            stableId = "bootstrap:$cameraId",
            route = route,
            streamSize = selected.size,
            facing = facing,
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

    private fun facing(value: Int?): CameraFacing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    private fun facingRank(facing: CameraFacing): Int = when (facing) {
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

    companion object {
        private const val MAX_CAMERA_IDS = 32
        private const val MAX_PREVIEW_SIZES = 64
        private const val MAX_FOCAL_LENGTHS = 16
        private const val TARGET_STANDARD_DIAGONAL_FOV_DEGREES = 75.0
        private const val UNKNOWN_FIELD_OF_VIEW_PENALTY = 10_000.0
    }
}
