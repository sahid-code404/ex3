package com.sahidcode404.camera.core.camera.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.sahidcode404.camera.core.camera.diagnostics.CameraTraceBuffer
import com.sahidcode404.camera.core.camera.diagnostics.CameraTraceEvent
import com.sahidcode404.camera.core.camera.diagnostics.CameraTracePoint
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public sealed interface CameraSessionState {
    public data object Stopped : CameraSessionState
    public data object AwaitingPermission : CameraSessionState
    public data object AwaitingSurface : CameraSessionState
    public data class Selecting(val generation: Long) : CameraSessionState
    public data class Opening(val generation: Long, val target: PreviewTarget) : CameraSessionState
    public data class Configuring(val generation: Long, val target: PreviewTarget) : CameraSessionState
    public data class Previewing(
        val generation: Long,
        val target: PreviewTarget,
        val firstFrameSeen: Boolean,
    ) : CameraSessionState
    public data class Failed(
        val generation: Long,
        val target: PreviewTarget?,
        val message: String,
    ) : CameraSessionState
}

public class CameraSessionController(context: Context) {
    private data class OpenIntent(
        val generation: Long,
        val target: PreviewTarget,
        val lensSwitch: Boolean,
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val bootstrapResolver = BootstrapPreviewTargetResolver(appContext, cameraManager)
    private val cameraThread = HandlerThread("CameraSessionController").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val generationCounter = AtomicLong(0L)
    private val traceBuffer = CameraTraceBuffer()
    private val mutableState = MutableStateFlow<CameraSessionState>(CameraSessionState.Stopped)

    public val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()

    private var started = false
    private var permissionGranted = false
    private var surfaceTexture: SurfaceTexture? = null
    private var requestedTarget: PreviewTarget? = null
    private var bootstrapTarget: PreviewTarget? = null
    private var pendingOpen: OpenIntent? = null
    private var openingGeneration: Long? = null
    private var closingDevice = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeTarget: PreviewTarget? = null
    private var activeGeneration: Long = 0L
    private var activeLensSwitch = false
    private var repeatingCaptureCount: Int = 0
    private var shutdown = false

    public fun start(hasCameraPermission: Boolean) {
        cameraHandler.post {
            if (shutdown) return@post
            started = true
            permissionGranted = hasCameraPermission
            evaluateDesiredPreview(lensSwitch = false)
        }
    }

    public fun stop() {
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown) return@post
            started = false
            pendingOpen = null
            requestedTarget = activeTarget ?: requestedTarget
            activeGeneration = generation
            closeCameraResources()
            mutableState.value = CameraSessionState.Stopped
        }
    }

    public fun shutdown() {
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown) return@post
            shutdown = true
            started = false
            pendingOpen = null
            activeGeneration = generation
            closeCameraResources()
            mutableState.value = CameraSessionState.Stopped
            cameraThread.quitSafely()
        }
    }

    public fun updatePermission(granted: Boolean) {
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown) return@post
            permissionGranted = granted
            activeGeneration = generation
            if (!granted) {
                pendingOpen = null
                closeCameraResources()
                mutableState.value = CameraSessionState.AwaitingPermission
            } else {
                evaluateDesiredPreview(lensSwitch = false)
            }
        }
    }

    public fun attachSurface(texture: SurfaceTexture) {
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown) return@post
            if (surfaceTexture !== texture) {
                surfaceTexture = texture
                bootstrapTarget = null
            }
            activeGeneration = generation
            record(CameraTraceEvent.SURFACE_READY, generation)
            evaluateDesiredPreview(lensSwitch = false)
        }
    }

    public fun detachSurface(texture: SurfaceTexture) {
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown || surfaceTexture !== texture) return@post
            surfaceTexture = null
            pendingOpen = null
            activeGeneration = generation
            closeCameraResources()
            mutableState.value = if (started && !permissionGranted) {
                CameraSessionState.AwaitingPermission
            } else if (started) {
                CameraSessionState.AwaitingSurface
            } else {
                CameraSessionState.Stopped
            }
        }
    }

    public fun selectProfile(profile: CameraProfile): Boolean {
        val target = PreviewTargetFactory.fromProfile(profile) ?: return false
        if (target.route.routingMethod == RoutingMethod.LOGICAL_PHYSICAL_MEMBER && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        val generation = generationCounter.incrementAndGet()
        cameraHandler.post {
            if (shutdown) return@post
            requestedTarget = target
            activeGeneration = generation
            record(CameraTraceEvent.LENS_SWITCH_REQUEST, generation)
            if (!started || !permissionGranted || surfaceTexture == null) {
                evaluateDesiredPreview(lensSwitch = true)
            } else {
                requestOpen(target, generation, lensSwitch = true)
            }
        }
        return true
    }

    public fun markPreviewFrame(texture: SurfaceTexture) {
        cameraHandler.post {
            if (shutdown || surfaceTexture !== texture) return@post
            val current = mutableState.value as? CameraSessionState.Previewing ?: return@post
            if (current.generation != activeGeneration || current.firstFrameSeen) return@post
            mutableState.value = current.copy(firstFrameSeen = true)
            record(
                if (activeLensSwitch) CameraTraceEvent.LENS_SWITCH_NEW_FIRST_FRAME
                else CameraTraceEvent.FIRST_PREVIEW_FRAME,
                current.generation,
            )
        }
    }

    public fun traceSnapshot(): List<CameraTracePoint> = traceBuffer.snapshot()

    private fun evaluateDesiredPreview(lensSwitch: Boolean) {
        if (!started) {
            mutableState.value = CameraSessionState.Stopped
            return
        }
        if (!permissionGranted || appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = CameraSessionState.AwaitingPermission
            return
        }
        if (surfaceTexture == null) {
            mutableState.value = CameraSessionState.AwaitingSurface
            return
        }

        val target = requestedTarget ?: bootstrapTarget ?: run {
            val selectingGeneration = generationCounter.incrementAndGet()
            activeGeneration = selectingGeneration
            mutableState.value = CameraSessionState.Selecting(selectingGeneration)
            bootstrapResolver.resolve()?.also { bootstrapTarget = it }
        }
        if (target == null) {
            val generation = generationCounter.get()
            mutableState.value = CameraSessionState.Failed(generation, null, "No preview-capable public camera route was found")
            return
        }
        val generation = generationCounter.incrementAndGet()
        activeGeneration = generation
        requestOpen(target, generation, lensSwitch)
    }

    private fun requestOpen(target: PreviewTarget, generation: Long, lensSwitch: Boolean) {
        if (generation != generationCounter.get()) return
        pendingOpen = OpenIntent(generation, target, lensSwitch)

        if (cameraDevice != null || captureSession != null || previewSurface != null) {
            closeCameraResources()
            if (!closingDevice && openingGeneration == null) openPendingIfPossible()
            return
        }
        if (!closingDevice && openingGeneration == null) openPendingIfPossible()
    }

    private fun openPendingIfPossible() {
        val intent = pendingOpen ?: return
        if (!started || !permissionGranted || surfaceTexture == null || shutdown) return
        if (intent.generation != generationCounter.get()) {
            pendingOpen = null
            return
        }
        if (openingGeneration != null || cameraDevice != null || closingDevice) return

        openingGeneration = intent.generation
        activeGeneration = intent.generation
        mutableState.value = CameraSessionState.Opening(intent.generation, intent.target)
        record(CameraTraceEvent.OPEN_REQUESTED, intent.generation)

        try {
            cameraManager.openCamera(
                intent.target.route.logicalCameraId.value,
                cameraDeviceCallback(intent),
                cameraHandler,
            )
        } catch (error: CameraAccessException) {
            openingGeneration = null
            pendingOpen = null
            fail(intent, "Camera open failed: ${error.reason}")
        } catch (_: SecurityException) {
            openingGeneration = null
            pendingOpen = null
            fail(intent, "Camera permission was revoked")
        } catch (_: IllegalArgumentException) {
            openingGeneration = null
            pendingOpen = null
            fail(intent, "Camera route is no longer available")
        }
    }

    private fun cameraDeviceCallback(intent: OpenIntent): CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            openingGeneration = null
            if (!isCurrent(intent)) {
                closingDevice = true
                camera.close()
                return
            }
            cameraDevice = camera
            activeTarget = intent.target
            activeLensSwitch = intent.lensSwitch
            pendingOpen = null
            record(CameraTraceEvent.CAMERA_OPENED, intent.generation)
            configureSession(camera, intent)
        }

        override fun onDisconnected(camera: CameraDevice) {
            handleDeviceLoss(camera, intent, "Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            handleDeviceLoss(camera, intent, "Camera device error $error")
        }

        override fun onClosed(camera: CameraDevice) {
            if (cameraDevice === camera) cameraDevice = null
            closingDevice = false
            if (!shutdown) openPendingIfPossible()
        }
    }

    private fun configureSession(camera: CameraDevice, intent: OpenIntent) {
        if (!isCurrent(intent) || cameraDevice !== camera) {
            closingDevice = true
            camera.close()
            return
        }
        val texture = surfaceTexture ?: run {
            closingDevice = true
            camera.close()
            return
        }

        try {
            texture.setDefaultBufferSize(intent.target.streamSize.width, intent.target.streamSize.height)
            previewSurface?.release()
            val surface = Surface(texture)
            previewSurface = surface
            mutableState.value = CameraSessionState.Configuring(intent.generation, intent.target)

            if (
                intent.target.route.routingMethod == RoutingMethod.LOGICAL_PHYSICAL_MEMBER &&
                intent.target.route.physicalCameraId != null
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    surface.release()
                    previewSurface = null
                    fail(intent, "Physical camera routing requires API 28+")
                    closingDevice = true
                    camera.close()
                    return
                }
                val output = OutputConfiguration(surface).apply {
                    setPhysicalCameraId(intent.target.route.physicalCameraId.value)
                }
                camera.createCaptureSessionByOutputConfigurations(
                    listOf(output),
                    sessionCallback(camera, intent, surface),
                    cameraHandler,
                )
            } else {
                camera.createCaptureSession(
                    listOf(surface),
                    sessionCallback(camera, intent, surface),
                    cameraHandler,
                )
            }
        } catch (error: CameraAccessException) {
            fail(intent, "Preview session failed: ${error.reason}")
            closingDevice = true
            camera.close()
        } catch (_: IllegalArgumentException) {
            fail(intent, "Preview stream configuration was rejected")
            closingDevice = true
            camera.close()
        } catch (_: IllegalStateException) {
            fail(intent, "Camera closed while configuring preview")
            closingDevice = true
            camera.close()
        }
    }

    private fun sessionCallback(
        camera: CameraDevice,
        intent: OpenIntent,
        surface: Surface,
    ): CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (!isCurrent(intent) || cameraDevice !== camera || previewSurface !== surface) {
                session.close()
                return
            }
            captureSession?.close()
            captureSession = session
            repeatingCaptureCount = 0
            record(CameraTraceEvent.SESSION_CONFIGURED, intent.generation)

            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                session.setRepeatingRequest(request, repeatingCaptureCallback(intent), cameraHandler)
                mutableState.value = CameraSessionState.Previewing(
                    generation = intent.generation,
                    target = intent.target,
                    firstFrameSeen = false,
                )
            } catch (error: CameraAccessException) {
                fail(intent, "Repeating preview failed: ${error.reason}")
                closeCameraResources()
            } catch (_: IllegalStateException) {
                fail(intent, "Preview session closed unexpectedly")
                closeCameraResources()
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            session.close()
            if (isCurrent(intent)) {
                fail(intent, "Camera rejected the preview session")
                closeCameraResources()
            }
        }

        override fun onClosed(session: CameraCaptureSession) {
            if (captureSession === session) captureSession = null
        }
    }

    private fun repeatingCaptureCallback(intent: OpenIntent): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (!isCurrent(intent) || captureSession !== session) return
                if (repeatingCaptureCount < STABLE_CAPTURE_COUNT) {
                    repeatingCaptureCount++
                    if (repeatingCaptureCount == STABLE_CAPTURE_COUNT) {
                        record(CameraTraceEvent.PREVIEW_STABLE, intent.generation)
                    }
                }
            }
        }

    private fun handleDeviceLoss(camera: CameraDevice, intent: OpenIntent, message: String) {
        if (openingGeneration == intent.generation) openingGeneration = null
        if (cameraDevice === camera) cameraDevice = null
        closingDevice = true
        camera.close()
        if (isCurrent(intent)) fail(intent, message)
    }

    private fun closeCameraResources() {
        try {
            captureSession?.stopRepeating()
        } catch (_: CameraAccessException) {
            // Closing is best-effort; resource close below is authoritative.
        } catch (_: IllegalStateException) {
            // Session is already closed.
        }
        captureSession?.close()
        captureSession = null
        previewSurface?.release()
        previewSurface = null
        val device = cameraDevice
        cameraDevice = null
        if (device != null) {
            closingDevice = true
            device.close()
        }
        repeatingCaptureCount = 0
    }

    private fun isCurrent(intent: OpenIntent): Boolean =
        started && !shutdown && permissionGranted && surfaceTexture != null &&
            intent.generation == generationCounter.get()

    private fun fail(intent: OpenIntent, message: String) {
        if (intent.generation != generationCounter.get()) return
        mutableState.value = CameraSessionState.Failed(intent.generation, intent.target, message)
    }

    private fun record(event: CameraTraceEvent, generation: Long, argument: Long = 0L) {
        traceBuffer.record(
            CameraTracePoint(
                event = event,
                monotonicNanos = SystemClock.elapsedRealtimeNanos(),
                generation = generation,
                argument = argument,
            ),
        )
    }

    private companion object {
        const val STABLE_CAPTURE_COUNT = 3
    }
}
