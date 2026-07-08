// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.models

data class Operation(
    var id: String,
    var type: Type,
    var state: State = State.RUNNING,
    var remote: String,
    var commitId: String,
) {
    enum class Type(
        val value: String,
    ) {
        PUSH("PUSH"),
        PULL("PULL"),
    }

    enum class State(
        val value: String,
    ) {
        RUNNING("RUNNING"),
        ABORTED("ABORTED"),
        FAILED("FAILED"),
        COMPLETE("COMPLETE"),
    }
}
