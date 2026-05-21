/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.context.docker.DockerZfsContext
import com.datadatdat.metadata.OperationData
import com.datadatdat.models.Commit
import com.datadatdat.models.Error
import com.datadatdat.models.Operation
import com.datadatdat.models.ProgressEntry
import com.datadatdat.models.Remote
import com.datadatdat.models.RemoteParameters
import com.datadatdat.models.Repository
import com.datadatdat.models.Volume
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.impl.annotations.SpyK
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.time.delay
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID

class OperationsApiTest : StringSpec() {
    @SpyK
    var context = DockerZfsContext(mapOf("pool" to "test"))

    lateinit var vs1: String
    lateinit var vs2: String

    @InjectMockKs
    @OverrideMockKs
    var services = ServiceLocator(mockk())

    val gson = GsonBuilder().create()

    override fun beforeSpec(spec: Spec) {
        services.metadata.init()
    }

    override fun beforeTest(testCase: TestCase) {
        services.operations.clearState()
        services.metadata.clear()
        transaction {
            services.metadata.createRepository(Repository(name = "foo", properties = mapOf()))
            services.metadata.addRemote("foo", Remote("nop", "remote"))
            vs1 = services.metadata.createVolumeSet("foo", null, true)
            vs2 = services.metadata.createVolumeSet("foo")
            services.metadata.createVolume(vs1, Volume("volume"))
        }
        MockKAnnotations.init(this)
        every { context.createVolumeSet(any()) } just Runs
        every { context.createVolume(any(), any()) } returns mapOf("mountpoint" to "/mountpoint")
        every { context.cloneVolume(any(), any(), any(), any(), any()) } returns mapOf("mountpoint" to "/mountpoint")
        every { context.activateVolume(any(), any(), any()) } just Runs
        every { context.deactivateVolume(any(), any(), any()) } just Runs
        every { context.deleteVolume(any(), any(), any()) } just Runs
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    fun loadOperation(data: OperationData) {
        transaction {
            services.metadata.createOperation("foo", data.operation.id, data)
        }
    }

    fun loadTestOperations() {
        loadOperation(
            OperationData(
                operation =
                    Operation(
                        id = vs1,
                        type = Operation.Type.PUSH,
                        state = Operation.State.RUNNING,
                        remote = "remote",
                        commitId = "commit1",
                    ),
                params = RemoteParameters("nop"),
                repo = "foo",
            ),
        )
        loadOperation(
            OperationData(
                operation =
                    Operation(
                        id = vs2,
                        type = Operation.Type.PULL,
                        state = Operation.State.RUNNING,
                        remote = "remote",
                        commitId = "commit2",
                    ),
                params = RemoteParameters("nop"),
                repo = "foo",
            ),
        )
    }

    init {
        "list empty operations succeeds" {
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "list operations succeeds" {
            loadTestOperations()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[{\"id\":\"$vs1\",\"type\":\"PUSH\"," +
                        "\"state\":\"RUNNING\",\"remote\":\"remote\",\"commitId\":\"commit1\"}," +
                        "{\"id\":\"$vs2\",\"type\":\"PULL\",\"state\":\"RUNNING\"," +
                        "\"remote\":\"remote\",\"commitId\":\"commit2\"}]"
                }
            }
        }

        "list operations by repo succeeds" {
            loadTestOperations()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations?repository=foo").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[{\"id\":\"$vs1\",\"type\":\"PUSH\"," +
                        "\"state\":\"RUNNING\",\"remote\":\"remote\",\"commitId\":\"commit1\"}," +
                        "{\"id\":\"$vs2\",\"type\":\"PULL\",\"state\":\"RUNNING\"," +
                        "\"remote\":\"remote\",\"commitId\":\"commit2\"}]"
                }
            }
        }

        "list operations by repo returns empty list" {
            loadTestOperations()
            transaction {
                services.metadata.createRepository(Repository(name = "bar", properties = mapOf()))
            }
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations?repository=bar").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "[]"
                }
            }
        }

        "get operation fails for non-existent operation" {
            val id = UUID.randomUUID().toString()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations/$id").apply {
                    status shouldBe HttpStatusCode.NotFound
                    val error = Gson().fromJson(bodyAsText(), Error::class.java)
                    error.code shouldBe "NoSuchObjectException"
                    error.message shouldBe "no such operation '$id'"
                }
            }
        }

        "get operation succeeds" {
            loadTestOperations()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations/$vs1").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType().toString() shouldBe "application/json"
                    bodyAsText() shouldBe "{\"id\":\"$vs1\",\"type\":\"PUSH\"," +
                        "\"state\":\"RUNNING\",\"remote\":\"remote\",\"commitId\":\"commit1\"}"
                }
            }
        }

        "abort in-progress operation results in aborted state" {
            // Pre-fix this test used loadState() to bring a RUNNING DB row
            // into runtime as an executor. After the operation-restart-
            // contract change (loadState marks RUNNING ops FAILED), that
            // path no longer puts an executor in runningOperations — the
            // abort would have nothing to cancel. Restructure to start a
            // real operation via POST /push, abort it via DELETE while
            // the nop provider's delay keeps it RUNNING, and assert
            // ABORTED.
            transaction {
                services.metadata.createCommit("foo", vs1, Commit("commit"))
            }
            testApplication {
                application { mainProvider(services) }
                // The nop provider's `delay` keeps the operation RUNNING
                // long enough for the DELETE to land before completion.
                val response =
                    client.post("/v1/repositories/foo/remotes/remote/commits/commit/push") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"properties\":{\"delay\":10}}")
                    }
                response.status shouldBe HttpStatusCode.Created
                val operation = gson.fromJson(response.bodyAsText(), Operation::class.java)

                client.delete("/v1/operations/${operation.id}").apply {
                    status shouldBe HttpStatusCode.NoContent
                }

                delay(Duration.ofMillis(500))

                client.get("/v1/operations/${operation.id}").apply {
                    status shouldBe HttpStatusCode.OK
                    val op = gson.fromJson(bodyAsText(), Operation::class.java)
                    op.state shouldBe Operation.State.ABORTED
                }
            }
        }

        "abort completed operation doesn't alter operation" {
            transaction {
                services.metadata.createCommit("foo", vs1, Commit("commit"))
            }
            loadOperation(
                OperationData(
                    Operation(
                        id = vs1,
                        type = Operation.Type.PUSH,
                        commitId = "commit",
                        state = Operation.State.COMPLETE,
                        remote = "remote",
                    ),
                    repo = "foo",
                    params = RemoteParameters("nop", mapOf("delay" to 10)),
                ),
            )
            services.operations.loadState()
            testApplication {
                application { mainProvider(services) }
                client.delete("/v1/operations/$vs1").apply {
                    status shouldBe HttpStatusCode.NoContent
                }

                client.get("/v1/operations/$vs1").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldBe "{\"id\":\"$vs1\",\"type\":\"PUSH\"," +
                        "\"state\":\"COMPLETE\",\"remote\":\"remote\",\"commitId\":\"commit\"}"
                }
            }
        }

        // loadState no longer resumes operations across a restart — instead
        // it marks any RUNNING operation FAILED with a documented progress
        // entry so the CLI can surface the abort to the user. See
        // OperationOrchestrator.loadState for the rationale.
        "loadState marks running operations failed with a progress entry" {
            transaction {
                services.metadata.createCommit("foo", vs1, Commit("commit"))
            }
            loadOperation(
                OperationData(
                    Operation(
                        id = vs2,
                        type = Operation.Type.PUSH,
                        commitId = "commit",
                        state = Operation.State.RUNNING,
                        remote = "remote",
                    ),
                    repo = "foo",
                    params = RemoteParameters("nop", mapOf("delay" to 10)),
                ),
            )
            services.operations.loadState()
            testApplication {
                application { mainProvider(services) }
                client.get("/v1/operations/$vs2").apply {
                    status shouldBe HttpStatusCode.OK
                    val op = gson.fromJson(bodyAsText(), Operation::class.java)
                    op.state shouldBe Operation.State.FAILED
                }
                client.get("/v1/operations/$vs2/progress").apply {
                    status shouldBe HttpStatusCode.OK
                    val entries: List<ProgressEntry> = gson.fromJson(bodyAsText(), object : TypeToken<List<ProgressEntry>>() { }.type)
                    entries.size shouldBe 1
                    entries[0].type shouldBe ProgressEntry.Type.FAILED
                    entries[0].message shouldBe "Server restarted; operation aborted. Retry the operation."
                }
            }
        }

        "push starts operation" {
            transaction {
                services.metadata.createCommit("foo", vs1, Commit("commit"))
            }

            testApplication {
                application { mainProvider(services) }
                val response =
                    client.post("/v1/repositories/foo/remotes/remote/commits/commit/push") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"properties\":{}}")
                    }
                response.status shouldBe HttpStatusCode.Created
                val operation = gson.fromJson(response.bodyAsText(), Operation::class.java)

                operation.commitId shouldBe "commit"
                operation.remote shouldBe "remote"
                operation.type shouldBe Operation.Type.PUSH
                client.get("/v1/operations/${operation.id}").apply {
                    status shouldBe HttpStatusCode.OK
                }
            }
        }

        "pull starts operation" {
            testApplication {
                application { mainProvider(services) }
                val response =
                    client.post("/v1/repositories/foo/remotes/remote/commits/commit/pull") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"provider\":\"nop\",\"properties\":{}}")
                    }
                response.status shouldBe HttpStatusCode.Created
                val operation = gson.fromJson(response.bodyAsText(), Operation::class.java)

                operation.commitId shouldBe "commit"
                operation.remote shouldBe "remote"
                operation.type shouldBe Operation.Type.PULL

                client.get("/v1/operations/${operation.id}").apply {
                    status shouldBe HttpStatusCode.OK
                }
            }
        }
    }
}
