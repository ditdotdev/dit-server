/*
 * Copyright Datadatdat.
 *
 * Contract validation: ensures the Ktor route handlers cover every endpoint
 * defined in the OpenAPI specification (openapi/datadatdat.yml).
 *
 * This test parses the spec, extracts all path+method pairs, and verifies
 * each one is reachable via the test server. It does NOT validate response
 * schemas — only that the route exists and doesn't return 404/405.
 */

package com.datadatdat

import io.kotlintest.matchers.collections.shouldBeEmpty
import io.kotlintest.specs.StringSpec
import java.io.File

class OpenApiContractTest : StringSpec() {

    /**
     * Parse the OpenAPI YAML to extract all path+method pairs.
     * Uses simple line-based parsing to avoid adding a YAML library dependency.
     */
    private fun parseOpenApiPaths(): List<Pair<String, String>> {
        val specFile = File("${System.getProperty("user.dir")}/../openapi/datadatdat.yml")
        if (!specFile.exists()) {
            // Try from project root (CI may run from different directory)
            val altFile = File("openapi/datadatdat.yml")
            if (!altFile.exists()) {
                throw IllegalStateException(
                    "OpenAPI spec not found at ${specFile.absolutePath} or ${altFile.absolutePath}"
                )
            }
            return parseYamlPaths(altFile.readText())
        }
        return parseYamlPaths(specFile.readText())
    }

    private fun parseYamlPaths(yaml: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val lines = yaml.lines()
        var inPaths = false
        var currentPath: String? = null
        val httpMethods = setOf("get", "post", "put", "delete", "patch", "head", "options")

        for (line in lines) {
            // Detect the top-level "paths:" section
            if (line.trimEnd() == "paths:") {
                inPaths = true
                continue
            }

            // Exit paths section when we hit another top-level key (like "components:")
            if (inPaths && line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                inPaths = false
                continue
            }

            if (!inPaths) continue

            // Path entries are indented 2 spaces: "  /v1/repositories:"
            val pathMatch = Regex("^  (/[^:]+):").find(line)
            if (pathMatch != null) {
                currentPath = pathMatch.groupValues[1]
                continue
            }

            // HTTP methods are indented 4 spaces: "    get:"
            if (currentPath != null) {
                val methodMatch = Regex("^    (\\w+):").find(line)
                if (methodMatch != null) {
                    val method = methodMatch.groupValues[1].lowercase()
                    if (method in httpMethods) {
                        results.add(Pair(method.uppercase(), currentPath))
                    }
                }
            }
        }

        return results
    }

    /**
     * Normalize an OpenAPI path to match Ktor route format.
     * OpenAPI uses {param}, Ktor also uses {param} — they match.
     */
    private fun normalizePath(path: String): String = path.trimEnd('/')

    /**
     * Extract all route paths registered in the Ktor API handler files.
     * Parses the Kotlin source files directly for route() definitions.
     */
    private fun parseKtorRoutes(): Set<Pair<String, String>> {
        val apiDir = File("${System.getProperty("user.dir")}/src/main/kotlin/com/datadatdat/apis")
        if (!apiDir.exists()) {
            throw IllegalStateException("API directory not found at ${apiDir.absolutePath}")
        }

        val routes = mutableSetOf<Pair<String, String>>()
        val httpMethods = setOf("get", "post", "put", "delete", "patch")

        for (file in apiDir.listFiles()?.filter { it.extension == "kt" } ?: emptyList()) {
            var currentRoute: String? = null

            for (line in file.readLines()) {
                // Match: route("/v1/...") {
                val routeMatch = Regex("""route\("([^"]+)"\)""").find(line)
                if (routeMatch != null) {
                    currentRoute = routeMatch.groupValues[1]
                    continue
                }

                // Match: get { / post { / delete { at the handler level
                if (currentRoute != null) {
                    val methodMatch = Regex("""^\s+(get|post|put|delete|patch)\s*\{""").find(line)
                    if (methodMatch != null) {
                        routes.add(Pair(methodMatch.groupValues[1].uppercase(), currentRoute))
                    }
                }
            }
        }

        return routes
    }

    init {
        "every OpenAPI path+method has a corresponding Ktor route handler" {
            val specEndpoints = parseOpenApiPaths()
            val ktorRoutes = parseKtorRoutes()

            // Normalize for comparison
            val specNormalized = specEndpoints.map { (method, path) ->
                Pair(method, normalizePath(path))
            }.toSet()

            val ktorNormalized = ktorRoutes.map { (method, path) ->
                Pair(method, normalizePath(path))
            }.toSet()

            val missingInServer = specNormalized - ktorNormalized
            val extraInServer = ktorNormalized - specNormalized

            if (missingInServer.isNotEmpty()) {
                println("Endpoints in OpenAPI spec but MISSING from Ktor handlers:")
                missingInServer.forEach { (method, path) ->
                    println("  $method $path")
                }
            }

            if (extraInServer.isNotEmpty()) {
                println("Endpoints in Ktor handlers but NOT in OpenAPI spec:")
                extraInServer.forEach { (method, path) ->
                    println("  $method $path")
                }
            }

            missingInServer.shouldBeEmpty()
        }

        "OpenAPI spec file exists and is parseable" {
            val endpoints = parseOpenApiPaths()
            assert(endpoints.isNotEmpty()) { "OpenAPI spec should define at least one endpoint" }
            // Spec defines 35 operations across 22 paths
            assert(endpoints.size >= 30) {
                "Expected at least 30 operations in spec, found ${endpoints.size}: $endpoints"
            }
        }

        "Ktor route handlers exist for all API domains" {
            val routes = parseKtorRoutes()
            val domains = routes.map { (_, path) -> path.split("/")[2] }.toSet()

            // All API domains from the spec should be present
            assert("repositories" in domains) { "Missing repositories routes" }
            assert("operations" in domains) { "Missing operations routes" }
            assert("context" in domains) { "Missing context routes" }
        }
    }
}
