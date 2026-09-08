import com.kobylynskyi.graphql.codegen.model.GeneratedLanguage
import io.github.kobylynskyi.graphql.codegen.gradle.GraphQLCodegenGradleTask
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    `java-library`
    alias(libs.plugins.graphql.codegen)
    alias(libs.plugins.openapi.generator)
}

dependencies {
    api(libs.graphql.java.codegen)
    api(libs.jackson.annotations)
    api(libs.jakarta.annotation.api)
}

tasks.withType<GraphQLCodegenGradleTask>().configureEach {
    generateClient = true
    generateApis = false
    generateDataFetchingEnvironmentArgumentInApis = false
    generateBuilder = true
    generateEqualsAndHashCode = true
    generateToString = true
    generateParameterizedFieldsResolvers = false
    generateModelsForRootTypes = false
    generateAllMethodInProjection = true
    generatedLanguage = GeneratedLanguage.JAVA
    addGeneratedAnnotation = true
    generatedAnnotation = "jakarta.annotation.Generated"
    modelValidationAnnotation = "@jakarta.annotation.Generated(\"graphql-codegen\")"
    modelNameSuffix = ""
    requestSuffix = "Request"
    responseSuffix = "Response"
    responseProjectionSuffix = "ResponseProjection"
    customTemplatesRoot = file("src/main/resources/graphql/templates")
    customTemplates = hashMapOf("RESPONSE_PROJECTION" to "response_projection.ftl")

    // Clear only this task's output so removed schema types cannot survive.
    doFirst {
        check(!outputDir.exists() || outputDir.deleteRecursively()) {
            "Cannot clear generated output: $outputDir"
        }
    }
}

val generateGithub =
    tasks.register<GraphQLCodegenGradleTask>("generateGithub") {
        // Query.relay returns Query, so its client response needs the root model.
        generateModelsForRootTypes = true
        graphqlSchemaPaths =
            listOf(file("src/main/resources/graphql/github/schema.github.graphql").absolutePath)
        outputDir = layout.buildDirectory.dir("generated/sources/github").get().asFile
        packageName = "de.tum.cit.aet.hephaestus.integration.scm.github.graphql"
        apiPackageName = "de.tum.cit.aet.hephaestus.integration.scm.github.graphql.api"
        modelPackageName = "de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model"
        modelNamePrefix = "GH"
        fieldsWithoutResolvers = hashSetOf("node", "nodes", "relay")
        customTypesMapping =
            hashMapOf(
                "DateTime" to "java.time.OffsetDateTime",
                "Date" to "java.time.LocalDate",
                "URI" to "java.net.URI",
                "HTML" to "java.lang.String",
                "GitObjectID" to "java.lang.String",
                "GitSSHRemote" to "java.lang.String",
                "GitTimestamp" to "java.time.OffsetDateTime",
                "PreciseDateTime" to "java.time.OffsetDateTime",
                "X509Certificate" to "java.lang.String",
                "Base64String" to "java.lang.String",
                "BigInt" to "java.math.BigInteger",
            )
    }

val generateGitlab =
    tasks.register<GraphQLCodegenGradleTask>("generateGitlab") {
        graphqlSchemaPaths =
            listOf(file("src/main/resources/graphql/gitlab/schema.gitlab.graphql").absolutePath)
        outputDir = layout.buildDirectory.dir("generated/sources/gitlab").get().asFile
        packageName = "de.tum.cit.aet.hephaestus.integration.scm.gitlab.graphql"
        apiPackageName = "de.tum.cit.aet.hephaestus.integration.scm.gitlab.graphql.api"
        modelPackageName = "de.tum.cit.aet.hephaestus.integration.scm.gitlab.graphql.model"
        modelNamePrefix = "GL"
        setGenerateNoArgsConstructorOnly(true)
        customTypesMapping =
            hashMapOf(
                "Time" to "java.time.OffsetDateTime",
                "ISO8601DateTime" to "java.time.OffsetDateTime",
                "ISO8601Date" to "java.time.LocalDate",
                "GlobalID" to "java.lang.String",
                "JSON" to "java.lang.Object",
                "UntrustedRegexp" to "java.lang.String",
                "Duration" to "java.lang.String",
                "BigInt" to "java.math.BigInteger",
                "TodoableID" to "java.lang.String",
                "UserID" to "java.lang.String",
                "IterationID" to "java.lang.String",
                "MilestoneID" to "java.lang.String",
                "WorkItemID" to "java.lang.String",
                "IssueID" to "java.lang.String",
                "ProjectID" to "java.lang.String",
                "GroupID" to "java.lang.String",
                "LabelsFilterType" to "java.lang.String",
                "NegatedIterationWildcardId" to "java.lang.String",
                "Upload" to "java.lang.String",
                "Color" to "java.lang.String",
                "PaymentMethodID" to "java.lang.String",
                "NamespaceID" to "java.lang.String",
                "AuditEventsStreamingHeadersID" to "java.lang.String",
                "AuditEventsExternalAuditEventDestinationID" to "java.lang.String",
                "GoogleCloudLoggingConfigurationID" to "java.lang.String",
                "AmazonS3ConfigurationID" to "java.lang.String",
                "InstanceAuditEventsStreamingHeadersID" to "java.lang.String",
                "InstanceExternalAuditEventDestinationID" to "java.lang.String",
                "InstanceGoogleCloudLoggingConfigurationID" to "java.lang.String",
                "InstanceAmazonS3ConfigurationID" to "java.lang.String",
                "JsonString" to "java.lang.String",
            )
    }

tasks.withType<GenerateTask>().configureEach {
    generatorName.set("java")
    library.set("webclient")
    modelPackage.set("de.tum.cit.aet.hephaestus.integration.outline.client.model")
    modelNamePrefix.set("Outline")
    modelNameMappings.set(
        mapOf("Collection" to "OutlineCollectionModel", "Document" to "OutlineDocumentModel")
    )
    generateApiTests.set(false)
    generateApiDocumentation.set(false)
    generateModelTests.set(false)
    generateModelDocumentation.set(false)
    globalProperties.set(mapOf("models" to ""))
    typeMappings.set(mapOf("OffsetDateTime" to "Instant", "UUID" to "String"))
    importMappings.set(mapOf("OffsetDateTime" to "java.time.Instant"))
    configOptions.set(
        mapOf(
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "sourceFolder" to ".",
            "hideGenerationTimestamp" to "true",
            "enumUnknownDefaultCase" to "true",
        )
    )
    cleanupOutput.set(true)
}

val generateOutline =
    tasks.register<GenerateTask>("generateOutline") {
        inputSpec.set(file("src/main/resources/openapi/outline/spec3.yml").absolutePath)
        outputDir.set(layout.buildDirectory.dir("generated/sources/outline"))
    }

val generateOutlineSupplement =
    tasks.register<GenerateTask>("generateOutlineSupplement") {
        inputSpec.set(
            file("src/main/resources/openapi/outline/outline-supplement.yaml").absolutePath
        )
        outputDir.set(layout.buildDirectory.dir("generated/sources/outline-supplement"))
    }

sourceSets.main {
    java.srcDir(generateGithub.map { it.outputDir })
    java.srcDir(generateGitlab.map { it.outputDir })
    java.srcDir(generateOutline.map { it.outputDir })
    java.srcDir(generateOutlineSupplement.map { it.outputDir })
}

tasks.compileJava {
    options.compilerArgs.add("-proc:none")
}

tasks.jar { archiveBaseName.set("hephaestus-generated-clients") }
