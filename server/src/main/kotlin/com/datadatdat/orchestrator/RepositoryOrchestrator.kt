package com.datadatdat.orchestrator

import com.datadatdat.ServiceLocator
import com.datadatdat.models.Repository
import com.datadatdat.models.RepositoryStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class RepositoryOrchestrator(
    val services: ServiceLocator,
) {
    fun createRepository(repo: Repository) {
        NameUtil.validateRepoName(repo.name)
        val volumeSet =
            transaction {
                services.metadata.createRepository(repo)
                val vs = services.metadata.createVolumeSet(repo.name, null, true)
                vs
            }
        services.context.createVolumeSet(volumeSet)
    }

    fun listRepositories(): List<Repository> =
        transaction {
            services.metadata.listRepositories()
        }

    fun getRepository(name: String): Repository {
        NameUtil.validateRepoName(name)
        return transaction {
            services.metadata.getRepository(name)
        }
    }

    fun getRepositoryStatus(name: String): RepositoryStatus {
        NameUtil.validateRepoName(name)
        // A repository with no active volume set is a valid "no commits yet"
        // state, not an error — surface null commit fields rather than 500.
        // See issue #137: pre-fix this 500'd with NullPointerException, which
        // broke the CLI's connection and made d3 run fail with EOF.
        return transaction {
            val volumeSet = services.metadata.getActiveVolumeSetOrNull(name)
            RepositoryStatus(
                lastCommit = services.metadata.getLastCommit(name),
                sourceCommit = volumeSet?.let { services.metadata.getCommitSource(it) },
            )
        }
    }

    fun updateRepository(
        name: String,
        repo: Repository,
    ) {
        NameUtil.validateRepoName(name)
        NameUtil.validateRepoName(repo.name)
        transaction {
            services.metadata.updateRepository(name, repo)
        }
    }

    fun deleteRepository(name: String) {
        NameUtil.validateRepoName(name)
        transaction {
            services.metadata.markAllVolumeSetsDeleting(name)
            services.metadata.deleteRepository(name)
        }
        services.reaper.signal()
    }
}
