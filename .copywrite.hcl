schema_version = 1

project {
  license          = "BUSL-1.1"
  copyright_holder = "Dit"
  copyright_year   = 2026

  header_ignore = [
    "gradlew",
    "gradlew.bat",
    "gradle/**",
    "build/**",
    "**/build/**",
    ".health/**",
    "**/*.out",
    # OpenAPI source spec (copied into the generated dit-client-go) and
    # logback config resources — not Dit source code needing a header.
    "openapi/dit.yml",
    "**/resources/logback*.xml",
  ]
}
