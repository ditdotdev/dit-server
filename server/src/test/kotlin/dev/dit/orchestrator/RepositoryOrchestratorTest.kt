// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.orchestrator

import dev.dit.ServiceLocator
import dev.dit.context.docker.DockerZfsContext
import dev.dit.exception.NoSuchObjectException
import dev.dit.exception.ObjectExistsException
import dev.dit.models.Commit
import dev.dit.models.Repository
import dev.dit.models.Volume
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class RepositoryOrchestratorTest : StringSpec() {
    @MockK
    lateinit var context: DockerZfsContext

    @MockK
    lateinit var reaper: Reaper

    @InjectMockKs
    @OverrideMockKs
    var services = ServiceLocator(mockk())

    override fun beforeSpec(spec: Spec) {
        services.metadata.init()
    }

    override fun beforeTest(testCase: TestCase) {
        services.metadata.clear()
        return MockKAnnotations.init(this)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    fun createRepository() {
        every { context.createVolumeSet(any()) } just Runs
        services.repositories.createRepository(Repository(name = "foo", properties = mapOf("a" to "b")))
    }

    init {
        "create repository succeeds" {
            createRepository()
            val vs =
                transaction {
                    services.metadata.getActiveVolumeSet("foo")
                }
            verifyAll {
                context.createVolumeSet(vs)
            }
        }

        "create repository with invalid name fails" {
            shouldThrow<IllegalArgumentException> {
                services.repositories.createRepository(Repository(name = "a".repeat(65)))
            }
        }

        "get repository succeeds" {
            createRepository()
            val repo = services.repositories.getRepository("foo")
            repo.name shouldBe "foo"
            repo.properties["a"] shouldBe "b"
        }

        "get repository with invalid name fails" {
            shouldThrow<IllegalArgumentException> {
                services.repositories.getRepository("bad/repo")
            }
        }

        "get non-existent repository fails" {
            shouldThrow<NoSuchObjectException> {
                services.repositories.getRepository("bar")
            }
        }

        "list repositories succeeds" {
            createRepository()
            services.repositories.createRepository(Repository(name = "bar"))
            val repos = services.repositories.listRepositories().sortedBy { it.name }
            repos.size shouldBe 2
            repos[0].name shouldBe "bar"
            repos[1].name shouldBe "foo"
        }

        "update repository succeeds" {
            createRepository()
            services.repositories.updateRepository("foo", Repository(name = "bar", properties = mapOf("b" to "c")))
            val repo = services.repositories.getRepository("bar")
            repo.name shouldBe "bar"
            repo.properties["b"] shouldBe "c"
            shouldThrow<NoSuchObjectException> {
                services.repositories.getRepository("foo")
            }
        }

        "update repository with invalid source name fails" {
            shouldThrow<IllegalArgumentException> {
                services.repositories.updateRepository("bad/repo", Repository(name = "foo"))
            }
        }

        "update repository with invalid new name fails" {
            createRepository()
            shouldThrow<IllegalArgumentException> {
                services.repositories.updateRepository("foo", Repository(name = "bad/repo"))
            }
        }

        "update non-existent repository fails" {
            shouldThrow<NoSuchObjectException> {
                services.repositories.updateRepository("foo", Repository(name = "foo"))
            }
        }

        "rename to conflicting repo fails" {
            createRepository()
            services.repositories.createRepository(Repository(name = "bar"))
            shouldThrow<ObjectExistsException> {
                services.repositories.updateRepository("foo", Repository(name = "bar"))
            }
        }

        "delete repository succeeds" {
            every { reaper.signal() } just Runs
            createRepository()
            services.repositories.deleteRepository("foo")
            shouldThrow<NoSuchObjectException> {
                services.repositories.getRepository("foo")
            }
            verify {
                reaper.signal()
            }
        }

        "get repository status succeeds" {
            createRepository()
            every { context.createVolume(any(), any()) } returns emptyMap()
            services.volumes.createVolume("foo", Volume("vol1"))
            services.volumes.createVolume("foo", Volume("vol2"))
            every { context.commitVolumeSet(any(), any()) } just Runs
            every { context.commitVolume(any(), any(), any(), any()) } just Runs
            services.commits.createCommit("foo", Commit(id = "id"))
            val status = services.repositories.getRepositoryStatus("foo")
            status.lastCommit shouldBe "id"
            status.sourceCommit shouldBe "id"
        }

        // Regression for issue #137: GET /v1/repositories/<repo>/status used
        // to 500 with a bare NullPointerException whenever the repo had no
        // active volume set yet. The 500 surfaced on the CLI side as EOF on
        // the next POST request — d3 run on a fresh kubernetes context
        // would crash mid-flow. Status for a repo without an active VS
        // should now succeed with null commit fields.
        "get repository status on a repo with no active volume set returns nulls instead of throwing" {
            // Create the repo via the metadata layer directly so no active
            // volume set is registered — mirrors the transient state seen
            // between createRepository and createVolumeSet on a fresh repo.
            transaction {
                services.metadata.createRepository(Repository("foo"))
            }
            val status = services.repositories.getRepositoryStatus("foo")
            status.lastCommit shouldBe null
            status.sourceCommit shouldBe null
        }

        // Regression guard for the cleanup of #137: the first cut conflated
        // "no active volume set" with "repo doesn't exist," so `d3 status
        // <nonexistent>` started returning 200 with nulls instead of 404 —
        // breaking tests/endtoend/container-lifecycle.bats:122 in the d3 CLI
        // suite. getRepositoryStatus must throw NoSuchObjectException when
        // the repo is genuinely missing.
        "get repository status on a non-existent repository throws NoSuchObjectException" {
            shouldThrow<NoSuchObjectException> {
                services.repositories.getRepositoryStatus("does-not-exist")
            }
        }
    }
}
