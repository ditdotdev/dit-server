/*
 * Copyright Dit.
 */

package dev.dit.models

data class Commit(
    var id: String,
    var properties: Map<String, Any> = emptyMap(),
)
