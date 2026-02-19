/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.context.docker.DockerZfsContext
import com.datadatdat.models.Repository
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
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
                    contentType().toString() shouldBe "application/json; charset=UTF-8"
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
    }
}
