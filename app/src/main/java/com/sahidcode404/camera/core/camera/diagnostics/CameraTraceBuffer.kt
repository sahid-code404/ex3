package com.sahidcode404.camera.core.camera.diagnostics

public enum class CameraTraceEvent {
    ACTIVITY_CREATE,
    SURFACE_READY,
    HOT_CACHE_READY,
    OPEN_REQUESTED,
    CAMERA_OPENED,
    SESSION_CONFIGURED,
    FIRST_PREVIEW_FRAME,
    PREVIEW_STABLE,
    LENS_SWITCH_REQUEST,
    LENS_SWITCH_NEW_FIRST_FRAME,
    SHUTTER_PRESS,
    BURST_START,
    BURST_END,
    PROCESSING_START,
    ALIGNMENT_END,
    MERGE_END,
    DNG_WRITE_START,
    DNG_WRITE_END,
    MEDIASTORE_PUBLISHED,
}

public data class CameraTracePoint(
    val event: CameraTraceEvent,
    val monotonicNanos: Long,
    val generation: Long,
    val argument: Long = 0L,
)

public class CameraTraceBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "Trace capacity must be positive" }
    }

    private val events = arrayOfNulls<CameraTracePoint>(capacity)
    private var nextIndex = 0
    private var currentSize = 0

    @Synchronized
    public fun record(point: CameraTracePoint) {
        events[nextIndex] = point
        nextIndex = (nextIndex + 1) % capacity
        if (currentSize < capacity) currentSize++
    }

    @Synchronized
    public fun snapshot(): List<CameraTracePoint> {
        if (currentSize == 0) return emptyList()
        val start = if (currentSize == capacity) nextIndex else 0
        return List(currentSize) { offset ->
            requireNotNull(events[(start + offset) % capacity])
        }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 128
    }
}
