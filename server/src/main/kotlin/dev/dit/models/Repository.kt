/*
 * Copyright Dit.
 */

package dev.dit.models

data class Repository(
    var name: String,
    var properties: Map<String, Any> = emptyMap(),
)
