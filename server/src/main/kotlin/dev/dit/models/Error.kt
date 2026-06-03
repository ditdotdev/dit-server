/*
 * Copyright Dit.
 */

package dev.dit.models

data class Error(
    var code: String? = null,
    var message: String,
    var details: String? = null,
)
