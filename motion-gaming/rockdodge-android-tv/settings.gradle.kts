// This sample is a standalone Gradle build, deliberately. It shares nothing with the AEP
// platform repository - not a settings file, not a version catalogue, not a source module.
// That is what makes "an external developer can build this using only published artifacts"
// a testable claim rather than an aspiration.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "rockdodge-android-tv"
include(":core")
include(":app")
