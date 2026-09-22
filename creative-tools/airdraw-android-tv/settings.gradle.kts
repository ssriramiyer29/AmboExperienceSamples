// A standalone Gradle build, deliberately. It shares nothing with the AEP platform repository -
// not a settings file, not a version catalogue, not a source module. That is what makes "an
// external developer can build this using only published artifacts" testable rather than
// aspirational.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "airdraw-android-tv"
include(":core")
include(":app")
