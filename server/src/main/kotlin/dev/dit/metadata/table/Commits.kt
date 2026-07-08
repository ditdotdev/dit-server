// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.dit.metadata.table

import dev.dit.metadata.MetadataProvider
import dev.dit.metadata.table.Remotes.references
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
// import org.jetbrains.exposed.v1.core.javatime.datetime

/*
 * Commits represent a snapshot of a volumeset. Each commit is given a GUID, but a commit can be in one
 * or more repository. We use a unique id for commits, as we can have commits share the same repository and
 * GUID if the repository has been deleted and is pending removal. Each commit is technically just an ID and some
 * properties, but there are a few properties that we pull out and represent in the database: timestap and tags. The
 * former is extracted and stored within the commit table for easy sorting. Tags are stored in a separate table for
 * easy searching.
 *
 * Like volumes and volumesets, we have a foreign key relationship with the volumeset of which it is a part, but
 * don't cascade on delete in order to force the explicit cleanup of commits and their associated storage
 */
object Commits : IntIdTable("commits") {
    val repo = varchar("repo", 64)
    val guid = varchar("guid", 64)
    val sourceCommit = varchar("source_commit", 64).nullable()
    val timestamp = varchar("timestamp", 64) // TODO: Fix datetime import for Exposed 0.32.1
    val volumeSet = uuid("volume_set").references(VolumeSets.id)
    val metadata = varchar("metadata", 8192)
    val state = enumerationByName("state", 16, MetadataProvider.VolumeState::class)
}
