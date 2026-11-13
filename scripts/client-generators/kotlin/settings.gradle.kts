// The Kotlin client of `vp run gate:client-generators`. The gate generates the client into generated/
// and copies the fixtures into src/test/resources/fixtures, then runs the tests.
pluginManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "hephaestus-api"
