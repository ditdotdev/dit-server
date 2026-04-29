import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    jacoco
    application
    id("com.gradleup.shadow") version("9.3.1")
}

val datadatdatVersion: String by rootProject.extra
group = "com.datadatdat"
version = datadatdatVersion

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo1.maven.org/maven2/")
    maven {
        name = "datadatdat-maven"
        url = uri("s3://datadatdat-maven")
        authentication {
            create<AwsImAuthentication>("awsIm")
        }
    }
}

val ktorVersion = "3.4.3"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.exposed:exposed-core:1.2.0")
    implementation("org.jetbrains.exposed:exposed-dao:1.2.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.2.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.2.0")
    implementation("org.postgresql:postgresql:42.7.10")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("joda-time:joda-time:2.14.2")
    implementation("io.kubernetes:client-java:26.0.0")
    implementation("io.kubernetes:client-java-api-fluent:26.0.0")
    implementation("com.datadatdat:command-executor:1.8.7")

    // Remote dependencies - conditionally included via composite build or skipped in CI
    // In CI environment these dependencies are not available, tests will be skipped
    try {
        implementation("com.datadatdat:remote-sdk:1.8.7")
        implementation("com.datadatdat:datadatdat-remote-server:1.8.7")
        implementation("com.datadatdat:nop-remote-server:1.8.7")
        implementation("com.datadatdat:ssh-remote-server:1.8.7")
        implementation("com.datadatdat:s3-remote-server:1.8.7")
        implementation("com.datadatdat:s3web-remote-server:1.8.7")
    } catch (e: Exception) {
        // Remote dependencies not available (likely CI environment)
        println("Remote dependencies not available, will skip related tests")
    }

    testImplementation("com.h2database:h2:2.4.240")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.14.9")
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
    mainClass.set("com.datadatdat.ApplicationKt")
}

tasks.withType<ShadowJar> {
    archiveFileName.set("datadatdat-server.jar")
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
