// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class RepositoryStatus(
    var lastCommit: String? = null,
    var sourceCommit: String? = null,
)
