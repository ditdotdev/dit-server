import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    jacoco
    application
    id("com.gradleup.shadow") version("9.3.1")
}

val ditVersion: String by rootProject.extra
group = "dev.dit"
version = ditVersion

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo1.maven.org/maven2/")
    maven {
        name = "dit-maven"
        url = uri("s3://dit-maven")
        authentication {
            create<AwsImAuthentication>("awsIm")
        }
    }
}

val ktorVersion = "3.5.1"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.37")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.jetbrains.exposed:exposed-core:1.3.1")
    implementation("org.jetbrains.exposed:exposed-dao:1.3.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.3.1")
    implementation("org.jetbrains.exposed:exposed-java-time:1.3.1")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("joda-time:joda-time:2.14.2")
    implementation("io.kubernetes:client-java:27.0.0")
    implementation("io.kubernetes:client-java-api-fluent:27.0.0")
    implementation("dev.dit:command-executor:1.9.8")

    // Remote dependencies - conditionally included via composite build or skipped in CI
    // In CI environment these dependencies are not available, tests will be skipped
    try {
        implementation("dev.dit:remote-sdk:1.9.8")
        implementation("dev.dit:dit-remote-server:1.9.8")
        implementation("dev.dit:nop-remote-server:1.9.8")
        implementation("dev.dit:ssh-remote-server:1.9.8")
        implementation("dev.dit:s3-remote-server:1.9.8")
        implementation("dev.dit:s3web-remote-server:1.9.8")
    } catch (e: Exception) {
        // Remote dependencies not available (likely CI environment)
        println("Remote dependencies not available, will skip related tests")
    }

    testImplementation("com.h2database:h2:2.4.240")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.apache.commons:commons-text:1.15.0")
}

jacoco {
    toolVersion = "0.8.7"
}

tasks.test {
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio.file=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.net=ALL-UNNAMED"
    )
}

application {
    mainClass.set("dev.dit.ApplicationKt")
}

tasks.withType<ShadowJar> {
    archiveFileName.set("dit-server.jar")
    // Must set duplicatesStrategy for service files BEFORE mergeServiceFiles()
    // or the default EXCLUDE strategy will discard all but the first service file
    // See: https://gradleup.com/shadow/configuration/merging/#handling-duplicates-strategy
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
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

apply(from = "${project.projectDir}/gradle/ktlint.gradle.kts")
apply(from = "${project.projectDir}/gradle/unitTest.gradle")
apply(from = "${project.projectDir}/gradle/integrationTest.gradle")
apply(from = "${project.projectDir}/gradle/docker.gradle.kts")
apply(from = "${project.projectDir}/gradle/shell.gradle.kts")

// jacocoTestReport defaults to consuming only the `test` task's exec data. The
// apis/* handlers and most context.* glue are exercised exclusively by the
// integrationTest task (Ktor test-engine), so without this, those packages
// score 0% in the report even when tests are passing. Include both exec
// files in the report's executionData so coverage reflects what actually
// ran.
tasks.jacocoTestReport {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}
