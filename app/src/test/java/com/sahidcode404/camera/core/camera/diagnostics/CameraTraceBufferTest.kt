package com.sahidcode404.camera.core.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTraceBufferTest {
    @Test
    fun overflowRetainsOnlyNewestPrimitiveEventsInOrder() {
        val buffer = CameraTraceBuffer(capacity = 3)
        for (generation in 1L..5L) {
            buffer.record(CameraTracePoint(CameraTraceEvent.OPEN_REQUESTED, generation, generation))
        }

        assertEquals(listOf(3L, 4L, 5L), buffer.snapshot().map { it.generation })
    }
}
