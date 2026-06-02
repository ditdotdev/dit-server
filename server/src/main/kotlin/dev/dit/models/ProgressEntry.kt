/*
 * Copyright Dit.
 */

package dev.dit.models

data class ProgressEntry(
    var type: Type,
    var message: String? = null,
    var percent: Int? = null,
    var id: Int = 0,
) {
    enum class Type(
        val value: String,
    ) {
        MESSAGE("MESSAGE"),
        START("START"),
        PROGRESS("PROGRESS"),
        END("END"),
        ERROR("ERROR"),
        ABORT("ABORTED"),
        FAILED("FAILED"),
        COMPLETE("COMPLETE"),
    }
}
