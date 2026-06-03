/*
 * Copyright Dit.
 */

package dev.dit.models

data class RepositoryStatus(
    var lastCommit: String? = null,
    var sourceCommit: String? = null,
)
