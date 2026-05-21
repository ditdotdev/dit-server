/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.context.docker.DockerZfsContext
import com.datadatdat.models.Error
import com.datadatdat.models.Repository
import com.google.gson.Gson
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.matchers.string.shouldContain
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExceptionHandlerTest : StringSpec() {
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
        "GET nonexistent repository returns 404 with error body" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/nonexistent").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldContain "nonexistent"
                }
            }
        }

        "GET nonexistent commit returns 404" {
            transaction {
                services.metadata.createRepository(Repository("testrepo"))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/testrepo/commits/nonexistent").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        "error response includes details field when datadatdat.debug=true" {
            // exceptionToError reads datadatdat.debug on every call (see
            // Application.kt). The production Docker image sets it; this
            // test sets it inline to match the production-with-debug
            // configuration.
            val previous = System.getProperty("datadatdat.debug")
            System.setProperty("datadatdat.debug", "true")
            try {
                testApplication {
                    application { mainProvider(services) }
                    client.get("/v1/repositories/missing").apply {
                        status shouldBe HttpStatusCode.NotFound
                        val error = Gson().fromJson(bodyAsText(), Error::class.java)
                        error.details shouldContain "NoSuchObjectException"
                    }
                }
            } finally {
                if (previous == null) System.clearProperty("datadatdat.debug") else System.setProperty("datadatdat.debug", previous)
            }
        }

        "error response omits details field when datadatdat.debug is unset" {
            val previous = System.clearProperty("datadatdat.debug")
            try {
                testApplication {
                    application { mainProvider(services) }
                    client.get("/v1/repositories/missing").apply {
                        status shouldBe HttpStatusCode.NotFound
                        val error = Gson().fromJson(bodyAsText(), Error::class.java)
                        error.details shouldBe null
                    }
                }
            } finally {
                if (previous != null) System.setProperty("datadatdat.debug", previous)
            }
        }
    }
}
