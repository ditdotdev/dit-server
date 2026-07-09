// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.orchestrator

import dev.dit.ServiceLocator
import dev.dit.models.Repository
import dev.dit.models.RepositoryStatus
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
        return transaction {
            // Validate the repo actually exists. getRepository throws
            // NoSuchObjectException → 404 when it doesn't, which the CLI
            // surfaces as a non-zero exit and "repository ... not found".
            //
            // Pre-#137 this distinction was implicit: getActiveVolumeSet's
            // firstOrNull()!! NPE'd on BOTH "no active VS" (transient state
            // for a freshly-created repo) AND "repo doesn't exist." The
            // first PR for #137 (dit-server#140) treated both as
            // "200 with null fields", regressing
            // tests/endtoend/container-lifecycle.bats:122 — `d3 status
            // <nonexistent>` no longer errored. Splitting the cases here:
            //   - repo doesn't exist                  -> 404 (this call)
            //   - repo exists, no active volume set   -> 200 with nulls
            services.metadata.getRepository(name)
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
