package com.sahidcode404.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sahidcode404.camera.core.camera.discovery.DiscoveryCoordinator
import com.sahidcode404.camera.core.camera.discovery.DiscoveryState
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.ota.DevelopmentUpdateState
import com.sahidcode404.camera.ota.DevelopmentUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var developmentUpdater: DevelopmentUpdater
    private lateinit var discoveryCoordinator: DiscoveryCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        developmentUpdater = DevelopmentUpdater(applicationContext)
        discoveryCoordinator = DiscoveryCoordinator(applicationContext)
        setContent {
            val updateState by developmentUpdater.state.collectAsState()
            val discoveryState by discoveryCoordinator.state.collectAsState()

            LaunchedEffect(discoveryCoordinator, developmentUpdater) {
                // Phase 1 is metadata-only and never opens a CameraDevice. When Phase 2 provides a
                // production viewfinder, both deep discovery and OTA move behind FIRST_PREVIEW_FRAME.
                delay(250)
                discoveryCoordinator.refresh()
                if (BuildConfig.OTA_CHANNEL == "development") {
                    developmentUpdater.checkForUpdates()
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FoundationScreen(
                        discoveryState = discoveryState,
                        updateState = updateState,
                        onRefreshDiscovery = {
                            lifecycleScope.launch { discoveryCoordinator.refresh() }
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
    }

    override fun onResume() {
        super.onResume()
        if (::developmentUpdater.isInitialized) {
            lifecycleScope.launch { developmentUpdater.resumeInstallIfPermissionGranted() }
        }
    }
}

@Composable
private fun FoundationScreen(
    discoveryState: DiscoveryState,
    updateState: DevelopmentUpdateState,
    onRefreshDiscovery: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Camera", style = MaterialTheme.typography.headlineLarge)
            Text("Phase 1 · universal discovery")
            Text("Metadata discovery only — no CameraDevice is opened yet.")
            Text(discoveryState.label())
            discoveryState.topologyOrNull()?.let { topology ->
                DiscoverySummary(topology)
            }
            Button(
                onClick = onRefreshDiscovery,
                enabled = discoveryState !is DiscoveryState.Discovering,
            ) {
                Text("Refresh camera discovery")
            }

            Text(updateState.label())
            if (BuildConfig.OTA_CHANNEL == "development") {
                Button(onClick = onCheckUpdate, enabled = updateState !is DevelopmentUpdateState.Checking) {
                    Text("Check development update")
                }
                if (
                    updateState is DevelopmentUpdateState.Ready ||
                    updateState is DevelopmentUpdateState.PermissionRequired
                ) {
                    Button(onClick = onInstallUpdate) {
                        Text("Install verified update")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySummary(topology: CameraTopology) {
    val diagnostics = topology.diagnostics
    Text("Public IDs: ${diagnostics.advertisedCameraCount}")
    Text("Profiles: ${diagnostics.profileCount} · Canonical lenses: ${diagnostics.canonicalLensCount}")
    Text("Discovery issues: ${diagnostics.failures.size + diagnostics.truncatedFailureCount}")
    val ndk = diagnostics.ndk
    Text(
        if (ndk.available) {
            "NDK evidence: ${ndk.advertisedCameraCount} IDs · differences ${ndk.ndkOnlyCameraIds.size + ndk.javaOnlyCameraIds.size + ndk.metadataMismatchCameraIds.size + ndk.truncatedDifferenceCount}"
        } else {
            "NDK evidence: unavailable${ndk.error?.let { " ($it)" } ?: ""}"
        },
    )
}

private fun DiscoveryState.topologyOrNull(): CameraTopology? = when (this) {
    DiscoveryState.Idle -> null
    is DiscoveryState.Cached -> topology
    is DiscoveryState.Discovering -> cachedTopology
    is DiscoveryState.Complete -> topology
    is DiscoveryState.Failed -> cachedTopology
}

private fun DiscoveryState.label(): String = when (this) {
    DiscoveryState.Idle -> "Discovery idle."
    is DiscoveryState.Cached -> "Validated cached topology loaded."
    is DiscoveryState.Discovering -> if (cachedTopology == null) {
        "Discovering public camera topology…"
    } else {
        "Refreshing cached camera topology…"
    }
    is DiscoveryState.Complete -> "Camera topology discovery complete."
    is DiscoveryState.Failed -> "Discovery: $message"
}

private fun DevelopmentUpdateState.label(): String = when (this) {
    DevelopmentUpdateState.Disabled -> "Development OTA is disabled in this build."
    DevelopmentUpdateState.Idle -> "Development OTA ready."
    DevelopmentUpdateState.Checking -> "Checking for development update…"
    DevelopmentUpdateState.UpToDate -> "Development build is up to date."
    is DevelopmentUpdateState.Downloading -> "Downloading update: $percent%"
    is DevelopmentUpdateState.Ready -> "Development update $versionName is verified and ready."
    is DevelopmentUpdateState.PermissionRequired -> "Allow Camera to install this verified development update."
    is DevelopmentUpdateState.InstallerLaunched -> "Android installer opened for $versionName."
    is DevelopmentUpdateState.Failed -> "Update: $message"
}
