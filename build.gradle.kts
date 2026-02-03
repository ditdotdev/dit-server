import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
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

    val ktlint by configurations.creating

    dependencies {
        ktlint("com.pinterest.ktlint:ktlint-cli:1.7.1")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(false)  // Temporarily disabled for Exposed 0.32.1 upgrade
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    tasks.register("style") {
        group = "Verification"
        description = "Run all style checks"
    }

    // Enable ktlint checks and formatting
    val ktlintTask = tasks.register<JavaExec>("ktlint") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Check Kotlin code style"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("src/**/*.kt")
    }

    tasks.register<JavaExec>("ktlintFormat") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Fix Kotlin code style deviations"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("-F", "src/**/*.kt")
    }

    // Run ktlint as part of the check task (if it exists)
    afterEvaluate {
        tasks.findByName("check")?.dependsOn(ktlintTask)
    }

    tasks.withType<DependencyUpdatesTask>().configureEach {
        resolutionStrategy {
            componentSelection {
                all { selection: ComponentSelection ->
                    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
                        selection.candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                    }
                    if (rejected) {
                        selection.reject("Release candidate")
                    }
                }
            }
        }
    }
}
