package com.sahidcode404.camera.core.camera.session

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.CanonicalLens
import com.sahidcode404.camera.core.camera.model.DiscoveryDiagnostics
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PhysicalCameraId
import com.sahidcode404.camera.core.camera.model.PreviewTrust
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import com.sahidcode404.camera.core.camera.model.StreamKind
import com.sahidcode404.camera.core.camera.model.StreamSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTargetFactoryTest {
    @Test
    fun bestTargetPrefersVerifiedProfile() {
        val advertised = profile(
            id = "advertised",
            route = CameraRoute(CameraTransportId("opaque-a"), null, RoutingMethod.DIRECT),
            previewTrust = PreviewTrust.ADVERTISED,
        )
        val verified = profile(
            id = "verified",
            route = CameraRoute(CameraTransportId("opaque-b"), null, RoutingMethod.DIRECT),
            previewTrust = PreviewTrust.PREVIEW_VERIFIED,
        )
        val topology = topology(advertised, verified)
        val target = PreviewTargetFactory.bestTargetForLens(topology, topology.lenses.single(), sdkInt = 23)

        assertNotNull(target)
        assertEquals("verified", target?.stableId)
    }

    @Test
    fun physicalMemberIsRejectedBeforeApi28() {
        val physical = profile(
            id = "physical",
            route = CameraRoute(
                logicalCameraId = CameraTransportId("opaque-logical"),
                physicalCameraId = PhysicalCameraId("opaque-physical"),
                routingMethod = RoutingMethod.LOGICAL_PHYSICAL_MEMBER,
            ),
            previewTrust = PreviewTrust.PREVIEW_VERIFIED,
        )
        val topology = topology(physical)

        assertNull(PreviewTargetFactory.bestTargetForLens(topology, topology.lenses.single(), sdkInt = 27))
        assertEquals(
            "physical",
            PreviewTargetFactory.bestTargetForLens(topology, topology.lenses.single(), sdkInt = 28)?.stableId,
        )
    }

    private fun profile(
        id: String,
        route: CameraRoute,
        previewTrust: PreviewTrust,
    ): CameraProfile = CameraProfile(
        profileId = id,
        route = route,
        capabilities = CameraCapabilities(
            facing = CameraFacing.BACK,
            sensorOrientationDegrees = 90,
            streams = listOf(
                StreamSpec(StreamKind.PRIVATE_PREVIEW, IntSizeValue(1920, 1080), minFrameDurationNs = 33_333_333L),
            ),
        ),
        metadataTrust = MetadataTrust.COMPLETE,
        previewTrust = previewTrust,
        publiclyAdvertised = route.physicalCameraId == null,
    )

    private fun topology(vararg profiles: CameraProfile): CameraTopology = CameraTopology(
        schemaVersion = 1,
        environmentFingerprint = "test",
        profiles = profiles.toList(),
        lenses = listOf(
            CanonicalLens(
                lensId = "lens",
                facing = CameraFacing.BACK,
                profileIds = profiles.map { it.profileId },
                evidence = emptyList(),
            ),
        ),
        diagnostics = DiscoveryDiagnostics(
            advertisedCameraCount = profiles.count { it.publiclyAdvertised },
            profileCount = profiles.size,
            canonicalLensCount = 1,
        ),
    )
}
