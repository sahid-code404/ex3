package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.PreviewTrust
import com.sahidcode404.camera.core.camera.model.RawTrust

internal object RuntimeTrustReconciler {
    fun merge(
        discovered: CameraTopology,
        previous: CameraTopology?,
        verifiedPreviewRoute: CameraRoute?,
    ): CameraTopology {
        val previousByProfileId = previous
            ?.takeIf { it.environmentFingerprint == discovered.environmentFingerprint }
            ?.profiles
            ?.associateBy { it.profileId }
            .orEmpty()

        val profiles = discovered.profiles.map { profile ->
            val previousProfile = previousByProfileId[profile.profileId]
            profile.copy(
                previewTrust = when {
                    verifiedPreviewRoute != null && profile.route == verifiedPreviewRoute -> PreviewTrust.PREVIEW_VERIFIED
                    previousProfile != null -> previousProfile.previewTrust
                    else -> profile.previewTrust
                },
                rawTrust = previousProfile?.rawTrust ?: profile.rawTrust,
            )
        }
        return discovered.copy(profiles = profiles)
    }

    fun markPreviewVerified(
        topology: CameraTopology,
        route: CameraRoute,
    ): CameraTopology? {
        var changed = false
        val profiles = topology.profiles.map { profile ->
            if (profile.route == route && profile.previewTrust != PreviewTrust.PREVIEW_VERIFIED) {
                changed = true
                profile.copy(previewTrust = PreviewTrust.PREVIEW_VERIFIED)
            } else {
                profile
            }
        }
        return if (changed) topology.copy(profiles = profiles) else null
    }

    fun markRawVerified(
        topology: CameraTopology,
        route: CameraRoute,
    ): CameraTopology? {
        var changed = false
        val profiles = topology.profiles.map { profile ->
            if (profile.route == route && profile.rawTrust != RawTrust.RAW_VERIFIED) {
                changed = true
                profile.copy(rawTrust = RawTrust.RAW_VERIFIED)
            } else {
                profile
            }
        }
        return if (changed) topology.copy(profiles = profiles) else null
    }
}
