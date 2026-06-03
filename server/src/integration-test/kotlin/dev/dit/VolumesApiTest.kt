/*
 * Copyright Dit.
 */

package dev.dit

import dev.dit.context.docker.DockerZfsContext
import dev.dit.models.Repository
import dev.dit.models.Volume
import dev.dit.models.VolumeStatus
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
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
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class VolumesApiTest : StringSpec() {
    lateinit var vs: String

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
        vs =
            transaction {
                services.metadata.createRepository(Repository(name = "foo", properties = emptyMap()))
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
        "list volumes succeeds" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/volumes").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "list volumes for non-existent repo fails" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/bar/volumes").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        "create volume succeeds" {
            every { context.createVolume(any(), "vol") } returns mapOf("mountpoint" to "/mnt/vol")
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/foo/volumes") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"vol\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Created
                        bodyAsText().contains("\"name\":\"vol\"") shouldBe true
                    }
            }
        }

        "create volume with invalid name fails" {
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/foo/volumes") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"name\":\"bad name\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.BadRequest
                    }
            }
        }

        "get volume succeeds" {
            every { context.createVolume(any(), "vol") } returns mapOf("mountpoint" to "/mnt/vol")
            transaction {
                services.metadata.createVolume(vs, Volume("vol", config = mapOf("mountpoint" to "/mnt/vol")))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/volumes/vol").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText().contains("\"name\":\"vol\"") shouldBe true
                }
            }
        }

        "get volume for unknown name returns not found" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/volumes/missing").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        "delete volume succeeds" {
            transaction {
                services.metadata.createVolume(vs, Volume("vol", config = mapOf("mountpoint" to "/mnt/vol")))
            }
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/foo/volumes/vol").apply {
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "activate volume succeeds" {
            transaction {
                services.metadata.createVolume(vs, Volume("vol", config = mapOf("mountpoint" to "/mnt/vol")))
            }
            every { context.activateVolume(any(), "vol", any()) } returns Unit
            testApplication {
                application { mainProvider(services) }
                client.post("/v1/repositories/foo/volumes/vol/activate").apply {
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "deactivate volume succeeds" {
            transaction {
                services.metadata.createVolume(vs, Volume("vol", config = mapOf("mountpoint" to "/mnt/vol")))
            }
            every { context.deactivateVolume(any(), "vol", any()) } returns Unit
            testApplication {
                application { mainProvider(services) }
                client.post("/v1/repositories/foo/volumes/vol/deactivate").apply {
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "get volume status succeeds" {
            transaction {
                services.metadata.createVolume(vs, Volume("vol", config = mapOf("mountpoint" to "/mnt/vol")))
            }
            every { context.getVolumeStatus(any(), "vol", any()) } returns
                VolumeStatus(name = "vol", logicalSize = 100, actualSize = 200, ready = true, error = null)
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/foo/volumes/vol/status").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText().contains("\"logicalSize\":100") shouldBe true
                }
            }
        }
    }
}
