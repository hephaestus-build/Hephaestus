pluginManagement {
    repositories {
        mavenCentral {
            // Plugin markers retain the verified metadata published by the Plugin Portal.
            content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "hephaestus-server"

include("generated-clients", "application")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
