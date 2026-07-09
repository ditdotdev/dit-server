// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class Repository(
    var name: String,
    var properties: Map<String, Any> = emptyMap(),
)
