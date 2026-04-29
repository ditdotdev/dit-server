@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.datadatdat.metadata

import com.datadatdat.exception.NoSuchObjectException
import com.datadatdat.exception.ObjectExistsException
import com.datadatdat.metadata.table.Commits
import com.datadatdat.metadata.table.Operations
import com.datadatdat.metadata.table.ProgressEntries
import com.datadatdat.metadata.table.Remotes
import com.datadatdat.metadata.table.Repositories
import com.datadatdat.metadata.table.Tags
import com.datadatdat.metadata.table.VolumeSets
import com.datadatdat.metadata.table.Volumes
import com.datadatdat.models.Commit
import com.datadatdat.models.Operation
import com.datadatdat.models.ProgressEntry
import com.datadatdat.models.Remote
import com.datadatdat.models.RemoteParameters
import com.datadatdat.models.Repository
import com.datadatdat.models.Volume
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import kotlin.uuid.Uuid

/*
 * The metadata provider is responsible for persistence of all metadata to the datadatdat database. With the exception of
 * init(), it's up to the caller to manage transactions.
 */
class MetadataProvider(
    val inMemory: Boolean = true,
    val databaseName: String = "datadatdat",
) {
    enum class VolumeState {
        INACTIVE,
        ACTIVE,
        DELETING,
    }

    private val gson = GsonBuilder().create()

    private fun memoryConfig(): HikariDataSource {
        val config = HikariConfig()
        config.driverClassName = "org.h2.Driver"
        config.jdbcUrl = "jdbc:h2:mem:$databaseName"
        config.maximumPoolSize = 3
        config.isAutoCommit = false
        config.transactionIsolation = "TRANSACTION_READ_COMMITTED"
        config.validate()
        return HikariDataSource(config)
    }

    private fun persistentConfig(): HikariDataSource {
        val config = HikariConfig()
        config.driverClassName = "org.postgresql.Driver"
        config.jdbcUrl = "jdbc:postgresql:$databaseName"
        config.username = "postgres"
        config.password = "postgres"
        config.maximumPoolSize = 3
        config.isAutoCommit = false
        config.transactionIsolation = "TRANSACTION_READ_COMMITTED"
        config.validate()
        return HikariDataSource(config)
    }

    fun init() {
        if (inMemory) {
            Database.connect(memoryConfig())
        } else {
            Database.connect(persistentConfig())
        }

        transaction {
            // SchemaUtils.create generates CREATE TABLE IF NOT EXISTS — idempotent
            // and safe to call on every startup. Replaces the deprecated
            // createMissingTablesAndColumns, which Exposed flagged because partial
            // failure can leave the schema half-applied. We don't rely on its
            // missing-column behavior: real schema evolution should land via a
            // migration tool, not as a silent ALTER on startup.
            SchemaUtils.create(
                Repositories,
                Remotes,
                VolumeSets,
                Volumes,
                Commits,
                Tags,
                Operations,
                ProgressEntries,
            )
        }
    }

    fun clear() {
        transaction {
            Operations.deleteAll()
            Commits.deleteAll()
            Volumes.deleteAll()
            VolumeSets.deleteAll()
            Repositories.deleteAll()
        }
    }

    private fun convertRepository(it: ResultRow): Repository {
        val name = it[Repositories.name]
        val metadata = it[Repositories.metadata]
        println("DEBUG convertRepository: name=$name, metadata='$metadata'")
        val properties: Map<String, Any> =
            // Repositories.metadata is non-nullable in the schema, so only the
            // literal-string "null" path is reachable (Gson serializes a null
            // reference into the four-character string).
            if (metadata == "null") {
                println("DEBUG convertRepository: metadata is 'null', using empty map")
                emptyMap()
            } else {
                try {
                    gson.fromJson(metadata, object : TypeToken<Map<String, Any>>() {}.type) as? Map<String, Any> ?: emptyMap()
                } catch (e: Exception) {
                    println("DEBUG convertRepository: failed to parse metadata '$metadata', using empty map: ${e.message}")
                    emptyMap()
                }
            }
        println("DEBUG convertRepository: parsed_properties=$properties")
        return Repository(name = name, properties = properties)
    }

    fun createRepository(repo: Repository) {
        // Repository.properties is non-nullable per the model; no Elvis fallback needed.
        val serialized = gson.toJson(repo.properties)
        println(
            "DEBUG createRepository: name=${repo.name}, properties=${repo.properties}, serialized='$serialized'",
        )
        try {
            Repositories.insert {
                it[name] = repo.name
                it[metadata] = serialized
            }
        } catch (e: ExposedSQLException) {
            throw ObjectExistsException("repository '${repo.name}' already exists")
        }
    }

    fun listRepositories(): List<Repository> = Repositories.selectAll().map { convertRepository(it) }

    fun getRepository(repoName: String): Repository =
        Repositories
            .selectAll()
            .where {
                Repositories.name eq repoName
            }.map { convertRepository(it) }
            .firstOrNull()
            ?: throw NoSuchObjectException("no such repository '$repoName'")

    fun updateRepository(
        repoName: String,
        repo: Repository,
    ) {
        try {
            val count =
                Repositories.update({ Repositories.name eq repoName }) {
                    it[name] = repo.name
                    it[metadata] = gson.toJson(repo.properties)
                }
            if (count == 0) {
                throw NoSuchObjectException("no such repository '$repoName'")
            }
        } catch (e: ExposedSQLException) {
            throw ObjectExistsException("repository '${repo.name}' already exists")
        }
    }

    fun deleteRepository(repoName: String) {
        val count =
            Repositories.deleteWhere {
                Repositories.name eq repoName
            }
        if (count == 0) {
            throw NoSuchObjectException("no such repository '$repoName'")
        }
    }

    private fun convertRemote(it: ResultRow): Remote = gson.fromJson(it[Remotes.metadata], Remote::class.java)

    fun addRemote(
        repoName: String,
        remote: Remote,
    ) {
        try {
            Remotes.insert {
                it[name] = remote.name
                it[repo] = repoName
                it[metadata] = gson.toJson(remote)
            }
        } catch (e: ExposedSQLException) {
            throw ObjectExistsException("remote '${remote.name}' already exists in repository $repoName")
        }
    }

    fun getRemote(
        repoName: String,
        remoteName: String,
    ): Remote =
        Remotes
            .selectAll()
            .where {
                (Remotes.name eq remoteName) and (Remotes.repo eq repoName)
            }.map { convertRemote(it) }
            .firstOrNull()
            ?: throw NoSuchObjectException("no such remote '$remoteName' in repository '$repoName'")

    fun listRemotes(repoName: String): List<Remote> =
        Remotes
            .selectAll()
            .where {
                Remotes.repo eq repoName
            }.map { convertRemote(it) }

    fun removeRemote(
        repoName: String,
        remoteName: String,
    ) {
        val count =
            Remotes.deleteWhere {
                (Remotes.name eq remoteName) and (Remotes.repo eq repoName)
            }
        if (count == 0) {
            throw NoSuchObjectException("no such remote '$remoteName' in repository '$repoName'")
        }
    }

    fun updateRemote(
        repoName: String,
        remoteName: String,
        remote: Remote,
    ) {
        try {
            val count =
                Remotes.update({
                    (Remotes.name eq remoteName) and (Remotes.repo eq repoName)
                }) {
                    it[name] = remote.name
                    it[metadata] = gson.toJson(remote)
                }
            if (count == 0) {
                throw NoSuchObjectException("no such remote '$remoteName' in repository '$repoName'")
            }
        } catch (e: ExposedSQLException) {
            throw ObjectExistsException("remote '${remote.name}' already exists in repository '$repoName'")
        }
    }

    fun createVolumeSet(
        repoName: String,
        sourceCommit: String? = null,
        activate: Boolean = false,
    ): String {
        val sourceId =
            if (sourceCommit == null) {
                null
            } else {
                Commits
                    .selectAll()
                    .where {
                        (Commits.repo eq repoName) and (Commits.guid eq sourceCommit) and (Commits.state eq VolumeState.ACTIVE)
                    }.map {
                        it[Commits.id].value
                    }.firstOrNull()
            }

        val id =
            VolumeSets.insert {
                it[repo] = repoName
                it[VolumeSets.sourceCommit] = sourceCommit
                it[VolumeSets.sourceId] = sourceId
                it[state] =
                    if (activate) {
                        VolumeState.ACTIVE
                    } else {
                        VolumeState.INACTIVE
                    }
            } get VolumeSets.id
        return id.toString()
    }

    /**
     * Returns the id of the active volume set for the repository, or null
     * when none is currently active. Use this from code paths where the
     * absence of an active volume set is a normal state (e.g. status
     * lookups for a freshly-created repository, or right after the only
     * volume set has been marked deleting).
     */
    fun getActiveVolumeSetOrNull(repoName: String): String? =
        VolumeSets
            .selectAll()
            .where {
                (VolumeSets.repo eq repoName) and (VolumeSets.state eq VolumeState.ACTIVE)
            }.map { it[VolumeSets.id].toString() }
            .firstOrNull()

    /**
     * Returns the id of the active volume set for the repository.
     *
     * Throws [NoSuchObjectException] when no active volume set exists.
     * Pre-issue-#137 this method dereferenced a null with `firstOrNull()!!`
     * and threw an unhelpful NullPointerException — which propagated out
     * through the HTTP layer as a 500 and broke the connection mid-request,
     * causing the CLI's next POST to receive EOF and crash `d3 run` on a
     * fresh kubernetes context.
     */
    fun getActiveVolumeSet(repoName: String): String =
        getActiveVolumeSetOrNull(repoName)
            ?: throw NoSuchObjectException("repository '$repoName' has no active volume set")

    fun getVolumeSetRepo(volumeSet: String): String? {
        val uuid = Uuid.parse(volumeSet)
        return VolumeSets
            .selectAll()
            .where {
                VolumeSets.id eq uuid
            }.map { it[VolumeSets.repo] }
            .firstOrNull()
    }

    fun activateVolumeSet(
        repoName: String,
        volumeSet: String,
    ) {
        VolumeSets.update({
            (VolumeSets.repo eq repoName) and (VolumeSets.state eq VolumeState.ACTIVE)
        }) {
            it[state] = VolumeState.INACTIVE
        }
        val count =
            VolumeSets.update({
                (VolumeSets.repo eq repoName) and (VolumeSets.id eq Uuid.parse(volumeSet))
            }) {
                it[state] = VolumeState.ACTIVE
            }
        if (count == 0) {
            // This should never happen, not a user-visible exception
            throw IllegalArgumentException("no such volume set '$volumeSet' in repository '$repoName'")
        }
    }

    fun markVolumeSetDeleting(volumeSet: String) {
        val uuid = Uuid.parse(volumeSet)
        VolumeSets.update({
            VolumeSets.id eq uuid
        }) {
            it[state] = VolumeState.DELETING
        }
        // Mark all commits in volumeset deleting
        Commits.update({
            Commits.volumeSet eq uuid
        }) {
            it[state] = VolumeState.DELETING
        }
    }

    fun isVolumeSetEmpty(volumeSet: String): Boolean {
        val uuid = Uuid.parse(volumeSet)
        return Commits
            .selectAll()
            .where {
                Commits.volumeSet eq uuid
            }.count() == 0L
    }

    fun listInactiveVolumeSets(): List<String> =
        VolumeSets
            .selectAll()
            .where {
                VolumeSets.state eq VolumeState.INACTIVE
            }.map { it[VolumeSets.id].toString() }

    fun markAllVolumeSetsDeleting(repo: String) {
        var volumeSets =
            VolumeSets
                .selectAll()
                .where {
                    VolumeSets.repo eq repo
                }.map { it[VolumeSets.id] }
        for (vs in volumeSets) {
            markVolumeSetDeleting(vs.toString())
        }
    }

    fun deleteVolumeSet(volumeSet: String) {
        VolumeSets.deleteWhere {
            VolumeSets.id eq Uuid.parse(volumeSet)
        }
    }

    fun listDeletingVolumeSets(): List<String> =
        VolumeSets
            .selectAll()
            .where {
                VolumeSets.state eq VolumeState.DELETING
            }.map { it[VolumeSets.id].toString() }

    private fun convertVolume(it: ResultRow) =
        Volume(
            name = it[Volumes.name],
            properties = gson.fromJson(it[Volumes.metadata], object : TypeToken<Map<String, Any>>() {}.type),
            config = gson.fromJson(it[Volumes.config], object : TypeToken<Map<String, Any>>() {}.type),
        )

    fun createVolume(
        volumeSet: String,
        volume: Volume,
    ) {
        try {
            Volumes.insert {
                it[Volumes.volumeSet] = Uuid.parse(volumeSet)
                it[name] = volume.name
                it[metadata] = gson.toJson(volume.properties)
                it[config] = gson.toJson(volume.config)
                it[state] = VolumeState.INACTIVE
            }
        } catch (e: ExposedSQLException) {
            throw ObjectExistsException("volume '${volume.name}' already exists")
        }
    }

    fun updateVolumeConfig(
        volumeSet: String,
        volumeName: String,
        config: Map<String, Any>,
    ) {
        Volumes.update({
            (Volumes.volumeSet eq Uuid.parse(volumeSet)) and (Volumes.name eq volumeName) and (Volumes.state neq VolumeState.DELETING)
        }) {
            it[Volumes.config] = gson.toJson(config)
        }
    }

    fun markVolumeDeleting(
        volumeSet: String,
        volumeName: String,
    ) {
        val count =
            Volumes.update({
                (Volumes.volumeSet eq Uuid.parse(volumeSet)) and (Volumes.name eq volumeName)
            }) {
                it[state] = VolumeState.DELETING
            }
        if (count == 0) {
            throw NoSuchObjectException("no such volume '$volumeName'")
        }
    }

    fun deleteVolume(
        volumeSet: String,
        volumeName: String,
    ) {
        val count =
            Volumes.deleteWhere {
                (Volumes.volumeSet eq Uuid.parse(volumeSet)) and (Volumes.name eq volumeName)
            }
        if (count == 0) {
            throw NoSuchObjectException("no such volume '$volumeName'")
        }
    }

    fun getVolume(
        volumeSet: String,
        volumeName: String,
    ): Volume =
        Volumes
            .selectAll()
            .where {
                (Volumes.volumeSet eq Uuid.parse(volumeSet)) and (Volumes.name eq volumeName) and
                    (Volumes.state neq VolumeState.DELETING)
            }.map { convertVolume(it) }
            .firstOrNull()
            ?: throw NoSuchObjectException("no such volume '$volumeName'")

    fun listVolumes(volumeSet: String): List<Volume> =
        Volumes
            .selectAll()
            .where {
                Volumes.volumeSet eq Uuid.parse(volumeSet)
            }.map { convertVolume(it) }

    fun listAllVolumes(): List<Volume> = Volumes.selectAll().map { convertVolume(it) }

    fun listDeletingVolumes(): List<Pair<String, Volume>> =
        Volumes
            .selectAll()
            .where {
                Volumes.state eq VolumeState.DELETING
            }.map { Pair(it[Volumes.volumeSet].toString(), convertVolume(it)) }

    private fun convertCommit(it: ResultRow) =
        Commit(
            id = it[Commits.guid],
            properties = gson.fromJson(it[Commits.metadata], object : TypeToken<Map<String, Any>>() {}.type),
        )

    private fun getTimestamp(commit: Commit): DateTime {
        val timestampString = commit.properties.get("timestamp")
        return if (timestampString != null) {
            DateTime.parse(timestampString.toString())
        } else {
            DateTime.now()
        }
    }

    fun createCommit(
        repo: String,
        volumeSet: String,
        commit: Commit,
    ) {
        val id =
            Commits.insert {
                it[Commits.repo] = repo
                it[Commits.volumeSet] = Uuid.parse(volumeSet)
                it[Commits.sourceCommit] = getCommitSource(volumeSet)
                it[Commits.timestamp] = getTimestamp(commit).toString()
                it[Commits.guid] = commit.id
                it[Commits.metadata] = gson.toJson(commit.properties)
                it[Commits.state] = VolumeState.ACTIVE
            } get Commits.id

        val rawTags = commit.properties["tags"]
        if (rawTags is Map<*, *>) {
            for ((key, value) in rawTags) {
                if (key is String) {
                    Tags.insert {
                        it[Tags.commit] = id.value
                        it[Tags.key] = key
                        it[Tags.value] = (value as? String) ?: ""
                    }
                }
            }
        }
    }

    fun getCommit(
        repo: String,
        commitId: String,
    ): Pair<String, Commit> =
        Commits
            .selectAll()
            .where {
                (Commits.repo eq repo) and (Commits.guid eq commitId) and (Commits.state eq VolumeState.ACTIVE)
            }.map {
                Pair(it[Commits.volumeSet].toString(), convertCommit(it))
            }.firstOrNull()
            ?: throw NoSuchObjectException("no such commit '$commitId' in repository '$repo'")

    fun getLastCommit(repo: String): String? =
        Commits
            .selectAll()
            .where {
                (Commits.repo eq repo) and (Commits.state eq VolumeState.ACTIVE)
            }.orderBy(Commits.timestamp, SortOrder.DESC)
            .limit(1)
            .map { it[Commits.guid] }
            .firstOrNull()

    fun getCommitSource(volumeSet: String): String? {
        // First, check to see if there's a latest commit for this volume set
        val volumeSetGuid = Uuid.parse(volumeSet)
        val prevCommit =
            Commits
                .selectAll()
                .where {
                    Commits.volumeSet eq volumeSetGuid
                }.orderBy(Commits.timestamp, SortOrder.DESC)
                .limit(1)
                .firstOrNull()

        if (prevCommit != null) {
            return prevCommit[Commits.guid]
        }

        // Otherwise, look at the source of the volumeset
        val volumeSetSource =
            VolumeSets
                .selectAll()
                .where {
                    VolumeSets.id eq volumeSetGuid
                }.firstOrNull()

        if (volumeSetSource != null) {
            return volumeSetSource[VolumeSets.sourceCommit]
        }

        return null
    }

    fun tagsMatch(
        commit: Commit,
        existCheck: List<String>,
        matchCheck: Map<String, String>,
    ): Boolean {
        val rawTags = commit.properties["tags"]
        val tags: Map<String, String> =
            when (rawTags) {
                is Map<*, *> -> rawTags.entries.associate { (k, v) -> (k as? String ?: "") to (v as? String ?: "") }
                else -> return false
            }

        for (key in existCheck) {
            if (!tags.containsKey(key)) {
                return false
            }
        }

        for ((key, value) in matchCheck) {
            if (tags[key] != value) {
                return false
            }
        }

        return true
    }

    fun listCommits(
        repo: String,
        tags: List<String>? = null,
    ): List<Commit> {
        // Build the search criteria
        val existCheck = mutableListOf<String>()
        val matchCheck = mutableMapOf<String, String>()
        tags?.let {
            for (tag in tags) {
                if (tag.contains("=")) {
                    val key = tag.substringBefore("=")
                    val value = tag.substringAfter("=")
                    matchCheck[key] = value
                } else {
                    existCheck.add(tag)
                }
            }
        }

        val query =
            if (!tags.isNullOrEmpty()) {
                val q =
                    (Commits innerJoin Tags)
                        .select(Commits.guid, Commits.volumeSet, Commits.metadata, Commits.timestamp)
                        .where { ((Commits.id eq Tags.commit) and (Commits.repo eq repo) and (Commits.state eq VolumeState.ACTIVE)) }

            /*
             * For filtering by multiple tags, we want to avoid complex temporary tables, etc. So we instead find tags
             * that match any such filter, and then post-process the list to do the AND query. This will get more
             * complicated if we want to do pagination, etc.
             */
                val conditions = mutableListOf<Op<Boolean>>()
                if (existCheck.size != 0) {
                    conditions.add(Tags.key inList existCheck)
                }

                for ((key, value) in matchCheck) {
                    conditions.add((Tags.key eq key) and (Tags.value eq value))
                }

                q
                    .andWhere {
                        conditions.compoundOr()
                    }.withDistinct(true)
                q
            } else {
                Commits.selectAll().where { (Commits.repo eq repo) and (Commits.state eq VolumeState.ACTIVE) }
            }

        val result =
            query
                .orderBy(Commits.timestamp, SortOrder.DESC)
                .map { convertCommit(it) }

        if (tags.isNullOrEmpty()) {
            return result
        } else {
            return result.filter { tagsMatch(it, existCheck, matchCheck) }
        }
    }

    data class CommitInfo(
        val id: Int,
        val guid: String,
        var volumeSet: String,
    )

    fun listDeletingCommits(): List<CommitInfo> =
        Commits
            .selectAll()
            .where {
                Commits.state eq VolumeState.DELETING
            }.map {
                CommitInfo(
                    id = it[Commits.id].value,
                    guid = it[Commits.guid],
                    volumeSet = it[Commits.volumeSet].toString(),
                )
            }

    fun hasClones(commit: CommitInfo): Boolean =
        VolumeSets
            .selectAll()
            .where {
                VolumeSets.sourceId eq commit.id
            }.count() > 0L

    fun updateCommit(
        repo: String,
        commit: Commit,
    ) {
        val id =
            Commits
                .selectAll()
                .where {
                    (Commits.repo eq repo) and (Commits.guid eq commit.id) and (Commits.state eq VolumeState.ACTIVE)
                }.map { it[Commits.id].value }
                .firstOrNull()
                ?: throw NoSuchObjectException("no such commit '${commit.id}' in repository '$repo'")
        Commits.update({
            Commits.id eq id
        }) {
            it[Commits.timestamp] = getTimestamp(commit).toString()
            it[Commits.metadata] = gson.toJson(commit.properties)
        }
        Tags.deleteWhere {
            Tags.commit eq id
        }
        val rawTags = commit.properties["tags"]
        if (rawTags is Map<*, *>) {
            for ((key, value) in rawTags) {
                if (key is String) {
                    Tags.insert {
                        it[Tags.commit] = id
                        it[Tags.key] = key
                        it[Tags.value] = (value as? String) ?: ""
                    }
                }
            }
        }
    }

    fun markCommitDeleting(
        repo: String,
        commitId: String,
    ) {
        val count =
            Commits.update({
                (Commits.repo eq repo) and (Commits.guid eq commitId) and (Commits.state eq VolumeState.ACTIVE)
            }) {
                it[state] = VolumeState.DELETING
            }
        if (count == 0) {
            throw NoSuchObjectException("no such commit '$commitId' in repository '$repo'")
        }
    }

    fun deleteCommit(commit: CommitInfo) {
        Commits.deleteWhere {
            (Commits.id eq commit.id)
        }
    }

    fun createOperation(
        repo: String,
        volumeSet: String,
        data: OperationData,
    ) {
        Operations.insert {
            it[id] = Uuid.parse(volumeSet)
            it[Operations.repo] = repo
            it[metadataOnly] = data.metadataOnly
            it[remoteParameters] = gson.toJson(data.params)
            it[remote] = data.operation.remote
            it[commitId] = data.operation.commitId
            it[type] = data.operation.type
            it[state] = data.operation.state
        }
    }

    private fun convertOperation(it: ResultRow) =
        OperationData(
            metadataOnly = it[Operations.metadataOnly],
            params = gson.fromJson(it[Operations.remoteParameters], RemoteParameters::class.java),
            repo = it[Operations.repo],
            operation =
                Operation(
                    id = it[Operations.id].toString(),
                    remote = it[Operations.remote],
                    commitId = it[Operations.commitId],
                    type = it[Operations.type],
                    state = it[Operations.state],
                ),
        )

    fun listOperationsByRepository(repo: String): List<OperationData> =
        Operations
            .selectAll()
            .where {
                (Operations.repo eq repo) and (Operations.state eq Operation.State.RUNNING)
            }.map { convertOperation(it) }

    fun listOperations(): List<OperationData> =
        Operations
            .selectAll()
            .where {
                Operations.state eq Operation.State.RUNNING
            }.map { convertOperation(it) }

    fun getOperation(id: String): OperationData {
        val uuid = Uuid.parse(id)
        return Operations
            .selectAll()
            .where {
                Operations.id eq uuid
            }.map { convertOperation(it) }
            .firstOrNull()
            ?: throw NoSuchObjectException("no such operation '$id'")
    }

    fun operationRunning(id: String): Boolean {
        val uuid = Uuid.parse(id)
        return Operations
            .selectAll()
            .where {
                (Operations.id eq uuid) and (Operations.state eq Operation.State.RUNNING)
            }.count() > 0
    }

    fun updateOperationState(
        id: String,
        state: Operation.State,
    ) {
        val uuid = Uuid.parse(id)
        Operations.update({
            Operations.id eq uuid
        }) {
            it[Operations.state] = state
        }
    }

    fun operationInProgress(
        repo: String,
        type: Operation.Type,
        commitId: String,
        remote: String?,
    ): String? {
        val query =
            Operations.selectAll().where {
                (Operations.repo eq repo) and (Operations.type eq type) and (Operations.commitId eq commitId) and
                    (Operations.state eq Operation.State.RUNNING)
            }
        remote?.let {
            query.andWhere { Operations.remote eq remote }
        }
        return query
            .map { it[Operations.id].toString() }
            .firstOrNull()
    }

    private fun convertProgressEntry(it: ResultRow) =
        ProgressEntry(
            id = it[ProgressEntries.id].value,
            message = it[ProgressEntries.message],
            percent = it[ProgressEntries.percent],
            type = it[ProgressEntries.type],
        )

    fun addProgressEntry(
        operation: String,
        entry: ProgressEntry,
    ): Int {
        val result =
            ProgressEntries.insert {
                it[ProgressEntries.operation] = Uuid.parse(operation)
                it[message] = entry.message
                it[type] = entry.type
                it[percent] = entry.percent
            } get ProgressEntries.id
        val operationState =
            when (entry.type) {
                ProgressEntry.Type.FAILED -> Operation.State.FAILED
                ProgressEntry.Type.ABORT -> Operation.State.ABORTED
                ProgressEntry.Type.COMPLETE -> Operation.State.COMPLETE
                else -> Operation.State.RUNNING
            }
        updateOperationState(operation, operationState)
        return result.value
    }

    fun listProgressEntries(
        operation: String,
        lastEntry: Int = 0,
    ): List<ProgressEntry> {
        val uuid = Uuid.parse(operation)
        return ProgressEntries
            .selectAll()
            .where {
                (ProgressEntries.operation eq uuid) and (ProgressEntries.id greater lastEntry)
            }.orderBy(ProgressEntries.id)
            .map { convertProgressEntry(it) }
    }
}
