package com.sahidcode404.camera.core.camera.discovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.sahidcode404.camera.core.camera.model.CameraProfile
import com.sahidcode404.camera.core.camera.model.CameraRoute
import com.sahidcode404.camera.core.camera.model.CameraTopology
import com.sahidcode404.camera.core.camera.model.CameraTransportId
import com.sahidcode404.camera.core.camera.model.DiscoveryFailure
import com.sahidcode404.camera.core.camera.model.MetadataTrust
import com.sahidcode404.camera.core.camera.model.PhysicalCameraId
import com.sahidcode404.camera.core.camera.model.RoutingMethod
import com.sahidcode404.camera.core.camera.topology.CameraTopologyResolver
import com.sahidcode404.camera.core.camera.topology.EnvironmentFingerprint
import java.io.IOException

class UniversalCameraDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cacheStore = TopologyCacheStore(appContext)

    fun loadCachedTopologyOrNull(): CameraTopology? {
        val ids = enumerateAdvertisedIds().getOrNull() ?: return null
        val fingerprint = environmentFingerprint(ids)
        return cacheStore.loadOrNull(fingerprint)
    }

    fun discover(): CameraTopology {
        val failures = mutableListOf<DiscoveryFailure>()
        val advertisedResult = enumerateAdvertisedIds()
        val advertisedIds = advertisedResult.getOrElse { error ->
            failures += DiscoveryFailure(
                stage = "camera2-enumeration",
                message = error.javaClass.simpleName,
            )
            emptyList()
        }
        val boundedAdvertisedIds = if (advertisedIds.size <= DiscoveryBounds.MAX_CAMERA_IDS) {
            advertisedIds
        } else {
            failures += DiscoveryFailure(
                stage = "camera2-enumeration",
                message = "camera-id-count-exceeds-bound:${advertisedIds.size}",
            )
            advertisedIds.take(DiscoveryBounds.MAX_CAMERA_IDS)
        }

        val fingerprint = environmentFingerprint(boundedAdvertisedIds)
        val permissionGranted = appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val collector = Camera2MetadataCollector(permissionGranted)
        val profiles = mutableListOf<CameraProfile>()
        val advertisedIdSet = boundedAdvertisedIds.mapTo(hashSetOf()) { it.value }

        boundedAdvertisedIds.sortedBy { it.value }.forEach { id ->
            val directCharacteristics = characteristicsOrNull(id.value, "camera2-characteristics", failures) ?: return@forEach
            val directCollected = collector.collect(directCharacteristics)
            val directRouting = if (directCollected.capabilities.isLogicalMultiCamera) {
                RoutingMethod.LOGICAL_DEFAULT
            } else {
                RoutingMethod.DIRECT
            }
            profiles += profile(
                route = CameraRoute(id, null, directRouting),
                collected = directCollected,
                publiclyAdvertised = true,
            )

            directCollected.capabilities.physicalMemberIds.forEach physicalLoop@ { physicalId ->
                if (profiles.size >= DiscoveryBounds.MAX_CAMERA_IDS * 2) {
                    failures += DiscoveryFailure(
                        stage = "physical-characteristics",
                        cameraId = id.value,
                        message = "profile-bound-reached",
                    )
                    return@physicalLoop
                }
                val physicalCharacteristics = characteristicsOrNull(
                    cameraId = physicalId.value,
                    stage = "physical-characteristics",
                    failures = failures,
                ) ?: return@physicalLoop
                val physicalCollected = collector.collect(physicalCharacteristics)
                profiles += profile(
                    route = CameraRoute(id, physicalId, RoutingMethod.LOGICAL_PHYSICAL_MEMBER),
                    collected = physicalCollected,
                    publiclyAdvertised = advertisedIdSet.contains(physicalId.value),
                )
            }
        }

        val topology = CameraTopologyResolver.resolve(
            environmentFingerprint = fingerprint,
            profiles = profiles,
            advertisedCameraCount = boundedAdvertisedIds.size,
            failures = failures,
            truncatedFailureCount = (advertisedIds.size - boundedAdvertisedIds.size).coerceAtLeast(0),
        )

        try {
            cacheStore.save(topology)
        } catch (error: IOException) {
            // Cache persistence is non-critical discovery state; discovery results remain valid in memory.
        } catch (error: IllegalArgumentException) {
            // A bounded serialization failure must not take down camera discovery.
        }
        return topology
    }

    private fun profile(
        route: CameraRoute,
        collected: CollectedCharacteristics,
        publiclyAdvertised: Boolean,
    ): CameraProfile = CameraProfile(
        profileId = ProfileFingerprint.create(route, collected.capabilities),
        route = route,
        capabilities = collected.capabilities,
        metadataTrust = collected.metadataTrust,
        publiclyAdvertised = publiclyAdvertised,
        evidence = collected.evidenceNotes,
    )

    private fun enumerateAdvertisedIds(): Result<List<CameraTransportId>> = runCatching {
        cameraManager.cameraIdList
            .filter { it.isNotBlank() }
            .distinct()
            .map(::CameraTransportId)
    }

    private fun characteristicsOrNull(
        cameraId: String,
        stage: String,
        failures: MutableList<DiscoveryFailure>,
    ): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(cameraId)
    } catch (error: CameraAccessException) {
        failures += DiscoveryFailure(stage, cameraId, "CameraAccessException:${error.reason}")
        null
    } catch (error: IllegalArgumentException) {
        failures += DiscoveryFailure(stage, cameraId, "IllegalArgumentException")
        null
    } catch (error: SecurityException) {
        failures += DiscoveryFailure(stage, cameraId, "SecurityException")
        null
    }

    private fun environmentFingerprint(ids: Collection<CameraTransportId>): String =
        EnvironmentFingerprint.create(
            osBuildFingerprint = Build.FINGERPRINT,
            sdkInt = Build.VERSION.SDK_INT,
            advertisedCameraIds = ids,
        )
}
