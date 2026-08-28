package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraTopology
import kotlinx.serialization.json.Json

object TopologyCacheCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(topology: CameraTopology): String {
        require(topology.schemaVersion == CameraTopologyResolver.SCHEMA_VERSION) { "Unsupported topology schema" }
        return json.encodeToString(CameraTopology.serializer(), topology)
    }

    fun decodeOrNull(serialized: String, expectedEnvironmentFingerprint: String): CameraTopology? =
        runCatching {
            json.decodeFromString(CameraTopology.serializer(), serialized)
        }.getOrNull()?.takeIf { topology ->
            topology.schemaVersion == CameraTopologyResolver.SCHEMA_VERSION &&
                topology.environmentFingerprint == expectedEnvironmentFingerprint &&
                topology.profiles.size <= MAX_CACHED_PROFILES &&
                topology.lenses.size <= MAX_CACHED_LENSES &&
                topology.diagnostics.failures.size <= MAX_CACHED_FAILURES
        }

    private const val MAX_CACHED_PROFILES = 256
    private const val MAX_CACHED_LENSES = 256
    private const val MAX_CACHED_FAILURES = 64
}
