/*
 * Copyright Datadatdat.
 */

package com.datadatdat.apis

import com.datadatdat.ServiceLocator
import com.datadatdat.models.Context
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * This is a single global context API to be able to get the current context configuration for the given server.
 */
fun Route.contextApi(services: ServiceLocator) {
    route("/v1/context") {
        get {
            call.respond(
                Context(
                    provider = services.context.getProvider(),
                    properties = services.context.getProperties(),
                ),
            )
        }
    }
}
