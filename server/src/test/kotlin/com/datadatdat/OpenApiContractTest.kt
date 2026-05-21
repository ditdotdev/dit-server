/*
 * Copyright Datadatdat.
 *
 * Contract validation in two layers:
 *
 *   1. Path + method coverage — every operation defined in the OpenAPI
 *      spec must have a corresponding Ktor route handler, and the spec
 *      must not have orphan endpoints with no implementation.
 *
 *   2. Schema shape validation — for every `components.schemas.X` in the
 *      spec, the corresponding Kotlin `com.datadatdat.models.X` data
 *      class must:
 *
 *        a. Exist with the expected fully-qualified name.
 *        b. Have every spec-required field declared as a non-nullable
 *           property with the same name.
 *        c. Not have extra non-spec properties that would indicate the
 *           Kotlin model has drifted ahead of the spec.
 *
 *      This catches drift the path-coverage check misses: e.g. spec
 *      adding a required field that the implementation never emits, or
 *      the implementation adding a field that consumers can't rely on
 *      because it's not in the spec.
 */

package com.datadatdat

import io.kotlintest.matchers.collections.shouldBeEmpty
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

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
                    "OpenAPI spec not found at ${specFile.absolutePath} or ${altFile.absolutePath}",
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
            val specNormalized =
                specEndpoints
                    .map { (method, path) -> Pair(method, normalizePath(path)) }
                    .toSet()

            val ktorNormalized =
                ktorRoutes
                    .map { (method, path) -> Pair(method, normalizePath(path)) }
                    .toSet()

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

        // Schema-shape validation: every components.schemas.X in the spec
        // must have a matching Kotlin data class. Required-by-spec fields
        // must be non-nullable Kotlin properties with the same name. Kotlin
        // properties that aren't in the spec are flagged so they don't
        // accidentally become part of the over-the-wire contract.
        "every OpenAPI schema has a matching Kotlin model class with required fields non-nullable" {
            val schemas = parseOpenApiSchemas()
            schemas.isEmpty() shouldBe false

            val problems = mutableListOf<String>()

            for ((schemaName, schema) in schemas) {
                val kotlinClass = resolveModelClass(schemaName)
                if (kotlinClass == null) {
                    problems += "spec schema '$schemaName' has no Kotlin model class at com.datadatdat.models"
                    continue
                }

                val kotlinProps = kotlinClass.memberProperties.associateBy { it.name }
                val specProps = schema.properties.keys

                // Every spec-required field must be a non-nullable Kotlin property.
                for (required in schema.required) {
                    val prop = kotlinProps[required]
                    if (prop == null) {
                        problems += "$schemaName: spec requires '$required' but the Kotlin model has no such property"
                    } else if (prop.returnType.isMarkedNullable) {
                        problems +=
                            "$schemaName: spec requires '$required' but the Kotlin property is nullable " +
                            "(serialized JSON could be null/absent and consumers would fail)"
                    }
                }

                // Every Kotlin property should correspond to a spec field. Extras
                // indicate the implementation has drifted ahead of the spec.
                for (propName in kotlinProps.keys) {
                    if (propName !in specProps) {
                        problems +=
                            "$schemaName: Kotlin property '$propName' is not declared in the spec " +
                            "(either add it to openapi/datadatdat.yml or remove it from the model)"
                    }
                }
            }

            if (problems.isNotEmpty()) {
                println("OpenAPI schema / Kotlin model drift detected:")
                problems.forEach { println("  $it") }
            }
            problems.shouldBeEmpty()
        }
    }

    // ------------------------------------------------------------------
    // Schema parsing + reflection helpers
    // ------------------------------------------------------------------

    private data class SpecSchema(
        val name: String,
        val required: Set<String>,
        val properties: Map<String, Any?>,
    )

    private fun parseOpenApiSchemas(): Map<String, SpecSchema> {
        val specFile =
            File("${System.getProperty("user.dir")}/../openapi/datadatdat.yml").takeIf { it.exists() }
                ?: File("openapi/datadatdat.yml").takeIf { it.exists() }
                ?: throw IllegalStateException("OpenAPI spec not found")

        @Suppress("UNCHECKED_CAST")
        val root = Yaml().load<Map<String, Any?>>(specFile.readText())

        @Suppress("UNCHECKED_CAST")
        val components =
            root["components"] as? Map<String, Any?>
                ?: throw IllegalStateException("spec has no components section")

        @Suppress("UNCHECKED_CAST")
        val schemas =
            components["schemas"] as? Map<String, Any?>
                ?: throw IllegalStateException("spec has no components.schemas section")

        return schemas.mapValues { (name, raw) ->
            @Suppress("UNCHECKED_CAST")
            val schema = raw as? Map<String, Any?> ?: emptyMap<String, Any?>()

            @Suppress("UNCHECKED_CAST")
            val required = (schema["required"] as? List<String>)?.toSet() ?: emptySet()

            @Suppress("UNCHECKED_CAST")
            val properties = (schema["properties"] as? Map<String, Any?>) ?: emptyMap()
            SpecSchema(name = name, required = required, properties = properties)
        }
    }

    /**
     * Maps an OpenAPI schema name to its Kotlin data class. The convention is
     * UpperCamelCase under `com.datadatdat.models`. Spec uses lowerCamelCase
     * (commit, repositoryStatus); Kotlin uses UpperCamelCase (Commit,
     * RepositoryStatus). The `apiError` schema maps to the `Error` model
     * class (renamed for Java compatibility — `ApiError` would collide with
     * the openapi-generator client's class on the consumer side).
     */
    private fun resolveModelClass(schemaName: String): KClass<*>? {
        val classNameMap =
            mapOf(
                "apiError" to "Error",
            )
        val className = classNameMap[schemaName] ?: schemaName.replaceFirstChar { it.uppercase() }
        return try {
            Class.forName("com.datadatdat.models.$className").kotlin
        } catch (e: ClassNotFoundException) {
            null
        }
    }
}
