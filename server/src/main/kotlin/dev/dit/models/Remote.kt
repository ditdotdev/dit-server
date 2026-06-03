/*
 * Copyright Dit.
 */

package dev.dit.models

data class Remote(
    var provider: String,
    var name: String,
    var properties: Map<String, Any> = emptyMap(),
)
