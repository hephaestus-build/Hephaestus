import java.time.Duration
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    java
    jacoco
    pmd
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.pitest)
}

dependencies {
    implementation(platform(libs.okhttp.bom))
    implementation(project(":generated-clients"))
    implementation(libs.jspecify)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.context.propagation)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.caffeine)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.restclient)
    implementation(libs.java.jwt)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.webclient)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.spring.boot.starter.logging)
    developmentOnly(libs.spring.boot.docker.compose)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.aspectj)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    compileOnly(libs.lombok)
    implementation(libs.spring.modulith.starter.core)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.webtestclient)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    implementation(libs.therapi.runtime.javadoc)
    implementation(libs.jnats)
    implementation(libs.docker.java.api)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)
    implementation(libs.bolt) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    implementation(libs.okhttp.jvm)
    implementation(libs.logging.interceptor)
    testImplementation(libs.mockwebserver3)
    implementation(libs.jakarta.ws.rs.api)
    implementation(libs.jakarta.annotation.api)
    implementation(libs.hibernate.validator)
    implementation(libs.sentry)
    implementation(libs.spring.boot.starter.graphql)
    implementation(libs.resilience4j.spring.boot4)
    implementation(libs.resilience4j.micrometer)
    implementation(libs.org.eclipse.jgit)
    implementation(libs.bucket4j.jdk17.core)
    implementation(libs.bucket4j.jdk17.postgresql)
    annotationProcessor(libs.lombok)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor(libs.therapi.scribe)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testAnnotationProcessor(libs.therapi.scribe)
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.postgresql)
    constraints {
        implementation(libs.archunit.core)
        implementation(libs.bcprov)
        implementation(libs.bouncycastle.lts)
        implementation(libs.bcutil)
        implementation(libs.bcpkix)
    }
}

tasks.processTestResources {
    from(rootProject.file("../docs/db/archive/v0.77.4")) { into("db") }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        // Spring, JPA and JUnit annotations are runtime metadata, not processor inputs. Javac's
        // processing lint reports every unclaimed runtime annotation despite successful processing.
        listOf("-Xlint:all,-processing", "-XDaddTypeAnnotationsToSymbol=true")
    )
    options.errorprone {
        disableAllChecks.set(true)
        error("NullAway", "RequireExplicitNullMarking")
        option("NullAway:AnnotatedPackages", "de.tum.cit.aet.hephaestus")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:TreatGeneratedAsUnannotated", "true")
        option("NullAway:HandleTestAssertionLibraries", "true")
        excludedPaths.set(".*/build/generated/.*")
    }
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
    }
}

pmd {
    toolVersion = libs.versions.pmd.get()
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("pmd-ruleset.xml"))
    isConsoleOutput = true
}

tasks.withType<Pmd>().configureEach {
    // Resolve platform types against the same JDK as the analysis task, not an implicit host JDK.
    classpath =
        files(classpath, javaLauncher.map { it.metadata.installationPath.file("lib/jrt-fs.jar") })
    reports.xml.required.set(true)
    val reportFile = reports.xml.outputLocation
    // Gradle counts rule violations, but PMD's recoverable analysis errors do not fail its task.
    doLast {
        val document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
                .parse(reportFile.get().asFile)
        check(
            document.getElementsByTagName("error").length == 0 &&
                document.getElementsByTagName("configerror").length == 0
        ) {
            "PMD could not analyze all sources. See ${reportFile.get().asFile}"
        }
    }
}

tasks.named("pmdTest") { enabled = false }

tasks.named("dependencies") {
    // PMD creates its auxiliary configuration lazily; lock updates must include that graph too.
    tasks.named("pmdMain").get()
}

jacoco { toolVersion = libs.versions.jacoco.get() }

val profileTests = providers.gradleProperty("profileTests").map(String::toBoolean).orElse(false)
val testSelection = providers.gradleProperty("testSelection").map(String::toBoolean).orElse(false)
val testJvmArgs = providers.gradleProperty("testJvmArgs").orElse("")
val integrationShard = providers.environmentVariable("HEPHAESTUS_INTEGRATION_SHARD").orElse("")
val packagedServer = providers.gradleProperty("packagedServer").map(String::toBoolean).orElse(false)
val testInventories =
    mapOf(
        "testInventory" to "",
        "integrationProvidersInventory" to "providers-and-startup",
        "integrationApplicationInventory" to "application",
    )

tasks.withType<Test>().configureEach {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    if (testSelection.get() || name in testInventories) {
        dryRun.set(true)
        val selectionDirectory = layout.buildDirectory.dir("test-selection/$name")
        reports.junitXml.outputLocation.set(selectionDirectory.map { it.dir("xml") })
        reports.html.outputLocation.set(selectionDirectory.map { it.dir("html") })
        binaryResultsDirectory.set(selectionDirectory.map { it.dir("binary") })
    }
    maxHeapSize = "4g"
    maxParallelForks = 1
    timeout.set(Duration.ofMinutes(20))
    systemProperty("spring.profiles.active", "test")
    systemProperty("file.encoding", "UTF-8")
    environment("MANAGEMENT_PORT", "0")
    environment("SERVER_PORT", "0")
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-XX:+ExitOnOutOfMemoryError")
    if (profileTests.get()) {
        jvmArgs(
            "-XX:StartFlightRecording=filename=${layout.buildDirectory.file("$name-profile.jfr").get().asFile},settings=profile,dumponexit=true",
            "-Xlog:jfr+startup=off",
        )
        systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
    }
    jvmArgs(testJvmArgs.get().split(" ").filter(String::isNotBlank))
    testLogging {
        showStandardStreams = profileTests.get()
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    doNotTrackState("Tests depend on containers, environment and mutable service state")
    extensions.configure<JacocoTaskExtension> { isEnabled = name == "test" && !testSelection.get() }
}

tasks.test {
    useJUnitPlatform {
        includeTags("unit")
        excludeTags("live")
    }
    if (!testSelection.get()) finalizedBy("jacocoTestReport")
}

fun Test.selectIntegrationShard(shard: String) {
    val providerTests =
        listOf(
            "de.tum.cit.aet.hephaestus.integration.*",
            "de.tum.cit.aet.hephaestus.StartupBudgetIntegrationTest",
        )
    when (shard) {
        "" -> Unit
        "providers-and-startup" -> filter { providerTests.forEach(::includeTestsMatching) }
        "application" -> filter { providerTests.forEach(::excludeTestsMatching) }
        else -> error("Unknown integration shard: $shard")
    }
}

for ((taskName, tag) in
    mapOf(
        "architectureTest" to "architecture",
        "integrationTest" to "integration",
        "databaseTest" to "database",
        "liveTest" to "live",
    )) {
    tasks.register<Test>(taskName) {
        description = "Runs the $tag test tier."
        group = "verification"
        useJUnitPlatform {
            includeTags(tag)
            if (tag != "live") excludeTags("live")
        }
        shouldRunAfter(tasks.test)
        if (tag == "integration") selectIntegrationShard(integrationShard.get())
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "PACKAGE"
            includes =
                listOf(
                    "de.tum.cit.aet.hephaestus.integration.core.webhook",
                    "de.tum.cit.aet.hephaestus.integration.scm.github.webhook",
                    "de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook",
                    "de.tum.cit.aet.hephaestus.integration.outline.webhook",
                    "de.tum.cit.aet.hephaestus.agent.context",
                    "de.tum.cit.aet.hephaestus.agent.context.providers",
                )
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes =
                listOf(
                    "de.tum.cit.aet.hephaestus.integration.slack.webhook",
                    "de.tum.cit.aet.hephaestus.evidence",
                    "de.tum.cit.aet.hephaestus.evidence.internal",
                )
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.register("verification") {
    group = "verification"
    dependsOn(tasks.test, "architectureTest", tasks.jacocoTestCoverageVerification)
}

tasks.check { dependsOn("verification") }

tasks.bootJar { archiveBaseName.set("hephaestus-application") }

tasks.jar { enabled = false }

tasks.bootRun {
    args("--spring.profiles.active=${providers.gradleProperty("profiles").getOrElse("local")}")
    jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-native-access=ALL-UNNAMED")
}

pitest {
    pitestVersion.set(libs.versions.pitest.asProvider().get())
    junit5PluginVersion.set(libs.versions.pitest.junit5.get())
    threads.set(1)
    timeoutConstInMillis.set(30000)
    jvmArgs.set(listOf("-Xmx1g", "-XX:+EnableDynamicAgentLoading"))
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    failWhenNoMutations.set(true)
    targetClasses.set(
        setOf(
            "de.tum.cit.aet.hephaestus.core.security.ServerUrlValidator",
            "de.tum.cit.aet.hephaestus.core.security.PrivateAddressGuard",
            "de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy",
            "de.tum.cit.aet.hephaestus.core.security.ImpersonationGuard",
            "de.tum.cit.aet.hephaestus.integration.core.oauth.state.HmacOAuthStateService",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.AdminBootstrapPolicy",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.ReturnToValidator",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.AuthIntentCookie",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.CookieOAuth2AuthorizationRequestRepository",
            "de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext",
            "de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabWebhookSignatureVerifier",
        )
    )
    targetTests.set(
        setOf(
            "de.tum.cit.aet.hephaestus.core.security.ServerUrlValidatorTest",
            "de.tum.cit.aet.hephaestus.core.security.PrivateAddressGuardTest",
            "de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicyTest",
            "de.tum.cit.aet.hephaestus.core.security.ImpersonationGuardTest",
            "de.tum.cit.aet.hephaestus.integration.core.oauth.state.HmacOAuthStateServiceTest",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.AdminBootstrapPolicyTest",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.ReturnToValidatorTest",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.AuthIntentCookieTest",
            "de.tum.cit.aet.hephaestus.core.auth.oauth.CookieOAuth2AuthorizationRequestRepositoryTest",
            "de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContextTest",
            "de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabWebhookSignatureVerifierTest",
        )
    )
}

val liquibaseTools = configurations.create("liquibaseTools")
val compiledMain =
    files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("resources/main"),
    )

dependencies {
    add(
        liquibaseTools.name,
        libs.liquibase.core,
    )
    add(
        liquibaseTools.name,
        libs.liquibase.hibernate7,
    )
    add(liquibaseTools.name, libs.picocli)
}

val databaseHost = providers.environmentVariable("POSTGRES_HOST").orElse("localhost")
val databasePort =
    providers
        .gradleProperty("postgresPort")
        .orElse(providers.environmentVariable("POSTGRES_PORT"))
        .orElse("5432")
val databaseName = providers.environmentVariable("POSTGRES_DB").orElse("hephaestus")
val databaseUser = providers.environmentVariable("POSTGRES_USER").orElse("root")
val databasePassword = providers.environmentVariable("POSTGRES_PASSWORD").orElse("root")

for ((taskName, command) in
    mapOf("liquibaseUpdate" to "update", "liquibaseDiff" to "diff-changelog")) {
    tasks.register<JavaExec>(taskName) {
        group = "database"
        description = "Runs Liquibase $command against PostgreSQL."
        mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        if (packagedServer.get()) {
            classpath =
                files(compiledMain, configurations.runtimeClasspath.get().files, liquibaseTools)
        } else {
            classpath =
                files(sourceSets.main.get().output, configurations.runtimeClasspath, liquibaseTools)
        }
        environment("LIQUIBASE_COMMAND_PASSWORD", databasePassword.get())
        args(
            "--search-path=${file("src/main/resources")}",
            command,
            "--url=jdbc:postgresql://${databaseHost.get()}:${databasePort.get()}/${databaseName.get()}",
            "--username=${databaseUser.get()}",
        )
        if (command == "update") {
            args("--changelog-file=db/master.xml")
        } else {
            mustRunAfter("liquibaseUpdate")
            args(
                "--changelog-file=${layout.buildDirectory.file("changelog_new.xml").get().asFile}",
                "--reference-url=hibernate:spring:de.tum.cit.aet.hephaestus?dialect=org.hibernate.dialect.PostgreSQLDialect&hibernate.physical_naming_strategy=org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy&hibernate.implicit_naming_strategy=org.springframework.boot.hibernate.SpringImplicitNamingStrategy",
                // Hibernate cannot emit unmapped tables, partitions, or scalar-id foreign keys.
                "--exclude-objects=table:shedlock,table:auth_rate_limit_bucket,table:auth_event_default,table:auth_event_p\\d+,foreignkey:sfk_.*",
            )
        }
    }
}

// Consumer jobs execute the package job's compiled test artifact; no compilation is scheduled.
if (packagedServer.get()) {
    tasks.named<Test>("databaseTest") {
        testClassesDirs = files(layout.buildDirectory.dir("classes/java/test"))
        classpath =
            files(
                testClassesDirs,
                compiledMain,
                layout.buildDirectory.dir("resources/test"),
                configurations.testRuntimeClasspath.get().files,
            )
    }
}

// Separate reports let one invocation prove tier coverage and disjoint shards without overwriting
// results.
for ((taskName, shard) in testInventories) {
    tasks.register<Test>(taskName) {
        group = "verification"
        description = "Discovers ${shard.ifEmpty { "all non-live" }} tests without executing them."
        useJUnitPlatform {
            excludeTags("live")
            if (shard.isNotEmpty()) includeTags("integration")
        }
        selectIntegrationShard(shard)
    }
}
