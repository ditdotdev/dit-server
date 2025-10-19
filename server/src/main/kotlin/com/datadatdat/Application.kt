/*
 * Copyright Datadatdat.
 */

package com.datadatdat

import com.datadatdat.apis.commitsApi
import com.datadatdat.apis.contextApi
import com.datadatdat.apis.operationsApi
import com.datadatdat.apis.remotesApi
import com.datadatdat.apis.repositoriesApi
import com.datadatdat.apis.volumesApi
import com.datadatdat.context.docker.DockerZfsContext
import com.datadatdat.context.kubernetes.KubernetesCsiContext
import com.datadatdat.exception.NoSuchObjectException
import com.datadatdat.exception.ObjectExistsException
import com.datadatdat.models.Error
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.features.CallLogging
import io.ktor.features.Compression
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.features.StatusPages
import io.ktor.features.deflate
import io.ktor.features.gzip
import io.ktor.features.minimumSize
import io.ktor.gson.GsonConverter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.kubernetes.client.openapi.ApiException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.PrintWriter
import java.io.StringWriter

fun exceptionToError(t: Throwable): Any {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    return Error(
        code = t.javaClass.simpleName,
        message = t.message ?: "unknown error",
        details = sw.toString(),
    )
}

internal fun applicationCompressionConfiguration(): Compression.Configuration.() -> Unit =
    {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }

@KtorExperimentalAPI
fun Application.main() {
    val context =
        System.getProperty("datadatdat.context")
            ?: throw IllegalArgumentException("datadatdat.context property must be set")
    log.info("Running with context $context")

    val configProperty = System.getProperty("datadatdat.contextConfig")
    val contextConfig =
        if (!configProperty.isNullOrEmpty()) {
            val map = mutableMapOf<String, String>()
            for (propval in configProperty.split(",")) {
                val components = propval.split("=")
                if (components.size != 2) {
                    throw IllegalArgumentException("invalid configuration property '$propval'")
                }
                map[components[0]] = components[1]
            }
            map
        } else {
            emptyMap<String, String>()
        }

    val runtimeContext =
        when (context) {
            "docker-zfs" -> {
                DockerZfsContext(contextConfig)
            }
            "kubernetes-csi" -> {
                KubernetesCsiContext(contextConfig)
            }
            else -> throw IllegalArgumentException("unknown context '$context', must be one of ('docker-zfs', 'kubernetes-csi')")
        }

    val services = ServiceLocator(runtimeContext, false)
    services.metadata.init()
    Thread(services.reaper).start()
    services.operations.loadState()
    mainProvider(services)
}

@KtorExperimentalAPI
fun Application.mainProvider(services: ServiceLocator) {
    val log = LoggerFactory.getLogger(Application::class.java)
    install(DefaultHeaders)
    val gsonConverter = GsonConverter(GsonBuilder().create())
    install(ContentNegotiation) {
        register(ContentType.Application.Json, gsonConverter)
        register(ContentType.parse("application/vnd.docker.plugins.v1.2+json"), gsonConverter)
        // Docker is really sloppy with setting Content-Type, so we need a default behavior
        register(ContentType.Any, gsonConverter)
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(Compression, applicationCompressionConfiguration())
    install(Routing) {
        commitsApi(services)
        contextApi(services)
        operationsApi(services)
        remotesApi(services)
        repositoriesApi(services)
        volumesApi(services)
    }
    install(StatusPages) {
        exception<NoSuchObjectException> { cause ->
            call.respond(HttpStatusCode.NotFound, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<ObjectExistsException> { cause ->
            call.respond(HttpStatusCode.Conflict, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<IllegalArgumentException> { cause ->
            call.respond(HttpStatusCode.BadRequest, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<JsonSyntaxException> { cause ->
            call.respond(HttpStatusCode.BadRequest, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<ApiException> { cause ->
            // Kubernetes API exceptions don't often provide useful messages, so log the response body instead
            call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
            log.error(cause.responseBody, cause)
        }
        exception<Throwable> { cause ->
            call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
            // For internal errors, log the whole exception and stack trace
            throw cause
        }
    }
}

@KtorExperimentalAPI
fun main(
    @Suppress("UNUSED_PARAMETER") args: Array<String>,
) {
    val server =
        embeddedServer(
            CIO,
            (System.getProperty("datadatdat.port") ?: "5001").toInt(),
            module = Application::main,
        )
    server.start(wait = true)
}
