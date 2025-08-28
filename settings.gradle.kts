rootProject.name = "titan-server"

include("server")

// Temporarily disabled composite builds to use published S3 dependencies for test compilation
// The composite build was causing dependency resolution conflicts where all remote servers
// were resolving to s3-remote:server instead of their individual projects

// includeBuild("../remote-sdk") {
//     dependencySubstitution {
//         substitute(module("io.titandata:remote-sdk")).with(project(":"))
//     }
// }

// includeBuild("../command-executor") {
//     dependencySubstitution {
//         substitute(module("io.titandata:command-executor")).with(project(":"))
//     }
// }

// // Multi-module remote projects - include their server modules  
// includeBuild("../nop-remote") {
//     dependencySubstitution {
//         substitute(module("io.titandata:nop-remote-server")).with(project(":server"))
//     }
// }

// includeBuild("../ssh-remote") {
//     dependencySubstitution {
//         substitute(module("io.titandata:ssh-remote-server")).with(project(":server"))
//     }
// }

// includeBuild("../s3-remote") {
//     dependencySubstitution {
//         substitute(module("io.titandata:s3-remote-server")).with(project(":server"))
//     }
// }

// includeBuild("../s3web-remote") {
//     dependencySubstitution {
//         substitute(module("io.titandata:s3web-remote-server")).with(project(":server"))
//     }
// }
