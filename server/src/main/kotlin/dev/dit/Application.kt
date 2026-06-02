/*
 * Copyright Dit.
 */

package dev.dit

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import dev.dit.apis.commitsApi
import dev.dit.apis.contextApi
import dev.dit.apis.operationsApi
import dev.dit.apis.remotesApi
import dev.dit.apis.repositoriesApi
import dev.dit.apis.volumesApi
import dev.dit.context.docker.DockerZfsContext
import dev.dit.context.kubernetes.KubernetesCsiContext
import dev.dit.exception.NoSuchObjectException
import dev.dit.exception.ObjectExistsException
import dev.dit.models.Error
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
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

// When true, server stack traces are serialized into ApiError.details
// for every error response. Acceptable for the local-CLI use case
// (loopback, single trusted user) but leaks server file paths, class
// names, and library versions to every API client. Gated off by
// default; opt in via `-Ddit.debug=true` on the JVM command
// line. Read on every call (not cached) so tests can toggle the
// property; the lookup cost is negligible relative to the rest of
// the error-response path.
//
// The local-CLI Docker image sets this in server/src/scripts/run; the
// multi-tenant dit-remote-server must NOT.
internal fun includeStackTraceInErrors(): Boolean = System.getProperty("dit.debug")?.toBoolean() == true

fun exceptionToError(t: Throwable): Any {
    val details =
        if (includeStackTraceInErrors()) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sw.toString()
        } else {
            null
        }
    return Error(
        code = t.javaClass.simpleName,
        message = t.message ?: "unknown error",
        details = details,
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
        System.getProperty("dit.context")
            ?: throw IllegalArgumentException("dit.context property must be set")
    log.info("Running with context $context")

    val configProperty = System.getProperty("dit.contextConfig")
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
    // Order matters: loadState() must run BEFORE the reaper starts.
    // The reaper's markEmptyVolumeSets sweep treats INACTIVE volumesets
    // with no associated commits as eligible for deletion. An in-flight
    // operation that survived a previous run leaves its volumeset
    // INACTIVE in the DB until loadState re-registers the executor as
    // a runningOperation. If the reaper runs first, that window allows
    // it to mark a still-needed volumeset DELETING.
    services.operations.loadState()
    Thread(services.reaper).start()
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

    // StatusPages MUST be installed before the routing block. In Ktor each
    // plugin's interceptors are registered into the call pipeline at install
    // time, in install order; routes opened with `routing { ... }` get a
    // snapshot of that pipeline. Pre-fix StatusPages was installed AFTER
    // routing, which left routes wired up without the exception interceptor
    // visible to them on the very first request — exceptions on a fresh
    // process leaked out of the pipeline and CIO closed the connection
    // before sending a response, surfacing on the d3 CLI as
    // `Post .../v1/repositories: EOF` (issue #139). Subsequent requests
    // worked because the lazy plugin wiring caught up after the first call.
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
        exception<IOException> { call, cause ->
            if (cause.message?.contains("401") == true) {
                call.respond(HttpStatusCode.Unauthorized, exceptionToError(cause))
            } else {
                call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
                // Explicitly log + stack trace before rethrowing. The
                // rethrow used to be the only signal but Ktor's pipeline
                // tends to swallow re-thrown exceptions silently when the
                // response has already been written — so callers see a
                // bare 500 with no server-side breadcrumb. Always log
                // first so the stack appears in `docker logs` regardless
                // of what the upstream pipeline does with the rethrow.
                log.error("Unhandled IOException -> 500 on ${call.request.local.uri}", cause)
                throw cause
            }
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, exceptionToError(cause))
            // Always log the full stack trace before rethrowing. The
            // bare `throw cause` previously here was supposed to surface
            // the exception via Ktor's pipeline logger, but in practice
            // (observed on PR dit-remote-server#639 with the d3
            // server returning 500 on `d3 push`) the stack trace never
            // appeared in `docker logs`, leaving callers with an
            // unactionable "500 Internal Server Error" response and no
            // server-side context. Log explicitly first.
            log.error("Unhandled exception -> 500 on ${call.request.local.uri}", cause)
            throw cause
        }
    }

    routing {
        commitsApi(services)
        contextApi(services)
        operationsApi(services)
        remotesApi(services)
        repositoriesApi(services)
        volumesApi(services)
    }
}

fun main(
    @Suppress("UNUSED_PARAMETER") args: Array<String>,
) {
    val server =
        embeddedServer(
            CIO,
            (System.getProperty("dit.port") ?: "5001").toInt(),
            module = Application::main,
        )
    server.start(wait = true)
}
