package com.sahidcode404.camera.core.camera.model

import kotlinx.serialization.Serializable

@Serializable
data class HotPreviewSeed(
    val stableId: String,
    val route: CameraRoute,
    val streamSize: IntSizeValue,
    val facing: CameraFacing,
    val sensorOrientationDegrees: Int,
    val sensorLandscapeAspect: Double,
) {
    init {
        require(stableId.isNotBlank()) { "Hot preview seed ID must not be blank" }
        require(sensorLandscapeAspect.isFinite() && sensorLandscapeAspect > 0.0) {
            "Hot preview seed aspect must be finite and positive"
        }
    }
}
