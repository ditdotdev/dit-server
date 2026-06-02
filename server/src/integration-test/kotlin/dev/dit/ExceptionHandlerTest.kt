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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.kubernetes.client.openapi.ApiException
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.IOException

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

        "error response includes details field when dit.debug=true" {
            // exceptionToError reads dit.debug on every call (see
            // Application.kt). The production Docker image sets it; this
            // test sets it inline to match the production-with-debug
            // configuration.
            val previous = System.getProperty("dit.debug")
            System.setProperty("dit.debug", "true")
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
                if (previous == null) System.clearProperty("dit.debug") else System.setProperty("dit.debug", previous)
            }
        }

        "error response omits details field when dit.debug is unset" {
            val previous = System.clearProperty("dit.debug")
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
                if (previous != null) System.setProperty("dit.debug", previous)
            }
        }

        // The handler is invoked via the /v1/context route — we replace
        // getProvider on the mocked DockerZfsContext to throw the target
        // exception type so the StatusPages plugin's typed exception
        // dispatch lights up the corresponding handler lambda.

        "ApiException from a handler is mapped to 500" {
            every { context.getProvider() } throws ApiException(500, "kube boom")
            every { context.getProperties() } returns emptyMap()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.InternalServerError
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "ApiException"
                }
            }
        }

        "IOException containing 401 is mapped to 401 Unauthorized" {
            every { context.getProvider() } throws IOException("server returned 401 Unauthorized")
            every { context.getProperties() } returns emptyMap()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        "IOException with non-auth message is mapped to 500" {
            every { context.getProvider() } throws IOException("disk on fire")
            every { context.getProperties() } returns emptyMap()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.InternalServerError
                }
            }
        }

        "Generic Throwable is mapped to 500" {
            every { context.getProvider() } throws RuntimeException("uncategorized boom")
            every { context.getProperties() } returns emptyMap()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.InternalServerError
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "RuntimeException"
                }
            }
        }
    }
}
