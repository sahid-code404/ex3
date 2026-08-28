package com.sahidcode404.camera.core.camera.model

import kotlinx.serialization.Serializable

@Serializable
data class CameraTransportId(val value: String) {
    init {
        require(value.isNotBlank()) { "Camera transport ID must not be blank" }
    }
}

@Serializable
data class PhysicalCameraId(val value: String) {
    init {
        require(value.isNotBlank()) { "Physical camera ID must not be blank" }
    }
}

@Serializable
data class IntSizeValue(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive" }
    }

    val area: Long get() = width.toLong() * height.toLong()
}

@Serializable
data class FloatSizeValue(val width: Float, val height: Float) {
    init {
        require(width > 0f && height > 0f) { "Dimensions must be positive" }
    }
}

@Serializable
data class IntRectValue(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "Rectangle must have positive area" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
data class IntRangeValue(val lower: Int, val upper: Int) {
    init {
        require(lower <= upper) { "Invalid integer range" }
    }
}

@Serializable
data class LongRangeValue(val lower: Long, val upper: Long) {
    init {
        require(lower <= upper) { "Invalid long range" }
    }
}

@Serializable
enum class CameraFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

@Serializable
enum class RoutingMethod {
    DIRECT,
    LOGICAL_DEFAULT,
    LOGICAL_PHYSICAL_MEMBER,
}

@Serializable
enum class MetadataTrust {
    COMPLETE,
    PARTIAL,
    FAILED,
}

@Serializable
enum class PreviewTrust {
    ADVERTISED,
    PREVIEW_VERIFIED,
    TEMPORARILY_FAILED,
    STRUCTURALLY_UNUSABLE,
}

@Serializable
enum class RawTrust {
    UNSUPPORTED,
    ADVERTISED,
    RAW_VERIFIED,
    TEMPORARILY_FAILED,
    STRUCTURALLY_UNUSABLE,
}

@Serializable
enum class StreamKind {
    RAW_SENSOR,
    PRIVATE_PREVIEW,
    YUV_420_888,
}

@Serializable
data class StreamSpec(
    val kind: StreamKind,
    val size: IntSizeValue,
    val minFrameDurationNs: Long? = null,
    val stallDurationNs: Long? = null,
)

@Serializable
data class CameraRoute(
    val logicalCameraId: CameraTransportId,
    val physicalCameraId: PhysicalCameraId? = null,
    val routingMethod: RoutingMethod,
)

@Serializable
data class CameraCapabilities(
    val facing: CameraFacing = CameraFacing.UNKNOWN,
    val focalLengthsMm: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList(),
    val sensorPhysicalSizeMm: FloatSizeValue? = null,
    val pixelArraySize: IntSizeValue? = null,
    val activeArray: IntRectValue? = null,
    val preCorrectionActiveArray: IntRectValue? = null,
    val sensorOrientationDegrees: Int? = null,
    val colorFilterArrangement: Int? = null,
    val hardwareLevel: Int? = null,
    val availableCapabilities: List<Int> = emptyList(),
    val isLogicalMultiCamera: Boolean = false,
    val physicalMemberIds: List<PhysicalCameraId> = emptyList(),
    val streams: List<StreamSpec> = emptyList(),
    val aeTargetFpsRanges: List<IntRangeValue> = emptyList(),
    val sensitivityRange: IntRangeValue? = null,
    val exposureTimeRangeNs: LongRangeValue? = null,
    val maxAnalogSensitivity: Int? = null,
    val maxRawOutputStreams: Int? = null,
    val maxProcessedOutputStreams: Int? = null,
    val maxProcessedStallingOutputStreams: Int? = null,
    val maxInputStreams: Int? = null,
    val croppingType: Int? = null,
    val zoomRatioRange: FloatSizeValue? = null,
    val distortionCorrectionModes: List<Int> = emptyList(),
    val lensShadingMapModes: List<Int> = emptyList(),
    val opticalStabilizationModes: List<Int> = emptyList(),
    val videoStabilizationModes: List<Int> = emptyList(),
    val minimumFocusDistanceDiopters: Float? = null,
    val hyperfocalDistanceDiopters: Float? = null,
    val flashAvailable: Boolean? = null,
) {
    fun streams(kind: StreamKind): List<StreamSpec> = streams.filter { it.kind == kind }

    val hasRawSensorOutput: Boolean
        get() = streams.any { it.kind == StreamKind.RAW_SENSOR }
}

@Serializable
data class CameraProfile(
    val profileId: String,
    val route: CameraRoute,
    val capabilities: CameraCapabilities,
    val metadataTrust: MetadataTrust,
    val previewTrust: PreviewTrust = PreviewTrust.ADVERTISED,
    val rawTrust: RawTrust = if (capabilities.hasRawSensorOutput) RawTrust.ADVERTISED else RawTrust.UNSUPPORTED,
    val publiclyAdvertised: Boolean,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class CanonicalLens(
    val lensId: String,
    val facing: CameraFacing,
    val profileIds: List<String>,
    val evidence: List<String>,
)

@Serializable
data class DiscoveryFailure(
    val stage: String,
    val cameraId: String? = null,
    val message: String,
)

@Serializable
data class DiscoveryDiagnostics(
    val advertisedCameraCount: Int,
    val profileCount: Int,
    val canonicalLensCount: Int,
    val failures: List<DiscoveryFailure> = emptyList(),
    val truncatedFailureCount: Int = 0,
)

@Serializable
data class CameraTopology(
    val schemaVersion: Int,
    val environmentFingerprint: String,
    val profiles: List<CameraProfile>,
    val lenses: List<CanonicalLens>,
    val diagnostics: DiscoveryDiagnostics,
)
