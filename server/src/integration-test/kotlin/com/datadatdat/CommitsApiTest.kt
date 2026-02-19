/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.context.docker.DockerZfsContext
import com.datadatdat.exception.NoSuchObjectException
import com.datadatdat.models.Commit
import com.datadatdat.models.CommitStatus
import com.datadatdat.models.Error
import com.datadatdat.models.Repository
import com.google.gson.Gson
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.matchers.string.shouldContain
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class CommitsApiTest : StringSpec() {
    lateinit var vs: String

    @MockK
    lateinit var context: DockerZfsContext

    @InjectMockKs
    @OverrideMockKs
    var services = ServiceLocator(mockk())

    override fun beforeSpec(spec: Spec) {
        services.metadata.init()
    }

    override fun beforeTest(testCase: TestCase) {
        services.metadata.clear()
        vs =
            transaction {
                services.metadata.createRepository(Repository("foo"))
                services.metadata.createVolumeSet("foo", null, true)
            }
        return MockKAnnotations.init(this)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "list empty commits succeeds" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json; charset=UTF-8"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "list commits succeeds" {
            transaction {
                services.metadata.createCommit(
                    "foo",
                    vs,
                    Commit(
                        id = "hash1",
                        properties =
                            mapOf(
                                "a" to "b",
                                "timestamp" to "2019-09-20T13:45:38Z",
                            ),
                    ),
                )
                services.metadata.createCommit(
                    "foo",
                    vs,
                    Commit(
                        id = "hash2",
                        properties =
                            mapOf(
                                "c" to "d",
                                "timestamp" to "2019-09-20T13:45:37Z",
                            ),
                    ),
                )
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe
                        "[{\"id\":\"hash1\",\"properties\":{\"a\":\"b\",\"timestamp\":\"2019-09-20T13:45:38Z\"}},{\"id\":\"hash2\",\"properties\":{\"c\":\"d\",\"timestamp\":\"2019-09-20T13:45:37Z\"}}]"
                }
            }
        }
        "list commits filters result with exact match" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit(id = "hash1", properties = mapOf("tags" to mapOf("a" to "b"))))
                services.metadata.createCommit("foo", vs, Commit(id = "hash2", properties = mapOf("tags" to mapOf("c" to "d"))))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits?tag=a=b").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "[{\"id\":\"hash1\",\"properties\":{\"tags\":{\"a\":\"b\"}}}]"
                }
            }
        }

        "list commits filters result with exists match" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit(id = "hash1", properties = mapOf("tags" to mapOf("a" to "b"))))
                services.metadata.createCommit("foo", vs, Commit(id = "hash2", properties = mapOf("tags" to mapOf("c" to "d"))))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits?tag=a").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "[{\"id\":\"hash1\",\"properties\":{\"tags\":{\"a\":\"b\"}}}]"
                }
            }
        }

        "list commits filters result with compound match" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit(id = "hash1", properties = mapOf("tags" to mapOf("a" to "b", "c" to "d"))))
                services.metadata.createCommit("foo", vs, Commit(id = "hash2", properties = mapOf("tags" to mapOf("c" to "d"))))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits?tag=a=b&tag=c=d").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "[{\"id\":\"hash1\",\"properties\":{\"tags\":{\"a\":\"b\",\"c\":\"d\"}}}]"
                }
            }
        }

        "list commits fails with non existent repository" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/repo/commits").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such repository 'repo'"
                }
            }
        }

        "get commit succeeds" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit(id = "hash", properties = mapOf("a" to "b")))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits/hash").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "{\"id\":\"hash\",\"properties\":{\"a\":\"b\"}}"
                }
            }
        }

        "get commit status succeeds" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit("hash"))
            }
            every { context.getCommitStatus(any(), any(), any()) } returns
                CommitStatus(logicalSize = 3, actualSize = 6, uniqueSize = 9, ready = false, error = "error")
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits/hash/status").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "{\"logicalSize\":3,\"actualSize\":6,\"uniqueSize\":9,\"ready\":false,\"error\":\"error\"}"
                }
            }
        }

        "get commit from non-existent repo returns no such object" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/bar/commits/hash").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such repository 'bar'"
                }
            }
        }

        "get non-existent commit returns no such object" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits/hash").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such commit 'hash' in repository 'foo'"
                }
            }
        }

        "get bad commit id returns bad request" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/commits/bad@hash").apply {
                    status shouldBe HttpStatusCode.BadRequest
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "IllegalArgumentException"
                    error.message shouldContain "invalid commit id"
                }
            }
        }

        "update commit succeeds" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit("hash"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/foo/commits/hash") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"id\":\"hash\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                        transaction {
                            val commit = services.metadata.getCommit("foo", "hash").second
                            commit.properties["a"] shouldBe "b"
                        }
                    }
            }
        }

        "delete commit succeeds" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit("hash"))
            }
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/foo/commits/hash").apply {
                    status shouldBe HttpStatusCode.NoContent
                    shouldThrow<NoSuchObjectException> {
                        transaction {
                            services.metadata.getCommit("foo", "hash")
                        }
                    }
                }
            }
        }

        "delete commit from non-existent repo returns no such object" {
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/bar/commits/hash").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such repository 'bar'"
                }
            }
        }

        "delete non-existent commit returns no such object" {
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/foo/commits/hash").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such commit 'hash' in repository 'foo'"
                }
            }
        }

        "create commit succeeds" {
            every { context.commitVolumeSet(any(), any()) } just Runs
            every { context.commitVolume(any(), any(), any(), any()) } just Runs
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/foo/commits") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"id\":\"hash\",\"properties\":{\"a\":\"b\",\"timestamp\":\"2019-04-28T23:04:06Z\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Created
                        contentType().toString() shouldBe "application/json; charset=UTF-8"
                        bodyAsText() shouldBe "{\"id\":\"hash\",\"properties\":{\"a\":\"b\",\"timestamp\":\"2019-04-28T23:04:06Z\"}}"
                        verify {
                            context.commitVolumeSet(vs, "hash")
                        }
                    }
            }
        }

        "create commit in non-existent repo returns no such object" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/bar/commits") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"id\":\"hash\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.NotFound
                        val error = Gson().fromJson(bodyAsText(), Error::class.java)
                        error.code shouldBe "NoSuchObjectException"
                        error.message shouldBe "no such repository 'bar'"
                    }
            }
        }

        "checkout commit succeeds" {
            transaction {
                services.metadata.createCommit("foo", vs, Commit("hash"))
            }

            every { context.cloneVolumeSet(any(), any(), any()) } just Runs
            every { context.cloneVolume(any(), any(), any(), any(), any()) } returns emptyMap()

            testApplication {
                application { mainProvider(services) }
                client.post("/v1/repositories/foo/commits/hash/checkout").apply {
                    status shouldBe HttpStatusCode.NoContent
                    val activeVs =
                        transaction {
                            services.metadata.getActiveVolumeSet("foo")
                        }
                    activeVs shouldNotBe vs
                    verify {
                        context.cloneVolumeSet(vs, "hash", activeVs)
                    }
                }
            }
        }
    }
}
