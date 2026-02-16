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

        "exceptionToError includes stack trace in details" {
            val ex = IllegalStateException("test error")
            val error = exceptionToError(ex) as Error
            error.details shouldNotBe null
            error.details!!.contains("IllegalStateException") shouldBe true
            error.details!!.contains("test error") shouldBe true
        }

        "exceptionToError handles nested exception" {
            val cause = RuntimeException("root cause")
            val ex = IllegalStateException("wrapper", cause)
            val error = exceptionToError(ex) as Error
            error.code shouldBe "IllegalStateException"
            error.message shouldBe "wrapper"
            error.details!!.contains("root cause") shouldBe true
        }

        "applicationCompressionConfiguration returns non-null configuration" {
            val config = applicationCompressionConfiguration()
            config shouldNotBe null
        }
    }
}
