/*
 * Copyright Dit.
 */

package dev.dit.models

data class RemoteParameters(
    var provider: String,
    var properties: Map<String, Any> = emptyMap(),
)
