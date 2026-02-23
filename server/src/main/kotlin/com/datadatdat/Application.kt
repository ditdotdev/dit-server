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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.GsonConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
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

internal fun applicationCompressionConfiguration(): CompressionConfig.() -> Unit =
    {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }

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

            else -> {
                throw IllegalArgumentException("unknown context '$context', must be one of ('docker-zfs', 'kubernetes-csi')")
            }
        }

    val services = ServiceLocator(runtimeContext, false)
    services.metadata.init()
    Thread(services.reaper).start()
    services.operations.loadState()
    mainProvider(services)
}

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
    routing {
        commitsApi(services)
        contextApi(services)
        operationsApi(services)
        remotesApi(services)
        repositoriesApi(services)
        volumesApi(services)
    }
    install(StatusPages) {
        exception<NoSuchObjectException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<ObjectExistsException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<JsonSyntaxException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, exceptionToError(cause))
            call.application.log.info(cause.message)
        }
        exception<BadRequestException> { call, cause ->
            val rootCause = cause.cause ?: cause
            call.respond(HttpStatusCode.BadRequest, exceptionToError(rootCause))
            call.application.log.info(rootCause.message)
        }
        exception<ApiException> { call, cause ->
            // Kubernetes API exceptions don't often provide useful messages, so log the response body instead
            call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
            log.error(cause.responseBody, cause)
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
            // For internal errors, log the whole exception and stack trace
            throw cause
        }
    }
}

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
