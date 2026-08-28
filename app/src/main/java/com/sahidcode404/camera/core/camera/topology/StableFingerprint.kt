package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraProfile
import java.security.MessageDigest
import java.util.Locale

internal object StableFingerprint {
    fun sha256(parts: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun profileEvidence(profile: CameraProfile): List<String> {
        val c = profile.capabilities
        return buildList {
            add("facing=${c.facing.name}")
            add("route=${profile.route.routingMethod.name}")
            add("transport=${profile.route.logicalCameraId.value}")
            profile.route.physicalCameraId?.let { add("physical=${it.value}") }
            add("focal=${c.focalLengthsMm.sorted().joinToString(",") { it.normalized() }}")
            add("aperture=${c.apertures.sorted().joinToString(",") { it.normalized() }}")
            c.sensorPhysicalSizeMm?.let { add("sensor=${it.width.normalized()}x${it.height.normalized()}") }
            c.pixelArraySize?.let { add("pixel=${it.width}x${it.height}") }
            c.activeArray?.let { add("active=${it.width}x${it.height}") }
            c.sensorOrientationDegrees?.let { add("orientation=$it") }
            c.colorFilterArrangement?.let { add("cfa=$it") }
            add("raw=${c.hasRawSensorOutput}")
        }
    }

    private fun Float.normalized(): String = String.format(Locale.ROOT, "%.5f", this)
}
