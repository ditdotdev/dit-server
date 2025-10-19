/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class VolumeStatus(
    var name: String,
    var logicalSize: Long,
    var actualSize: Long,
    var properties: Map<String, Any> = emptyMap(),
    var ready: Boolean,
    var error: String?,
)
