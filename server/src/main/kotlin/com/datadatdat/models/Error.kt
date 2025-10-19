/*
 * Copyright Datadatdat.
 */

package com.datadatdat.models

data class Error(
    var code: String? = null,
    var message: String,
    var details: String? = null,
)
