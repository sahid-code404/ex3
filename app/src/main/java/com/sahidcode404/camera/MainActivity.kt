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
import com.sahidcode404.camera.ota.DevelopmentUpdateState
import com.sahidcode404.camera.ota.DevelopmentUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var developmentUpdater: DevelopmentUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        developmentUpdater = DevelopmentUpdater(applicationContext)
        setContent {
            val updateState by developmentUpdater.state.collectAsState()
            LaunchedEffect(developmentUpdater) {
                if (BuildConfig.OTA_CHANNEL == "development") {
                    // Phase 0 has no production camera preview yet. This non-blocking trigger moves
                    // behind FIRST_PREVIEW_FRAME when Phase 2 wires the production viewfinder.
                    delay(1_500)
                    developmentUpdater.checkForUpdates()
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FoundationScreen(
                        updateState = updateState,
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
    updateState: DevelopmentUpdateState,
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
            Text("Phase 0 · OTA-capable foundation")
            Text("Camera hardware is intentionally not opened by the UI yet.")
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
