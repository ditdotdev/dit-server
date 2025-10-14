import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    }
}

plugins {
    id("com.github.ben-manes.versions") version("0.27.0")
}

val datadatdatVersion by extra(when(project.hasProperty("datadatdatVersion")) {
    true -> project.property("datadatdatVersion")
    false -> "latest"
})

tasks.register("check")

allprojects {

    apply(plugin = "com.github.ben-manes.versions")

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            allWarningsAsErrors.set(false)  // Temporarily disabled for Exposed 0.32.1 upgrade
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    tasks.register("style") {
        group = "Verification"
        description = "Run all style checks"
    }

    tasks.withType<DependencyUpdatesTask>().configureEach {
        resolutionStrategy {
            componentSelection {
                all {
                    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
                        candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                    }
                    if (rejected) {
                        reject("Release candidate")
                    }
                }
            }
        }
    }
}
