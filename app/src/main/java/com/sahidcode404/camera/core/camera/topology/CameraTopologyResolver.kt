package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CanonicalLens
import com.sahidcode404.camera.core.camera.model.DiscoveryDiagnostics
import kotlin.math.abs
import kotlin.math.max

object CameraTopologyResolver {
    const val SCHEMA_VERSION = 1

    fun resolve(
        environmentFingerprint: String,
        profiles: List<CameraProfile>,
        advertisedCameraCount: Int,
        failures: List<com.sahidcode404.camera.core.camera.model.DiscoveryFailure> = emptyList(),
        truncatedFailureCount: Int = 0,
    ): CameraTopology {
        val normalizedProfiles = profiles
            .distinctBy { it.profileId }
            .sortedBy { it.profileId }

        val parent = IntArray(normalizedProfiles.size) { it }

        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        for (i in normalizedProfiles.indices) {
            for (j in i + 1 until normalizedProfiles.size) {
                if (shouldMerge(normalizedProfiles[i], normalizedProfiles[j])) union(i, j)
            }
        }

        val groups = linkedMapOf<Int, MutableList<CameraProfile>>()
        normalizedProfiles.indices.forEach { index ->
            groups.getOrPut(find(index)) { mutableListOf() }.add(normalizedProfiles[index])
        }

        val lenses = groups.values
            .map { group -> createLens(group.sortedBy { it.profileId }) }
            .sortedBy { it.lensId }

        val retainedFailures = failures.take(MAX_FAILURES)
        return CameraTopology(
            schemaVersion = SCHEMA_VERSION,
            environmentFingerprint = environmentFingerprint,
            profiles = normalizedProfiles,
            lenses = lenses,
            diagnostics = DiscoveryDiagnostics(
                advertisedCameraCount = advertisedCameraCount,
                profileCount = normalizedProfiles.size,
                canonicalLensCount = lenses.size,
                failures = retainedFailures,
                truncatedFailureCount = truncatedFailureCount + (failures.size - retainedFailures.size).coerceAtLeast(0),
            ),
        )
    }

    internal fun shouldMerge(a: CameraProfile, b: CameraProfile): Boolean {
        if (a.capabilities.facing != b.capabilities.facing) return false
        if (a.capabilities.facing == CameraFacing.UNKNOWN) return false

        if (samePhysicalIdentity(a, b)) return !hasOpticalContradiction(a, b)

        // A logical default route may automatically change its backing physical camera. Treat it
        // as its own routing profile unless runtime evidence later proves a single optical identity.
        if (a.capabilities.isLogicalMultiCamera || b.capabilities.isLogicalMultiCamera) return false
        if (hasOpticalContradiction(a, b)) return false

        return opticalEvidenceScore(a, b) >= MIN_OPTICAL_EVIDENCE_FAMILIES
    }

    private fun samePhysicalIdentity(a: CameraProfile, b: CameraProfile): Boolean {
        val aPhysical = a.route.physicalCameraId?.value
        val bPhysical = b.route.physicalCameraId?.value
        if (aPhysical != null && bPhysical != null && aPhysical == bPhysical) return true
        if (aPhysical != null && aPhysical == b.route.logicalCameraId.value) return true
        if (bPhysical != null && bPhysical == a.route.logicalCameraId.value) return true
        return false
    }

    private fun hasOpticalContradiction(a: CameraProfile, b: CameraProfile): Boolean {
        val ac = a.capabilities
        val bc = b.capabilities
        if (
            ac.sensorOrientationDegrees != null && bc.sensorOrientationDegrees != null &&
            ac.sensorOrientationDegrees != bc.sensorOrientationDegrees
        ) return true
        if (
            ac.colorFilterArrangement != null && bc.colorFilterArrangement != null &&
            ac.colorFilterArrangement != bc.colorFilterArrangement
        ) return true
        if (ac.focalLengthsMm.isNotEmpty() && bc.focalLengthsMm.isNotEmpty() && !floatSetsClose(ac.focalLengthsMm, bc.focalLengthsMm, 0.03f)) {
            return true
        }
        if (
            ac.sensorPhysicalSizeMm != null && bc.sensorPhysicalSizeMm != null &&
            (!close(ac.sensorPhysicalSizeMm.width, bc.sensorPhysicalSizeMm.width, 0.03f) ||
                !close(ac.sensorPhysicalSizeMm.height, bc.sensorPhysicalSizeMm.height, 0.03f))
        ) return true
        return false
    }

    private fun opticalEvidenceScore(a: CameraProfile, b: CameraProfile): Int {
        val ac = a.capabilities
        val bc = b.capabilities
        var score = 0
        if (ac.focalLengthsMm.isNotEmpty() && bc.focalLengthsMm.isNotEmpty() && floatSetsClose(ac.focalLengthsMm, bc.focalLengthsMm, 0.01f)) score++
        if (
            ac.sensorPhysicalSizeMm != null && bc.sensorPhysicalSizeMm != null &&
            close(ac.sensorPhysicalSizeMm.width, bc.sensorPhysicalSizeMm.width, 0.02f) &&
            close(ac.sensorPhysicalSizeMm.height, bc.sensorPhysicalSizeMm.height, 0.02f)
        ) score++
        if (ac.pixelArraySize != null && ac.pixelArraySize == bc.pixelArraySize) score++
        if (ac.activeArray != null && ac.activeArray == bc.activeArray) score++
        if (ac.sensorOrientationDegrees != null && ac.sensorOrientationDegrees == bc.sensorOrientationDegrees) score++
        if (ac.colorFilterArrangement != null && ac.colorFilterArrangement == bc.colorFilterArrangement) score++
        if (ac.apertures.isNotEmpty() && bc.apertures.isNotEmpty() && floatSetsClose(ac.apertures, bc.apertures, 0.02f)) score++
        return score
    }

    private fun createLens(group: List<CameraProfile>): CanonicalLens {
        val evidence = group.flatMap { StableFingerprint.profileEvidence(it) }.distinct().sorted()
        val lensId = StableFingerprint.sha256(
            buildList {
                add("canonical-lens-v1")
                group.forEach { profile -> addAll(StableFingerprint.profileEvidence(profile)) }
            },
        )
        return CanonicalLens(
            lensId = lensId,
            facing = group.first().capabilities.facing,
            profileIds = group.map { it.profileId }.sorted(),
            evidence = evidence.take(MAX_LENS_EVIDENCE),
        )
    }

    private fun floatSetsClose(a: List<Float>, b: List<Float>, tolerance: Float): Boolean {
        if (a.size != b.size) return false
        return a.sorted().zip(b.sorted()).all { (left, right) -> close(left, right, tolerance) }
    }

    private fun close(a: Float, b: Float, tolerance: Float): Boolean {
        val scale = max(max(abs(a), abs(b)), 0.0001f)
        return abs(a - b) / scale <= tolerance
    }

    private const val MIN_OPTICAL_EVIDENCE_FAMILIES = 5
    private const val MAX_LENS_EVIDENCE = 64
    private const val MAX_FAILURES = 64
}
