// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class CommitStatus(
    var logicalSize: Long,
    var actualSize: Long,
    var uniqueSize: Long,
    var ready: Boolean,
    var error: String?,
)
