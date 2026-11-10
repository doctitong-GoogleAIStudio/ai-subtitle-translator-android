pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "subtitle-translator-android"
include(":app")
// Google Play build only (`./gradlew bundleRelease -Pplay`): the models go into an
// install-time asset pack, because Play's base module is limited to 200 MB.
if (providers.gradleProperty("play").isPresent) include(":models")
