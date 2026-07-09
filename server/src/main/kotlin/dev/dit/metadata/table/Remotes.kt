// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.metadata.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Remotes : Table() {
    val name = varchar("name", 64)
    val repo = varchar("repositories", 64).references(Repositories.name, onDelete = ReferenceOption.CASCADE)
    val metadata = varchar("metadata", 8192)

    override val primaryKey = PrimaryKey(name, repo)
}
