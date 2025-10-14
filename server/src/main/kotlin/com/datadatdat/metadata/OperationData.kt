/*
 * Copyright Datadatdat.
 */

package com.datadatdat.metadata

import com.datadatdat.models.Operation
import com.datadatdat.models.RemoteParameters

/**
 * this is a very simple data class that lets us store the (operation, request) tuple on disk.
 */
data class OperationData(
    val operation: Operation,
    val params: RemoteParameters,
    val repo: String,
    var metadataOnly: Boolean = false
)
