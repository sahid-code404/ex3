package com.sahidcode404.camera.core.camera.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryBoundsTest {
    @Test
    fun deterministicBoundDoesNotDependOnInputOrder() {
        val first = DiscoveryBounds.takeDeterministic(listOf(4, 2, 3, 1), 3, compareBy<Int> { it })
        val second = DiscoveryBounds.takeDeterministic(listOf(1, 4, 3, 2), 3, compareBy<Int> { it })

        assertEquals(listOf(1, 2, 3), first)
        assertEquals(first, second)
    }
}
