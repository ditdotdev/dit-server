// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit

import dev.dit.remote.RemoteServer
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.every
import io.mockk.mockk

class ServiceLocatorTest : StringSpec() {
    init {
        "remoteProvider throws for unknown provider type" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            val ex =
                shouldThrow<IllegalArgumentException> {
                    services.remoteProvider("nonexistent")
                }
            ex.message!!.contains("nonexistent") shouldBe true
        }

        "setRemoteProvider and remoteProvider roundtrip" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            val mockServer = mockk<RemoteServer>()
            every { mockServer.getProvider() } returns "test-provider"

            services.setRemoteProvider("test-provider", mockServer)
            services.remoteProvider("test-provider") shouldBe mockServer
        }

        "setRemoteProvider overwrites existing provider" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            val server1 = mockk<RemoteServer>()
            val server2 = mockk<RemoteServer>()

            services.setRemoteProvider("s3", server1)
            services.remoteProvider("s3") shouldBe server1

            services.setRemoteProvider("s3", server2)
            services.remoteProvider("s3") shouldBe server2
        }

        "ServiceLocator initializes metadata provider" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            services.metadata shouldBe services.metadata
        }

        "ServiceLocator initializes orchestrators" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            services.commits shouldBe services.commits
            services.repositories shouldBe services.repositories
            services.operations shouldBe services.operations
            services.volumes shouldBe services.volumes
            services.remotes shouldBe services.remotes
            services.reaper shouldBe services.reaper
        }

        "ServiceLocator initializes gson" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            services.gson shouldBe services.gson
        }

        "remoteProvider error message includes available providers" {
            val context = mockk<dev.dit.context.RuntimeContext>(relaxed = true)
            val services = ServiceLocator(context)

            val mockServer = mockk<RemoteServer>()
            services.setRemoteProvider("test-type", mockServer)

            val ex =
                shouldThrow<IllegalArgumentException> {
                    services.remoteProvider("definitely-not-a-real-provider-xyz")
                }
            ex.message!!.contains("test-type") shouldBe true
        }
    }
}
