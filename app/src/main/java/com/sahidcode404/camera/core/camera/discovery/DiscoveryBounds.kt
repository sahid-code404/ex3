package com.sahidcode404.camera.core.camera.discovery

object DiscoveryBounds {
    const val MAX_CAMERA_IDS = 256
    const val MAX_PHYSICAL_IDS_PER_LOGICAL = 64
    const val MAX_STREAM_SIZES_PER_KIND = 64
    const val MAX_INT_METADATA_VALUES = 64
    const val MAX_FLOAT_METADATA_VALUES = 32
    const val MAX_FPS_RANGES = 32
    const val MAX_EVIDENCE_NOTES = 32

    fun <T> takeDeterministic(values: Collection<T>, limit: Int, comparator: Comparator<T>): List<T> {
        require(limit >= 0) { "Limit must not be negative" }
        return values.sortedWith(comparator).take(limit)
    }
}
