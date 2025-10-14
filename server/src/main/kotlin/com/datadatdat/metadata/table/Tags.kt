package com.datadatdat.metadata.table

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/*
 * Tags are name/value pairs that are associated with commits. The key name must be unique, and hence forms
 * part of the primary key with the commit ID.
 */
object Tags : Table("tags") {
    val commit = integer("commit").references(Commits.id, onDelete = ReferenceOption.CASCADE)
    val key = varchar("key", 64)
    val value = varchar("value", 64)

    override val primaryKey = PrimaryKey(commit, key)
}
