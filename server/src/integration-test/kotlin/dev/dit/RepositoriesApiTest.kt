/*
 * Copyright Dit.
 */

package dev.dit

import com.google.gson.Gson
import dev.dit.context.docker.DockerZfsContext
import dev.dit.models.Error
import dev.dit.models.Repository
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.matchers.string.shouldContain
import io.kotlintest.shouldBe
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class RepositoriesApiTest : StringSpec() {
    @MockK
    var context = DockerZfsContext(mapOf("pool" to "test"))

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

    init {
        "list empty repositories succeeds" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "list repositories succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo1", properties = mapOf("a" to "b")))
                services.metadata.createRepository(Repository(name = "repo2", properties = mapOf()))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "[{\"name\":\"repo1\",\"properties\":{\"a\":\"b\"}}," +
                        "{\"name\":\"repo2\",\"properties\":{}}]"
                }
            }
        }

        "get repository succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf("a" to "b")))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/repo").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "{\"name\":\"repo\",\"properties\":{\"a\":\"b\"}}"
                }
            }
        }

        "get unknown repository returns not found" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/repo").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such repository 'repo'"
                }
            }
        }

        "get bad repository name returns bad request" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/bad@name").apply {
                    status shouldBe HttpStatusCode.BadRequest
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "IllegalArgumentException"
                    error.message shouldContain "invalid repository name"
                }
            }
        }

        "get repository status succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "foo", properties = emptyMap()))
                services.metadata.createVolumeSet("foo", null, true)
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/status").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "{}"
                }
            }
        }

        "delete repository succeeds" {
            transaction {
                services.metadata.createRepository(Repository("repo"))
            }
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/repo").apply {
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "delete bad repository name returns bad request" {
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/bad@name").apply {
                    status shouldBe HttpStatusCode.BadRequest
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "IllegalArgumentException"
                    error.message shouldContain "invalid repository name"
                }
            }
        }

        "create repository succeeds" {
            every { context.createVolumeSet(any()) } just Runs
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"repo\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Created
                        contentType().toString() shouldBe "application/json"
                        bodyAsText() shouldBe "{\"name\":\"repo\",\"properties\":{\"a\":\"b\"}}"
                    }
            }
        }

        "create repository fails with bad name" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"bad@name\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.BadRequest
                        val error = Gson().fromJson(bodyAsText(), Error::class.java)
                        error.code shouldBe "IllegalArgumentException"
                        error.message shouldContain "invalid repository name"
                    }
            }
        }

        "create repository fails with bad json" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories") {
                        contentType(ContentType.Application.Json)
                        setBody("-")
                    }.apply {
                        status shouldBe HttpStatusCode.BadRequest
                    }
            }
        }

        "update repository succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo1", properties = mapOf("a" to "b")))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo1") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"repo2\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                        contentType().toString() shouldBe "application/json"
                        bodyAsText() shouldBe "{\"name\":\"repo2\",\"properties\":{\"a\":\"b\"}}"
                    }
            }
        }

        "update repository fails with bad json" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo") {
                        contentType(ContentType.Application.Json)
                        setBody("-")
                    }.apply {
                        status shouldBe HttpStatusCode.BadRequest
                    }
            }
        }

        "update repository fails with bad name" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"bad@name\",\"properties\":{\"a\":\"b\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.BadRequest
                        val error = Gson().fromJson(bodyAsText(), Error::class.java)
                        error.code shouldBe "IllegalArgumentException"
                        error.message shouldContain "invalid repository name"
                    }
            }
        }
    }
}
