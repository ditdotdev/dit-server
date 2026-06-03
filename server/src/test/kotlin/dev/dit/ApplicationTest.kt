/*
 * Copyright Dit.
 */

package dev.dit

import dev.dit.models.Error
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class ApplicationTest : StringSpec() {
    init {
        "exceptionToError maps exception class name to code" {
            val ex = IllegalArgumentException("bad input")
            val error = exceptionToError(ex) as Error
            error.code shouldBe "IllegalArgumentException"
        }

        "exceptionToError maps exception message" {
            val ex = RuntimeException("something went wrong")
            val error = exceptionToError(ex) as Error
            error.message shouldBe "something went wrong"
        }

        "exceptionToError uses default message for null message" {
            val ex = RuntimeException()
            val error = exceptionToError(ex) as Error
            error.message shouldBe "unknown error"
        }

        "exceptionToError omits details when dit.debug is unset" {
            // Default behavior: no stack trace in details (protects remote
            // deployments from leaking server internals).
            val previous = System.clearProperty("dit.debug")
            try {
                val ex = IllegalStateException("test error")
                val error = exceptionToError(ex) as Error
                error.code shouldBe "IllegalStateException"
                error.message shouldBe "test error"
                error.details shouldBe null
            } finally {
                if (previous != null) System.setProperty("dit.debug", previous)
            }
        }

        "exceptionToError includes stack trace in details when dit.debug=true" {
            val previous = System.getProperty("dit.debug")
            System.setProperty("dit.debug", "true")
            try {
                val ex = IllegalStateException("test error")
                val error = exceptionToError(ex) as Error
                error.details shouldNotBe null
                error.details!!.contains("IllegalStateException") shouldBe true
                error.details!!.contains("test error") shouldBe true
            } finally {
                if (previous == null) System.clearProperty("dit.debug") else System.setProperty("dit.debug", previous)
            }
        }

        "exceptionToError handles nested exception" {
            val previous = System.getProperty("dit.debug")
            System.setProperty("dit.debug", "true")
            try {
                val cause = RuntimeException("root cause")
                val ex = IllegalStateException("wrapper", cause)
                val error = exceptionToError(ex) as Error
                error.code shouldBe "IllegalStateException"
                error.message shouldBe "wrapper"
                error.details!!.contains("root cause") shouldBe true
            } finally {
                if (previous == null) System.clearProperty("dit.debug") else System.setProperty("dit.debug", previous)
            }
        }

        "applicationCompressionConfiguration returns non-null configuration" {
            val config = applicationCompressionConfiguration()
            config shouldNotBe null
        }
    }
}
