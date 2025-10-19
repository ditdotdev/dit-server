/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class Repository(
    var name: String,
    var properties: Map<String, Any> = emptyMap(),
)
