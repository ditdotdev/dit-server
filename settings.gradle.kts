rootProject.name = "datadatdat-server"

include("server")

// Temporarily disabled composite builds to use published S3 dependencies for test compilation
// The composite build was causing dependency resolution conflicts where all remote servers
// were resolving to s3-remote:server instead of their individual projects

// includeBuild("../remote-sdk") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:remote-sdk")).using(project(":"))
//     }
// }

// includeBuild("../command-executor") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:command-executor")).using(project(":"))
//     }
// }

// // Multi-module remote projects - include their server modules
// includeBuild("../nop-remote") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:nop-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../ssh-remote") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:ssh-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../s3-remote") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:s3-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../s3web-remote") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:s3web-remote-server")).using(project(":server"))
//     }
// }

// includeBuild("../datadatdat-remote") {
//     dependencySubstitution {
//         substitute(module("com.datadatdat:datadatdat-remote-server")).using(project(":server"))
//         substitute(module("com.datadatdat:datadatdat-remote-client")).using(project(":client"))
//     }
// }
