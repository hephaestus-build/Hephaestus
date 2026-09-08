import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

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
        options.encoding = "UTF-8"
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4g"
        options.release.set(javaVersion)
    }
    dependencyLocking { lockAllConfigurations() }
}

tasks.named("check") { dependsOn(":application:check") }

spotless {
    kotlinGradle {
        target("*.gradle.kts", "application/*.gradle.kts", "generated-clients/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
