import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.54.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.github.ben-manes.versions") version("0.54.0")
}

val ditVersion by extra(when(project.hasProperty("ditVersion")) {
    true -> project.property("ditVersion")
    false -> "latest"
})

tasks.register("check")

allprojects {

    apply(plugin = "com.github.ben-manes.versions")

    val ktlint by configurations.creating

    dependencies {
        ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    val styleTask =
        tasks.register("style") {
            group = "Verification"
            description = "Run all style checks"
        }

    // ktlint is only meaningful in projects that actually carry Kotlin
    // sources. Registering it on the bare root project would point at a
    // non-existent `src/` and either (a) noop silently or (b) fail when the
    // glob resolves to nothing, depending on ktlint-cli version. Gate
    // registration on the project actually having a src/ directory.
    if (file("src").isDirectory) {
        val ktlintTask =
            tasks.register<JavaExec>("ktlint") {
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

        // Make `style` and `check` actually do something: depend on ktlint.
        styleTask.configure { dependsOn(ktlintTask) }
        afterEvaluate { tasks.findByName("check")?.dependsOn(ktlintTask) }
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
