import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    jacoco
    application
    id("com.github.johnrengelman.shadow") version("6.0.0")
}

val titanVersion: String by rootProject.extra
group = "io.titandata"
version = titanVersion

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo1.maven.org/maven2/")
}

val ktorVersion = "1.3.1"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-gson:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.3.1")
    implementation("org.jetbrains.exposed:exposed-core:0.32.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.32.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.32.1")
    implementation("org.postgresql:postgresql:42.2.10")
    implementation("com.zaxxer:HikariCP:3.4.2")
    implementation("io.kubernetes:client-java:11.0.0")

    // Remotes - commented out temporarily for build fix
    // implementation("io.titandata:remote-sdk:0.2.0")
    // implementation("io.titandata:nop-remote-server:0.2.0")
    // implementation("io.titandata:ssh-remote-server:0.2.1")
    // implementation("io.titandata:s3-remote-server:0.2.0")
    // implementation("io.titandata:s3web-remote-server:0.2.0")

    testImplementation("com.h2database:h2:1.4.200")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("org.apache.commons:commons-text:1.8")
}

jacoco {
    toolVersion = "0.8.7"
}

application {
    mainClassName = "io.titandata.ApplicationKt"
}

tasks.withType<ShadowJar> {
    archiveFileName.set("titan-server.jar")
    mergeServiceFiles()
}

tasks.register("rebuild") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Fast rebuild of docker image"
}

tasks.register("publish") {
    group = "Publishing"
    description = "Publish build artifacts"
}

apply{ from("${project.projectDir}/gradle/ktlint.gradle.kts") }
apply{ from("${project.projectDir}/gradle/unitTest.gradle") }
apply{ from("${project.projectDir}/gradle/integrationTest.gradle") }
apply{ from("${project.projectDir}/gradle/docker.gradle.kts") }
apply{ from("${project.projectDir}/gradle/shell.gradle.kts") }
