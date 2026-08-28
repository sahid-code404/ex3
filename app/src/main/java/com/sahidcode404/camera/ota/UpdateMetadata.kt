package com.sahidcode404.camera.ota

import kotlinx.serialization.Serializable

@Serializable
data class UpdateMetadata(
    val schema: Int,
    val channel: String,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apk: String,
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val sourceCommit: String,
    val buildTime: String,
    val changelog: String,
)
