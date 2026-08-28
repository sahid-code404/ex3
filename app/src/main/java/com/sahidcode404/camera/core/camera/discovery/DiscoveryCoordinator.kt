package com.sahidcode404.camera.core.camera.discovery

import android.content.Context
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.HotPreviewSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data class Cached(val topology: CameraTopology) : DiscoveryState
    data class Discovering(val cachedTopology: CameraTopology?) : DiscoveryState
    data class Complete(val topology: CameraTopology) : DiscoveryState
    data class Failed(val cachedTopology: CameraTopology?, val message: String) : DiscoveryState
}

class DiscoveryCoordinator(context: Context) {
    private val discovery = UniversalCameraDiscovery(context.applicationContext)
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)

    val state: StateFlow<DiscoveryState> = mutableState.asStateFlow()

    suspend fun recordPreviewVerified(seed: HotPreviewSeed) {
        refreshMutex.withLock {
            val updated = withContext(Dispatchers.IO) { discovery.recordPreviewVerified(seed) } ?: return@withLock
            mutableState.value = when (val current = mutableState.value) {
                DiscoveryState.Idle -> DiscoveryState.Cached(updated)
                is DiscoveryState.Cached -> DiscoveryState.Cached(updated)
                is DiscoveryState.Discovering -> DiscoveryState.Discovering(updated)
                is DiscoveryState.Complete -> DiscoveryState.Complete(updated)
                is DiscoveryState.Failed -> current.copy(cachedTopology = updated)
            }
        }
    }

    suspend fun refresh(verifiedPreviewRoute: CameraRoute? = null) {
        refreshMutex.withLock {
            val cached = withContext(Dispatchers.IO) { discovery.loadCachedTopologyOrNull() }
            if (cached != null) mutableState.value = DiscoveryState.Cached(cached)
            mutableState.value = DiscoveryState.Discovering(cached)

            try {
                val topology = withContext(Dispatchers.IO) {
                    discovery.discover(verifiedPreviewRoute = verifiedPreviewRoute)
                }
                mutableState.value = DiscoveryState.Complete(topology)
            } catch (error: RuntimeException) {
                mutableState.value = DiscoveryState.Failed(
                    cachedTopology = cached,
                    message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                )
            }
        }
    }
}
