package com.sahidcode404.camera.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val signer = "1".repeat(64)

    @Test
    fun acceptsStrictlyNewerCompatiblePinnedDevelopmentApk() {
        val decision = UpdatePolicy.evaluate(
            metadata = metadata(versionCode = 101, signerSha256 = signer),
            currentVersionCode = 100,
            applicationId = "com.sahidcode404.universalcamera",
            sdkInt = 23,
            expectedSignerSha256 = signer,
        )
        assertTrue(decision is UpdatePolicy.Decision.Accepted)
    }

    @Test
    fun equalVersionIsUpToDate() {
        val decision = UpdatePolicy.evaluate(
            metadata = metadata(versionCode = 100, signerSha256 = signer),
            currentVersionCode = 100,
            applicationId = "com.sahidcode404.universalcamera",
            sdkInt = 23,
            expectedSignerSha256 = signer,
        )
        assertEquals(UpdatePolicy.Decision.UpToDate, decision)
    }

    @Test
    fun downgradeIsRejected() {
        val decision = evaluate(metadata(versionCode = 99, signerSha256 = signer))
        assertRejected(decision, "downgrade")
    }

    @Test
    fun wrongPackageIsRejected() {
        val decision = evaluate(metadata(applicationId = "example.wrong", signerSha256 = signer))
        assertRejected(decision, "application id")
    }

    @Test
    fun wrongSignerIsRejected() {
        val decision = evaluate(metadata(signerSha256 = "2".repeat(64)))
        assertRejected(decision, "signer")
    }

    @Test
    fun pathLikeApkNameIsRejected() {
        val decision = evaluate(metadata(apk = "../Camera-dev.apk", signerSha256 = signer))
        assertRejected(decision, "filename")
    }

    @Test
    fun incompatibleMinSdkIsRejected() {
        val decision = evaluate(metadata(minSdk = 24, signerSha256 = signer))
        assertRejected(decision, "newer Android")
    }

    private fun evaluate(metadata: UpdateMetadata): UpdatePolicy.Decision =
        UpdatePolicy.evaluate(
            metadata = metadata,
            currentVersionCode = 100,
            applicationId = "com.sahidcode404.universalcamera",
            sdkInt = 23,
            expectedSignerSha256 = signer,
        )

    private fun assertRejected(decision: UpdatePolicy.Decision, contains: String) {
        assertTrue(decision is UpdatePolicy.Decision.Rejected)
        assertTrue((decision as UpdatePolicy.Decision.Rejected).reason.contains(contains))
    }

    private fun metadata(
        applicationId: String = "com.sahidcode404.universalcamera",
        versionCode: Long = 101,
        minSdk: Int = 23,
        apk: String = "Camera-dev.apk",
        signerSha256: String,
    ): UpdateMetadata = UpdateMetadata(
        schema = 1,
        channel = "development",
        applicationId = applicationId,
        versionCode = versionCode,
        versionName = "0.1.101-dev",
        minSdk = minSdk,
        apk = apk,
        size = 12_000_000,
        sha256 = "a".repeat(64),
        signerSha256 = signerSha256,
        sourceCommit = "deadbeef",
        buildTime = "2026-08-28T00:00:00Z",
        changelog = "test",
    )
}
