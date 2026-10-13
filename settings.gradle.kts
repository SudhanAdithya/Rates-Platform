rootProject.name = "rates-platform"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Create modules only when the current milestone needs them (CLAUDE.md).
include("schemas", "common", "venue-sim", "bench")
