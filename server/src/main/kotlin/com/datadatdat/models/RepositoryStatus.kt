/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class RepositoryStatus(
    var lastCommit: String? = null,
    var sourceCommit: String? = null
)
