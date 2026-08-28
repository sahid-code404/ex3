package com.sahidcode404.camera.core.camera.preview

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.StreamKind
import com.sahidcode404.camera.core.camera.model.StreamSpec
import kotlin.math.abs
import kotlin.math.ln

public enum class PreviewAspectMode {
    SENSOR,
    SQUARE,
    FOUR_THREE,
    SIXTEEN_NINE,
    FULL,
}

public object PreviewStreamSelector {
    public fun select(capabilities: CameraCapabilities): StreamSpec? {
        val candidates = capabilities.streams(StreamKind.PRIVATE_PREVIEW)
        if (candidates.isEmpty()) return null

        val sensorAspect = sensorAspect(capabilities) ?: candidates
            .maxByOrNull { it.size.area }
            ?.size
            ?.aspect
            ?: return null

        return candidates.minWithOrNull(
            compareBy<StreamSpec>(
                { frameDurationClass(it.minFrameDurationNs) },
                { aspectError(it.size.aspect, sensorAspect) },
                { oversizePenalty(it.size.area) },
                { pixelDistance(it.size.area) },
                { -it.size.area },
            ),
        )
    }

    public fun sensorAspect(capabilities: CameraCapabilities): Double? {
        val active = capabilities.activeArray
        if (active != null && active.width > 0 && active.height > 0) {
            return active.width.toDouble() / active.height.toDouble()
        }
        val pixel = capabilities.pixelArraySize
        if (pixel != null) return pixel.aspect
        return null
    }

    private fun frameDurationClass(durationNs: Long?): Int = when {
        durationNs == null || durationNs <= TARGET_FRAME_DURATION_NS -> 0
        durationNs <= MAX_PREFERRED_FRAME_DURATION_NS -> 1
        else -> 2
    }

    private fun aspectError(candidate: Double, target: Double): Double =
        abs(ln(candidate / target))

    private fun oversizePenalty(area: Long): Double =
        if (area <= MAX_PREFERRED_PREVIEW_PIXELS) 0.0
        else (area - MAX_PREFERRED_PREVIEW_PIXELS).toDouble() / MAX_PREFERRED_PREVIEW_PIXELS.toDouble()

    private fun pixelDistance(area: Long): Double =
        abs(area - TARGET_PREVIEW_PIXELS).toDouble() / TARGET_PREVIEW_PIXELS.toDouble()

    private val IntSizeValue.aspect: Double
        get() = width.toDouble() / height.toDouble()

    private const val TARGET_FRAME_DURATION_NS = 33_333_334L
    private const val MAX_PREFERRED_FRAME_DURATION_NS = 50_000_000L
    private const val TARGET_PREVIEW_PIXELS = 2_073_600L
    private const val MAX_PREFERRED_PREVIEW_PIXELS = 2_764_800L
}
