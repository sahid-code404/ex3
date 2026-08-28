package com.sahidcode404.camera.core.camera.preview

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.orientation.PreviewGeometryEngine
import com.sahidcode404.camera.core.camera.session.CameraSessionController
import com.sahidcode404.camera.core.camera.session.PreviewTarget
import kotlin.math.max

public class CameraPreviewTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var controller: CameraSessionController? = null
    private var target: PreviewTarget? = null
    private val cornerRadiusPx = 24f * resources.displayMetrics.density

    init {
        surfaceTextureListener = this
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    public fun bindController(value: CameraSessionController) {
        if (controller === value) return
        controller = value
        if (isAvailable) surfaceTexture?.let(value::attachSurface)
    }

    public fun setPreviewTarget(value: PreviewTarget?) {
        if (target == value) return
        target = value
        applyPreviewTransform()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        controller?.attachSurface(surface)
        applyPreviewTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyPreviewTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        controller?.detachSurface(surface)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        controller?.markPreviewFrame(surface)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateOutline()
        applyPreviewTransform()
    }

    private fun applyPreviewTransform() {
        val previewTarget = target ?: run {
            setTransform(Matrix())
            return
        }
        if (width <= 0 || height <= 0) return

        val rotationDegrees = displayRotationDegrees(display?.rotation ?: Surface.ROTATION_0)
        val relativeRotation = PreviewGeometryEngine.relativeRotationDegrees(
            sensorOrientationDegrees = previewTarget.sensorOrientationDegrees,
            displayRotationDegrees = rotationDegrees,
            facing = previewTarget.facing,
        )
        val bufferWidth = previewTarget.streamSize.width.toFloat()
        val bufferHeight = previewTarget.streamSize.height.toFloat()
        val rotatedWidth = if (relativeRotation == 90 || relativeRotation == 270) bufferHeight else bufferWidth
        val rotatedHeight = if (relativeRotation == 90 || relativeRotation == 270) bufferWidth else bufferHeight
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)

        val matrix = Matrix().apply {
            setTranslate(-bufferWidth / 2f, -bufferHeight / 2f)
            postRotate(relativeRotation.toFloat())
            if (previewTarget.facing == CameraFacing.FRONT) postScale(-1f, 1f)
            postScale(scale, scale)
            postTranslate(width / 2f, height / 2f)
        }
        setTransform(matrix)
    }

    private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
