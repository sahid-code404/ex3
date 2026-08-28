package com.sahidcode404.camera.core.camera.session

import android.net.Uri
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.IntSizeValue

public sealed interface RawCaptureState {
    public data object Idle : RawCaptureState

    public data class Preparing(
        val captureId: Long,
        val route: CameraRoute,
    ) : RawCaptureState

    public data class Capturing(
        val captureId: Long,
        val route: CameraRoute,
        val size: IntSizeValue,
    ) : RawCaptureState

    public data class Saving(
        val captureId: Long,
        val route: CameraRoute,
        val size: IntSizeValue,
    ) : RawCaptureState

    public data class Saved(
        val captureId: Long,
        val route: CameraRoute,
        val size: IntSizeValue,
        val uri: Uri,
    ) : RawCaptureState

    public data class Unsupported(
        val route: CameraRoute?,
        val message: String,
    ) : RawCaptureState

    public data class Failed(
        val route: CameraRoute?,
        val message: String,
    ) : RawCaptureState
}

public val RawCaptureState.isBusy: Boolean
    get() = this is RawCaptureState.Preparing ||
        this is RawCaptureState.Capturing ||
        this is RawCaptureState.Saving
