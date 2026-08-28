package com.sahidcode404.camera.core.camera.discovery

import android.content.Context
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.topology.TopologyCacheCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class TopologyCacheStore(context: Context) {
    private val cacheDirectory = File(context.filesDir, "camera-topology")
    private val cacheFile = File(cacheDirectory, "topology-v1.json")
    private val temporaryFile = File(cacheDirectory, "topology-v1.json.tmp")

    fun loadOrNull(expectedEnvironmentFingerprint: String): CameraTopology? {
        if (!cacheFile.isFile) return null
        if (cacheFile.length() !in 1..MAX_CACHE_BYTES) return null
        return try {
            TopologyCacheCodec.decodeOrNull(cacheFile.readText(Charsets.UTF_8), expectedEnvironmentFingerprint)
        } catch (_: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(topology: CameraTopology) {
        val serialized = TopologyCacheCodec.encode(topology).toByteArray(Charsets.UTF_8)
        require(serialized.size <= MAX_CACHE_BYTES) { "Topology cache exceeds bound" }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Unable to create topology cache directory")
        }

        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Unable to replace stale topology temp file")
        }

        FileOutputStream(temporaryFile).use { output ->
            output.write(serialized)
            output.flush()
            output.fd.sync()
        }

        if (cacheFile.exists() && !cacheFile.delete()) {
            temporaryFile.delete()
            throw IOException("Unable to replace topology cache")
        }
        if (!temporaryFile.renameTo(cacheFile)) {
            temporaryFile.delete()
            throw IOException("Unable to atomically promote topology cache")
        }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    }
}
