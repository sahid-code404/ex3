package com.sahidcode404.camera.ota

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sahidcode404.camera.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface DevelopmentUpdateState {
    data object Disabled : DevelopmentUpdateState
    data object Idle : DevelopmentUpdateState
    data object Checking : DevelopmentUpdateState
    data object UpToDate : DevelopmentUpdateState
    data class Downloading(val percent: Int) : DevelopmentUpdateState
    data class Ready(val versionName: String) : DevelopmentUpdateState
    data class PermissionRequired(val versionName: String) : DevelopmentUpdateState
    data class InstallerLaunched(val versionName: String) : DevelopmentUpdateState
    data class Failed(val message: String) : DevelopmentUpdateState
}

class DevelopmentUpdater(private val context: Context) {
    private data class VerifiedUpdate(val file: File, val metadata: UpdateMetadata)

    private val json = Json { ignoreUnknownKeys = false }
    private val checkMutex = Mutex()
    private val installMutex = Mutex()
    private val mutableState = MutableStateFlow<DevelopmentUpdateState>(
        if (BuildConfig.OTA_CHANNEL == UpdatePolicy.DEVELOPMENT_CHANNEL) {
            DevelopmentUpdateState.Idle
        } else {
            DevelopmentUpdateState.Disabled
        },
    )

    val state: StateFlow<DevelopmentUpdateState> = mutableState.asStateFlow()

    @Volatile
    private var verifiedUpdate: VerifiedUpdate? = null

    suspend fun checkForUpdates() {
        if (BuildConfig.OTA_CHANNEL != UpdatePolicy.DEVELOPMENT_CHANNEL) return
        checkMutex.withLock {
            mutableState.value = DevelopmentUpdateState.Checking
            runCatching {
                withContext(Dispatchers.IO) { checkForUpdatesBlocking() }
            }.onFailure { error ->
                mutableState.value = DevelopmentUpdateState.Failed(error.safeMessage("Update check failed"))
            }
        }
    }

    suspend fun installVerifiedUpdate() {
        if (BuildConfig.OTA_CHANNEL != UpdatePolicy.DEVELOPMENT_CHANNEL) return
        installMutex.withLock {
            val update = verifiedUpdate ?: run {
                mutableState.value = DevelopmentUpdateState.Failed("No verified update is ready")
                return
            }

            val reverified = runCatching {
                withContext(Dispatchers.IO) {
                    require(update.file.canonicalFile == verifiedApkFile().canonicalFile) { "verified APK path changed" }
                    require(update.file.isFile) { "verified APK is missing" }
                    require(update.file.length() == update.metadata.size) { "verified APK size changed" }
                    require(sha256(update.file).equals(update.metadata.sha256, ignoreCase = true)) {
                        "verified APK hash changed"
                    }
                    verifyDownloadedApk(update.file, update.metadata)
                }
            }

            if (reverified.isFailure) {
                verifiedUpdate = null
                mutableState.value = DevelopmentUpdateState.Failed(
                    reverified.exceptionOrNull().safeMessage("Update verification failed"),
                )
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                mutableState.value = DevelopmentUpdateState.PermissionRequired(update.metadata.versionName)
                launchUnknownSourceSettings()
                return
            }

            launchInstaller(update)
        }
    }

    suspend fun resumeInstallIfPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (state.value !is DevelopmentUpdateState.PermissionRequired) return
        if (context.packageManager.canRequestPackageInstalls()) {
            installVerifiedUpdate()
        }
    }

    private fun checkForUpdatesBlocking() {
        val metadataText = fetchSmallText(BuildConfig.DEV_UPDATE_METADATA_URL, MAX_METADATA_BYTES)
        val metadata = json.decodeFromString<UpdateMetadata>(metadataText)
        when (
            val decision = UpdatePolicy.evaluate(
                metadata = metadata,
                currentVersionCode = currentVersionCode(),
                applicationId = context.packageName,
                sdkInt = Build.VERSION.SDK_INT,
                expectedSignerSha256 = BuildConfig.DEV_SIGNER_SHA256,
            )
        ) {
            UpdatePolicy.Decision.UpToDate -> {
                verifiedUpdate = null
                mutableState.value = DevelopmentUpdateState.UpToDate
            }

            is UpdatePolicy.Decision.Rejected -> {
                verifiedUpdate = null
                mutableState.value = DevelopmentUpdateState.Failed("Rejected update: ${decision.reason}")
            }

            is UpdatePolicy.Decision.Accepted -> {
                val verified = obtainVerifiedApk(decision.metadata)
                verifiedUpdate = verified
                mutableState.value = DevelopmentUpdateState.Ready(decision.metadata.versionName)
            }
        }
    }

    private fun obtainVerifiedApk(metadata: UpdateMetadata): VerifiedUpdate {
        val finalFile = verifiedApkFile()
        if (
            finalFile.isFile &&
            finalFile.length() == metadata.size &&
            sha256(finalFile).equals(metadata.sha256, ignoreCase = true)
        ) {
            runCatching { verifyDownloadedApk(finalFile, metadata) }.onSuccess {
                return VerifiedUpdate(finalFile, metadata)
            }
            finalFile.delete()
        }

        val root = updateRoot().apply { mkdirs() }
        val transfer = File(root, "${UpdatePolicy.DEVELOPMENT_APK_NAME}.part")
        val candidate = File(root, "Camera-dev.candidate.apk")
        transfer.delete()
        candidate.delete()

        try {
            val apkUrl = URL(URL(BuildConfig.DEV_UPDATE_METADATA_URL), metadata.apk).toString()
            downloadApk(apkUrl, transfer, metadata)
            require(sha256(transfer).equals(metadata.sha256, ignoreCase = true)) { "APK SHA-256 mismatch" }
            require(transfer.renameTo(candidate)) { "unable to stage downloaded APK" }
            verifyDownloadedApk(candidate, metadata)

            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) require(finalFile.delete()) { "unable to replace old verified APK" }
            require(candidate.renameTo(finalFile)) { "unable to promote verified APK" }
            return VerifiedUpdate(finalFile, metadata)
        } catch (error: Throwable) {
            transfer.delete()
            candidate.delete()
            throw error
        }
    }

    private fun downloadApk(url: String, target: File, metadata: UpdateMetadata) {
        val connection = openConnection(url)
        try {
            val response = connection.responseCode
            require(response in 200..299) { "APK HTTP $response" }
            val declaredSize = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (declaredSize > 0) require(declaredSize == metadata.size) { "APK Content-Length mismatch" }
            require(metadata.size <= UpdatePolicy.MAX_APK_BYTES) { "APK exceeds download bound" }

            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= metadata.size && total <= UpdatePolicy.MAX_APK_BYTES) {
                            "APK exceeded declared size"
                        }
                        output.write(buffer, 0, count)
                        val percent = ((total * 100L) / metadata.size).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            mutableState.value = DevelopmentUpdateState.Downloading(percent)
                            lastPercent = percent
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            require(total == metadata.size) { "APK download ended at $total of ${metadata.size} bytes" }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchSmallText(url: String, maxBytes: Int): String {
        val connection = openConnection(url)
        try {
            val response = connection.responseCode
            require(response in 200..299) { "metadata HTTP $response" }
            val declared = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (declared > 0) require(declared <= maxBytes) { "metadata response too large" }

            val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "metadata response exceeded limit" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream, application/json")
            setRequestProperty("User-Agent", "Camera-Development-Updater")
        }

    private fun verifyDownloadedApk(file: File, metadata: UpdateMetadata) {
        val info = archivePackageInfo(file) ?: error("Downloaded APK could not be parsed")
        require(info.packageName == context.packageName) { "Downloaded APK package mismatch" }
        require(packageVersionCode(info) == metadata.versionCode) { "Downloaded APK version mismatch" }

        val signerDigests = signerSha256Digests(info)
        require(signerDigests.size == 1) { "Downloaded APK must have exactly one signer" }
        val signer = signerDigests.single()
        require(signer.equals(BuildConfig.DEV_SIGNER_SHA256, ignoreCase = true)) { "Downloaded APK signer is not pinned signer" }
        require(signer.equals(metadata.signerSha256, ignoreCase = true)) { "Downloaded APK signer differs from metadata" }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun signerSha256Digests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }.orEmpty()
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageVersionCode(info)
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun updateRoot(): File = File(context.cacheDir, "updates")

    private fun verifiedApkFile(): File =
        File(File(updateRoot(), "verified"), UpdatePolicy.DEVELOPMENT_APK_NAME)

    private fun launchUnknownSourceSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            mutableState.value = DevelopmentUpdateState.Failed("Android update-install permission screen is unavailable")
        }
    }

    private fun launchInstaller(update: VerifiedUpdate) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            update.file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            mutableState.value = DevelopmentUpdateState.InstallerLaunched(update.metadata.versionName)
        } catch (_: ActivityNotFoundException) {
            mutableState.value = DevelopmentUpdateState.Failed("Android package installer is unavailable")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun Throwable?.safeMessage(fallback: String): String =
        this?.message?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val NETWORK_BUFFER_BYTES = 128 * 1024
        private const val HASH_BUFFER_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
