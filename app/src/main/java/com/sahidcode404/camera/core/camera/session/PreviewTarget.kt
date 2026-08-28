package com.sahidcode404.camera.core.camera.session

import android.os.Build
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CanonicalLens
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PreviewTrust
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import com.sahidcode404.camera.core.camera.preview.PreviewStreamSelector
import kotlin.math.max

public data class PreviewTarget(
    val stableId: String,
    val route: CameraRoute,
    val streamSize: IntSizeValue,
    val facing: CameraFacing,
    val sensorOrientationDegrees: Int,
    val sensorLandscapeAspect: Double,
)

public object PreviewTargetFactory {
    public fun fromProfile(profile: CameraProfile): PreviewTarget? {
        val stream = PreviewStreamSelector.select(profile.capabilities) ?: return null
        val rawAspect = PreviewStreamSelector.sensorAspect(profile.capabilities)
            ?: (stream.size.width.toDouble() / stream.size.height.toDouble())
        return PreviewTarget(
            stableId = profile.profileId,
            route = profile.route,
            streamSize = stream.size,
            facing = profile.capabilities.facing,
            sensorOrientationDegrees = profile.capabilities.sensorOrientationDegrees ?: 0,
            sensorLandscapeAspect = max(rawAspect, 1.0 / rawAspect),
        )
    }

    public fun bestTargetForLens(
        topology: CameraTopology,
        lens: CanonicalLens,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): PreviewTarget? = lens.profileIds
        .asSequence()
        .mapNotNull { id -> topology.profiles.firstOrNull { it.profileId == id } }
        .filter { profile ->
            profile.route.routingMethod != RoutingMethod.LOGICAL_PHYSICAL_MEMBER || sdkInt >= Build.VERSION_CODES.P
        }
        .mapNotNull { profile -> fromProfile(profile)?.let { profile to it } }
        .sortedWith(
            compareBy<Pair<CameraProfile, PreviewTarget>>(
                { previewTrustRank(it.first.previewTrust) },
                { metadataTrustRank(it.first.metadataTrust) },
                { routingRank(it.first.route.routingMethod) },
                { if (it.first.publiclyAdvertised) 0 else 1 },
                { it.second.streamSize.area },
            ),
        )
        .map { it.second }
        .firstOrNull()

    private fun previewTrustRank(trust: PreviewTrust): Int = when (trust) {
        PreviewTrust.PREVIEW_VERIFIED -> 0
        PreviewTrust.ADVERTISED -> 1
        PreviewTrust.TEMPORARILY_FAILED -> 2
        PreviewTrust.STRUCTURALLY_UNUSABLE -> 3
    }

    private fun metadataTrustRank(trust: MetadataTrust): Int = when (trust) {
        MetadataTrust.COMPLETE -> 0
        MetadataTrust.PARTIAL -> 1
        MetadataTrust.FAILED -> 2
    }

    private fun routingRank(method: RoutingMethod): Int = when (method) {
        RoutingMethod.LOGICAL_DEFAULT -> 0
        RoutingMethod.DIRECT -> 1
        RoutingMethod.LOGICAL_PHYSICAL_MEMBER -> 2
    }
}
