/*
 * Copyright Dit.
 */

package dev.dit.apis

import dev.dit.ServiceLocator
import dev.dit.models.RemoteParameters
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// Parses an optional boolean query parameter strictly. Returns the default
// when the key is absent. When the key is present but the value is not
// exactly "true" or "false" (case-sensitive), throws IllegalArgumentException
// — the StatusPages handler maps that to 400 Bad Request.
//
// Previously this used Kotlin's String.toBoolean(), which silently returns
// false for anything that isn't "true" (e.g. "yse", "1", ""). Callers had
// no way to learn their typo had been ignored.
private fun parseBooleanQueryParam(
    raw: String?,
    name: String,
    default: Boolean,
): Boolean =
    when (raw) {
        null -> default

        "true" -> true

        "false" -> false

        else -> throw IllegalArgumentException(
            "$name must be 'true' or 'false', got '$raw'",
        )
    }

fun Route.operationsApi(services: ServiceLocator) {
    route("/v1/operations") {
        get {
            val repo = call.request.queryParameters["repository"]
            call.respond(services.operations.listOperations(repo))
        }
    }

    route("/v1/operations/{operationId}") {
        delete {
            val operation = call.parameters["operationId"] ?: throw IllegalArgumentException("missing operation id parameter")
            services.operations.abortOperation(operation)
            call.respond(HttpStatusCode.NoContent)
        }

        get {
            val operation = call.parameters["operationId"] ?: throw IllegalArgumentException("missing operation id parameter")
            call.respond(services.operations.getOperation(operation))
        }
    }

    route("/v1/operations/{operationId}/progress") {
        get {
            val operation = call.parameters["operationId"] ?: throw IllegalArgumentException("missing operation id parameter")
            val lastId =
                call.request.queryParameters
                    .get("lastId")
                    ?.toInt() ?: 0
            call.respond(services.operations.getProgress(operation, lastId))
        }
    }

    route("/v1/repositories/{repositoryName}/remotes/{remoteName}/commits/{commitId}/pull") {
        post {
            val repo = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repository name parameter")
            val remote = call.parameters["remoteName"] ?: throw IllegalArgumentException("missing remote name parameter")
            val commitId = call.parameters["commitId"] ?: throw IllegalArgumentException("missing commit id parameter")
            val params = call.receive(RemoteParameters::class)
            val metadataOnly =
                parseBooleanQueryParam(
                    call.request.queryParameters["metadataOnly"],
                    "metadataOnly",
                    default = false,
                )
            call.respond(HttpStatusCode.Created, services.operations.startPull(repo, remote, commitId, params, metadataOnly))
        }
    }

    route("/v1/repositories/{repositoryName}/remotes/{remoteName}/commits/{commitId}/push") {
        post {
            val repo = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repository name parameter")
            val remote = call.parameters["remoteName"] ?: throw IllegalArgumentException("missing remote name parameter")
            val commitId = call.parameters["commitId"] ?: throw IllegalArgumentException("missing commit id parameter")
            val params = call.receive(RemoteParameters::class)
            val metadataOnly =
                parseBooleanQueryParam(
                    call.request.queryParameters["metadataOnly"],
                    "metadataOnly",
                    default = false,
                )
            call.respond(HttpStatusCode.Created, services.operations.startPush(repo, remote, commitId, params, metadataOnly))
        }
    }
}
