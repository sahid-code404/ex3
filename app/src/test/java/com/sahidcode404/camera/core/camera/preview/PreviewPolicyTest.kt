package com.sahidcode404.camera.core.camera.preview

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.IntRectValue
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.StreamKind
import com.sahidcode404.camera.core.camera.model.StreamSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewPolicyTest {
    @Test
    fun selectorPrefersSensorAspectAndReasonablePreviewWorkload() {
        val capabilities = CameraCapabilities(
            activeArray = IntRectValue(0, 0, 4000, 3000),
            streams = listOf(
                preview(4000, 3000, 33_000_000L),
                preview(1920, 1440, 33_000_000L),
                preview(1920, 1080, 16_000_000L),
            ),
        )

        assertEquals(IntSizeValue(1920, 1440), PreviewStreamSelector.select(capabilities)?.size)
    }

    @Test
    fun selectorNeverReturnsNonPrivateStream() {
        val capabilities = CameraCapabilities(
            streams = listOf(StreamSpec(StreamKind.YUV_420_888, IntSizeValue(1920, 1080))),
        )

        assertNull(PreviewStreamSelector.select(capabilities))
    }

    private fun preview(width: Int, height: Int, duration: Long) = StreamSpec(
        kind = StreamKind.PRIVATE_PREVIEW,
        size = IntSizeValue(width, height),
        minFrameDurationNs = duration,
    )
}
