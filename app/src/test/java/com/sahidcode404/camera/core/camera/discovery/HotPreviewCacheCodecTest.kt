package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.HotPreviewSeed
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotPreviewCacheCodecTest {
    @Test
    fun roundTripRequiresMatchingEnvironment() {
        val seed = HotPreviewSeed(
            stableId = "profile",
            route = CameraRoute(
                logicalCameraId = CameraTransportId("opaque-route"),
                physicalCameraId = null,
                routingMethod = RoutingMethod.DIRECT,
            ),
            streamSize = IntSizeValue(1920, 1080),
            facing = CameraFacing.BACK,
            sensorOrientationDegrees = 90,
            sensorLandscapeAspect = 4.0 / 3.0,
        )
        val encoded = HotPreviewCacheCodec.encode("environment-a", seed)

        assertEquals(seed, HotPreviewCacheCodec.decodeOrNull(encoded, "environment-a"))
        assertNull(HotPreviewCacheCodec.decodeOrNull(encoded, "environment-b"))
        assertNull(HotPreviewCacheCodec.decodeOrNull("not-json", "environment-a"))
    }
}
