/*
 * Copyright Dit.
 */

package dev.dit.apis

import dev.dit.ServiceLocator
import dev.dit.models.Repository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.repositoriesApi(services: ServiceLocator) {
    route("/v1/repositories") {
        post {
            val repo = call.receive(Repository::class)
            services.repositories.createRepository(repo)
            call.respond(HttpStatusCode.Created, repo)
        }

        get {
            call.respond(services.repositories.listRepositories())
        }
    }

    route("/v1/repositories/{repositoryName}") {
        get {
            val name = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")
            call.respond(services.repositories.getRepository(name))
        }

        post {
            val name = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")
            val repo = call.receive(Repository::class)
            services.repositories.updateRepository(name, repo)
            call.respond(repo)
        }

        delete {
            val name = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")
            services.repositories.deleteRepository(name)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/repositories/{repositoryName}/status") {
        get {
            val name = call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")
            call.respond(services.repositories.getRepositoryStatus(name))
        }
    }
}
