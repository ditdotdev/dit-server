/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.models.Error
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

        "exceptionToError omits details when datadatdat.debug is unset" {
            // Default behavior: no stack trace in details (protects remote
            // deployments from leaking server internals).
            val previous = System.clearProperty("datadatdat.debug")
            try {
                val ex = IllegalStateException("test error")
                val error = exceptionToError(ex) as Error
                error.code shouldBe "IllegalStateException"
                error.message shouldBe "test error"
                error.details shouldBe null
            } finally {
                if (previous != null) System.setProperty("datadatdat.debug", previous)
            }
        }

        "exceptionToError includes stack trace in details when datadatdat.debug=true" {
            val previous = System.getProperty("datadatdat.debug")
            System.setProperty("datadatdat.debug", "true")
            try {
                val ex = IllegalStateException("test error")
                val error = exceptionToError(ex) as Error
                error.details shouldNotBe null
                error.details!!.contains("IllegalStateException") shouldBe true
                error.details!!.contains("test error") shouldBe true
            } finally {
                if (previous == null) System.clearProperty("datadatdat.debug") else System.setProperty("datadatdat.debug", previous)
            }
        }

        "exceptionToError handles nested exception" {
            val previous = System.getProperty("datadatdat.debug")
            System.setProperty("datadatdat.debug", "true")
            try {
                val cause = RuntimeException("root cause")
                val ex = IllegalStateException("wrapper", cause)
                val error = exceptionToError(ex) as Error
                error.code shouldBe "IllegalStateException"
                error.message shouldBe "wrapper"
                error.details!!.contains("root cause") shouldBe true
            } finally {
                if (previous == null) System.clearProperty("datadatdat.debug") else System.setProperty("datadatdat.debug", previous)
            }
        }

        "applicationCompressionConfiguration returns non-null configuration" {
            val config = applicationCompressionConfiguration()
            config shouldNotBe null
        }
    }
}
