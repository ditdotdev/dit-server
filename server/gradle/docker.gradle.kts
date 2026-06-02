val imageName = when(project.hasProperty("serverImageName")) {
    true -> project.property("serverImageName")
    false -> "ditdotdev/dit"
}

val ditVersion = when(project.hasProperty("ditVersion")) {
    true -> project.property("ditVersion")
    false -> "latest"
}

// Resolve GitHub token: use GO_MODULES_TOKEN if set, otherwise fall back to gh CLI auth
fun resolveGhToken(): String {
    val envToken = System.getenv("GO_MODULES_TOKEN")
    if (!envToken.isNullOrBlank()) return envToken
    return try {
        val process = ProcessBuilder("gh", "auth", "token").start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        ""
    }
}

var buildDockerServer = tasks.register<Exec>("buildDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build docker server image"
    environment("DOCKER_BUILDKIT", "1")
    environment("GO_MODULES_TOKEN", resolveGhToken())
    commandLine("docker", "build", "--secret", "id=gh_token,env=GO_MODULES_TOKEN", "-t", "$imageName:$ditVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    dependsOn(tasks.named("shadowJar"))
}

var publishDockerServer = tasks.register<Exec>("publishDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build and publish docker server image"
    environment("GO_MODULES_TOKEN", resolveGhToken())
    commandLine("docker", "buildx", "build", "--secret", "id=gh_token,env=GO_MODULES_TOKEN", "--platform", "linux/amd64,linux/arm64", "--push", "--no-cache", "-t", "$imageName:$ditVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    dependsOn(tasks.named("shadowJar"))
}

// Convenience function that doesn't do --no-cache for quick rebuilds (at risk of potentially stale data
var rebuildDockerServer = tasks.register<Exec>("rebuildDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build docker server image"
    environment("DOCKER_BUILDKIT", "1")
    environment("GO_MODULES_TOKEN", resolveGhToken())
    commandLine("docker", "build", "--secret", "id=gh_token,env=GO_MODULES_TOKEN", "-t", "$imageName:$ditVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    dependsOn(tasks.named("shadowJar"))
}

var tagDockerServer = tasks.register<Exec>("tagDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Tag docker server image with current version"
    commandLine("docker", "tag", "$imageName:$ditVersion", "$imageName:${project.version}")
    mustRunAfter(tasks.named("buildDockerServer"))
}

var tagLocalDockerServer = tasks.register<Exec>("tagLocalDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Tag docker server image with current version"
    commandLine("docker", "tag", "$imageName:$ditVersion", "dit:latest")
    mustRunAfter(tasks.named("buildDockerServer"))
}

tasks.named("assemble").configure {
    dependsOn(buildDockerServer)
    dependsOn(tagDockerServer)
    dependsOn(tagLocalDockerServer)
}

tasks.named("rebuild").configure {
    dependsOn(tasks.named("shadowJar"))
    dependsOn(rebuildDockerServer)
    dependsOn(tagLocalDockerServer)
}

var tagDockerLatest = tasks.register<Exec>("tagDockerLatest") {
    group = "Publishing"
    description = "Tag docker server image as latest"
    commandLine("docker", "tag", "$imageName:${project.version}", "$imageName:latest")
    mustRunAfter(tasks.named("tagDockerServer"))
}

var publishDockerVersion = tasks.register<Exec>("publishDockerVersion") {
    group = "Publishing"
    description = "Publish versioned docker server image to docker hub"
    commandLine("docker", "push", "$imageName:${project.version}")
    mustRunAfter(tasks.named("tagDockerServer"))
}

var publishDockerLatest = tasks.register<Exec>("publishDockerLatest") {
    group = "Publishing"
    description = "Publish latest docker server image to docker hub"
    commandLine("docker", "push", "$imageName:latest")
    mustRunAfter(tasks.named("tagDockerLatest"))
}

tasks.named("publish").configure {
    dependsOn(publishDockerVersion)
    dependsOn(tagDockerLatest)
    dependsOn(publishDockerLatest)
}
