package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraRoute
import java.security.MessageDigest
import java.util.Locale

internal object ProfileFingerprint {
    fun create(route: CameraRoute, capabilities: CameraCapabilities): String {
        val parts = buildList {
            add("profile-v1")
            add("route=${route.routingMethod.name}")
            add("logical=${route.logicalCameraId.value}")
            route.physicalCameraId?.let { add("physical=${it.value}") }
            add("facing=${capabilities.facing.name}")
            add("focal=${capabilities.focalLengthsMm.sorted().joinToString(",") { it.normalized() }}")
            add("aperture=${capabilities.apertures.sorted().joinToString(",") { it.normalized() }}")
            capabilities.sensorPhysicalSizeMm?.let { add("sensor=${it.width.normalized()}x${it.height.normalized()}") }
            capabilities.pixelArraySize?.let { add("pixel=${it.width}x${it.height}") }
            capabilities.activeArray?.let { add("active=${it.left},${it.top},${it.right},${it.bottom}") }
            capabilities.sensorOrientationDegrees?.let { add("orientation=$it") }
            capabilities.colorFilterArrangement?.let { add("cfa=$it") }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Float.normalized(): String = String.format(Locale.ROOT, "%.5f", this)
}
