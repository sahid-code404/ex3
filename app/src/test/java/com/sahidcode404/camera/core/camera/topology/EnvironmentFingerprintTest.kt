package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraTransportId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnvironmentFingerprintTest {
    @Test
    fun cameraEnumerationOrderDoesNotChangeFingerprint() {
        val first = EnvironmentFingerprint.create(
            osBuildFingerprint = "build",
            sdkInt = 35,
            advertisedCameraIds = listOf(CameraTransportId("opaque-a"), CameraTransportId("opaque-b")),
        )
        val second = EnvironmentFingerprint.create(
            osBuildFingerprint = "build",
            sdkInt = 35,
            advertisedCameraIds = listOf(CameraTransportId("opaque-b"), CameraTransportId("opaque-a")),
        )

        assertEquals(first, second)
    }

    @Test
    fun environmentChangeInvalidatesFingerprint() {
        val first = EnvironmentFingerprint.create("build-a", 35, listOf(CameraTransportId("opaque")))
        val second = EnvironmentFingerprint.create("build-b", 35, listOf(CameraTransportId("opaque")))

        assertNotEquals(first, second)
    }
}
