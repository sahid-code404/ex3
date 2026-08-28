package com.sahidcode404.camera.core.camera.discovery

import android.content.Context
import com.sahidcode404.camera.core.camera.model.HotPreviewSeed
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class HotPreviewCacheRecord(
    val schemaVersion: Int,
    val environmentFingerprint: String,
    val seed: HotPreviewSeed,
)

internal object HotPreviewCacheCodec {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(environmentFingerprint: String, seed: HotPreviewSeed): String {
        require(environmentFingerprint.isNotBlank()) { "Environment fingerprint must not be blank" }
        return json.encodeToString(
            HotPreviewCacheRecord.serializer(),
            HotPreviewCacheRecord(
                schemaVersion = SCHEMA_VERSION,
                environmentFingerprint = environmentFingerprint,
                seed = seed,
            ),
        )
    }

    fun decodeOrNull(serialized: String, expectedEnvironmentFingerprint: String): HotPreviewSeed? =
        runCatching {
            json.decodeFromString(HotPreviewCacheRecord.serializer(), serialized)
        }.getOrNull()?.takeIf { record ->
            record.schemaVersion == SCHEMA_VERSION &&
                record.environmentFingerprint == expectedEnvironmentFingerprint
        }?.seed
}

internal class HotPreviewCacheStore(context: Context) {
    private val directory = File(context.filesDir, "camera-hot")
    private val cacheFile = File(directory, "preview-v1.json")
    private val temporaryFile = File(directory, "preview-v1.json.tmp")

    fun loadOrNull(expectedEnvironmentFingerprint: String): HotPreviewSeed? {
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_CACHE_BYTES) return null
        return try {
            HotPreviewCacheCodec.decodeOrNull(
                serialized = cacheFile.readText(Charsets.UTF_8),
                expectedEnvironmentFingerprint = expectedEnvironmentFingerprint,
            )
        } catch (_: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(environmentFingerprint: String, seed: HotPreviewSeed) {
        val serialized = HotPreviewCacheCodec.encode(environmentFingerprint, seed)
            .toByteArray(Charsets.UTF_8)
        require(serialized.size <= MAX_CACHE_BYTES) { "Hot preview cache exceeds bound" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create hot preview cache directory")
        }
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Unable to replace stale hot preview cache temp file")
        }
        FileOutputStream(temporaryFile).use { output ->
            output.write(serialized)
            output.flush()
            output.fd.sync()
        }
        if (cacheFile.exists() && !cacheFile.delete()) {
            temporaryFile.delete()
            throw IOException("Unable to replace hot preview cache")
        }
        if (!temporaryFile.renameTo(cacheFile)) {
            temporaryFile.delete()
            throw IOException("Unable to promote hot preview cache")
        }
    }

    fun invalidate() {
        temporaryFile.delete()
        cacheFile.delete()
    }

    private companion object {
        const val MAX_CACHE_BYTES = 16L * 1024L
    }
}
