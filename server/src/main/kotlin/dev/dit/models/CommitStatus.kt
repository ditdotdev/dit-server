/*
 * Copyright Dit.
 */

package dev.dit.models

data class CommitStatus(
    var logicalSize: Long,
    var actualSize: Long,
    var uniqueSize: Long,
    var ready: Boolean,
    var error: String?,
)
