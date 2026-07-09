// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.dit.metadata.table

import dev.dit.metadata.MetadataProvider
import org.jetbrains.exposed.v1.core.Table

/*
 * Volumes represent a single mount within a repository. They are grouped into VolumeSets, so that they are snapshotted
 * and cloned as a group. Volume names are not globally unique, but are unique within a given volumeset, so the
 * (volumeset, name) tuple uniquely identifies any volume in the system. Volumes can have user properties, which can
 * be used to store the path where the volume is supposed to be mounted or other useful mmetadata.
 *
 * Volumes have a foreign key relationship with volumesets, but we don't cascade on delete because we want to ensure
 * that all volumes are explicitly deleted prior to deleting the volumeset.
 */
object Volumes : Table("volumes") {
    val volumeSet = uuid("volume_set").references(VolumeSets.id)
    val name = varchar("name", 64)
    val metadata = varchar("metadata", 8192)
    val config = varchar("config", 8192)
    val state = enumerationByName("state", 16, MetadataProvider.VolumeState::class)

    override val primaryKey = PrimaryKey(volumeSet, name)
}
