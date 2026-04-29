/*
 * Copyright Datadatdat.
 */

package com.datadatdat.context.kubernetes

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class KubernetesCsiContextTest : StringSpec() {
    init {
        "parseHostAliases returns empty list for null" {
            KubernetesCsiContext.parseHostAliases(null).size shouldBe 0
        }

        "parseHostAliases returns empty list for blank" {
            KubernetesCsiContext.parseHostAliases("   ").size shouldBe 0
        }

        "parseHostAliases parses single entry" {
            val out = KubernetesCsiContext.parseHostAliases("api-gateway=172.18.0.5")
            out.size shouldBe 1
            out[0].ip shouldBe "172.18.0.5"
            out[0].hostnames shouldBe listOf("api-gateway")
        }

        "parseHostAliases parses multiple comma-separated entries" {
            val out = KubernetesCsiContext.parseHostAliases("api-gateway=172.18.0.5,worker=172.18.0.6")
            out.size shouldBe 2
            out[0].hostnames shouldBe listOf("api-gateway")
            out[0].ip shouldBe "172.18.0.5"
            out[1].hostnames shouldBe listOf("worker")
            out[1].ip shouldBe "172.18.0.6"
        }

        "parseHostAliases tolerates whitespace around tokens" {
            val out = KubernetesCsiContext.parseHostAliases("  api-gateway = 172.18.0.5 , worker = 172.18.0.6 ")
            out.size shouldBe 2
            out[0].hostnames shouldBe listOf("api-gateway")
            out[0].ip shouldBe "172.18.0.5"
        }

        "parseHostAliases skips malformed entries with no equals" {
            val out = KubernetesCsiContext.parseHostAliases("api-gateway-no-ip,worker=172.18.0.6")
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("worker")
        }

        "parseHostAliases skips malformed entries with empty host or ip" {
            val out = KubernetesCsiContext.parseHostAliases("=172.18.0.5,worker=,real=10.0.0.1")
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("real")
        }

        "parseHostAliases skips empty entries from trailing commas" {
            val out = KubernetesCsiContext.parseHostAliases("api=1.2.3.4,,,")
            out.size shouldBe 1
        }
    }
}
