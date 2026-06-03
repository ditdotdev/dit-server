package dev.dit.orchestrator

import dev.dit.ServiceLocator
import dev.dit.exception.NoSuchObjectException
import dev.dit.exception.ObjectExistsException
import dev.dit.models.Commit
import dev.dit.models.CommitStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.format.DateTimeFormatter

class CommitOrchestrator(
    val services: ServiceLocator,
) {
    /*
     * To create a new commit, we fetch the active volume set, and then inform the storage provider to create a commit
     * for all volumes within that volume set.
     */
    fun createCommit(
        repo: String,
        commit: Commit,
        existingVolumeSet: String? = null,
    ): Commit {
        NameUtil.validateCommitId(commit.id)
        services.repositories.getRepository(repo)

        // Set the creation timestamp in metadata if it doesn't already exists
        val props = commit.properties.toMutableMap()
        if (!props.containsKey("timestamp")) {
            props["timestamp"] = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        }
        val newCommit = Commit(id = commit.id, properties = props)

        val volumeSet =
            transaction {
                try {
                    services.metadata.getCommit(repo, commit.id)
                    throw ObjectExistsException("commit '${commit.id}' already exists in repository '$repo'")
                } catch (e: NoSuchObjectException) {
                    // Ignore
                }
                val vs =
                    if (existingVolumeSet != null) {
                        existingVolumeSet
                    } else {
                        services.metadata.getActiveVolumeSet(repo)
                    }
                services.metadata.createCommit(repo, vs, newCommit)
                vs
            }

        val volumes =
            transaction {
                services.metadata.listVolumes(volumeSet)
            }

        // Partial-failure rollback: if any storage-layer commit call fails,
        // the metadata commit row committed above would otherwise persist
        // forever — listDeletingCommits only catches DELETING commits,
        // so an ACTIVE commit row with no underlying ZFS snapshot leaks.
        // Catch, best-effort-clean-up any partial ZFS state, delete the
        // metadata row, and rethrow with the original error. See #177.
        try {
            services.context.commitVolumeSet(volumeSet, newCommit.id)
            for (v in volumes) {
                services.context.commitVolume(volumeSet, newCommit.id, v.name, v.config)
            }
        } catch (t: Throwable) {
            // Best-effort: drop whichever snapshots may have made it onto
            // disk before the failure. Errors here are expected (the
            // snapshot we failed to create won't exist for deleteX) and
            // swallowed; the metadata cleanup is the load-bearing step.
            runCatching { services.context.deleteVolumeSetCommit(volumeSet, newCommit.id) }
            for (v in volumes) {
                runCatching { services.context.deleteVolumeCommit(volumeSet, newCommit.id, v.name) }
            }
            transaction {
                services.metadata.deleteCommitByGuid(volumeSet, newCommit.id)
            }
            throw t
        }
        return newCommit
    }

    fun getCommit(
        repo: String,
        id: String,
    ): Commit {
        NameUtil.validateCommitId(id)
        services.repositories.getRepository(repo)
        return transaction {
            services.metadata.getCommit(repo, id).second
        }
    }

    fun getCommitStatus(
        repo: String,
        id: String,
    ): CommitStatus {
        NameUtil.validateCommitId(id)
        services.repositories.getRepository(repo)
        val (vs, volumes) =
            transaction {
                val vs = services.metadata.getCommit(repo, id).first
                Pair(vs, services.metadata.listVolumes(vs).map { it.name })
            }
        return services.context.getCommitStatus(vs, id, volumes)
    }

    fun listCommits(
        repo: String,
        tags: List<String>? = null,
    ): List<Commit> {
        services.repositories.getRepository(repo)
        return transaction {
            services.metadata.listCommits(repo, tags)
        }
    }

    fun deleteCommit(
        repo: String,
        commit: String,
    ) {
        NameUtil.validateCommitId(commit)
        services.repositories.getRepository(repo)

        transaction {
            services.metadata.markCommitDeleting(repo, commit)
        }
        services.reaper.signal()
    }

    fun checkoutCommit(
        repo: String,
        commit: String,
    ) {
        NameUtil.validateCommitId(commit)
        services.repositories.getRepository(repo)
        val (sourceVolumeSet, newVolumeSet) =
            transaction {
                val vs = services.metadata.getCommit(repo, commit).first
                val newVolumeSet = services.metadata.createVolumeSet(repo, commit)
                val volumes = services.metadata.listVolumes(vs)
                for (v in volumes) {
                    services.metadata.createVolume(newVolumeSet, v)
                }
                Pair(vs, newVolumeSet)
            }

        val volumes =
            transaction {
                services.metadata.listVolumes(newVolumeSet)
            }

        services.context.cloneVolumeSet(sourceVolumeSet, commit, newVolumeSet)
        for (v in volumes) {
            val config = services.context.cloneVolume(sourceVolumeSet, commit, newVolumeSet, v.name, v.config)
            transaction {
                services.metadata.updateVolumeConfig(newVolumeSet, v.name, config)
            }
        }

        transaction {
            services.metadata.activateVolumeSet(repo, newVolumeSet)
        }
        services.reaper.signal()
    }

    fun updateCommit(
        repo: String,
        commit: Commit,
    ) {
        NameUtil.validateCommitId(commit.id)
        services.repositories.getRepository(repo)

        transaction {
            services.metadata.updateCommit(repo, commit)
        }
    }
}
