// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit

import dev.dit.context.docker.DockerZfsContext
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.mockk

class ContextApiTest :
    StringSpec({
        "get context returns configured provider and properties" {
            val context = DockerZfsContext(mapOf("pool" to "test"))
            val services = ServiceLocator(context)
            services.metadata.init()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    val body = bodyAsText()
                    body.contains("\"provider\":\"docker-zfs\"") shouldBe true
                    body.contains("\"pool\":\"test\"") shouldBe true
                }
            }
        }

        "get context tolerates a mocked context" {
            val context = mockk<DockerZfsContext>(relaxed = true)
            io.mockk.every { context.getProvider() } returns "fake"
            io.mockk.every { context.getProperties() } returns mapOf("k" to "v")
            val services = ServiceLocator(context)
            services.metadata.init()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/context").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText().contains("\"provider\":\"fake\"") shouldBe true
                }
            }
        }
    })
