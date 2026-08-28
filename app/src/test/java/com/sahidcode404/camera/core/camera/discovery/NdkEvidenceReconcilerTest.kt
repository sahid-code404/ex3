package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdkEvidenceReconcilerTest {
    @Test
    fun reportsEnumerationDifferencesWithoutInventingProfiles() {
        val snapshot = NdkEvidenceSnapshot(
            available = true,
            truncated = false,
            error = null,
            cameras = listOf(ndk("opaque-a"), ndk("ndk-only")),
        )

        val summary = NdkEvidenceReconciler.summarize(
            javaAdvertisedIds = setOf("opaque-a", "java-only"),
            profiles = listOf(profile("opaque-a")),
            ndk = snapshot,
        )

        assertEquals(listOf("ndk-only"), summary.ndkOnlyCameraIds)
        assertEquals(listOf("java-only"), summary.javaOnlyCameraIds)
    }

    @Test
    fun contradictoryFacingIsRecordedAsMetadataMismatch() {
        val snapshot = NdkEvidenceSnapshot(
            available = true,
            truncated = false,
            error = null,
            cameras = listOf(ndk("opaque-a", CameraFacing.FRONT)),
        )

        val summary = NdkEvidenceReconciler.summarize(
            javaAdvertisedIds = setOf("opaque-a"),
            profiles = listOf(profile("opaque-a", CameraFacing.BACK)),
            ndk = snapshot,
        )

        assertTrue(summary.metadataMismatchCameraIds.contains("opaque-a"))
    }

    private fun profile(id: String, facing: CameraFacing = CameraFacing.BACK): CameraProfile = CameraProfile(
        profileId = id,
        route = CameraRoute(CameraTransportId(id), null, RoutingMethod.DIRECT),
        capabilities = CameraCapabilities(facing = facing),
        metadataTrust = MetadataTrust.COMPLETE,
        publiclyAdvertised = true,
    )

    private fun ndk(id: String, facing: CameraFacing = CameraFacing.BACK) = NdkCameraMetadataEvidence(
        id = id,
        status = 0,
        facing = facing,
        hardwareLevel = null,
        sensorOrientationDegrees = null,
        focalLengthsMm = emptyList(),
        availableCapabilities = emptyList(),
        sensorPhysicalSizeMm = null,
        pixelArraySize = null,
    )
}
