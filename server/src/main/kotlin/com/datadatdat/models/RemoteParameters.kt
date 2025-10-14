/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class RemoteParameters(
    var provider: String,
    var properties: Map<String, Any> = emptyMap()
)
