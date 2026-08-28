package com.sahidcode404.camera.core.camera.topology

import com.sahidcode404.camera.core.camera.model.CameraCapabilities
import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.FloatSizeValue
import com.sahidcode404.camera.core.camera.model.IntRectValue
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PhysicalCameraId
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTopologyResolverTest {
    @Test
    fun identicalOpticalEvidenceGroupsDuplicateAliases() {
        val first = profile("a")
        val second = profile("b")

        val topology = CameraTopologyResolver.resolve("env", listOf(first, second), advertisedCameraCount = 2)

        assertEquals(1, topology.lenses.size)
        assertEquals(listOf("a", "b"), topology.lenses.single().profileIds)
    }

    @Test
    fun matchingFocalLengthAloneNeverGroupsTwoLenses() {
        val first = profile("a")
        val second = profile(
            id = "b",
            capabilities = baseCapabilities().copy(
                sensorPhysicalSizeMm = null,
                pixelArraySize = null,
                activeArray = null,
                sensorOrientationDegrees = null,
                colorFilterArrangement = null,
                apertures = emptyList(),
            ),
        )

        assertFalse(CameraTopologyResolver.shouldMerge(first, second))
    }

    @Test
    fun directAndLogicalPhysicalRouteForSamePhysicalIdGroup() {
        val direct = profile("direct", transport = "physical-opaque")
        val logicalPhysical = profile(
            id = "logical-physical",
            transport = "logical-opaque",
            physical = "physical-opaque",
            routingMethod = RoutingMethod.LOGICAL_PHYSICAL_MEMBER,
        )

        assertTrue(CameraTopologyResolver.shouldMerge(direct, logicalPhysical))
    }

    @Test
    fun logicalDefaultIsNotCollapsedIntoPhysicalLensFromMetadataAlone() {
        val logical = profile(
            id = "logical",
            capabilities = baseCapabilities().copy(isLogicalMultiCamera = true),
            routingMethod = RoutingMethod.LOGICAL_DEFAULT,
        )
        val physical = profile("physical")

        assertFalse(CameraTopologyResolver.shouldMerge(logical, physical))
    }

    @Test
    fun frontAndBackNeverGroup() {
        val back = profile("back")
        val front = profile("front", capabilities = baseCapabilities().copy(facing = CameraFacing.FRONT))

        assertFalse(CameraTopologyResolver.shouldMerge(back, front))
    }

    private fun profile(
        id: String,
        transport: String = id,
        physical: String? = null,
        routingMethod: RoutingMethod = RoutingMethod.DIRECT,
        capabilities: CameraCapabilities = baseCapabilities(),
    ): CameraProfile = CameraProfile(
        profileId = id,
        route = CameraRoute(
            logicalCameraId = CameraTransportId(transport),
            physicalCameraId = physical?.let(::PhysicalCameraId),
            routingMethod = routingMethod,
        ),
        capabilities = capabilities,
        metadataTrust = MetadataTrust.COMPLETE,
        publiclyAdvertised = routingMethod == RoutingMethod.DIRECT,
    )

    private fun baseCapabilities(): CameraCapabilities = CameraCapabilities(
        facing = CameraFacing.BACK,
        focalLengthsMm = listOf(5.4f),
        apertures = listOf(1.8f),
        sensorPhysicalSizeMm = FloatSizeValue(7.2f, 5.4f),
        pixelArraySize = IntSizeValue(4000, 3000),
        activeArray = IntRectValue(0, 0, 3984, 2988),
        sensorOrientationDegrees = 90,
        colorFilterArrangement = 0,
    )
}
