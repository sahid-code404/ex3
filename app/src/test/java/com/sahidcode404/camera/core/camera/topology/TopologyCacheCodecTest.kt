package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.DiscoveryDiagnostics
import com.sahidcode404.camera.core.camera.model.CameraTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopologyCacheCodecTest {
    @Test
    fun roundTripRequiresMatchingEnvironmentFingerprint() {
        val topology = CameraTopology(
            schemaVersion = CameraTopologyResolver.SCHEMA_VERSION,
            environmentFingerprint = "environment-a",
            profiles = emptyList(),
            lenses = emptyList(),
            diagnostics = DiscoveryDiagnostics(0, 0, 0),
        )

        val encoded = TopologyCacheCodec.encode(topology)

        assertEquals(topology, TopologyCacheCodec.decodeOrNull(encoded, "environment-a"))
        assertNull(TopologyCacheCodec.decodeOrNull(encoded, "environment-b"))
    }

    @Test
    fun corruptionIsCacheMiss() {
        assertNull(TopologyCacheCodec.decodeOrNull("{not-json", "environment-a"))
    }
}
