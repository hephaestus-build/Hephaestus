// The versions are those that OpenAPI Generator writes into the build of a `jvm-okhttp4` client.
plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        kotlin.srcDir("generated/src/main/kotlin")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// cache-gradle.yml runs it, so the jobs that run this build restore its plugins and dependencies.
tasks.register("resolveDependencies") {
    description = "Resolves every resolvable configuration."
    inputs.files(configurations.matching { it.isCanBeResolved })
    doLast {}
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
