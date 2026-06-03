/*
 * Copyright Dit.
 */
package dev.dit.models

data class Volume(
    var name: String,
    var properties: Map<String, Any> = emptyMap(),
    var config: Map<String, Any> = emptyMap(),
)
