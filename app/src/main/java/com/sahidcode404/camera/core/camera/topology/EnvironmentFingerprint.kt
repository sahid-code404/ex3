package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraTransportId

object EnvironmentFingerprint {
    fun create(
        osBuildFingerprint: String,
        sdkInt: Int,
        advertisedCameraIds: Collection<CameraTransportId>,
    ): String = StableFingerprint.sha256(
        buildList {
            add("environment-v1")
            add("sdk=$sdkInt")
            add("build=$osBuildFingerprint")
            advertisedCameraIds.map { it.value }.sorted().forEach { add("camera=$it") }
        },
    )
}
