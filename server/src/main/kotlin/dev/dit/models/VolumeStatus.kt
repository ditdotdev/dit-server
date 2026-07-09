// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class VolumeStatus(
    var name: String,
    var logicalSize: Long,
    var actualSize: Long,
    var properties: Map<String, Any> = emptyMap(),
    var ready: Boolean,
    var error: String?,
)
