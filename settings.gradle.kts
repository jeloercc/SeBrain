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

rootProject.name = "sebrain"
include(":core:vault")
include(":core:crypto")
include(":ingest")
include(":cognition")
include(":app")
include(":core:ml")
