// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.metadata

import dev.dit.exception.NoSuchObjectException
import dev.dit.models.Commit
import dev.dit.models.Repository
import dev.dit.models.Volume
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

class VolumeSetMetadataTest : StringSpec() {
    val md = MetadataProvider()

    override fun beforeSpec(spec: Spec) {
        md.init()
    }

    override fun beforeTest(testCase: TestCase) {
        md.clear()
    }

    init {
        "create volume set succeeds" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo")
                // Make sure it can be parsed
                UUID.fromString(vs)
            }
        }

        "create volume set with commit source succeeds" {
            transaction {
                md.createRepository(Repository("foo"))
                val src = md.createVolumeSet("foo")
                md.createCommit("foo", src, Commit(id = "id"))
                val dst = md.createVolumeSet("foo", "id")

                md.getCommitSource(dst) shouldBe "id"
            }
        }

        "get active volumeset returns vs if activate" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.getActiveVolumeSet("foo") shouldBe vs
            }
        }

        // Regression for issue #137: getActiveVolumeSet used to dereference
        // a null with `firstOrNull()!!`, leaking a bare NullPointerException
        // out of the HTTP layer (and breaking the connection mid-request,
        // which crashed the CLI's next POST with EOF). Should now throw a
        // clear NoSuchObjectException that callers can recognize.
        "get active volumeset throws NoSuchObjectException when no active vs exists" {
            transaction {
                md.createRepository(Repository("foo"))
                val ex =
                    shouldThrow<NoSuchObjectException> {
                        md.getActiveVolumeSet("foo")
                    }
                ex.message!!.contains("foo") shouldBe true
            }
        }

        "get active volumeset OrNull returns null when no active vs exists" {
            transaction {
                md.createRepository(Repository("foo"))
                md.getActiveVolumeSetOrNull("foo") shouldBe null
            }
        }

        "get active volumeset OrNull returns vs when one is active" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.getActiveVolumeSetOrNull("foo") shouldBe vs
            }
        }

        "get volumeset repo returns correct info" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.getVolumeSetRepo(vs) shouldBe "foo"
            }
        }

        "activate volumeset marks other volumeset inactive" {
            transaction {
                md.createRepository(Repository("foo"))
                md.createVolumeSet("foo", null, true)
                val vs = md.createVolumeSet("foo", null, false)
                md.activateVolumeSet("foo", vs)
                md.getActiveVolumeSet("foo") shouldBe vs
            }
        }

        "activate unknown volume set fails" {
            shouldThrow<IllegalArgumentException> {
                transaction {
                    md.createRepository(Repository("foo"))
                    md.activateVolumeSet("foo", UUID.randomUUID().toString())
                }
            }
        }

        "mark volumeset deleting succeeds" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo")
                md.markVolumeSetDeleting(vs)
            }
        }

        "mark volumeset deleting marks all commits" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo")
                md.createVolume(vs, Volume("vol"))
                val commit = Commit(id = "id")
                md.createCommit("foo", vs, commit)
                md.markVolumeSetDeleting(vs)
                shouldThrow<NoSuchObjectException> {
                    md.getCommit("foo", "id")
                }
            }
        }

        "mark all volumesets deleting succeeds" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo")
                md.markAllVolumeSetsDeleting("foo")
                val volumeSets = md.listDeletingVolumeSets()
                volumeSets.size shouldBe 1
                volumeSets[0] shouldBe vs
            }
        }

        "mark active volumeset deleting makes it no longer active" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.markVolumeSetDeleting(vs)
                // After marking the only active VS as deleting there is no
                // longer an active VS for this repo. Pre-issue-#137 this
                // surfaced as a NullPointerException; now it's a typed
                // NoSuchObjectException.
                shouldThrow<NoSuchObjectException> {
                    md.getActiveVolumeSet("foo")
                }
                md.getActiveVolumeSetOrNull("foo") shouldBe null
            }
        }

        "deleting volume set shows up in list" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.markVolumeSetDeleting(vs)
                val deleting = md.listDeletingVolumeSets()
                deleting.size shouldBe 1
                deleting[0] shouldBe vs
            }
        }

        "volume set deletion succeeds" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.deleteVolumeSet(vs)
                // After deleting the only volume set the repo has no active
                // VS. Pre-#137 this surfaced as NullPointerException; now
                // we get a typed NoSuchObjectException.
                shouldThrow<NoSuchObjectException> {
                    md.getActiveVolumeSet("foo")
                }
                md.getActiveVolumeSetOrNull("foo") shouldBe null
            }
        }

        "volume set detected as empty" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.isVolumeSetEmpty(vs) shouldBe true
            }
        }

        "volume set detected as non-empty" {
            transaction {
                md.createRepository(Repository("foo"))
                val vs = md.createVolumeSet("foo", null, true)
                md.createCommit("foo", vs, Commit("id"))
                md.isVolumeSetEmpty(vs) shouldBe false
            }
        }

        "list inactive volume sets returns empty list" {
            transaction {
                md.createRepository(Repository("foo"))
                md.createVolumeSet("foo", null, true)
                md.listInactiveVolumeSets().size shouldBe 0
            }
        }

        "list inactive volume sets returns non-empty list" {
            transaction {
                md.createRepository(Repository("foo"))
                md.createVolumeSet("foo")
                md.listInactiveVolumeSets().size shouldBe 1
            }
        }
    }
}
