/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class Commit(
    var id: String,
    var properties: Map<String, Any> = emptyMap(),
)
