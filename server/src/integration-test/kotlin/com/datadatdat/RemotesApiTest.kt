/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.models.Remote
import com.datadatdat.models.Repository
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class RemotesApiTest : StringSpec() {
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

    /*
     * Note that docker explicitly doesn't set the Content-Type header to application/json, so
     * we want to make sure that we respond correctly even when this header isn't set.
     */
    init {
        "get empty remote list succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/repo/remotes").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "get remote list succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
                services.metadata.addRemote(
                    "repo",
                    Remote(
                        "engine",
                        "bar",
                        mapOf(
                            "address" to "a",
                            "username" to "u",
                            "password" to "p",
                            "repository" to "r",
                        ),
                    ),
                )
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/repositories/repo/remotes").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[{\"provider\":\"nop\",\"name\":\"foo\",\"properties\":{}}," +
                        "{\"provider\":\"engine\",\"name\":\"bar\",\"properties\":{\"address\":\"a\"," +
                        "\"username\":\"u\",\"password\":\"p\",\"repository\":\"r\"}}]"
                }
            }
        }

        "create remote succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"name\":\"a\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Created
                        bodyAsText() shouldBe "{\"provider\":\"nop\",\"name\":\"a\",\"properties\":{}}"
                    }
            }
        }

        "add duplicate remote fails" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "a"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"name\":\"a\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Conflict
                    }
            }
        }

        "update non-existent remote fails" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes/foo") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"name\":\"a\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.NotFound
                    }
            }
        }

        "update remote succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
                services.metadata.addRemote("repo", Remote("s3", "bar", mapOf("bucket" to "bucket")))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes/bar") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"s3\",\"name\":\"bar\",\"properties\":{\"bucket\":\"bocket\"}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                        bodyAsText() shouldBe "{\"provider\":\"s3\",\"name\":\"bar\",\"properties\":{\"bucket\":\"bocket\"}}"
                    }
            }
        }

        "rename remote succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
                services.metadata.addRemote("repo", Remote("nop", "bar"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes/bar") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"name\":\"baz\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                    }
            }
        }

        "rename remote to existing name fails" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
                services.metadata.addRemote("repo", Remote("nop", "bar"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .post("/v1/repositories/repo/remotes/bar") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"name\":\"foo\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.Conflict
                    }
            }
        }

        "delete remote succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
                services.metadata.addRemote("repo", Remote("nop", "bar"))
            }
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/repositories/repo/remotes/bar").apply {
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "list remote commits succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .get("/v1/repositories/repo/remotes/foo/commits") {
                        header("datadatdat-remote-parameters", "{\"provider\":\"nop\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                        contentType().toString() shouldBe "application/json"
                        bodyAsText() shouldBe "[]"
                    }
            }
        }

        "get remote commit succeeds" {
            transaction {
                services.metadata.createRepository(Repository(name = "repo", properties = mapOf()))
                services.metadata.addRemote("repo", Remote("nop", "foo"))
            }
            testApplication {
                application { mainProvider(services) }
                client
                    .get("/v1/repositories/repo/remotes/foo/commits/c") {
                        header("datadatdat-remote-parameters", "{\"provider\":\"nop\",\"properties\":{}}")
                    }.apply {
                        status shouldBe HttpStatusCode.OK
                        contentType().toString() shouldBe "application/json"
                        bodyAsText() shouldBe "{\"id\":\"c\",\"properties\":{}}"
                    }
            }
        }
    }
}
