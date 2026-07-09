// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.apis

import dev.dit.ServiceLocator
import dev.dit.models.Remote
import dev.dit.models.RemoteParameters
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The remotes API is slightly more substantial because while we expose a more complete CRUD
 * interface over the REST API, we implement it at the provider level as a single list object
 * that is set as a unit. Most of the time we'll only have a single remote, and pushing the
 * complexity of managing different remotes by name down to the storage provider is not really
 * worth it.
 */
fun Route.remotesApi(services: ServiceLocator) {
    fun getRepoName(call: ApplicationCall): String =
        call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")

    fun getRemoteName(call: ApplicationCall): String =
        call.parameters["remoteName"] ?: throw IllegalArgumentException("missing remoteName parameter")

    fun getCommitId(call: ApplicationCall): String =
        call.parameters["commitId"] ?: throw IllegalArgumentException("missing commitId parameter")

    route("/v1/repositories/{repositoryName}/remotes") {
        get {
            call.respond(services.remotes.listRemotes(getRepoName(call)))
        }

        post {
            val repo = getRepoName(call)
            val remote = call.receive(Remote::class)
            services.remotes.addRemote(repo, remote)
            call.respond(HttpStatusCode.Created, remote)
        }
    }

    route("/v1/repositories/{repositoryName}/remotes/{remoteName}") {
        get {
            val repo = getRepoName(call)
            val remoteName = getRemoteName(call)
            call.respond(services.remotes.getRemote(repo, remoteName))
        }

        delete {
            val repo = getRepoName(call)
            val remoteName = getRemoteName(call)
            services.remotes.removeRemote(repo, remoteName)
            call.respond(HttpStatusCode.NoContent)
        }

        post {
            val repo = getRepoName(call)
            val remoteName = getRemoteName(call)
            val remote = call.receive(Remote::class)
            services.remotes.updateRemote(repo, remoteName, remote)
            call.respond(remote)
        }
    }

    // listRemoteCommits and getRemoteCommit historically used GET with a
    // `dit-remote-parameters` object header. The OpenAPI v7 client
    // generator can't encode complex objects in HTTP headers without
    // producing invalid header field names, so these moved to POST with
    // RemoteParameters in the request body. See dit-client-go#35.
    route("/v1/repositories/{repositoryName}/remotes/{remoteName}/commits") {
        post {
            val repo = getRepoName(call)
            val remoteName = getRemoteName(call)
            val params = call.receive(RemoteParameters::class)
            val tags = call.request.queryParameters.getAll("tag")
            call.respond(services.remotes.listRemoteCommits(repo, remoteName, params, tags))
        }
    }

    route("/v1/repositories/{repositoryName}/remotes/{remoteName}/commits/{commitId}") {
        post {
            val repo = getRepoName(call)
            val remoteName = getRemoteName(call)
            val commitId = getCommitId(call)
            val params = call.receive(RemoteParameters::class)
            call.respond(services.remotes.getRemoteCommit(repo, remoteName, params, commitId))
        }
    }
}
