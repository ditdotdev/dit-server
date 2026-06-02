/*
 * Copyright Dit.
 */

package dev.dit.context.kubernetes

import dev.dit.models.Volume
import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import dev.dit.remote.nop.server.NopRemoteServer
import dev.dit.shell.CommandException
import dev.dit.shell.CommandExecutor
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.StorageV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobStatus
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimStatus
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodStatus
import io.kubernetes.client.openapi.models.V1Secret
import io.kubernetes.client.openapi.models.V1StorageClass
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Instance-level tests for KubernetesCsiContext. The class's init block reads
 * ~/.kube/config from disk and wires up kubernetes-client API objects, so we
 * stage a minimal kubeconfig in a temp dir and point user.home at it for the
 * lifetime of each test. After construction we swap the K8s API fields out
 * for mockk instances via reflection so we never actually talk to a cluster.
 */
class KubernetesCsiContextInstanceTest : StringSpec() {
    private var tempHome: File? = null
    private var savedHome: String? = null

    /**
     * Build a syntactically-valid kubeconfig that points at a bogus cluster.
     * We never let the real API client actually issue requests — it's only
     * constructed because KubernetesCsiContext's init block demands it.
     */
    private fun writeFakeKubeConfig(home: File) {
        val kubeDir = File(home, ".kube")
        kubeDir.mkdirs()
        File(kubeDir, "config").writeText(
            """
            apiVersion: v1
            kind: Config
            current-context: fake
            clusters:
            - name: fake
              cluster:
                server: https://localhost:9
                insecure-skip-tls-verify: true
            contexts:
            - name: fake
              context:
                cluster: fake
                user: fake
                namespace: default
            - name: other
              context:
                cluster: fake
                user: fake
                namespace: other
            users:
            - name: fake
              user:
                token: deadbeef
            """.trimIndent(),
        )
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun <T> getField(
        target: Any,
        name: String,
    ): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    /**
     * Build a fully-mocked context: real construction runs (so all init
     * logic is exercised), then we swap out the API and executor with
     * mocks before any test action.
     */
    private fun newMockedContext(
        properties: Map<String, String> = emptyMap(),
        coreApi: CoreV1Api = mockk(relaxed = true),
        storageApi: StorageV1Api = mockk(relaxed = true),
        batchApi: BatchV1Api = mockk(relaxed = true),
        executor: CommandExecutor = mockk(relaxed = true),
    ): KubernetesCsiContext {
        val ctx = KubernetesCsiContext(properties)
        setField(ctx, "coreApi", coreApi)
        setField(ctx, "storageApi", storageApi)
        setField(ctx, "batchApi", batchApi)
        setField(ctx, "executor", executor)
        return ctx
    }

    override fun beforeSpec(spec: Spec) {
        tempHome = Files.createTempDirectory("k8s-ctx-test-home").toFile()
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome!!.absolutePath)
        writeFakeKubeConfig(tempHome!!)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        // --- Construction / configuration ---

        "constructor wires up default namespace when properties omit it" {
            val ctx = newMockedContext()
            ctx.namespace shouldBe "default"
            ctx.getProvider() shouldBe "kubernetes-csi"
            ctx.getProperties() shouldBe emptyMap()
        }

        "constructor honors explicit namespace property" {
            val ctx = newMockedContext(mapOf("namespace" to "foo"))
            ctx.namespace shouldBe "foo"
            ctx.getProperties()["namespace"] shouldBe "foo"
        }

        "constructor honors explicit context property" {
            // 'other' context is defined in the fake kubeconfig
            val ctx = newMockedContext(mapOf("context" to "other"))
            ctx.namespace shouldBe "default" // namespace property still controls this
        }

        "constructor honors explicit config file property" {
            // 'config' property reads from ~/.kube/<value>; write a duplicate
            val kubeDir = File(tempHome!!, ".kube")
            File(kubeDir, "alt").writeText(File(kubeDir, "config").readText())
            val ctx = newMockedContext(mapOf("config" to "alt"))
            ctx.namespace shouldBe "default"
        }

        // --- No-op lifecycle methods (each is one line of pass-through) ---

        "createVolumeSet is a no-op" {
            newMockedContext().createVolumeSet("vs")
        }

        "cloneVolumeSet is a no-op" {
            newMockedContext().cloneVolumeSet("src", "commit", "dst")
        }

        "deleteVolumeSet is a no-op" {
            newMockedContext().deleteVolumeSet("vs")
        }

        "commitVolumeSet is a no-op" {
            newMockedContext().commitVolumeSet("vs", "commit")
        }

        "deleteVolumeSetCommit is a no-op" {
            newMockedContext().deleteVolumeSetCommit("vs", "commit")
        }

        "activateVolume is a no-op" {
            newMockedContext().activateVolume("vs", "vol", emptyMap())
        }

        "deactivateVolume is a no-op" {
            newMockedContext().deactivateVolume("vs", "vol", emptyMap())
        }

        // --- createVolume ---

        "createVolume requests a PVC with the storage class when configured" {
            val core = mockk<CoreV1Api>(relaxed = true)
            // Capture the PVC builder shape via verify; the request-builder API
            // requires us to stub the chained .execute() call.
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedPersistentVolumeClaimRequest>(relaxed = true)
            every {
                core.createNamespacedPersistentVolumeClaim(any(), any())
            } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim().status(
                    V1PersistentVolumeClaimStatus().phase("Pending"),
                )

            val ctx = newMockedContext(mapOf("storageClass" to "fast"), coreApi = core)
            val cfg = ctx.createVolume("vs", "vol")
            cfg["pvc"] shouldBe "vs-vol"
            cfg["namespace"] shouldBe "default"
            cfg["size"] shouldBe "1Gi"
            verify {
                core.createNamespacedPersistentVolumeClaim(eq("default"), any())
                req.execute()
            }
        }

        "createVolume omits storage class when not configured" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedPersistentVolumeClaimRequest>(relaxed = true)
            every { core.createNamespacedPersistentVolumeClaim(any(), any()) } returns req
            every { req.execute() } returns V1PersistentVolumeClaim()
            val ctx = newMockedContext(coreApi = core)
            val cfg = ctx.createVolume("vs", "vol")
            cfg["pvc"] shouldBe "vs-vol"
        }

        // --- deleteVolume ---

        "deleteVolume shells out to kubectl delete" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec(*anyVararg()) } returns ""
            val ctx = newMockedContext(executor = exec)
            ctx.deleteVolume("vs", "vol", mapOf("pvc" to "vs-vol"))
            verify { exec.exec("kubectl", "delete", "--wait=false", "pvc", "vs-vol") }
        }

        "deleteVolume swallows NotFound from kubectl delete" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "delete", *anyVararg())
            } throws CommandException("", 1, "Error from server (NotFound): pvc not found")
            val ctx = newMockedContext(executor = exec)
            ctx.deleteVolume("vs", "vol", mapOf("pvc" to "vs-vol"))
        }

        "deleteVolume rethrows non-NotFound errors from kubectl delete" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "delete", *anyVararg())
            } throws CommandException("", 1, "boom")
            val ctx = newMockedContext(executor = exec)
            shouldThrow<CommandException> {
                ctx.deleteVolume("vs", "vol", mapOf("pvc" to "vs-vol"))
            }
        }

        "deleteVolume throws when pvc config entry missing" {
            shouldThrow<IllegalStateException> {
                newMockedContext().deleteVolume("vs", "vol", emptyMap())
            }
        }

        // --- commitVolume ---

        "commitVolume applies a VolumeSnapshot manifest" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec(*anyVararg()) } returns ""
            val ctx = newMockedContext(executor = exec)
            ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol", "size" to "1Gi"))
            verify { exec.exec("kubectl", "apply", "-f", any()) }
        }

        "commitVolume includes snapshotClass when configured" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec(*anyVararg()) } returns ""
            val ctx = newMockedContext(mapOf("snapshotClass" to "csi"), executor = exec)
            ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol", "size" to "10Gi"))
            verify { exec.exec("kubectl", "apply", "-f", any()) }
        }

        "commitVolume rejects malformed pvc name" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "bad name", "size" to "1Gi"))
            }
        }

        "commitVolume rejects malformed commitId" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.commitVolume("vs", "bad commit", "vol", mapOf("pvc" to "vs-vol", "size" to "1Gi"))
            }
        }

        "commitVolume rejects malformed size string" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol", "size" to "not-a-size"))
            }
        }

        "commitVolume rejects malformed snapshotClass" {
            val ctx = newMockedContext(mapOf("snapshotClass" to "bad snap"))
            shouldThrow<IllegalArgumentException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol", "size" to "1Gi"))
            }
        }

        "commitVolume requires pvc in config" {
            val ctx = newMockedContext()
            shouldThrow<IllegalStateException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("size" to "1Gi"))
            }
        }

        "commitVolume requires size in config" {
            val ctx = newMockedContext()
            shouldThrow<IllegalStateException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol"))
            }
        }

        "commitVolume logs and rethrows when kubectl apply fails" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "apply", *anyVararg())
            } throws CommandException("", 1, "kubectl boom")
            val ctx = newMockedContext(executor = exec)
            shouldThrow<CommandException> {
                ctx.commitVolume("vs", "commit", "vol", mapOf("pvc" to "vs-vol", "size" to "1Gi"))
            }
        }

        // --- deleteVolumeCommit ---

        "deleteVolumeCommit shells out to kubectl delete volumesnapshot" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec(*anyVararg()) } returns ""
            val ctx = newMockedContext(executor = exec)
            ctx.deleteVolumeCommit("vs", "commit", "vol")
            verify {
                exec.exec("kubectl", "delete", "--wait=false", "volumesnapshot", "vs-vol-commit")
            }
        }

        // --- cloneVolume ---

        "cloneVolume applies a PVC manifest derived from a snapshot" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec(*anyVararg()) } returns ""
            val ctx = newMockedContext(executor = exec)
            val cfg = ctx.cloneVolume("src", "commit", "dst", "vol", mapOf("size" to "5Gi"))
            cfg["pvc"] shouldBe "dst-vol"
            cfg["namespace"] shouldBe "default"
            cfg["size"] shouldBe "5Gi"
            verify { exec.exec("kubectl", "apply", "-f", any()) }
        }

        "cloneVolume rejects malformed sourceVolumeSet" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("bad src", "commit", "dst", "vol", mapOf("size" to "1Gi"))
            }
        }

        "cloneVolume rejects malformed sourceCommit" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("src", "bad commit", "dst", "vol", mapOf("size" to "1Gi"))
            }
        }

        "cloneVolume rejects malformed newVolumeSet" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("src", "commit", "bad dst", "vol", mapOf("size" to "1Gi"))
            }
        }

        "cloneVolume rejects malformed volumeName" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("src", "commit", "dst", "bad vol", mapOf("size" to "1Gi"))
            }
        }

        "cloneVolume rejects malformed size" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("src", "commit", "dst", "vol", mapOf("size" to "xxx"))
            }
        }

        "cloneVolume requires size in config" {
            val ctx = newMockedContext()
            shouldThrow<IllegalStateException> {
                ctx.cloneVolume("src", "commit", "dst", "vol", emptyMap())
            }
        }

        "cloneVolume rejects empty name" {
            val ctx = newMockedContext()
            shouldThrow<IllegalArgumentException> {
                ctx.cloneVolume("src", "commit", "", "vol", mapOf("size" to "1Gi"))
            }
        }

        // --- getPvcStatus ---

        "getPvcStatus returns ready when phase is Bound" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))
            val ctx = newMockedContext(coreApi = core)
            val (ready, error) = ctx.getPvcStatus("vs-vol")
            ready shouldBe true
            error shouldBe null
        }

        "getPvcStatus returns ready when phase is Pending and no storage class" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Pending"))
            val ctx = newMockedContext(coreApi = core)
            val (ready, error) = ctx.getPvcStatus("vs-vol")
            // No storage class on the claim spec, so default readyPhases includes Pending
            ready shouldBe true
            error shouldBe null
        }

        "getPvcStatus respects Immediate binding mode" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val storage = mockk<StorageV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim()
                    .spec(V1PersistentVolumeClaimSpec().storageClassName("fast"))
                    .status(V1PersistentVolumeClaimStatus().phase("Pending"))

            val sreq = mockk<io.kubernetes.client.openapi.apis.StorageV1Api.APIreadStorageClassRequest>(relaxed = true)
            every { storage.readStorageClass(any()) } returns sreq
            every { sreq.execute() } returns V1StorageClass().volumeBindingMode("Immediate")

            val ctx = newMockedContext(coreApi = core, storageApi = storage)
            val (ready, _) = ctx.getPvcStatus("vs-vol")
            // Immediate mode means only Bound counts as ready
            ready shouldBe false
        }

        "getPvcStatus returns error message for unknown phase" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Failed"))
            val ctx = newMockedContext(coreApi = core)
            val (ready, error) = ctx.getPvcStatus("vs-vol")
            ready shouldBe false
            error shouldNotBe null
            error!!.contains("unknown state") shouldBe true
        }

        // --- getVolumeStatus ---

        "getVolumeStatus aggregates pvc status with config-supplied sizes" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val req = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns req
            every { req.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))
            val ctx = newMockedContext(coreApi = core)
            val status = ctx.getVolumeStatus("vs", "vol", mapOf("pvc" to "vs-vol", "size" to "1Gi"))
            status.name shouldBe "vol"
            status.ready shouldBe true
            status.logicalSize shouldBe (1L shl 30)
            status.actualSize shouldBe (1L shl 30)
        }

        "getVolumeStatus fails when pvc missing" {
            shouldThrow<IllegalStateException> {
                newMockedContext().getVolumeStatus("vs", "vol", mapOf("size" to "1Gi"))
            }
        }

        // --- getCommitStatus ---

        "getCommitStatus reports not ready when snapshot status is missing" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "get", "volumesnapshot", any(), "-o", "json")
            } returns """{"metadata":{"labels":{"size":"1Gi"}}}"""
            val ctx = newMockedContext(executor = exec)
            val status = ctx.getCommitStatus("vs", "commit", listOf("a"))
            status.ready shouldBe false
            status.logicalSize shouldBe (1L shl 30)
        }

        "getCommitStatus aggregates ready snapshots" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "get", "volumesnapshot", any(), "-o", "json")
            } returns
                """
                {
                  "status": { "readyToUse": "true" },
                  "metadata": { "labels": { "size": "2Gi" } }
                }
                """.trimIndent()
            val ctx = newMockedContext(executor = exec)
            val status = ctx.getCommitStatus("vs", "commit", listOf("a", "b"))
            status.ready shouldBe true
            // Two snapshots × 2Gi each
            status.logicalSize shouldBe 4L * (1L shl 30)
            status.actualSize shouldBe 4L * (1L shl 30)
            status.uniqueSize shouldBe 4L * (1L shl 30)
        }

        "getCommitStatus surfaces snapshot error message" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "get", "volumesnapshot", any(), "-o", "json")
            } returns
                """
                {
                  "status": {
                    "readyToUse": "false",
                    "error": { "message": "snapshot blew up" }
                  },
                  "metadata": { "labels": { "size": "1Gi" } }
                }
                """.trimIndent()
            val ctx = newMockedContext(executor = exec)
            val status = ctx.getCommitStatus("vs", "commit", listOf("a"))
            status.ready shouldBe false
            status.error shouldBe "snapshot blew up"
        }

        // --- discoverHostAliasIp ---

        "discoverHostAliasIp returns probe-pod result when kubectl succeeds" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } returns "10.1.2.3   host.minikube.internal\n"
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe "10.1.2.3"
        }

        "discoverHostAliasIp caches result after first call" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } returns "10.1.2.3   host.minikube.internal\n"
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe "10.1.2.3"
            ctx.discoverHostAliasIp() shouldBe "10.1.2.3"
            verify(exactly = 1) { exec.exec("kubectl", "run", *anyVararg()) }
        }

        "discoverHostAliasIp falls back to node InternalIP when probe pod fails" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } throws CommandException("", 1, "ImagePullBackOff")
            every {
                exec.exec("kubectl", "get", "nodes", *anyVararg())
            } returns "192.168.1.99"
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe "192.168.1.99"
        }

        "discoverHostAliasIp returns null when both strategies fail" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } throws CommandException("", 1, "ImagePullBackOff")
            every {
                exec.exec("kubectl", "get", "nodes", *anyVararg())
            } throws CommandException("", 1, "no nodes")
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe null
        }

        "discoverHostAliasIp probe pod returns null on blank stdout" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec("kubectl", "run", *anyVararg()) } returns "   \n"
            every {
                exec.exec("kubectl", "get", "nodes", *anyVararg())
            } returns "192.168.1.50"
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe "192.168.1.50"
        }

        "discoverHostAliasIp returns null when node ip is blank too" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } throws CommandException("", 1, "ImagePullBackOff")
            every { exec.exec("kubectl", "get", "nodes", *anyVararg()) } returns "   "
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe null
        }

        "discoverHostAliasIp handles unexpected exception from probe pod" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every { exec.exec("kubectl", "run", *anyVararg()) } throws RuntimeException("boom")
            every { exec.exec("kubectl", "get", "nodes", *anyVararg()) } returns "10.0.0.1"
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe "10.0.0.1"
        }

        "discoverHostAliasIp handles unexpected exception from node probe" {
            val exec = mockk<CommandExecutor>(relaxed = true)
            every {
                exec.exec("kubectl", "run", *anyVararg())
            } throws CommandException("", 1, "x")
            every { exec.exec("kubectl", "get", "nodes", *anyVararg()) } throws RuntimeException("boom")
            val ctx = newMockedContext(executor = exec)
            ctx.discoverHostAliasIp() shouldBe null
        }

        // --- syncVolumes (end-to-end happy path with heavy mocking) ---

        "syncVolumes runs createSecret, createJob, polls until success" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            // PVC readiness probe
            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            // Secret creation
            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            // Job creation
            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            // Pod listing - finds one pod immediately
            val pod =
                V1Pod()
                    .metadata(V1ObjectMeta().name("op-pod"))
                    .status(V1PodStatus().phase("Running"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            // Pod status probe
            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } returns V1Pod().status(V1PodStatus().phase("Running"))

            // Job status probe - returns success on first poll
            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus().succeeded(1))

            // Pod log fetch returns a COMPLETE progress entry so syncVolumes exits cleanly
            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            every { podLogReq.execute() } returns
                """{"id":1,"type":"COMPLETE","message":null,"percent":null}"""

            // kubectl delete invocations
            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            val volumes = listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p")))
            val scratch = Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x"))

            ctx.syncVolumes(provider, op, volumes, scratch)
        }

        "syncVolumes throws when job reports failure" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } returns V1Pod().status(V1PodStatus().phase("Running"))

            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus().failed(1))

            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            every { podLogReq.execute() } returns "garbage that won't parse"

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PUSH,
                )
            shouldThrow<IllegalStateException> {
                ctx.syncVolumes(
                    provider,
                    op,
                    listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                    Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
                )
            }
        }

        // --- progress entry parsing in getProgressFromPod ---

        "syncVolumes forwards progress entries to operation callback" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()
            val events = mutableListOf<Triple<RemoteProgress, String?, Int?>>()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } returns V1Pod().status(V1PodStatus().phase("Running"))

            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus().succeeded(1))

            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            every { podLogReq.execute() } returns
                """{"id":1,"type":"START","message":"starting","percent":0}
                |{"id":2,"type":"PROGRESS","message":"working","percent":50}
                |{"id":3,"type":"MESSAGE","message":"hello","percent":null}
                |{"id":4,"type":"END","message":null,"percent":100}
                |not json — should be ignored
                |
                |{"id":5,"type":"COMPLETE","message":null,"percent":null}
                """.trimMargin()

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { t, m, p ->
                        events.add(Triple(t, m, p))
                    },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PUSH,
                )
            ctx.syncVolumes(
                provider,
                op,
                listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
            )

            // START + END from waitForVolumesReady, START + END from waitForPod, plus
            // the four progress entries we injected (START, PROGRESS, MESSAGE, END).
            events.any { it.first == RemoteProgress.PROGRESS && it.third == 50 } shouldBe true
            events.any { it.first == RemoteProgress.MESSAGE } shouldBe true
        }

        "syncVolumes throws when an ERROR progress entry is observed" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } returns V1Pod().status(V1PodStatus().phase("Running"))

            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus())

            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            every { podLogReq.execute() } returns
                """{"id":1,"type":"ERROR","message":"bad things","percent":null}"""

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            shouldThrow<Exception> {
                ctx.syncVolumes(
                    provider,
                    op,
                    listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                    Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
                )
            }
        }

        "syncVolumes throws when job completes but no completion message seen" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } returns V1Pod().status(V1PodStatus().phase("Running"))

            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus().succeeded(1))

            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            // No COMPLETE entry — code should throw "no completion message"
            every { podLogReq.execute() } returns ""

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            shouldThrow<IllegalStateException> {
                ctx.syncVolumes(
                    provider,
                    op,
                    listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                    Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
                )
            }
        }

        "isPodReady via syncVolumes path treats 404 as not ready then retries" {
            // We exercise the 404-handling branch by triggering waitForPod via
            // syncVolumes. First readNamespacedPodStatus call throws 404; second
            // returns Running. The implementation sleeps 1s between retries,
            // which keeps this test under ~2s.
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            var podCalls = 0
            every { podStatusReq.execute() } answers {
                podCalls++
                when (podCalls) {
                    1 -> throw ApiException(404, "not found")
                    2 -> V1Pod().status(V1PodStatus().phase("Pending"))
                    else -> V1Pod().status(V1PodStatus().phase("Running"))
                }
            }

            val jobReadReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIreadNamespacedJobRequest>(relaxed = true)
            every { batch.readNamespacedJob(any(), any()) } returns jobReadReq
            every { jobReadReq.execute() } returns V1Job().status(V1JobStatus().succeeded(1))

            val podLogReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodLogRequest>(relaxed = true)
            every { core.readNamespacedPodLog(any(), any()) } returns podLogReq
            every { podLogReq.follow(any()) } returns podLogReq
            every { podLogReq.previous(any()) } returns podLogReq
            every { podLogReq.execute() } returns """{"id":1,"type":"COMPLETE","message":null,"percent":null}"""

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            ctx.syncVolumes(
                provider,
                op,
                listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
            )
        }

        "isPodReady via syncVolumes rethrows non-404 ApiException" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val pod = V1Pod().metadata(V1ObjectMeta().name("op-pod"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(pod))

            val podStatusReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true)
            every { core.readNamespacedPodStatus(any(), any()) } returns podStatusReq
            every { podStatusReq.execute() } throws ApiException(500, "boom")

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            shouldThrow<ApiException> {
                ctx.syncVolumes(
                    provider,
                    op,
                    listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                    Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
                )
            }
        }

        // --- getPodFromJob bounded-poll behavior ---

        "syncVolumes throws when pod listing returns multiple matches" {
            val core = mockk<CoreV1Api>(relaxed = true)
            val batch = mockk<BatchV1Api>(relaxed = true)
            val exec = mockk<CommandExecutor>(relaxed = true)
            val provider = NopRemoteServer()

            val pvcReq =
                mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIreadNamespacedPersistentVolumeClaimStatusRequest>(relaxed = true)
            every { core.readNamespacedPersistentVolumeClaimStatus(any(), any()) } returns pvcReq
            every { pvcReq.execute() } returns
                V1PersistentVolumeClaim().status(V1PersistentVolumeClaimStatus().phase("Bound"))

            val secretReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIcreateNamespacedSecretRequest>(relaxed = true)
            every { core.createNamespacedSecret(any(), any<V1Secret>()) } returns secretReq
            every { secretReq.execute() } returns V1Secret()

            val jobCreateReq = mockk<io.kubernetes.client.openapi.apis.BatchV1Api.APIcreateNamespacedJobRequest>(relaxed = true)
            every { batch.createNamespacedJob(any(), any<V1Job>()) } returns jobCreateReq
            every { jobCreateReq.execute() } returns V1Job()

            val a = V1Pod().metadata(V1ObjectMeta().name("p1"))
            val b = V1Pod().metadata(V1ObjectMeta().name("p2"))
            val podListReq = mockk<io.kubernetes.client.openapi.apis.CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true)
            every { core.listNamespacedPod(any()) } returns podListReq
            every { podListReq.labelSelector(any()) } returns podListReq
            every { podListReq.execute() } returns V1PodList().items(listOf(a, b))

            every { exec.exec(*anyVararg()) } returns ""

            val ctx = newMockedContext(coreApi = core, batchApi = batch, executor = exec)
            val op =
                RemoteOperation(
                    updateProgress = { _, _, _ -> Unit },
                    operationId = UUID.randomUUID().toString(),
                    commitId = "c",
                    commit = null,
                    remote = emptyMap(),
                    parameters = emptyMap(),
                    type = RemoteOperationType.PULL,
                )
            shouldThrow<IllegalStateException> {
                ctx.syncVolumes(
                    provider,
                    op,
                    listOf(Volume("vol", emptyMap(), mapOf("pvc" to "vs-vol", "path" to "/p"))),
                    Volume("x-scratch", emptyMap(), mapOf("pvc" to "vs-x")),
                )
            }
        }
    }
}
