/*
 * Copyright Dit.
 */

package dev.dit.apis

import dev.dit.ServiceLocator
import dev.dit.models.Volume
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.volumesApi(services: ServiceLocator) {
    fun getRepoName(call: ApplicationCall): String =
        call.parameters["repositoryName"] ?: throw IllegalArgumentException("missing repositoryName parameter")

    fun getVolumeName(call: ApplicationCall): String =
        call.parameters["volumeName"] ?: throw IllegalArgumentException("missing volumeName parameter")

    route("/v1/repositories/{repositoryName}/volumes") {
        get {
            call.respond(services.volumes.listVolumes(getRepoName(call)))
        }

        post {
            val repo = getRepoName(call)
            val volume = call.receive(Volume::class)
            val result = services.volumes.createVolume(repo, volume)
            call.respond(HttpStatusCode.Created, result)
        }
    }

    route("/v1/repositories/{repositoryName}/volumes/{volumeName}") {
        get {
            val repo = getRepoName(call)
            val volumeName = getVolumeName(call)
            call.respond(services.volumes.getVolume(repo, volumeName))
        }

        delete {
            val repo = getRepoName(call)
            val volumeName = getVolumeName(call)
            services.volumes.deleteVolume(repo, volumeName)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/repositories/{repositoryName}/volumes/{volumeName}/activate") {
        post {
            val repo = getRepoName(call)
            val volumeName = getVolumeName(call)
            services.volumes.activateVolume(repo, volumeName)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/repositories/{repositoryName}/volumes/{volumeName}/deactivate") {
        post {
            val repo = getRepoName(call)
            val volumeName = getVolumeName(call)
            services.volumes.deactivateVolume(repo, volumeName)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/repositories/{repositoryName}/volumes/{volumeName}/status") {
        get {
            val repo = getRepoName(call)
            val volumeName = getVolumeName(call)
            call.respond(services.volumes.getVolumeStatus(repo, volumeName))
        }
    }
}
