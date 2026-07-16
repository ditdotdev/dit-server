// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.context.kubernetes

import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.SnapshotClassInfo
import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.StorageClassInfo
import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.parseSnapshotClassList
import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.parseStorageClassList
import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.resolveSnapshotClass
import dev.dit.context.kubernetes.KubernetesCsiContext.Companion.resolveStorageClass
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

/**
 * CSI storage/snapshot class auto-selection (#217): a user who installs the
 * kubernetes context without -p storageClass on a cluster whose default
 * class is non-CSI (e.g. minikube's `standard`) used to get volumes that
 * could never be snapshotted. These specs pin the pure resolution logic.
 */
class KubernetesCsiContextStorageClassTest : StringSpec() {
    private val standard = StorageClassInfo("standard", "k8s.io/minikube-hostpath", isDefault = true)
    private val csiHostpath = StorageClassInfo("csi-hostpath-sc", "hostpath.csi.k8s.io", isDefault = false)
    private val csiOther = StorageClassInfo("aaa-other-csi", "ebs.csi.aws.com", isDefault = false)
    private val hostpathSnap = SnapshotClassInfo("csi-hostpath-snapclass", "hostpath.csi.k8s.io", isDefault = false)
    private val ebsSnap = SnapshotClassInfo("ebs-vsc", "ebs.csi.aws.com", isDefault = false)

    init {
        // ---- resolveStorageClass -----------------------------------------

        "explicit configuration always wins" {
            val r = resolveStorageClass("my-class", listOf(standard, csiHostpath), setOf("hostpath.csi.k8s.io"))
            r.className shouldBe "my-class"
            r.warning shouldBe null
        }

        "snapshot-capable cluster default is left alone (field omitted)" {
            val default = StorageClassInfo("gp3", "ebs.csi.aws.com", isDefault = true)
            val r = resolveStorageClass(null, listOf(default), setOf("ebs.csi.aws.com"))
            r.className shouldBe null
            r.warning shouldBe null
        }

        "non-CSI default with one capable class auto-selects it" {
            val r = resolveStorageClass(null, listOf(standard, csiHostpath), setOf("hostpath.csi.k8s.io"))
            r.className shouldBe "csi-hostpath-sc"
            r.warning shouldBe null
            r.reason.contains("auto-selected") shouldBe true
        }

        "multiple capable classes pick alphabetically and list candidates" {
            val r =
                resolveStorageClass(
                    null,
                    listOf(standard, csiHostpath, csiOther),
                    setOf("hostpath.csi.k8s.io", "ebs.csi.aws.com"),
                )
            r.className shouldBe "aaa-other-csi"
            r.reason.contains("candidates") shouldBe true
        }

        "no capable class falls back to default with an actionable warning" {
            val r = resolveStorageClass(null, listOf(standard), emptySet())
            r.className shouldBe null
            (r.warning ?: "").contains("dit commit") shouldBe true
            (r.warning ?: "").contains("-p storageClass") shouldBe true
        }

        "no default and no capable class still warns without crashing" {
            val nonDefault = StorageClassInfo("standard", "k8s.io/minikube-hostpath", isDefault = false)
            val r = resolveStorageClass(null, listOf(nonDefault), emptySet())
            r.className shouldBe null
            (r.warning != null) shouldBe true
        }

        // ---- resolveSnapshotClass ----------------------------------------

        "explicit snapshot class wins" {
            resolveSnapshotClass("mine", "hostpath.csi.k8s.io", listOf(hostpathSnap)) shouldBe "mine"
        }

        "unknown provisioner relies on cluster default" {
            resolveSnapshotClass(null, null, listOf(hostpathSnap)) shouldBe null
        }

        "matching default snapshot class means field is omitted" {
            val defaultSnap = SnapshotClassInfo("csi-hostpath-snapclass", "hostpath.csi.k8s.io", isDefault = true)
            resolveSnapshotClass(null, "hostpath.csi.k8s.io", listOf(defaultSnap)) shouldBe null
        }

        "non-matching default pairs the snapshot class by driver" {
            val wrongDefault = SnapshotClassInfo("other-default", "ebs.csi.aws.com", isDefault = true)
            resolveSnapshotClass(null, "hostpath.csi.k8s.io", listOf(wrongDefault, hostpathSnap)) shouldBe
                "csi-hostpath-snapclass"
        }

        "multiple matching snapshot classes pick alphabetically" {
            val a = SnapshotClassInfo("a-snap", "hostpath.csi.k8s.io", isDefault = false)
            val b = SnapshotClassInfo("b-snap", "hostpath.csi.k8s.io", isDefault = false)
            resolveSnapshotClass(null, "hostpath.csi.k8s.io", listOf(b, a)) shouldBe "a-snap"
        }

        "no matching snapshot class relies on cluster default" {
            resolveSnapshotClass(null, "hostpath.csi.k8s.io", listOf(ebsSnap)) shouldBe null
        }

        // ---- kubectl JSON parsing ----------------------------------------

        "parseStorageClassList reads name, provisioner and default annotation" {
            val json =
                """
                {"items":[
                  {"metadata":{"name":"standard","annotations":{"storageclass.kubernetes.io/is-default-class":"true"}},
                   "provisioner":"k8s.io/minikube-hostpath"},
                  {"metadata":{"name":"csi-hostpath-sc"},"provisioner":"hostpath.csi.k8s.io"}
                ]}
                """.trimIndent()
            val out = parseStorageClassList(json)
            out.size shouldBe 2
            out[0] shouldBe StorageClassInfo("standard", "k8s.io/minikube-hostpath", isDefault = true)
            out[1] shouldBe StorageClassInfo("csi-hostpath-sc", "hostpath.csi.k8s.io", isDefault = false)
        }

        "parseSnapshotClassList reads name, driver and default annotation" {
            val json =
                """
                {"items":[
                  {"metadata":{"name":"csi-hostpath-snapclass",
                               "annotations":{"snapshot.storage.kubernetes.io/is-default-class":"true"}},
                   "driver":"hostpath.csi.k8s.io"}
                ]}
                """.trimIndent()
            val out = parseSnapshotClassList(json)
            out.size shouldBe 1
            out[0] shouldBe SnapshotClassInfo("csi-hostpath-snapclass", "hostpath.csi.k8s.io", isDefault = true)
        }

        "parsers tolerate empty and malformed items" {
            parseStorageClassList("""{"items":[]}""").size shouldBe 0
            parseSnapshotClassList("""{"items":[{"metadata":{"name":"x"}}]}""").size shouldBe 0
        }
    }
}
