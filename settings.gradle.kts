// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

rootProject.name = "dit-server"

include("server")

// Temporarily disabled composite builds to use published S3 dependencies for test compilation
// The composite build was causing dependency resolution conflicts where all remote servers
// were resolving to s3-remote:server instead of their individual projects

// includeBuild("../remote-sdk") {
//     dependencySubstitution {
//         substitute(module("dev.dit:remote-sdk")).using(project(":"))
//     }
// }

// includeBuild("../command-executor") {
//     dependencySubstitution {
//         substitute(module("dev.dit:command-executor")).using(project(":"))
//     }
// }

// // Multi-module remote projects - include their server modules
// includeBuild("../nop-remote") {
//     dependencySubstitution {
//         substitute(module("dev.dit:nop-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../ssh-remote") {
//     dependencySubstitution {
//         substitute(module("dev.dit:ssh-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../s3-remote") {
//     dependencySubstitution {
//         substitute(module("dev.dit:s3-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../s3web-remote") {
//     dependencySubstitution {
//         substitute(module("dev.dit:s3web-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../dit-remote") {
//     dependencySubstitution {
//         substitute(module("dev.dit:dit-remote-server")).using(project(":server"))
//         substitute(module("dev.dit:dit-remote-client")).using(project(":client"))
//     }
// }
