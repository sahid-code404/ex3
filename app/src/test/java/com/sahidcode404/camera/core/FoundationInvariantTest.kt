package com.sahidcode404.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FoundationInvariantTest {
    @Test
    fun api23AndPermanentIdentityAreFrozen() {
        assertEquals(23, ArchitectureConstants.MIN_SDK)
        assertEquals("com.sahidcode404.camera", ArchitectureConstants.APPLICATION_ID)
    }

    @Test
    fun cameraOwnerNameIsNotAUiComponent() {
        assertFalse(ArchitectureConstants.CAMERA_OWNER_COMPONENT.contains("Activity"))
        assertFalse(ArchitectureConstants.CAMERA_OWNER_COMPONENT.contains("Composable"))
    }
}
