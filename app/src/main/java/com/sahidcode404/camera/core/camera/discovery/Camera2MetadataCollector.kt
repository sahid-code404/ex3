package com.sahidcode404.camera.core.camera.discovery

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.FloatRangeValue
import com.sahidcode404.camera.core.camera.model.FloatSizeValue
import com.sahidcode404.camera.core.camera.model.IntRangeValue
import com.sahidcode404.camera.core.camera.model.IntRectValue
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.LongRangeValue
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PhysicalCameraId
import com.sahidcode404.camera.core.camera.model.StreamKind
import com.sahidcode404.camera.core.camera.model.StreamSpec

internal data class CollectedCharacteristics(
    val capabilities: CameraCapabilities,
    val metadataTrust: MetadataTrust,
    val evidenceNotes: List<String>,
)

internal class Camera2MetadataCollector(private val cameraPermissionGranted: Boolean) {
    fun collect(characteristics: CameraCharacteristics): CollectedCharacteristics {
        val issues = mutableListOf<String>()
        val availableCapabilities = safeRead("available-capabilities", issues) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toList()
                ?.sorted()
                ?.take(DiscoveryBounds.MAX_INT_METADATA_VALUES)
        }.orEmpty()

        val logicalMetadata = logicalMetadata(characteristics, availableCapabilities, issues)
        val streamMap = safeRead("stream-map", issues) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }

        val capabilities = CameraCapabilities(
            facing = mapFacing(safeRead("facing", issues) { characteristics.get(CameraCharacteristics.LENS_FACING) }),
            focalLengthsMm = safeRead("focal-lengths", issues) {
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.filter { it.isFinite() && it > 0f }
                    ?.sorted()
                    ?.take(DiscoveryBounds.MAX_FLOAT_METADATA_VALUES)
            }.orEmpty(),
            apertures = safeRead("apertures", issues) {
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.filter { it.isFinite() && it > 0f }
                    ?.sorted()
                    ?.take(DiscoveryBounds.MAX_FLOAT_METADATA_VALUES)
            }.orEmpty(),
            sensorPhysicalSizeMm = safeRead("sensor-physical-size", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { size ->
                    if (size.width > 0f && size.height > 0f) FloatSizeValue(size.width, size.height) else null
                }
            },
            pixelArraySize = safeRead("pixel-array", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toModelOrNull()
            },
            activeArray = safeRead("active-array", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
                    if (rect.width() > 0 && rect.height() > 0) IntRectValue(rect.left, rect.top, rect.right, rect.bottom) else null
                }
            },
            preCorrectionActiveArray = safeRead("pre-correction-array", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let { rect ->
                    if (rect.width() > 0 && rect.height() > 0) IntRectValue(rect.left, rect.top, rect.right, rect.bottom) else null
                }
            },
            sensorOrientationDegrees = safeRead("sensor-orientation", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            },
            colorFilterArrangement = safeRead("cfa", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            },
            hardwareLevel = safeRead("hardware-level", issues) {
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            },
            availableCapabilities = availableCapabilities,
            isLogicalMultiCamera = logicalMetadata.first,
            physicalMemberIds = logicalMetadata.second,
            streams = collectStreams(streamMap, issues),
            aeTargetFpsRanges = safeRead("ae-fps-ranges", issues) {
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { range -> IntRangeValue(range.lower, range.upper) }
                    ?.sortedWith(compareBy<IntRangeValue> { it.lower }.thenBy { it.upper })
                    ?.take(DiscoveryBounds.MAX_FPS_RANGES)
            }.orEmpty(),
            sensitivityRange = safeRead("sensitivity-range", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toIntRange()
            },
            exposureTimeRangeNs = safeRead("exposure-range", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toLongRange()
            },
            maxAnalogSensitivity = safeRead("max-analog-sensitivity", issues) {
                characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
            },
            maxRawOutputStreams = safeRead("max-raw-streams", issues) {
                characteristics.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW)
            },
            maxProcessedOutputStreams = safeRead("max-processed-streams", issues) {
                characteristics.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC)
            },
            maxProcessedStallingOutputStreams = safeRead("max-stalling-streams", issues) {
                characteristics.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING)
            },
            maxInputStreams = safeRead("max-input-streams", issues) {
                characteristics.get(CameraCharacteristics.REQUEST_MAX_NUM_INPUT_STREAMS)
            },
            croppingType = safeRead("cropping-type", issues) {
                characteristics.get(CameraCharacteristics.SCALER_CROPPING_TYPE)
            },
            maxDigitalZoom = safeRead("max-digital-zoom", issues) {
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            },
            zoomRatioRange = zoomRatioRange(characteristics, issues),
            distortionCorrectionModes = distortionModes(characteristics, issues),
            lensShadingMapModes = safeRead("lens-shading-map-modes", issues) {
                characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                    ?.toList()?.sorted()?.take(DiscoveryBounds.MAX_INT_METADATA_VALUES)
            }.orEmpty(),
            opticalStabilizationModes = safeRead("ois-modes", issues) {
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.toList()?.sorted()?.take(DiscoveryBounds.MAX_INT_METADATA_VALUES)
            }.orEmpty(),
            videoStabilizationModes = safeRead("video-stabilization-modes", issues) {
                characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.toList()?.sorted()?.take(DiscoveryBounds.MAX_INT_METADATA_VALUES)
            }.orEmpty(),
            minimumFocusDistanceDiopters = safeRead("minimum-focus-distance", issues) {
                characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            },
            hyperfocalDistanceDiopters = safeRead("hyperfocal-distance", issues) {
                characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
            },
            flashAvailable = safeRead("flash-available", issues) {
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            },
        )

        return CollectedCharacteristics(
            capabilities = capabilities,
            metadataTrust = if (issues.isEmpty() && cameraPermissionGranted) MetadataTrust.COMPLETE else MetadataTrust.PARTIAL,
            evidenceNotes = issues.take(DiscoveryBounds.MAX_EVIDENCE_NOTES),
        )
    }

    private fun collectStreams(map: StreamConfigurationMap?, issues: MutableList<String>): List<StreamSpec> {
        if (map == null) return emptyList()
        val specs = ArrayList<StreamSpec>(DiscoveryBounds.MAX_STREAM_SIZES_PER_KIND * 3)
        collectFormat(map, ImageFormat.RAW_SENSOR, StreamKind.RAW_SENSOR, specs, issues)
        collectFormat(map, ImageFormat.PRIVATE, StreamKind.PRIVATE_PREVIEW, specs, issues)
        collectFormat(map, ImageFormat.YUV_420_888, StreamKind.YUV_420_888, specs, issues)
        return specs
    }

    private fun collectFormat(
        map: StreamConfigurationMap,
        format: Int,
        kind: StreamKind,
        destination: MutableList<StreamSpec>,
        issues: MutableList<String>,
    ) {
        val sizes = safeRead("stream-sizes-${kind.name}", issues) {
            map.getOutputSizes(format)?.toList().orEmpty()
        }.orEmpty()
        val bounded = DiscoveryBounds.takeDeterministic(
            values = sizes,
            limit = DiscoveryBounds.MAX_STREAM_SIZES_PER_KIND,
            comparator = compareByDescending<Size> { it.width.toLong() * it.height.toLong() }
                .thenByDescending { it.width }
                .thenByDescending { it.height },
        )
        bounded.forEach { size ->
            val modelSize = size.toModelOrNull() ?: return@forEach
            val minDuration = safeRead("min-duration-${kind.name}", issues) {
                map.getOutputMinFrameDuration(format, size).takeIf { it >= 0L }
            }
            val stallDuration = safeRead("stall-duration-${kind.name}", issues) {
                map.getOutputStallDuration(format, size).takeIf { it >= 0L }
            }
            destination += StreamSpec(kind, modelSize, minDuration, stallDuration)
        }
    }

    private fun logicalMetadata(
        characteristics: CameraCharacteristics,
        availableCapabilities: List<Int>,
        issues: MutableList<String>,
    ): Pair<Boolean, List<PhysicalCameraId>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            logicalMetadataApi28(characteristics, availableCapabilities, issues)
        } else {
            false to emptyList()
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun logicalMetadataApi28(
        characteristics: CameraCharacteristics,
        availableCapabilities: List<Int>,
        issues: MutableList<String>,
    ): Pair<Boolean, List<PhysicalCameraId>> {
        val logical = availableCapabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        if (!logical) return false to emptyList()
        val physicalIds = safeRead("physical-camera-ids", issues) {
            characteristics.physicalCameraIds
                .filter { it.isNotBlank() }
                .sorted()
                .take(DiscoveryBounds.MAX_PHYSICAL_IDS_PER_LOGICAL)
                .map(::PhysicalCameraId)
        }.orEmpty()
        return true to physicalIds
    }

    private fun zoomRatioRange(characteristics: CameraCharacteristics, issues: MutableList<String>): FloatRangeValue? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            zoomRatioRangeApi30(characteristics, issues)
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun zoomRatioRangeApi30(
        characteristics: CameraCharacteristics,
        issues: MutableList<String>,
    ): FloatRangeValue? = safeRead("zoom-ratio-range", issues) {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
            FloatRangeValue(range.lower, range.upper)
        }
    }

    private fun distortionModes(characteristics: CameraCharacteristics, issues: MutableList<String>): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            distortionModesApi28(characteristics, issues)
        } else {
            emptyList()
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun distortionModesApi28(
        characteristics: CameraCharacteristics,
        issues: MutableList<String>,
    ): List<Int> = safeRead("distortion-modes", issues) {
        characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            ?.toList()?.sorted()?.take(DiscoveryBounds.MAX_INT_METADATA_VALUES)
    }.orEmpty()

    private fun mapFacing(value: Int?): CameraFacing = when (value) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    private fun Size.toModelOrNull(): IntSizeValue? =
        if (width > 0 && height > 0) IntSizeValue(width, height) else null

    private fun Range<Int>.toIntRange(): IntRangeValue = IntRangeValue(lower, upper)

    private fun Range<Long>.toLongRange(): LongRangeValue = LongRangeValue(lower, upper)

    private inline fun <T> safeRead(label: String, issues: MutableList<String>, block: () -> T?): T? =
        try {
            block()
        } catch (error: RuntimeException) {
            if (issues.size < DiscoveryBounds.MAX_EVIDENCE_NOTES) {
                issues += "$label:${error.javaClass.simpleName}"
            }
            null
        }
}
