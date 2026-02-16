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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.ktor.util.KtorExperimentalAPI
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.mockk
import org.jetbrains.exposed.sql.transactions.transaction

@OptIn(KtorExperimentalAPI::class)
class ExceptionHandlerTest : StringSpec() {
    @MockK
    lateinit var context: DockerZfsContext

    @InjectMockKs
    @OverrideMockKs
    var services = ServiceLocator(mockk())

    var engine = TestApplicationEngine(createTestEnvironment())

    override fun beforeSpec(spec: Spec) {
        with(engine) {
            start()
            services.metadata.init()
            application.mainProvider(services)
        }
    }

    override fun afterSpec(spec: Spec) {
        engine.stop(0L, 0L)
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
            with(engine.handleRequest(HttpMethod.Get, "/v1/repositories/nonexistent")) {
                response.status() shouldBe HttpStatusCode.NotFound
                val error = Gson().fromJson(response.content, Error::class.java)
                error.code shouldBe "NoSuchObjectException"
                error.message shouldContain "nonexistent"
            }
        }

        "GET nonexistent commit returns 404" {
            transaction {
                services.metadata.createRepository(Repository("testrepo"))
            }
            with(engine.handleRequest(HttpMethod.Get, "/v1/repositories/testrepo/commits/nonexistent")) {
                response.status() shouldBe HttpStatusCode.NotFound
            }
        }

        "error response includes details field" {
            with(engine.handleRequest(HttpMethod.Get, "/v1/repositories/missing")) {
                response.status() shouldBe HttpStatusCode.NotFound
                val error = Gson().fromJson(response.content, Error::class.java)
                error.details shouldContain "NoSuchObjectException"
            }
        }
    }
}
