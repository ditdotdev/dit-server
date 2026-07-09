// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.context.kubernetes

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

        // parseHostAliases is the literal-only convenience wrapper. host=auto
        // entries are skipped here (they require runtime cluster discovery,
        // which only the instance method has access to).
        "parseHostAliases skips host=auto entries silently" {
            val out = KubernetesCsiContext.parseHostAliases("api=auto,real=10.0.0.1")
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("real")
            out[0].ip shouldBe "10.0.0.1"
        }

        // resolveHostAliasEntries — the full resolver used by createJob —
        // takes a discoverIp callback that supplies the runtime IP for any
        // host=auto entry. Tests below pin down its behavior without
        // standing up a real cluster.

        "resolveHostAliasEntries returns empty for null spec" {
            val out = KubernetesCsiContext.resolveHostAliasEntries(null) { error("must not call") }
            out.size shouldBe 0
        }

        "resolveHostAliasEntries handles literal entries without invoking discovery" {
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("api=10.0.0.1,worker=10.0.0.2") {
                    error("discovery must not run for purely literal specs")
                }
            out.size shouldBe 2
            out[0].ip shouldBe "10.0.0.1"
            out[1].ip shouldBe "10.0.0.2"
        }

        "resolveHostAliasEntries substitutes discovered IP for host=auto" {
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("api=auto") { "192.168.99.99" }
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("api")
            out[0].ip shouldBe "192.168.99.99"
        }

        "resolveHostAliasEntries treats =AUTO as case-insensitive" {
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("api=AUTO,worker=Auto") { "10.0.0.5" }
            out.size shouldBe 2
            out[0].ip shouldBe "10.0.0.5"
            out[1].ip shouldBe "10.0.0.5"
        }

        // Discovery must run at most once per resolution even when multiple
        // entries need it — probing the cluster is a multi-second pod
        // creation, and the answer can't change inside one createJob call.
        "resolveHostAliasEntries invokes discoverIp at most once per call" {
            var calls = 0
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("a=auto,b=auto,c=auto") {
                    calls++
                    "10.1.2.3"
                }
            out.size shouldBe 3
            calls shouldBe 1
        }

        // Discovery is lazy: a spec with only literal entries must not pay
        // the discovery cost.
        "resolveHostAliasEntries does not invoke discoverIp when no auto entries present" {
            var calls = 0
            KubernetesCsiContext.resolveHostAliasEntries("a=10.0.0.1,b=10.0.0.2") {
                calls++
                "should-not-be-used"
            }
            calls shouldBe 0
        }

        // When discovery returns null (both probe-pod and node-InternalIP
        // strategies failed), the auto entry is dropped with a warning;
        // any literal entries in the same spec still go through.
        "resolveHostAliasEntries drops host=auto entries when discoverIp returns null" {
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("api=auto,real=10.0.0.1") { null }
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("real")
            out[0].ip shouldBe "10.0.0.1"
        }

        // Backwards compat: the malformed-entry warning text was tweaked to
        // mention the new "host=auto" syntax. Spec parsing itself still
        // tolerates the same shapes as before.
        "resolveHostAliasEntries skips malformed entries the same way parseHostAliases does" {
            val out =
                KubernetesCsiContext.resolveHostAliasEntries("no-equals,worker=,real=10.0.0.1") { null }
            out.size shouldBe 1
            out[0].hostnames shouldBe listOf("real")
        }
    }
}
