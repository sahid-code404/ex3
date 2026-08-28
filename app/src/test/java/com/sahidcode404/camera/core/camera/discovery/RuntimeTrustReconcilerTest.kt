package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.DiscoveryDiagnostics
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PreviewTrust
import com.sahidcode404.camera.core.camera.model.RawTrust
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTrustReconcilerTest {
    @Test
    fun preservesExactProfileTrustAndMarksCurrentVerifiedRoute() {
        val oldRoute = CameraRoute(CameraTransportId("opaque-old"), null, RoutingMethod.DIRECT)
        val currentRoute = CameraRoute(CameraTransportId("opaque-current"), null, RoutingMethod.DIRECT)
        val previous = topology(
            profile("same-profile", oldRoute, PreviewTrust.PREVIEW_VERIFIED, RawTrust.RAW_VERIFIED),
        )
        val discovered = topology(
            profile("same-profile", oldRoute, PreviewTrust.ADVERTISED, RawTrust.ADVERTISED),
            profile("new-profile", currentRoute, PreviewTrust.ADVERTISED, RawTrust.UNSUPPORTED),
        )

        val merged = RuntimeTrustReconciler.merge(discovered, previous, currentRoute)

        assertEquals(PreviewTrust.PREVIEW_VERIFIED, merged.profiles[0].previewTrust)
        assertEquals(RawTrust.RAW_VERIFIED, merged.profiles[0].rawTrust)
        assertEquals(PreviewTrust.PREVIEW_VERIFIED, merged.profiles[1].previewTrust)
    }

    @Test
    fun markVerifiedIsNoOpWhenAlreadyVerified() {
        val route = CameraRoute(CameraTransportId("opaque"), null, RoutingMethod.DIRECT)
        val topology = topology(profile("profile", route, PreviewTrust.PREVIEW_VERIFIED, RawTrust.UNSUPPORTED))
        assertNull(RuntimeTrustReconciler.markPreviewVerified(topology, route))
    }

    @Test
    fun successfulRawCapturePromotesOnlyExactRoute() {
        val verifiedRoute = CameraRoute(CameraTransportId("opaque-a"), null, RoutingMethod.DIRECT)
        val otherRoute = CameraRoute(CameraTransportId("opaque-b"), null, RoutingMethod.DIRECT)
        val topology = topology(
            profile("verified", verifiedRoute, PreviewTrust.PREVIEW_VERIFIED, RawTrust.ADVERTISED),
            profile("other", otherRoute, PreviewTrust.PREVIEW_VERIFIED, RawTrust.ADVERTISED),
        )

        val updated = requireNotNull(RuntimeTrustReconciler.markRawVerified(topology, verifiedRoute))

        assertEquals(RawTrust.RAW_VERIFIED, updated.profiles[0].rawTrust)
        assertEquals(RawTrust.ADVERTISED, updated.profiles[1].rawTrust)
        assertNull(RuntimeTrustReconciler.markRawVerified(updated, verifiedRoute))
    }

    private fun profile(
        id: String,
        route: CameraRoute,
        previewTrust: PreviewTrust,
        rawTrust: RawTrust,
    ): CameraProfile = CameraProfile(
        profileId = id,
        route = route,
        capabilities = CameraCapabilities(facing = CameraFacing.BACK),
        metadataTrust = MetadataTrust.COMPLETE,
        previewTrust = previewTrust,
        rawTrust = rawTrust,
        publiclyAdvertised = true,
    )

    private fun topology(vararg profiles: CameraProfile): CameraTopology = CameraTopology(
        schemaVersion = 1,
        environmentFingerprint = "environment",
        profiles = profiles.toList(),
        lenses = emptyList(),
        diagnostics = DiscoveryDiagnostics(
            advertisedCameraCount = profiles.size,
            profileCount = profiles.size,
            canonicalLensCount = 0,
        ),
    )
}
