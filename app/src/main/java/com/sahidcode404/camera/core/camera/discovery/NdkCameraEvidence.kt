package com.sahidcode404.camera.core.camera.discovery

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import com.sahidcode404.camera.core.camera.model.CameraFacing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class NdkCameraMetadataEvidence(
    val id: String,
    val status: Int,
    val facing: CameraFacing?,
    val hardwareLevel: Int?,
    val sensorOrientationDegrees: Int?,
    val focalLengthsMm: List<Float>,
    val availableCapabilities: List<Int>,
    val sensorPhysicalSizeMm: Pair<Float, Float>?,
    val pixelArraySize: Pair<Int, Int>?,
)

internal data class NdkEvidenceSnapshot(
    val available: Boolean,
    val truncated: Boolean,
    val error: String?,
    val cameras: List<NdkCameraMetadataEvidence>,
)

internal object NdkCameraEvidence {
    private val json = Json { ignoreUnknownKeys = false }
    private val nativeLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("camera_native") }.isSuccess
    }

    fun collect(): NdkEvidenceSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return NdkEvidenceSnapshot(false, false, "api-below-24", emptyList())
        }
        if (!nativeLoaded) {
            return NdkEvidenceSnapshot(false, false, "native-library-load-failed", emptyList())
        }
        val wire = runCatching {
            json.decodeFromString<NdkEvidenceWire>(nativeCollectEncoded())
        }.getOrElse { error ->
            return NdkEvidenceSnapshot(false, false, "decode-${error.javaClass.simpleName}", emptyList())
        }
        if (wire.schema != 1) {
            return NdkEvidenceSnapshot(false, wire.truncated, "unsupported-schema-${wire.schema}", emptyList())
        }
        return NdkEvidenceSnapshot(
            available = wire.available,
            truncated = wire.truncated,
            error = wire.error,
            cameras = wire.cameras.take(DiscoveryBounds.MAX_CAMERA_IDS).map { camera ->
                NdkCameraMetadataEvidence(
                    id = camera.id,
                    status = camera.status,
                    facing = mapFacing(camera.lensFacing),
                    hardwareLevel = camera.hardwareLevel,
                    sensorOrientationDegrees = camera.sensorOrientation,
                    focalLengthsMm = camera.focalLengthsMm.filter { it.isFinite() && it > 0f }
                        .take(DiscoveryBounds.MAX_FLOAT_METADATA_VALUES),
                    availableCapabilities = camera.availableCapabilities.take(DiscoveryBounds.MAX_INT_METADATA_VALUES),
                    sensorPhysicalSizeMm = camera.sensorPhysicalSizeMm?.takeIf { it.size >= 2 }
                        ?.let { it[0] to it[1] },
                    pixelArraySize = camera.pixelArraySize?.takeIf { it.size >= 2 }
                        ?.let { it[0] to it[1] },
                )
            },
        )
    }

    private fun mapFacing(value: Int?): CameraFacing? = when (value) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        null -> null
        else -> CameraFacing.UNKNOWN
    }

    private external fun nativeCollectEncoded(): String
}

@Serializable
private data class NdkEvidenceWire(
    val schema: Int,
    val available: Boolean,
    val truncated: Boolean,
    val error: String? = null,
    val cameras: List<NdkCameraWire> = emptyList(),
)

@Serializable
private data class NdkCameraWire(
    val id: String,
    val status: Int,
    val lensFacing: Int? = null,
    val hardwareLevel: Int? = null,
    val sensorOrientation: Int? = null,
    val focalLengthsMm: List<Float> = emptyList(),
    val availableCapabilities: List<Int> = emptyList(),
    val sensorPhysicalSizeMm: List<Float>? = null,
    val pixelArraySize: List<Int>? = null,
)
