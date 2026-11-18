// The Kotlin client of `vp run gate:client-generators`. The gate generates the client into generated/
// and copies the fixtures into src/test/resources/fixtures, then runs the tests.
// The same plugin repositories as server/settings.gradle.kts, so the cache producer and every job
// resolve the markers from one source.
pluginManagement {
    repositories {
        mavenCentral {
            // Plugin markers retain the verified metadata published by the Plugin Portal.
            content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "hephaestus-api"
