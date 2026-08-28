package com.sahidcode404.camera.core.camera.discovery

import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.NdkDiscoverySummary
import kotlin.math.abs
import kotlin.math.max

internal object NdkEvidenceReconciler {
    fun summarize(
        javaAdvertisedIds: Set<String>,
        profiles: List<CameraProfile>,
        ndk: NdkEvidenceSnapshot,
    ): NdkDiscoverySummary {
        if (!ndk.available) {
            return NdkDiscoverySummary(available = false, error = ndk.error)
        }

        val ndkIds = ndk.cameras.map { it.id }.toSet()
        val ndkOnlyAll = (ndkIds - javaAdvertisedIds).sorted()
        val javaOnlyAll = (javaAdvertisedIds - ndkIds).sorted()
        val mismatchesAll = ndk.cameras.asSequence()
            .filter { it.id in javaAdvertisedIds && it.status == 0 }
            .filter { ndkCamera ->
                val javaProfile = profiles.firstOrNull { profile ->
                    profile.route.logicalCameraId.value == ndkCamera.id && profile.route.physicalCameraId == null
                }
                javaProfile != null && contradicts(javaProfile, ndkCamera)
            }
            .map { it.id }
            .distinct()
            .sorted()
            .toList()

        val retainedNdkOnly = ndkOnlyAll.take(MAX_REPORTED_DIFFERENCES)
        val retainedJavaOnly = javaOnlyAll.take(MAX_REPORTED_DIFFERENCES)
        val retainedMismatches = mismatchesAll.take(MAX_REPORTED_DIFFERENCES)
        val truncated =
            (ndkOnlyAll.size - retainedNdkOnly.size).coerceAtLeast(0) +
                (javaOnlyAll.size - retainedJavaOnly.size).coerceAtLeast(0) +
                (mismatchesAll.size - retainedMismatches.size).coerceAtLeast(0) +
                if (ndk.truncated) 1 else 0

        return NdkDiscoverySummary(
            available = true,
            advertisedCameraCount = ndkIds.size,
            ndkOnlyCameraIds = retainedNdkOnly,
            javaOnlyCameraIds = retainedJavaOnly,
            metadataMismatchCameraIds = retainedMismatches,
            truncatedDifferenceCount = truncated,
            error = ndk.error,
        )
    }

    private fun contradicts(javaProfile: CameraProfile, ndk: NdkCameraMetadataEvidence): Boolean {
        val java = javaProfile.capabilities
        if (ndk.facing != null && java.facing != ndk.facing) return true
        if (
            ndk.sensorOrientationDegrees != null && java.sensorOrientationDegrees != null &&
            ndk.sensorOrientationDegrees != java.sensorOrientationDegrees
        ) return true
        if (
            ndk.focalLengthsMm.isNotEmpty() && java.focalLengthsMm.isNotEmpty() &&
            !floatListsClose(ndk.focalLengthsMm, java.focalLengthsMm, 0.01f)
        ) return true
        ndk.sensorPhysicalSizeMm?.let { ndkSize ->
            java.sensorPhysicalSizeMm?.let { javaSize ->
                if (!close(ndkSize.first, javaSize.width, 0.02f) || !close(ndkSize.second, javaSize.height, 0.02f)) {
                    return true
                }
            }
        }
        ndk.pixelArraySize?.let { ndkSize ->
            java.pixelArraySize?.let { javaSize ->
                if (ndkSize.first != javaSize.width || ndkSize.second != javaSize.height) return true
            }
        }
        return false
    }

    private fun floatListsClose(a: List<Float>, b: List<Float>, tolerance: Float): Boolean {
        if (a.size != b.size) return false
        return a.sorted().zip(b.sorted()).all { (left, right) -> close(left, right, tolerance) }
    }

    private fun close(a: Float, b: Float, tolerance: Float): Boolean {
        val scale = max(max(abs(a), abs(b)), 0.0001f)
        return abs(a - b) / scale <= tolerance
    }

    private const val MAX_REPORTED_DIFFERENCES = 64
}
