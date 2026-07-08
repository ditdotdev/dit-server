// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.metadata.table

import org.jetbrains.exposed.v1.core.Table

/*
 * Repositories are the top level abstraction within the dit metadata. Everything is connected in some shape or form
 * to a repository, and repository names must be unique. There is no on-disk state associated with repositories
 * (only volumes and/or volumesets have such state), so these exist entirely within dit metadata.
 */
object Repositories : Table() {
    val name = varchar("name", 64)
    val metadata = varchar("metadata", 8192)

    override val primaryKey = PrimaryKey(name)
}
