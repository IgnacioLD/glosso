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

rootProject.name = "glosso"

include(":engine")
include(":server")

// The app modules need the Android SDK (and :shared, on Linux, has no other target).
// The server image builds with -Pglosso.android=false and has no SDK, so it leaves
// them out instead of configuring modules it can never build.
if (providers.gradleProperty("glosso.android").orNull?.toBoolean() != false) {
    include(":shared")
    include(":androidApp")
}
