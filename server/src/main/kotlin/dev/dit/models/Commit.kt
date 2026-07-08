// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class Commit(
    var id: String,
    var properties: Map<String, Any> = emptyMap(),
)
