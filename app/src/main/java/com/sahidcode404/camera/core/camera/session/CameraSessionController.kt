package com.sahidcode404.camera.core.camera.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.sahidcode404.camera.core.camera.capture.RawDngMediaStoreWriter
import com.sahidcode404.camera.core.camera.diagnostics.CameraTraceBuffer
import com.sahidcode404.camera.core.camera.diagnostics.CameraTraceEvent
import com.sahidcode404.camera.core.camera.diagnostics.CameraTracePoint
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.IntSizeValue
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

    private data class RawCaptureSpec(
        val size: IntSizeValue,
        val characteristics: CameraCharacteristics,
    )

    private data class RawCaptureMetadata(
        val timestampNs: Long,
        val result: CaptureResult,
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val bootstrapResolver = BootstrapPreviewTargetResolver(appContext, cameraManager)
    private val cameraThread = HandlerThread("CameraSessionController").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val writerThread = HandlerThread("RawDngWriter").apply { start() }
    private val writerHandler = Handler(writerThread.looper)
    private val dngWriter = RawDngMediaStoreWriter(appContext)
    private val generationCounter = AtomicLong(0L)
    private val rawCaptureCounter = AtomicLong(0L)
    private val traceBuffer = CameraTraceBuffer()
    private val mutableState = MutableStateFlow<CameraSessionState>(CameraSessionState.Stopped)
    private val mutableRawCaptureState = MutableStateFlow<RawCaptureState>(RawCaptureState.Idle)

    public val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()
    public val rawCaptureState: StateFlow<RawCaptureState> = mutableRawCaptureState.asStateFlow()

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

    private var rawImageReader: ImageReader? = null
    private var rawCaptureSpec: RawCaptureSpec? = null
    private var rawReadyRoute: CameraRoute? = null
    private var pendingRawCaptureId: Long? = null
    private var pendingRawImage: Image? = null
    private var pendingRawMetadata: RawCaptureMetadata? = null
    private var rawTimeoutRunnable: Runnable? = null
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
            writerThread.quitSafely()
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
            mutableRawCaptureState.value = RawCaptureState.Idle
            record(CameraTraceEvent.LENS_SWITCH_REQUEST, generation)
            if (!started || !permissionGranted || surfaceTexture == null) {
                evaluateDesiredPreview(lensSwitch = true)
            } else {
                requestOpen(target, generation, lensSwitch = true)
            }
        }
        return true
    }

    public fun captureRawDng() {
        cameraHandler.post {
            if (shutdown) return@post
            beginRawCapture()
        }
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
            activeLensSwitch = false
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
            rawReadyRoute = null
            repeatingCaptureCount = 0
            record(CameraTraceEvent.SESSION_CONFIGURED, intent.generation)
            startPreviewRepeating(session, camera, intent)
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

    private fun startPreviewRepeating(
        session: CameraCaptureSession,
        camera: CameraDevice,
        intent: OpenIntent,
    ): Boolean {
        return try {
            val surface = previewSurface ?: return false
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
            true
        } catch (error: CameraAccessException) {
            fail(intent, "Repeating preview failed: ${error.reason}")
            closeCameraResources()
            false
        } catch (_: IllegalStateException) {
            fail(intent, "Preview session closed unexpectedly")
            closeCameraResources()
            false
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

    private fun beginRawCapture() {
        val preview = mutableState.value as? CameraSessionState.Previewing
        val target = preview?.target
        if (preview == null || target == null || !preview.firstFrameSeen) {
            mutableRawCaptureState.value = RawCaptureState.Failed(
                route = target?.route,
                message = "RAW capture requires a rendered preview",
            )
            return
        }
        if (mutableRawCaptureState.value.isBusy) return
        if (cameraDevice == null || captureSession == null || previewSurface == null) {
            mutableRawCaptureState.value = RawCaptureState.Failed(target.route, "Camera session is not ready")
            return
        }

        val captureId = rawCaptureCounter.incrementAndGet()
        pendingRawCaptureId = captureId
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawMetadata = null
        record(CameraTraceEvent.SHUTTER_PRESS, activeGeneration, captureId)

        val existingSpec = rawCaptureSpec
        if (
            rawReadyRoute == target.route &&
            rawImageReader != null &&
            existingSpec != null
        ) {
            issueRawCapture(captureId, target, existingSpec)
            return
        }
        prepareRawSession(captureId, target)
    }

    private fun prepareRawSession(captureId: Long, target: PreviewTarget) {
        mutableRawCaptureState.value = RawCaptureState.Preparing(captureId, target.route)
        val camera = cameraDevice ?: run {
            failRawCapture(target.route, "Camera closed before RAW session preparation")
            return
        }
        val preview = previewSurface ?: run {
            failRawCapture(target.route, "Preview surface unavailable for RAW session")
            return
        }

        val spec = try {
            resolveRawCaptureSpec(target)
        } catch (error: CameraAccessException) {
            failRawCapture(target.route, "RAW metadata access failed: ${error.reason}")
            return
        } catch (_: SecurityException) {
            failRawCapture(target.route, "Camera permission was revoked during RAW preparation")
            return
        } catch (_: IllegalArgumentException) {
            failRawCapture(target.route, "RAW metadata route is no longer available")
            return
        }
        if (spec == null) {
            clearPendingRawCapture()
            mutableRawCaptureState.value = RawCaptureState.Unsupported(
                route = target.route,
                message = "Selected camera route does not advertise RAW_SENSOR output",
            )
            return
        }

        closeRawReader()
        val reader = try {
            ImageReader.newInstance(
                spec.size.width,
                spec.size.height,
                ImageFormat.RAW_SENSOR,
                RAW_MAX_IMAGES,
            )
        } catch (_: IllegalArgumentException) {
            failRawCapture(target.route, "RAW ImageReader configuration was rejected")
            return
        }
        reader.setOnImageAvailableListener({ source -> onRawImageAvailable(source) }, cameraHandler)
        rawImageReader = reader
        rawCaptureSpec = spec
        rawReadyRoute = null

        try {
            try {
                captureSession?.stopRepeating()
            } catch (_: CameraAccessException) {
                // The replacement session below is authoritative.
            } catch (_: IllegalStateException) {
                // The old preview session already closed.
            }
            captureSession?.close()
            captureSession = null
            val rawSurface = reader.surface
            val callback = rawSessionCallback(
                camera = camera,
                target = target,
                captureId = captureId,
                reader = reader,
                preview = preview,
                rawSurface = rawSurface,
                spec = spec,
            )
            if (
                target.route.routingMethod == RoutingMethod.LOGICAL_PHYSICAL_MEMBER &&
                target.route.physicalCameraId != null
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    failRawSessionPreparation(camera, target, "Physical RAW routing requires API 28+")
                    return
                }
                val physicalId = target.route.physicalCameraId.value
                val outputs = listOf(preview, rawSurface).map { surface ->
                    OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) }
                }
                camera.createCaptureSessionByOutputConfigurations(outputs, callback, cameraHandler)
            } else {
                camera.createCaptureSession(listOf(preview, rawSurface), callback, cameraHandler)
            }
        } catch (error: CameraAccessException) {
            failRawSessionPreparation(camera, target, "RAW session failed: ${error.reason}")
        } catch (_: IllegalArgumentException) {
            failRawSessionPreparation(camera, target, "Camera rejected RAW + preview outputs")
        } catch (_: IllegalStateException) {
            failRawSessionPreparation(camera, target, "Camera closed while preparing RAW capture")
        }
    }

    private fun rawSessionCallback(
        camera: CameraDevice,
        target: PreviewTarget,
        captureId: Long,
        reader: ImageReader,
        preview: Surface,
        rawSurface: Surface,
        spec: RawCaptureSpec,
    ): CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val intent = OpenIntent(activeGeneration, target, lensSwitch = false)
            if (
                !isCurrent(intent) ||
                cameraDevice !== camera ||
                previewSurface !== preview ||
                rawImageReader !== reader ||
                reader.surface !== rawSurface ||
                pendingRawCaptureId != captureId
            ) {
                session.close()
                return
            }
            captureSession = session
            rawReadyRoute = target.route
            repeatingCaptureCount = 0
            record(CameraTraceEvent.SESSION_CONFIGURED, activeGeneration)
            activeLensSwitch = false
            if (startPreviewRepeating(session, camera, intent)) {
                issueRawCapture(captureId, target, spec)
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            session.close()
            if (pendingRawCaptureId == captureId) {
                failRawSessionPreparation(camera, target, "Camera rejected RAW + preview session")
            }
        }

        override fun onClosed(session: CameraCaptureSession) {
            if (captureSession === session) captureSession = null
        }
    }

    private fun resolveRawCaptureSpec(target: PreviewTarget): RawCaptureSpec? {
        val characteristicsId = target.route.physicalCameraId?.value ?: target.route.logicalCameraId.value
        val characteristics = cameraManager.getCameraCharacteristics(characteristicsId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = try {
            streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
        } catch (_: IllegalArgumentException) {
            null
        }.orEmpty().filter { it.width > 0 && it.height > 0 }.take(MAX_RAW_SIZES)
        val selected = sizes.maxWithOrNull(
            compareBy<android.util.Size>({ it.width.toLong() * it.height.toLong() }, { it.width }, { it.height }),
        ) ?: return null
        return RawCaptureSpec(
            size = IntSizeValue(selected.width, selected.height),
            characteristics = characteristics,
        )
    }

    private fun issueRawCapture(captureId: Long, target: PreviewTarget, spec: RawCaptureSpec) {
        if (pendingRawCaptureId != captureId) return
        val session = captureSession ?: run {
            failRawCapture(target.route, "RAW-ready session disappeared")
            return
        }
        val camera = cameraDevice ?: run {
            failRawCapture(target.route, "Camera closed before RAW capture")
            return
        }
        val reader = rawImageReader ?: run {
            failRawCapture(target.route, "RAW reader unavailable")
            return
        }
        if (rawReadyRoute != target.route) {
            failRawCapture(target.route, "RAW session route changed")
            return
        }

        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawMetadata = null
        mutableRawCaptureState.value = RawCaptureState.Capturing(captureId, target.route, spec.size)
        scheduleRawTimeout(captureId, target.route)

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                previewSurface?.let(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            session.capture(request, rawCaptureCallback(captureId, target, spec), cameraHandler)
        } catch (error: CameraAccessException) {
            failRawCapture(target.route, "RAW capture request failed: ${error.reason}")
        } catch (_: IllegalStateException) {
            failRawCapture(target.route, "RAW capture session closed unexpectedly")
        } catch (_: IllegalArgumentException) {
            failRawCapture(target.route, "RAW capture request was rejected")
        }
    }

    private fun rawCaptureCallback(
        captureId: Long,
        target: PreviewTarget,
        spec: RawCaptureSpec,
    ): CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (pendingRawCaptureId != captureId || captureSession !== session) return
            val metadata = captureResultForTarget(result, target) ?: run {
                failRawCapture(target.route, "Physical RAW capture metadata was not returned")
                return
            }
            val timestampNs = metadata.get(CaptureResult.SENSOR_TIMESTAMP) ?: run {
                failRawCapture(target.route, "RAW capture metadata has no sensor timestamp")
                return
            }
            pendingRawMetadata = RawCaptureMetadata(timestampNs, metadata)
            dispatchRawWriteIfPaired(captureId, target, spec)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            if (pendingRawCaptureId != captureId) return
            failRawCapture(target.route, "RAW capture failed: reason=${failure.reason}")
        }
    }

    private fun captureResultForTarget(
        result: TotalCaptureResult,
        target: PreviewTarget,
    ): CaptureResult? {
        val physicalId = target.route.physicalCameraId?.value ?: return result
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return result.physicalCameraResults[physicalId]
    }

    private fun onRawImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        val captureId = pendingRawCaptureId
        val target = activeTarget
        val spec = rawCaptureSpec
        if (
            captureId == null ||
            target == null ||
            spec == null ||
            rawImageReader !== reader ||
            rawReadyRoute != target.route
        ) {
            image.close()
            return
        }
        pendingRawImage?.close()
        pendingRawImage = image
        dispatchRawWriteIfPaired(captureId, target, spec)
    }

    private fun dispatchRawWriteIfPaired(captureId: Long, target: PreviewTarget, spec: RawCaptureSpec) {
        if (pendingRawCaptureId != captureId) return
        val image = pendingRawImage ?: return
        val metadata = pendingRawMetadata ?: return
        if (image.timestamp != metadata.timestampNs) {
            failRawCapture(
                target.route,
                "RAW timestamp mismatch: image=${image.timestamp} result=${metadata.timestampNs}",
            )
            return
        }

        pendingRawImage = null
        pendingRawMetadata = null
        pendingRawCaptureId = null
        cancelRawTimeout()
        mutableRawCaptureState.value = RawCaptureState.Saving(captureId, target.route, spec.size)
        record(CameraTraceEvent.DNG_WRITE_START, activeGeneration, captureId)

        writerHandler.post {
            val outcome = runCatching {
                dngWriter.write(
                    captureId = captureId,
                    characteristics = spec.characteristics,
                    result = metadata.result,
                    image = image,
                    size = spec.size,
                )
            }
            image.close()
            cameraHandler.post {
                outcome.fold(
                    onSuccess = { published ->
                        record(CameraTraceEvent.DNG_WRITE_END, activeGeneration, captureId)
                        record(CameraTraceEvent.MEDIASTORE_PUBLISHED, activeGeneration, captureId)
                        mutableRawCaptureState.value = RawCaptureState.Saved(
                            captureId = captureId,
                            route = target.route,
                            size = spec.size,
                            uri = published.uri,
                        )
                    },
                    onFailure = { error ->
                        mutableRawCaptureState.value = RawCaptureState.Failed(
                            route = target.route,
                            message = error.message?.takeIf { it.isNotBlank() }
                                ?: "RAW DNG write failed: ${error.javaClass.simpleName}",
                        )
                    },
                )
            }
        }
    }

    private fun scheduleRawTimeout(captureId: Long, route: CameraRoute) {
        cancelRawTimeout()
        val task = Runnable {
            if (pendingRawCaptureId == captureId) {
                failRawCapture(route, "RAW image/result pairing timed out")
            }
        }
        rawTimeoutRunnable = task
        cameraHandler.postDelayed(task, RAW_CAPTURE_TIMEOUT_MS)
    }

    private fun cancelRawTimeout() {
        rawTimeoutRunnable?.let(cameraHandler::removeCallbacks)
        rawTimeoutRunnable = null
    }

    private fun failRawCapture(route: CameraRoute?, message: String) {
        cancelRawTimeout()
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawMetadata = null
        pendingRawCaptureId = null
        mutableRawCaptureState.value = RawCaptureState.Failed(route, message)
    }

    private fun failRawSessionPreparation(camera: CameraDevice, target: PreviewTarget, message: String) {
        failRawCapture(target.route, message)
        closeRawReader()
        val intent = OpenIntent(activeGeneration, target, lensSwitch = false)
        if (cameraDevice === camera && isCurrent(intent)) {
            configureSession(camera, intent)
        }
    }

    private fun clearPendingRawCapture() {
        cancelRawTimeout()
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawMetadata = null
        pendingRawCaptureId = null
    }

    private fun closeRawReader() {
        clearPendingRawCapture()
        rawImageReader?.close()
        rawImageReader = null
        rawCaptureSpec = null
        rawReadyRoute = null
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
        closeRawReader()
        mutableRawCaptureState.value = RawCaptureState.Idle
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
        const val RAW_MAX_IMAGES = 2
        const val MAX_RAW_SIZES = 64
        const val RAW_CAPTURE_TIMEOUT_MS = 4_000L
    }
}
