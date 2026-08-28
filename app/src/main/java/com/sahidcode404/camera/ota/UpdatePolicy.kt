package com.sahidcode404.camera.ota

object UpdatePolicy {
    const val METADATA_SCHEMA = 1
    const val DEVELOPMENT_CHANNEL = "development"
    const val DEVELOPMENT_APK_NAME = "Camera-dev.apk"
    const val MAX_APK_BYTES: Long = 512L * 1024L * 1024L

    sealed interface Decision {
        data object UpToDate : Decision
        data class Accepted(val metadata: UpdateMetadata) : Decision
        data class Rejected(val reason: String) : Decision
    }

    fun evaluate(
        metadata: UpdateMetadata,
        currentVersionCode: Long,
        applicationId: String,
        sdkInt: Int,
        expectedSignerSha256: String,
    ): Decision {
        if (metadata.schema != METADATA_SCHEMA) return Decision.Rejected("unsupported metadata schema")
        if (metadata.channel != DEVELOPMENT_CHANNEL) return Decision.Rejected("wrong update channel")
        if (metadata.applicationId != applicationId) return Decision.Rejected("wrong application id")
        if (metadata.versionCode < currentVersionCode) return Decision.Rejected("downgrade metadata")
        if (metadata.versionCode == currentVersionCode) return Decision.UpToDate
        if (metadata.minSdk > sdkInt) return Decision.Rejected("update requires a newer Android API")
        if (metadata.apk != DEVELOPMENT_APK_NAME) return Decision.Rejected("unexpected APK filename")
        if (metadata.size !in 1..MAX_APK_BYTES) return Decision.Rejected("invalid APK size")
        if (!metadata.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return Decision.Rejected("invalid APK SHA-256")
        if (!metadata.signerSha256.matches(Regex("[0-9a-fA-F]{64}"))) return Decision.Rejected("invalid signer SHA-256")
        if (!metadata.signerSha256.equals(expectedSignerSha256, ignoreCase = true)) {
            return Decision.Rejected("signer pin mismatch")
        }
        return Decision.Accepted(metadata)
    }
}
