// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.metadata

import dev.dit.models.Operation
import dev.dit.models.RemoteParameters

/**
 * this is a very simple data class that lets us store the (operation, request) tuple on disk.
 */
data class OperationData(
    val operation: Operation,
    val params: RemoteParameters,
    val repo: String,
    var metadataOnly: Boolean = false,
)
