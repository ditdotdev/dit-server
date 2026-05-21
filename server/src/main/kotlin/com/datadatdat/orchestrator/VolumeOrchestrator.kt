package com.datadatdat.orchestrator

import com.datadatdat.ServiceLocator
import com.datadatdat.models.Volume
import com.datadatdat.models.VolumeStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class VolumeOrchestrator(
    val services: ServiceLocator,
) {
    fun createVolume(
        repo: String,
        volume: Volume,
    ): Volume {
        NameUtil.validateVolumeName(volume.name)
        services.repositories.getRepository(repo)

        val vs =
            transaction {
                val vs = services.metadata.getActiveVolumeSet(repo)
                services.metadata.createVolume(vs, volume)
                vs
            }
        // Partial-failure rollback: if the storage-layer createVolume fails
        // (ZFS unavailable, k8s API errored, etc.), the metadata row
        // committed above would otherwise persist forever — the volumeset
        // is ACTIVE, so the reaper's markEmptyVolumeSets sweep (which only
        // touches INACTIVE volumesets) won't catch it. Catch the failure,
        // delete the orphaned metadata row, and rethrow so the API
        // response carries the original error.
        //
        // Best-effort cleanup: we also try context.deleteVolume in case
        // createVolume failed partway through (e.g. ZFS dataset created
        // but a follow-up step failed). Swallow any error from that path
        // — the metadata cleanup is what matters; orphaned ZFS state
        // without metadata is a known follow-up class but not a leak
        // visible to the user. See issue #177.
        val config =
            try {
                services.context.createVolume(vs, volume.name)
            } catch (t: Throwable) {
                runCatching { services.context.deleteVolume(vs, volume.name, emptyMap()) }
                transaction { services.metadata.deleteVolume(vs, volume.name) }
                throw t
            }
        return transaction {
            services.metadata.updateVolumeConfig(vs, volume.name, config)
            services.metadata.getVolume(vs, volume.name)
        }
    }

    fun deleteVolume(
        repo: String,
        name: String,
    ) {
        NameUtil.validateVolumeName(name)
        services.repositories.getRepository(repo)

        transaction {
            val vs = services.metadata.getActiveVolumeSet(repo)
            services.metadata.markVolumeDeleting(vs, name)
        }
        services.reaper.signal()
    }

    fun getVolume(
        repo: String,
        name: String,
    ): Volume {
        NameUtil.validateVolumeName(name)
        services.repositories.getRepository(repo)

        return transaction {
            val vs = services.metadata.getActiveVolumeSet(repo)
            services.metadata.getVolume(vs, name)
        }
    }

    fun getVolumeStatus(
        repo: String,
        name: String,
    ): VolumeStatus {
        val vol = getVolume(repo, name)
        val vs =
            transaction {
                services.metadata.getActiveVolumeSet(repo)
            }
        val rawStatus = services.context.getVolumeStatus(vs, name, vol.config)
        return VolumeStatus(
            name = vol.name,
            logicalSize = rawStatus.logicalSize,
            actualSize = rawStatus.actualSize,
            properties = vol.properties,
            ready = rawStatus.ready,
            error = rawStatus.error,
        )
    }

    fun activateVolume(
        repo: String,
        name: String,
    ) {
        NameUtil.validateVolumeName(name)
        services.repositories.getRepository(repo)

        val (vs, volume) =
            transaction {
                val vs = services.metadata.getActiveVolumeSet(repo)
                Pair(vs, services.metadata.getVolume(vs, name))
            }
        services.context.activateVolume(vs, name, volume.config)
    }

    fun deactivateVolume(
        repo: String,
        name: String,
    ) {
        NameUtil.validateVolumeName(name)
        services.repositories.getRepository(repo)
        val (vs, volume) =
            transaction {
                val vs = services.metadata.getActiveVolumeSet(repo)
                Pair(vs, services.metadata.getVolume(vs, name))
            }
        services.context.deactivateVolume(vs, name, volume.config)
    }

    fun listVolumes(repo: String): List<Volume> {
        services.repositories.getRepository(repo)
        return transaction {
            val vs = services.metadata.getActiveVolumeSet(repo)
            services.metadata.listVolumes(vs)
        }
    }
}
