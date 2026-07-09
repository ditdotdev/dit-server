// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

var batsVersion = "v1.1.0"

var testShell = tasks.register<Exec>("testShell") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compile ZFS from default kernel version"
    var args = mutableListOf("docker", "run", "--rm", "-v", "${project.projectDir}:/test",
            "bats/bats:${batsVersion}")
    for (file in fileTree("${project.projectDir}/src/scripts-test")) {
        if (file.isFile()) {
            args.add("/test/src/scripts-test/${file.name}")
        }
    }
    commandLine(args)
    
    // Skip shell tests in CI environments where ZFS modules are not available
    onlyIf {
        val isCI = System.getenv("CI") != null
        val skipShellTests = System.getProperty("skip.shell.tests") == "true"
        !isCI && !skipShellTests
    }
}

tasks.named("check").configure {
    dependsOn(testShell)
}
