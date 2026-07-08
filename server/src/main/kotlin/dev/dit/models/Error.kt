// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class Error(
    var code: String? = null,
    var message: String,
    var details: String? = null,
)
