import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

// Two build plugins put vulnerable libraries on the script classpath, and both are at their latest
// release: the GraphQL codegen plugin brings `graphql-java` 20.2 (fixed in 20.9), and the OpenAPI
// Generator brings `handlebars` 4.3.1 (fixed in 4.5.2). Neither reaches a shipped image — they run
// only while generating clients — but they are the repository's only open Dependabot alerts. Each
// is
// forced inside the line its plugin compiled against, so the API those plugins call does not move.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("com.graphql-java:graphql-java:20.9")
            force("com.github.jknack:handlebars:4.5.2")
        }
        // 4.5.2 offers a Nashorn-backed helper and the ASM tree it needs. The generation configured
        // here renders Mustache and FreeMarker templates, registers no JavaScript helper and
        // precompiles nothing, so the patch is taken without the engine: ten artifacts would
        // otherwise join the build classpath to fix one advisory.
        exclude(group = "org.openjdk.nashorn")
        exclude(group = "org.ow2.asm")
    }
}

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.graphql.codegen) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.pitest) apply false
}

val javaVersion = file("../.java-version").readText().trim().toInt()
val bootVersion = libs.versions.spring.boot.get()
val managedOverrides =
    mapOf(
        "netty.version" to libs.versions.netty.get(),
        "tomcat.version" to libs.versions.tomcat.get(),
        "spring-modulith.version" to libs.versions.spring.modulith.get(),
        "liquibase.version" to libs.versions.liquibase.asProvider().get(),
    )

subprojects {
    group = "de.tum.cit.aet"
    version = "0.0.0-development"
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    managedOverrides.forEach { (key, value) -> extra[key] = value }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$bootVersion") {
                // Keep Boot's coordinated families together, including ahead-of-BOM security fixes.
                bomProperties(managedOverrides)
            }
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        // Extraction must observe javac, but generated source outputs remain safe to restore.
        if (providers.gradleProperty("codeqlExtraction").orNull == "true") {
            outputs.cacheIf { false }
        }
        options.encoding = "UTF-8"
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4g"
        options.release.set(javaVersion)
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

tasks.named("check") { dependsOn(":application:check") }

// Renovate uses the root dependency report to refresh verification metadata.
tasks.named("dependencies") { dependsOn(subprojects.map { "${it.path}:dependencies" }) }

spotless {
    kotlinGradle {
        target("*.gradle.kts", "application/*.gradle.kts", "generated-clients/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
