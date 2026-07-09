// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.context.kubernetes

import dev.dit.models.ProgressEntry
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import dev.dit.remote.RemoteServer
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/**
 * Unit tests for KubernetesRunner — the per-pod entry point that's executed
 * inside a Kubernetes Job. We override the ServiceLoader-discovered remote
 * providers via reflection so we never depend on a real remote provider being
 * on the classpath. Stdout-driven progress reporting is captured by swapping
 * System.out for a buffered PrintStream.
 */
class KubernetesRunnerTest : StringSpec() {
    private fun installFakeProvider(
        runner: KubernetesRunner,
        providers: Map<String, RemoteServer>,
    ) {
        val field = runner.javaClass.getDeclaredField("remoteProviders")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(runner) as MutableMap<String, RemoteServer>
        map.clear()
        map.putAll(providers)
    }

    private fun captureStdout(block: () -> Unit): String {
        val saved = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(saved)
        }
        return buffer.toString()
    }

    init {
        "runner constructor does not blow up when no providers registered" {
            // ServiceLoader returns whatever's on the test classpath. The
            // important property is the runner constructs without throwing,
            // and progressId starts at 1.
            val runner = KubernetesRunner()
            runner.progressId shouldBe 1
        }

        "updateProgress maps RemoteProgress types to ProgressEntry types and emits JSON" {
            val runner = KubernetesRunner()
            val out =
                captureStdout {
                    runner.updateProgress(RemoteProgress.START, "starting", null)
                    runner.updateProgress(RemoteProgress.PROGRESS, "working", 25)
                    runner.updateProgress(RemoteProgress.MESSAGE, "msg", null)
                    runner.updateProgress(RemoteProgress.END, null, 100)
                }
            out.contains("\"type\":\"START\"") shouldBe true
            out.contains("\"type\":\"PROGRESS\"") shouldBe true
            out.contains("\"type\":\"MESSAGE\"") shouldBe true
            out.contains("\"type\":\"END\"") shouldBe true
            out.contains("\"message\":\"starting\"") shouldBe true
            out.contains("\"percent\":25") shouldBe true
            runner.progressId shouldBe 5
        }

        "runOperation drives the remote provider through start, per-volume, end" {
            val runner = KubernetesRunner()
            val remote = mockk<RemoteServer>(relaxed = true)
            every { remote.getProvider() } returns "fakeprov"
            every { remote.syncDataStart(any()) } returns "opdata"
            installFakeProvider(runner, mapOf("fakeprov" to remote))

            val params =
                KubernetesOperation(
                    remoteType = "fakeprov",
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    type = RemoteOperationType.PULL,
                    scratchVolume = "x-scratch",
                    volumes = listOf("v1", "v2"),
                    volumeDescriptions = listOf("/d1", "/d2"),
                )
            runner.runOperation("/data", params)

            verify { remote.syncDataStart(any()) }
            verify { remote.syncDataVolume(any(), "opdata", "v1", "/d1", "/data/v1", "/data/x-scratch") }
            verify { remote.syncDataVolume(any(), "opdata", "v2", "/d2", "/data/v2", "/data/x-scratch") }
            verify { remote.syncDataEnd(any(), "opdata", true) }
        }

        "runOperation marks operation failed when a volume sync throws" {
            val runner = KubernetesRunner()
            val remote = mockk<RemoteServer>(relaxed = true)
            every { remote.getProvider() } returns "fakeprov"
            every { remote.syncDataStart(any()) } returns null
            every { remote.syncDataVolume(any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
            installFakeProvider(runner, mapOf("fakeprov" to remote))

            val params =
                KubernetesOperation(
                    remoteType = "fakeprov",
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    operationId = "op",
                    commitId = "c",
                    commit = null,
                    type = RemoteOperationType.PUSH,
                    scratchVolume = "x-scratch",
                    volumes = listOf("v1"),
                    volumeDescriptions = listOf("/d1"),
                )
            shouldThrow<RuntimeException> {
                runner.runOperation("/data", params)
            }
            verify { remote.syncDataEnd(any(), any(), false) }
        }

        "runOperation throws when remoteType is unknown" {
            val runner = KubernetesRunner()
            installFakeProvider(runner, emptyMap())
            val params =
                KubernetesOperation(
                    remoteType = "nope",
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    operationId = "op",
                    commitId = "c",
                    commit = null,
                    type = RemoteOperationType.PULL,
                    scratchVolume = "x-scratch",
                    volumes = emptyList(),
                    volumeDescriptions = emptyList(),
                )
            shouldThrow<IllegalStateException> {
                runner.runOperation("/data", params)
            }
        }

        "KubernetesOperation data class round-trips through equality" {
            val a =
                KubernetesOperation(
                    remoteType = "x",
                    remote = mapOf("k" to "v"),
                    parameters = mapOf("p" to 1),
                    operationId = "o",
                    commitId = "c",
                    commit = mapOf("tag" to "v1"),
                    type = RemoteOperationType.PULL,
                    scratchVolume = "x-scratch",
                    volumes = listOf("a"),
                    volumeDescriptions = listOf("/a"),
                )
            val b = a.copy()
            (a == b) shouldBe true
            a.hashCode() shouldBe b.hashCode()
            a.toString().contains("KubernetesOperation") shouldBe true
        }

        "ProgressEntry round-trip through json emitted by updateProgress can be parsed" {
            val runner = KubernetesRunner()
            val out =
                captureStdout {
                    runner.updateProgress(RemoteProgress.PROGRESS, "working", 42)
                }
            val gson =
                com.google.gson
                    .GsonBuilder()
                    .create()
            val parsed = gson.fromJson(out.trim(), ProgressEntry::class.java)
            parsed.type shouldBe ProgressEntry.Type.PROGRESS
            parsed.message shouldBe "working"
            parsed.percent shouldBe 42
        }
    }
}
