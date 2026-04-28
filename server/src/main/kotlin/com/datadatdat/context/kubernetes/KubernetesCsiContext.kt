package com.datadatdat.context.kubernetes

import com.datadatdat.context.RuntimeContext
import com.datadatdat.models.CommitStatus
import com.datadatdat.models.ProgressEntry
import com.datadatdat.models.Volume
import com.datadatdat.models.VolumeStatus
import com.datadatdat.remote.RemoteOperation
import com.datadatdat.remote.RemoteProgress
import com.datadatdat.remote.RemoteServer
import com.datadatdat.shell.CommandException
import com.datadatdat.shell.CommandExecutor
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration.setDefaultApiClient
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.StorageV1Api
import io.kubernetes.client.openapi.models.V1ContainerBuilder
import io.kubernetes.client.openapi.models.V1EnvVarBuilder
import io.kubernetes.client.openapi.models.V1JobBuilder
import io.kubernetes.client.openapi.models.V1JobSpecBuilder
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1ObjectMetaBuilder
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimBuilder
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpecBuilder
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSourceBuilder
import io.kubernetes.client.openapi.models.V1PodSpecBuilder
import io.kubernetes.client.openapi.models.V1PodTemplateSpecBuilder
import io.kubernetes.client.openapi.models.V1SecretBuilder
import io.kubernetes.client.openapi.models.V1SecretVolumeSourceBuilder
import io.kubernetes.client.openapi.models.V1VolumeBuilder
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirementsBuilder
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.KubeConfig
import org.slf4j.LoggerFactory
import java.io.FileReader
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

/**
 * Kubernetes runtime context. In a k8s environment, we leverage CSI (Container Storage Interface) drivers to do
 * snapshot and cloning of data for us. Each volume is a PersistentVolumeClaim, while every commit is a
 * VolumeSnapshot.
 *
 * The context supports the following configuration:
 *
 *      configFile      Path, relative to ~/.kube, for the kubernetes configuration file. If not specified, then the
 *                      the default ("config") is used.
 *
 *      context         The context to use for all operations.
 *
 *      namespace       Cluster namespace, defaults to "default".
 *
 *      storageClass    Default volume storage class to use when creating volumes. If unspecified, then the
 *                      cluster default is used.
 *
 *      snapshotClass   Default snapshot class to use when createing snapshots. If unspecified, then the cluster
 *                      default is used.
 *
 *      datadatdatImage      Datadatdat image to use when running operations. This should be the same image as the current
 *                      datadatdat server, and defaults to "datadatdat:latest". It must be accessible from within the
 *                      kubernetes cluster.
 */
class KubernetesCsiContext(
    private val properties: Map<String, String> = emptyMap(),
) : RuntimeContext {
    private val coreApi: CoreV1Api
    private val storageApi: StorageV1Api
    private val batchApi: BatchV1Api
    val namespace: String
    internal val executor = CommandExecutor()
    private val gson = GsonBuilder().create()

    companion object {
        val log = LoggerFactory.getLogger(KubernetesCsiContext::class.java)
    }

    init {
        val home = System.getProperty("user.home")
        val config =
            if (properties.containsKey("config")) {
                KubeConfig.loadKubeConfig(FileReader("$home/.kube/${properties["config"]}"))
            } else {
                KubeConfig.loadKubeConfig(FileReader("$home/.kube/config"))
            }
        if (properties.containsKey("context")) {
            config.setContext(properties["context"])
        }

        setDefaultApiClient(Config.fromConfig(config))
        coreApi = CoreV1Api()
        storageApi = StorageV1Api()
        batchApi = BatchV1Api()
        namespace = properties.get("namespace") ?: "default"
    }

    private fun deleteObject(
        type: String,
        name: String,
    ) {
        // Java client has issues with deletion (https://github.com/kubernetes-client/java/issues/86) so use kubectl
        try {
            executor.exec("kubectl", "delete", "--wait=false", type, name)
        } catch (e: CommandException) {
            if (!e.output.contains("NotFound")) {
                throw e
            }
        }
    }

    /**
     * Write YAML to a temp file and `kubectl apply -f` it. On apply failure log the kubectl stderr
     * AND the rejected YAML at ERROR level — the bare `Exit 1: kubectl, apply, ...` line that
     * CommandExecutor logs by default tells us nothing about why kubectl rejected the manifest.
     * Surfaced by datadatdat#639 where d3 push returned 500 from inside the server but the only
     * server-side breadcrumb was an opaque "Exit 1: kubectl, apply" line.
     *
     * Always rethrows the original CommandException so callers (and the StatusPages handler)
     * still see the failure — this is purely diagnostic, not a behavior change.
     */
    private fun applyYaml(
        yaml: String,
        label: String,
    ) {
        val file = createTempFile().toFile()
        try {
            file.writeText(yaml)
            try {
                executor.exec("kubectl", "apply", "-f", file.path)
            } catch (e: CommandException) {
                log.error("kubectl apply failed for $label: ${e.output}\n--- rejected YAML ---\n$yaml--- end YAML ---")
                throw e
            }
        } finally {
            file.delete()
        }
    }

    override fun getProvider(): String = "kubernetes-csi"

    override fun getProperties(): Map<String, String> = properties

    override fun createVolumeSet(volumeSet: String) {
        // Nothing to do
    }

    override fun cloneVolumeSet(
        sourceVolumeSet: String,
        sourceCommit: String,
        newVolumeSet: String,
    ) {
        // Nothing to do
    }

    override fun deleteVolumeSet(volumeSet: String) {
        // Nothing to do
    }

    override fun commitVolumeSet(
        volumeSet: String,
        commitId: String,
    ) {
        // Nothing to do
    }

    override fun deleteVolumeSetCommit(
        volumeSet: String,
        commitId: String,
    ) {
        // Nothing to do
    }

    override fun createVolume(
        volumeSet: String,
        volumeName: String,
    ): Map<String, Any> {
        val name = "$volumeSet-$volumeName"
        val size = "1Gi"

        val request =
            V1PersistentVolumeClaimBuilder()
                .withMetadata(
                    V1ObjectMetaBuilder()
                        .withName(name)
                        .addToLabels("datadatdatVolume", volumeName)
                        .build(),
                ).withSpec(
                    V1PersistentVolumeClaimSpecBuilder()
                        .withAccessModes("ReadWriteOnce")
                        .withResources(
                            V1VolumeResourceRequirementsBuilder()
                                .addToRequests("storage", Quantity.fromString(size))
                                .build(),
                        ).build(),
                ).build()
        if (properties["storageClass"] != null) {
            request.spec!!.storageClassName = properties["storageClass"]
        }
        val claim = coreApi.createNamespacedPersistentVolumeClaim(namespace, request).execute()
        log.info("Created PersistentVolumeClaim '$name', status = ${claim.status?.phase}")
        return mapOf(
            "pvc" to name,
            "namespace" to namespace,
            "size" to size,
        )
    }

    override fun deleteVolume(
        volumeSet: String,
        volumeName: String,
        config: Map<String, Any>,
    ) {
        val pvc = config["pvc"] as? String ?: throw IllegalStateException("missing or invalid pvc name in volume config")
        deleteObject("pvc", pvc)
        log.info("Deleted PersistentVolumeClaim '$pvc'")
    }

    /**
     * VolumeSnapshot is GA on snapshot.storage.k8s.io/v1; the alpha API this code originally targeted has
     * been removed from upstream Kubernetes for years. We still apply via `kubectl` instead of using the
     * typed client-java VolumeSnapshot APIs to keep the snapshot/clone/delete codepath uniform — see
     * `cloneVolume` and `getSnapshot` below. Switching all of those to the typed API is a separate
     * refactor (kubernetes-client/java#86 — the historical reason for shelling out — has long been fixed).
     *
     * v1alpha1 → v1 field shape changes captured here:
     *   - spec.source.{kind, name}        →  spec.source.persistentVolumeClaimName
     *   - spec.snapshotClassName          →  spec.volumeSnapshotClassName
     */
    override fun commitVolume(
        volumeSet: String,
        commitId: String,
        volumeName: String,
        config: Map<String, Any>,
    ) {
        val pvc = config["pvc"] as? String ?: throw IllegalStateException("missing or invalid pvc name in volume config")
        val size = config["size"] as? String ?: throw IllegalStateException("missing or invalid size in volume config")
        val name = "$pvc-$commitId"

        val yaml =
            "apiVersion: snapshot.storage.k8s.io/v1\n" +
                "kind: VolumeSnapshot\n" +
                "metadata:\n" +
                "  name: $name\n" +
                "  labels:\n" +
                "    datadatdatVolume: $volumeName\n" +
                "    datadatdatCommit: $commitId\n" +
                "    datadatdatSize: $size\n" +
                "spec:\n" +
                "  source:\n" +
                "    persistentVolumeClaimName: $pvc\n" +
                if (properties["snapshotClass"] != null) {
                    "  volumeSnapshotClassName: ${properties["snapshotClass"]}\n"
                } else {
                    ""
                }
        applyYaml(yaml, "VolumeSnapshot/$name")
    }

    override fun deleteVolumeCommit(
        volumeSet: String,
        commitId: String,
        volumeName: String,
    ) {
        deleteObject("volumesnapshot", "$volumeSet-$volumeName-$commitId")
    }

    override fun cloneVolume(
        sourceVolumeSet: String,
        sourceCommit: String,
        newVolumeSet: String,
        volumeName: String,
        sourceConfig: Map<String, Any>,
    ): Map<String, Any> {
        val size = sourceConfig["size"] as? String ?: throw IllegalStateException("missing or invalid size in volume config")
        val name = "$newVolumeSet-$volumeName"
        val snapshotName = "$sourceVolumeSet-$volumeName-$sourceCommit"

        val yaml =
            "apiVersion: v1\n" +
                "kind: PersistentVolumeClaim\n" +
                "metadata:\n" +
                "  name: $name\n" +
                "  labels:\n" +
                "    datadatdatVolume: $volumeName\n" +
                "spec:\n" +
                "  dataSource:\n" +
                "    kind: VolumeSnapshot\n" +
                "    apiGroup: snapshot.storage.k8s.io\n" +
                "    name: $snapshotName\n" +
                "  accessModes:\n" +
                "    - ReadWriteOnce\n" +
                "  resources:\n" +
                "    requests:\n" +
                "      storage: $size\n"
        applyYaml(yaml, "PersistentVolumeClaim/$name (cloned from VolumeSnapshot/$snapshotName)")

        return mapOf(
            "pvc" to name,
            "namespace" to namespace,
            "size" to size,
        )
    }

    fun getPvcStatus(pvc: String): Pair<Boolean, String?> {
        val claim = coreApi.readNamespacedPersistentVolumeClaimStatus(pvc, namespace).execute()

        val okPhases = listOf("Pending", "Bound")
        var readyPhases = listOf("Pending", "Bound")
        if (claim.spec?.storageClassName != null) {
            val storageClass = storageApi.readStorageClass(claim.spec?.storageClassName!!).execute()
            if (storageClass.volumeBindingMode == "Immediate") {
                readyPhases = listOf("Bound")
            }
        }

        val ready = claim.status?.phase in readyPhases
        val error =
            if (claim.status?.phase in okPhases) {
                null
            } else {
                "volume '$pvc' is in unknown state ${claim.status?.phase}"
            }

        return ready to error
    }

    override fun getVolumeStatus(
        volumeSet: String,
        volume: String,
        config: Map<String, Any>,
    ): VolumeStatus {
        val pvc = config["pvc"] as? String ?: error("missing or invalid pvc name in volume config")
        val (ready, error) = getPvcStatus(pvc)

        // Volumes can change size, this is just the original size
        val size = Quantity(config["size"] as String).number.toLong()
        return VolumeStatus(
            name = volume,
            logicalSize = size,
            actualSize = size,
            ready = ready,
            error = error,
        )
    }

    private fun getSnapshot(name: String): JsonObject? {
        val output = executor.exec("kubectl", "get", "volumesnapshot", name, "-o", "json")
        val json = JsonParser.parseString(output)
        return json.asJsonObject
    }

    override fun getCommitStatus(
        volumeSet: String,
        commitId: String,
        volumeNames: List<String>,
    ): CommitStatus {
        var size = 0L
        var ready = true
        var error: String? = null
        for (v in volumeNames) {
            val snapshotName = "$volumeSet-$v-$commitId"
            val snapshot = getSnapshot(snapshotName)
            val status = snapshot?.getAsJsonObject("status")

            if (status == null) {
                ready = false
            } else if (status.get("readyToUse") != null) {
                if (status.get("readyToUse").asString != "true") {
                    ready = false
                }
                if (error == null) {
                    error = status.getAsJsonObject("error")?.get("message")?.asString
                }
            }

            val sizeLabel =
                snapshot
                    ?.getAsJsonObject("metadata")
                    ?.getAsJsonObject("labels")
                    ?.getAsJsonPrimitive("size")
                    ?.asString
            if (sizeLabel != null) {
                size += Quantity.fromString(sizeLabel).number.toLong()
            }
        }

        return CommitStatus(
            logicalSize = size,
            actualSize = size,
            uniqueSize = size,
            ready = ready,
            error = error,
        )
    }

    override fun activateVolume(
        volumeSet: String,
        volumeName: String,
        config: Map<String, Any>,
    ) {
        // Nothing to do
    }

    override fun deactivateVolume(
        volumeSet: String,
        volumeName: String,
        config: Map<String, Any>,
    ) {
        // Nothing to do
    }

    /**
     * Wait for all the volumes associated with this operation to become ready.
     */
    private fun waitForVolumesReady(
        volumes: List<Volume>,
        scratchVolume: Volume,
    ) {
        while (true) {
            val allVolumes = volumes + scratchVolume
            var ready = true
            for (vol in allVolumes) {
                val pvc = vol.config["pvc"] as? String ?: error("missing or invalid pvc name in volume '${vol.name}' config")
                val (volumeReady) = getPvcStatus(pvc)
                if (!volumeReady) {
                    ready = false
                }
            }
            if (ready) {
                break
            }
            Thread.sleep(1000)
        }
    }

    /**
     * Create the secret containing the KubernetesOperation data required for the runner.
     */
    private fun createSecret(
        provider: RemoteServer,
        operation: RemoteOperation,
        volumes: List<Volume>,
        scratchVolume: Volume,
        metadata: V1ObjectMeta,
    ) {
        // Create the KubernetesObperation object that will be passed as a secret
        val kubeOperation =
            KubernetesOperation(
                commit = operation.commit,
                commitId = operation.commitId,
                operationId = operation.operationId,
                remote = operation.remote,
                parameters = operation.parameters,
                remoteType = provider.getProvider(),
                type = operation.type,
                scratchVolume = scratchVolume.name,
                volumes = volumes.map { it.name },
                volumeDescriptions = volumes.map { it.properties["path"] as? String ?: it.name },
            )
        val configJson = gson.toJson(kubeOperation)
        log.info("creating secret ${metadata.name}")
        coreApi
            .createNamespacedSecret(
                namespace,
                V1SecretBuilder()
                    .withMetadata(metadata)
                    .withType("Opaque")
                    .addToStringData("config", configJson)
                    .build(),
            ).execute()
    }

    /**
     * Create the runner job.
     */
    private fun createJob(
        volumes: List<Volume>,
        scratchVolume: Volume,
        metadata: V1ObjectMeta,
    ) {
        val image = properties["datadatdatImage"] ?: error("missing datadatdatImage property")
        val basePath = "/var/datadatdat"

        log.info("creating job ${metadata.name}")
        // Now create the job that will run the operation
        batchApi
            .createNamespacedJob(
                namespace,
                V1JobBuilder()
                    .withMetadata(metadata)
                    .withSpec(
                        V1JobSpecBuilder()
                            .withTemplate(
                                V1PodTemplateSpecBuilder()
                                    .withMetadata(metadata)
                                    .withSpec(
                                        V1PodSpecBuilder()
                                            .withRestartPolicy("OnFailure")
                                            .withContainers(
                                                V1ContainerBuilder()
                                                    .withName("operation")
                                                    .withImage(image)
                                                    .withCommand("/datadatdat/kubernetesOperation")
                                                    .withVolumeMounts(
                                                        V1VolumeMountBuilder()
                                                            .withName("x-secret")
                                                            .withReadOnly(true)
                                                            .withMountPath("$basePath/x-secret")
                                                            .build(),
                                                        V1VolumeMountBuilder()
                                                            .withName(scratchVolume.name)
                                                            .withMountPath("$basePath/${scratchVolume.name}")
                                                            .build(),
                                                        *volumes
                                                            .map {
                                                                V1VolumeMountBuilder()
                                                                    .withName(it.name)
                                                                    .withMountPath("$basePath/${it.name}")
                                                                    .build()
                                                            }.toTypedArray(),
                                                    ).withEnv(
                                                        V1EnvVarBuilder()
                                                            .withName("DATADATDAT_PATH")
                                                            .withValue(basePath)
                                                            .build(),
                                                    ).build(),
                                            ).withVolumes(
                                                V1VolumeBuilder()
                                                    .withName("x-secret")
                                                    .withSecret(
                                                        V1SecretVolumeSourceBuilder()
                                                            .withSecretName(metadata.name)
                                                            .build(),
                                                    ).build(),
                                                V1VolumeBuilder()
                                                    .withName(scratchVolume.name)
                                                    .withPersistentVolumeClaim(
                                                        V1PersistentVolumeClaimVolumeSourceBuilder()
                                                            .withClaimName(scratchVolume.config["pvc"] as String)
                                                            .build(),
                                                    ).build(),
                                                *volumes
                                                    .map {
                                                        V1VolumeBuilder()
                                                            .withName(it.name)
                                                            .withPersistentVolumeClaim(
                                                                V1PersistentVolumeClaimVolumeSourceBuilder()
                                                                    .withClaimName(it.config["pvc"] as String)
                                                                    .build(),
                                                            ).build()
                                                    }.toTypedArray(),
                                            ).build(),
                                    ).build(),
                            ).withBackoffLimit(1)
                            .build(),
                    ).build(),
            ).execute()
    }

    private fun getPodFromJob(name: String): String {
        val pods = coreApi.listNamespacedPod(namespace).labelSelector("job-name=$name").execute()
        if (pods.items.size != 1) {
            throw IllegalStateException("found ${pods.items.size} pods instead of 1 for job $name")
        }
        return pods.items[0].metadata?.name ?: error("pod does not have an associated name")
    }

    private fun isPodReady(name: String): Boolean {
        try {
            val status = coreApi.readNamespacedPodStatus(name, namespace).execute()
            if (status.status?.phase == "Pending") {
                return false
            }
        } catch (e: ApiException) {
            if (e.code == 404) {
                return false
            }
            throw e
        }
        return true
    }

    private fun waitForPod(jobName: String) {
        val podName = getPodFromJob(jobName)
        while (true) {
            if (isPodReady(podName)) {
                break
            }
            Thread.sleep(1000)
        }
    }

    /**
     * Get progress entries from the output of the given pod. This will always fetch just the last three seconds of
     * logs (since we expect to call it once a second), and ignore entries that have been seen before.
     */
    private fun getProgressFromPod(
        name: String,
        lastIdSeen: Int,
    ): List<ProgressEntry> {
        if (!isPodReady(name)) {
            return emptyList()
        }

        val output =
            coreApi
                .readNamespacedPodLog(name, namespace)
                .follow(false)
                .previous(false)
                .execute()
                ?: return emptyList()

        val ret = mutableListOf<ProgressEntry>()
        for (line in output.lines()) {
            if (line.trim() == "") {
                continue
            }
            try {
                val progress = gson.fromJson(line, ProgressEntry::class.java)
                if (progress.id > lastIdSeen) {
                    ret.add(progress)
                }
            } catch (e: JsonParseException) {
                // Ignore output we don't understand, such as a stack trace
            }
        }
        return ret
    }

    /**
     * Within kubernetes, we need to run the operations in a separate pod in order be able to access the data
     * volumes. We run this as a Kubernetes Job
     */
    override fun syncVolumes(
        provider: RemoteServer,
        operation: RemoteOperation,
        volumes: List<Volume>,
        scratchVolume: Volume,
    ) {
        val name = "datadatdat-operation-${operation.operationId}"
        val metadata =
            V1ObjectMetaBuilder()
                .withName(name)
                .build()

        operation.updateProgress(RemoteProgress.START, "Waiting for volumes to be ready", null)
        waitForVolumesReady(volumes, scratchVolume)
        operation.updateProgress(RemoteProgress.END, null, null)

        createSecret(provider, operation, volumes, scratchVolume, metadata)

        operation.updateProgress(RemoteProgress.START, "Starting job", null)
        createJob(volumes, scratchVolume, metadata)
        waitForPod(name)
        operation.updateProgress(RemoteProgress.END, null, null)

        try {
            try {
                val podName = getPodFromJob(name)
                var jobComplete = false
                var progressComplete = false
                var lastId = 0
                while (!jobComplete) {
                    val job = batchApi.readNamespacedJob(name, namespace).execute()
                    if (job.status?.succeeded == 1) {
                        jobComplete = true
                    }
                    if (job.status?.failed == 1) {
                        try {
                            // Try to log what we can
                            val output =
                                coreApi.readNamespacedPodLog(podName, namespace).execute()
                            if (output != null) {
                                log.error(output)
                            }
                        } catch (t: Throwable) {
                            // Ignore
                        }
                        throw IllegalStateException("job failed unexpectedly")
                    }

                    val entries = getProgressFromPod(podName, lastId)
                    for (progress in entries) {
                        lastId = progress.id
                        if (progress.type == ProgressEntry.Type.COMPLETE) {
                            // This is not a real completion but a way of signalling the job finished successfully
                            progressComplete = true
                        } else if (progress.type == ProgressEntry.Type.ERROR) {
                            throw Exception(progress.message)
                        } else {
                            val progressType =
                                when (progress.type) {
                                    ProgressEntry.Type.START -> RemoteProgress.START
                                    ProgressEntry.Type.END -> RemoteProgress.END
                                    ProgressEntry.Type.PROGRESS -> RemoteProgress.PROGRESS
                                    ProgressEntry.Type.MESSAGE -> RemoteProgress.MESSAGE
                                    else -> error("invalid progress type ${progress.type}")
                                }
                            operation.updateProgress(progressType, progress.message, progress.percent)
                        }
                    }

                    if (!jobComplete) {
                        Thread.sleep(1000)
                    } else if (!progressComplete) {
                        throw IllegalStateException("job completed but no completion message received")
                    }
                }
            } finally {
                deleteObject("job", name)
            }
        } finally {
            deleteObject("secret", name)
        }
    }
}
