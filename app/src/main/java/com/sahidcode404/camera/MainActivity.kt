package com.sahidcode404.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.sahidcode404.camera.core.camera.discovery.DiscoveryCoordinator
import com.sahidcode404.camera.core.camera.discovery.DiscoveryState
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CanonicalLens
import com.sahidcode404.camera.core.camera.orientation.PreviewGeometryEngine
import com.sahidcode404.camera.core.camera.preview.CameraPreviewTextureView
import com.sahidcode404.camera.core.camera.preview.PreviewAspectMode
import com.sahidcode404.camera.core.camera.session.CameraSessionController
import com.sahidcode404.camera.core.camera.session.CameraSessionState
import com.sahidcode404.camera.core.camera.session.PreviewTarget
import com.sahidcode404.camera.core.camera.session.PreviewTargetFactory
import com.sahidcode404.camera.core.camera.session.RawCaptureState
import com.sahidcode404.camera.core.camera.session.isBusy
import com.sahidcode404.camera.core.camera.session.toHotPreviewSeed
import com.sahidcode404.camera.ota.DevelopmentUpdateState
import com.sahidcode404.camera.ota.DevelopmentUpdater
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var developmentUpdater: DevelopmentUpdater
    private lateinit var discoveryCoordinator: DiscoveryCoordinator
    private lateinit var cameraSessionController: CameraSessionController
    private var cameraPermissionGranted by mutableStateOf(false)
    private var pendingRawCaptureAfterStoragePermission = false
    private var postPreviewWorkStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        developmentUpdater = DevelopmentUpdater(applicationContext)
        discoveryCoordinator = DiscoveryCoordinator(applicationContext)
        cameraSessionController = CameraSessionController(applicationContext)

        setContent {
            val updateState by developmentUpdater.state.collectAsState()
            val discoveryState by discoveryCoordinator.state.collectAsState()
            val sessionState by cameraSessionController.state.collectAsState()
            val rawCaptureState by cameraSessionController.rawCaptureState.collectAsState()
            var aspectMode by remember { mutableStateOf(PreviewAspectMode.FOUR_THREE) }

            val renderedPreview = (sessionState as? CameraSessionState.Previewing)
                ?.takeIf { it.firstFrameSeen }
            val firstFrameGeneration = renderedPreview?.generation
            LaunchedEffect(firstFrameGeneration) {
                val verifiedPreview = renderedPreview ?: return@LaunchedEffect
                discoveryCoordinator.recordPreviewVerified(verifiedPreview.target.toHotPreviewSeed())
                if (!postPreviewWorkStarted) {
                    postPreviewWorkStarted = true
                    discoveryCoordinator.refresh(verifiedPreviewRoute = verifiedPreview.target.route)
                    if (BuildConfig.OTA_CHANNEL == "development") {
                        developmentUpdater.checkForUpdates()
                    }
                }
            }

            val savedRaw = rawCaptureState as? RawCaptureState.Saved
            LaunchedEffect(savedRaw?.captureId) {
                val saved = savedRaw ?: return@LaunchedEffect
                discoveryCoordinator.recordRawVerified(saved.route)
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraScreen(
                        permissionGranted = cameraPermissionGranted,
                        sessionState = sessionState,
                        rawCaptureState = rawCaptureState,
                        discoveryState = discoveryState,
                        updateState = updateState,
                        aspectMode = aspectMode,
                        controller = cameraSessionController,
                        onAspectMode = { aspectMode = it },
                        onRequestPermission = ::requestCameraPermission,
                        onCaptureRaw = ::requestRawCapture,
                        onRefreshDiscovery = {
                            val verifiedRoute = (sessionState as? CameraSessionState.Previewing)
                                ?.takeIf { it.firstFrameSeen }
                                ?.target
                                ?.route
                            lifecycleScope.launch {
                                discoveryCoordinator.refresh(verifiedPreviewRoute = verifiedRoute)
                            }
                        },
                        onCheckUpdate = {
                            lifecycleScope.launch { developmentUpdater.checkForUpdates() }
                        },
                        onInstallUpdate = {
                            lifecycleScope.launch { developmentUpdater.installVerifiedUpdate() }
                        },
                    )
                }
            }
        }

        if (!cameraPermissionGranted) requestCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        cameraSessionController.start(cameraPermissionGranted)
    }

    override fun onResume() {
        super.onResume()
        if (::developmentUpdater.isInitialized) {
            lifecycleScope.launch { developmentUpdater.resumeInstallIfPermissionGranted() }
        }
    }

    override fun onStop() {
        cameraSessionController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cameraSessionController.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                cameraPermissionGranted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                cameraSessionController.updatePermission(cameraPermissionGranted)
            }
            STORAGE_PERMISSION_REQUEST -> {
                val captureAfterGrant = pendingRawCaptureAfterStoragePermission
                pendingRawCaptureAfterStoragePermission = false
                if (
                    captureAfterGrant &&
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraSessionController.captureRawDng()
                }
            }
        }
    }

    private fun requestCameraPermission() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun requestRawCapture() {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRawCaptureAfterStoragePermission = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST,
            )
            return
        }
        cameraSessionController.captureRawDng()
    }

    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 1001
        const val STORAGE_PERMISSION_REQUEST = 1002
    }
}

@Composable
private fun CameraScreen(
    permissionGranted: Boolean,
    sessionState: CameraSessionState,
    rawCaptureState: RawCaptureState,
    discoveryState: DiscoveryState,
    updateState: DevelopmentUpdateState,
    aspectMode: PreviewAspectMode,
    controller: CameraSessionController,
    onAspectMode: (PreviewAspectMode) -> Unit,
    onRequestPermission: () -> Unit,
    onCaptureRaw: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val target = sessionState.targetOrNull()
    val topology = discoveryState.topologyOrNull()
    val previewReady = (sessionState as? CameraSessionState.Previewing)?.firstFrameSeen == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Camera", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(sessionState.shortLabel(), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }

        if (permissionGranted) {
            PreviewViewport(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                controller = controller,
                target = target,
                aspectMode = aspectMode,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission is required for preview.", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermission) { Text("Allow camera") }
                }
            }
        }

        AspectModeStrip(aspectMode = aspectMode, onAspectMode = onAspectMode)

        if (topology != null) {
            LensStrip(
                topology = topology,
                activeTarget = target,
                onLens = { lens ->
                    val selectedTarget = PreviewTargetFactory.bestTargetForLens(topology, lens)
                    val profile = selectedTarget?.let { targetValue ->
                        topology.profiles.firstOrNull { it.profileId == targetValue.stableId }
                    }
                    if (profile != null) controller.selectProfile(profile)
                },
            )
        }

        Button(
            onClick = onCaptureRaw,
            enabled = previewReady && !rawCaptureState.isBusy,
        ) {
            Text(if (rawCaptureState.isBusy) "RAW…" else "RAW DNG")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRefreshDiscovery) { Text("Discovery") }
            if (BuildConfig.OTA_CHANNEL == "development") {
                OutlinedButton(onClick = onCheckUpdate, enabled = updateState !is DevelopmentUpdateState.Checking) {
                    Text("Update")
                }
                if (updateState is DevelopmentUpdateState.Ready || updateState is DevelopmentUpdateState.PermissionRequired) {
                    Button(onClick = onInstallUpdate) { Text("Install") }
                }
            }
        }

        Text(
            text = discoveryState.compactLabel() + " · " + rawCaptureState.compactLabel() + " · " + updateState.compactLabel(),
            color = Color.LightGray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
        )
    }
}

@Composable
private fun PreviewViewport(
    modifier: Modifier,
    controller: CameraSessionController,
    target: PreviewTarget?,
    aspectMode: PreviewAspectMode,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val availableAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
        val portrait = maxHeight >= maxWidth
        val sensorAspect = target?.sensorLandscapeAspect ?: 4.0 / 3.0
        val presentationAspect = if (aspectMode == PreviewAspectMode.FULL) {
            availableAspect.toDouble()
        } else {
            PreviewGeometryEngine.presentationAspect(
                mode = aspectMode,
                sensorLandscapeAspect = sensorAspect,
                usableDisplayAspect = availableAspect.toDouble(),
                portraitPresentation = portrait,
            )
        }.toFloat()
        val fitted = fitInside(maxWidth, maxHeight, presentationAspect)

        AndroidView(
            factory = { context ->
                CameraPreviewTextureView(context).apply {
                    bindController(controller)
                    setPreviewTarget(target)
                }
            },
            update = { view ->
                view.bindController(controller)
                view.setPreviewTarget(target)
            },
            modifier = Modifier
                .size(fitted.first, fitted.second)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
        )
    }
}

@Composable
private fun AspectModeStrip(
    aspectMode: PreviewAspectMode,
    onAspectMode: (PreviewAspectMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PreviewAspectMode.entries.forEach { mode ->
            if (mode == aspectMode) {
                Button(onClick = { onAspectMode(mode) }) { Text(mode.label()) }
            } else {
                OutlinedButton(onClick = { onAspectMode(mode) }) { Text(mode.label()) }
            }
        }
    }
}

@Composable
private fun LensStrip(
    topology: CameraTopology,
    activeTarget: PreviewTarget?,
    onLens: (CanonicalLens) -> Unit,
) {
    val backLenses = topology.lenses.filter { it.facing == CameraFacing.BACK }
    val frontLenses = topology.lenses.filter { it.facing == CameraFacing.FRONT }
    val lenses = if (activeTarget?.facing == CameraFacing.FRONT && frontLenses.isNotEmpty()) frontLenses else backLenses
    if (lenses.isEmpty()) return
    val labels = remember(topology) { dynamicLensLabels(topology, lenses) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lenses.forEach { lens ->
            val selected = activeTarget?.stableId in lens.profileIds
            if (selected) {
                Button(onClick = { onLens(lens) }) { Text(labels[lens.lensId] ?: "Lens") }
            } else {
                OutlinedButton(onClick = { onLens(lens) }) { Text(labels[lens.lensId] ?: "Lens") }
            }
        }
    }
}

private fun fitInside(maxWidth: Dp, maxHeight: Dp, aspect: Float): Pair<Dp, Dp> {
    if (aspect <= 0f || maxWidth.value <= 0f || maxHeight.value <= 0f) return maxWidth to maxHeight
    val availableAspect = maxWidth.value / maxHeight.value
    return if (availableAspect > aspect) {
        (maxHeight * aspect) to maxHeight
    } else {
        maxWidth to (maxWidth / aspect)
    }
}

private fun dynamicLensLabels(topology: CameraTopology, lenses: List<CanonicalLens>): Map<String, String> {
    data class Optics(val lens: CanonicalLens, val normalizedFocal: Double, val diagonalFov: Double)

    val optics = lenses.mapNotNull { lens ->
        val profile = lens.profileIds.asSequence()
            .mapNotNull { id -> topology.profiles.firstOrNull { it.profileId == id } }
            .firstOrNull { profile ->
                profile.capabilities.sensorPhysicalSizeMm != null && profile.capabilities.focalLengthsMm.isNotEmpty()
            } ?: return@mapNotNull null
        val sensor = profile.capabilities.sensorPhysicalSizeMm ?: return@mapNotNull null
        val focal = profile.capabilities.focalLengthsMm.filter { it > 0f }.minOrNull() ?: return@mapNotNull null
        val sensorDiagonal = hypot(sensor.width.toDouble(), sensor.height.toDouble())
        if (sensorDiagonal <= 0.0) return@mapNotNull null
        val normalized = focal.toDouble() / sensorDiagonal
        val fov = 2.0 * atan(1.0 / (2.0 * normalized)) * 180.0 / PI
        Optics(lens, normalized, fov)
    }
    if (optics.isEmpty()) return lenses.associate { it.lensId to "Lens" }
    val reference = optics.minByOrNull { abs(it.diagonalFov - 75.0) } ?: optics.first()
    return lenses.associate { lens ->
        val value = optics.firstOrNull { it.lens.lensId == lens.lensId }
        val label = if (value == null) {
            "Lens"
        } else {
            val zoom = value.normalizedFocal / reference.normalizedFocal
            String.format(Locale.US, "%.1fx", zoom)
        }
        lens.lensId to label
    }
}

private fun PreviewAspectMode.label(): String = when (this) {
    PreviewAspectMode.SENSOR -> "Sensor"
    PreviewAspectMode.SQUARE -> "1:1"
    PreviewAspectMode.FOUR_THREE -> "4:3"
    PreviewAspectMode.SIXTEEN_NINE -> "16:9"
    PreviewAspectMode.FULL -> "Full"
}

private fun CameraSessionState.targetOrNull(): PreviewTarget? = when (this) {
    CameraSessionState.Stopped,
    CameraSessionState.AwaitingPermission,
    CameraSessionState.AwaitingSurface,
    is CameraSessionState.Selecting,
    -> null
    is CameraSessionState.Opening -> target
    is CameraSessionState.Configuring -> target
    is CameraSessionState.Previewing -> target
    is CameraSessionState.Failed -> target
}

private fun CameraSessionState.shortLabel(): String = when (this) {
    CameraSessionState.Stopped -> "Stopped"
    CameraSessionState.AwaitingPermission -> "Permission"
    CameraSessionState.AwaitingSurface -> "Surface"
    is CameraSessionState.Selecting -> "Selecting"
    is CameraSessionState.Opening -> "Opening"
    is CameraSessionState.Configuring -> "Configuring"
    is CameraSessionState.Previewing -> if (firstFrameSeen) "Preview" else "Starting"
    is CameraSessionState.Failed -> "Preview error"
}

private fun DiscoveryState.topologyOrNull(): CameraTopology? = when (this) {
    DiscoveryState.Idle -> null
    is DiscoveryState.Cached -> topology
    is DiscoveryState.Discovering -> cachedTopology
    is DiscoveryState.Complete -> topology
    is DiscoveryState.Failed -> cachedTopology
}

private fun DiscoveryState.compactLabel(): String = when (this) {
    DiscoveryState.Idle -> "Discovery waits for first frame"
    is DiscoveryState.Cached -> "Cached topology"
    is DiscoveryState.Discovering -> "Discovering"
    is DiscoveryState.Complete -> "${topology.lenses.size} lenses"
    is DiscoveryState.Failed -> "Discovery failed"
}

private fun RawCaptureState.compactLabel(): String = when (this) {
    RawCaptureState.Idle -> "RAW ready"
    is RawCaptureState.Preparing -> "RAW preparing"
    is RawCaptureState.Capturing -> "RAW capturing ${size.width}×${size.height}"
    is RawCaptureState.Saving -> "RAW saving"
    is RawCaptureState.Saved -> "DNG saved ${size.width}×${size.height}"
    is RawCaptureState.Unsupported -> "RAW unsupported"
    is RawCaptureState.Failed -> "RAW failed"
}

private fun DevelopmentUpdateState.compactLabel(): String = when (this) {
    DevelopmentUpdateState.Disabled -> "OTA off"
    DevelopmentUpdateState.Idle -> "OTA ready"
    DevelopmentUpdateState.Checking -> "OTA checking"
    DevelopmentUpdateState.UpToDate -> "Up to date"
    is DevelopmentUpdateState.Downloading -> "OTA $percent%"
    is DevelopmentUpdateState.Ready -> "Update $versionName ready"
    is DevelopmentUpdateState.PermissionRequired -> "Installer permission"
    is DevelopmentUpdateState.InstallerLaunched -> "Installer opened"
    is DevelopmentUpdateState.Failed -> "OTA failed"
}
