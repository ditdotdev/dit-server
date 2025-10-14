val imageName = when(project.hasProperty("serverImageName")) {
    true -> project.property("serverImageName")
    false -> "datadatdat/datadatdat"
}

val datadatdatVersion = when(project.hasProperty("datadatdatVersion")) {
    true -> project.property("datadatdatVersion")
    false -> "latest"
}

var buildDockerServer = tasks.register<Exec>("buildDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build docker server image"
    commandLine("docker", "build", "--no-cache", "-t", "$imageName:$datadatdatVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    mustRunAfter(tasks.named("shadowJar"))
}

var publishDockerServer = tasks.register<Exec>("publishDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build and publish docker server image"
    commandLine("docker", "buildx", "build", "--platform", "linux/amd64,linux/arm64", "--push", "--no-cache", "-t", "$imageName:$datadatdatVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    mustRunAfter(tasks.named("shadowJar"))
}

// Convenience function that doesn't do --no-cache for quick rebuilds (at risk of potentially stale data
var rebuildDockerServer = tasks.register<Exec>("rebuildDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Build docker server image"
    commandLine("docker", "build", "-t", "$imageName:$datadatdatVersion", "-f", "${project.projectDir}/docker/server.Dockerfile", "${project.projectDir}")
    mustRunAfter(tasks.named("shadowJar"))
}

var tagDockerServer = tasks.register<Exec>("tagDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Tag docker server image with current version"
    commandLine("docker", "tag", "$imageName:$datadatdatVersion", "$imageName:${project.version}")
    mustRunAfter(tasks.named("buildDockerServer"))
}

var tagLocalDockerServer = tasks.register<Exec>("tagLocalDockerServer") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Tag docker server image with current version"
    commandLine("docker", "tag", "$imageName:$datadatdatVersion", "datadatdat:latest")
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
