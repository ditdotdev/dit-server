// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.dit.metadata.table

import dev.dit.metadata.table.Remotes.references
import dev.dit.models.ProgressEntry
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Progress entries record information from ongoing operations. Each entry has a monotonically increasing ID number,
 * such that consumers can fetch any progress entries that have been added since the last time they checked.
 */
object ProgressEntries : IntIdTable("progress_entries") {
    val operation = uuid("operation_id").references(Operations.id, onDelete = ReferenceOption.CASCADE)
    val percent = integer("percent").nullable()
    val message = varchar("message", 4096).nullable()
    val type = enumerationByName("type", 16, ProgressEntry.Type::class)
}
