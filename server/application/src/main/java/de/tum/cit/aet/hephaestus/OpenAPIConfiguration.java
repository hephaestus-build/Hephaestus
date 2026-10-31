package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.core.web.RequestReference;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.PagedModel;

/**
 * OpenAPI configuration: processes server DTOs (strips the {@code DTO} suffix from schema
 * names and {@code $ref}s), normalises paths (workspace-slug parameter, tag cleanup,
 * WorkspaceContext filtering) and declares exact decimals as such.
 */
@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Hephaestus API",
                        description = "API documentation for Hephaestus. Practice review bigint ids and "
                                + "monetary amounts are decimal strings. Monetary fields are USD; clients read "
                                + "server-computed totals and budget decisions. Provider mirror ids retain "
                                + "their numeric contract. Required nullable response fields are present with null.",
                        version = "0.0.0-development",
                        contact = @Contact(name = "Felix T.J. Dietrich", email = "felixtj.dietrich@tum.de"),
                        license =
                                @License(
                                        name = "MIT License",
                                        url = "https://github.com/hephaestus-build/Hephaestus/blob/main/LICENSE")),
        tags = {
            @Tag(
                    name = "Practice reviews",
                    description = "Workspace-admin access to reviews, results, and feedback delivery history"),
            @Tag(
                    name = "Practice review trace",
                    description =
                            "Why each practice did or did not run on one piece of work. Any workspace member can read"
                                    + " it."),
        },
        servers = {@Server(url = "/", description = "Default Server URL")},
        security = {@SecurityRequirement(name = "bearerAuth")})
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Hephaestus-native JWT bearer authentication. The SPA normally authenticates through the "
                + "`__Host-HEPHAESTUS_AT` session cookie. This scheme documents the equivalent bearer token.")
@SecurityScheme(
        name = "workerBearer",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description =
                "Worker credential on the private sandbox gateway connector. Bound to the worker, workspace, job and"
                        + " attempt, with the required proxy scope. Browser cookies and app-admin authority do not"
                        + " authorize it.")
public class OpenAPIConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAPIConfiguration.class);

    /**
     * Domain objects to include even without DTO suffix
     */
    private static final List<String> ALLOWED_DOMAIN_OBJECTS = List.of(
            "PageableObject",
            "SortObject",
            "ProblemDetail",
            "PageMetadata",
            "PracticeDefinition",
            "ReviewOn",
            "AppliesWhen",
            "AppliesWhenClause",
            "PracticeReads",
            "PracticeReadSource",
            "ReviewedBy",
            "ReviewLimit",
            "ScanDefinition",
            "PracticeGuidance",
            "PracticeVisual",
            "PracticeGuide",
            "PracticeDefinitionField",
            "PracticeReleaseChoice",
            "SourceContractVersion",
            "SourceKind",
            "WorkspaceReviewScope",
            "ReviewRepositoryTarget",
            "TimeBucketSize");

    /**
     * Zalando's non-standard-but-conventional {@code format} for an exact decimal, which stops a client
     * generator binding the value to {@code double}.
     */
    private static final String DECIMAL_FORMAT = "decimal";

    private static final String NUMBER_TYPE = "number";

    @Bean
    public OperationCustomizer userViewReasonHeader() {
        return (operation, handler) -> {
            if (handler.hasMethodAnnotation(UserViewRead.class)) {
                operation.addParametersItem(new HeaderParameter()
                        .name(UserViewContextHolder.REASON_HEADER)
                        .required(true)
                        .schema(new StringSchema())
                        .description(
                                "Why the administrator views this account: percent-encoded UTF-8, 1–500 characters"));
            }
            return operation;
        };
    }

    @Bean
    public OpenApiCustomizer schemaCustomizer(
            @Value("${spring.application.version:0.0.0-development}") String appVersion) {
        return openApi -> {
            openApi.getInfo().setVersion(appVersion);
            includeSpringDataPageMetadata(openApi);
            Map<String, String> schemaNames = processApplicationServerSchemas(openApi);
            processAllPaths(openApi, schemaNames);
            normalizeDiscriminatedUnions(openApi);
            declareExactDecimals(openApi);
            declareProviderIdentityKinds(openApi);
            declareProblemRequestId(openApi);
        };
    }

    /**
     * Spring writes a problem's extension properties at the top level, and every problem names its
     * request ({@link RequestReference}). With tracing off there is no id, so the field is optional.
     */
    private static void declareProblemRequestId(OpenAPI openApi) {
        Schema<?> problem = openApi.getComponents().getSchemas().get("ProblemDetail");
        if (problem != null) {
            problem.addProperty(
                    RequestReference.PROPERTY,
                    new StringSchema()
                            .pattern("^[0-9a-f]{32}$")
                            .description("The request's trace id, the same as the X-Request-Id header"));
        }
    }

    private void includeSpringDataPageMetadata(OpenAPI openApi) {
        ModelConverters.getInstance().read(PagedModel.PageMetadata.class).forEach((name, schema) -> {
            Schema<?> metadata = schema;
            if (metadata.getProperties() != null)
                metadata.setRequired(new ArrayList<>(metadata.getProperties().keySet()));
            openApi.getComponents().addSchemas(name, schema);
        });
    }

    /*
     * Process server schemas: include DTOs and remove suffix.
     */
    // Swagger exposes Map<String, Schema> publicly; stream element types inherit that raw schema type.
    @SuppressWarnings("rawtypes")
    private Map<String, String> processApplicationServerSchemas(OpenAPI openApi) {
        var components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            log.warn("No schemas found in server OpenAPI spec");
            return Map.of();
        }

        Map<String, Schema> filteredSchemas = new HashMap<>();
        Map<String, String> schemaNames = new HashMap<>();
        components.getSchemas().keySet().forEach(original -> {
            String candidate = original.endsWith("DTO") ? original.substring(0, original.length() - 3) : original;
            schemaNames.put(
                    original,
                    !candidate.equals(original) && components.getSchemas().containsKey(candidate)
                            ? original
                            : candidate);
        });

        components.getSchemas().entrySet().stream()
                .filter(e -> e.getKey().endsWith("DTO"))
                .forEach(e -> {
                    String nameWithoutDto = schemaNames.getOrDefault(e.getKey(), e.getKey());
                    e.getValue().setName(nameWithoutDto);
                    filteredSchemas.put(nameWithoutDto, e.getValue());
                });

        components.getSchemas().entrySet().stream()
                .filter(e -> ALLOWED_DOMAIN_OBJECTS.contains(e.getKey()))
                .forEach(e -> filteredSchemas.put(e.getKey(), e.getValue()));

        var remaining = new ArrayList<>(filteredSchemas.values());
        Set<Schema> seen = new HashSet<>();
        for (int index = 0; index < remaining.size(); index++) {
            retainReferencedSchemas(
                    remaining.get(index), components.getSchemas(), filteredSchemas, remaining, seen, schemaNames);
        }
        filteredSchemas.values().forEach(schema -> normalizeSchemaReferences(schema, schemaNames));
        components.setSchemas(filteredSchemas);
        return schemaNames;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void retainReferencedSchemas(
            Schema schema,
            Map<String, Schema> source,
            Map<String, Schema> retained,
            List<Schema> pending,
            Set<Schema> seen,
            Map<String, String> schemaNames) {
        if (schema == null || !seen.add(schema)) return;
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith("#/components/schemas/")) {
            String original = ref.substring("#/components/schemas/".length());
            String name = schemaNames.getOrDefault(original, original);
            Schema target = source.get(original);
            if (target != null && retained.putIfAbsent(name, target) == null) pending.add(target);
        }
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null)
            properties
                    .values()
                    .forEach(child -> retainReferencedSchemas(child, source, retained, pending, seen, schemaNames));
        retainReferencedSchemas(schema.getItems(), source, retained, pending, seen, schemaNames);
        retainReferencedSchemas(schema.getNot(), source, retained, pending, seen, schemaNames);
        Stream.of(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .forEach(
                        child -> retainReferencedSchemas((Schema) child, source, retained, pending, seen, schemaNames));
        if (schema.getAdditionalProperties() instanceof Schema child)
            retainReferencedSchemas(child, source, retained, pending, seen, schemaNames);
    }

    /*
     * Process all paths: clean up tags, parameters, ensure workspaceSlug.
     */
    private void processAllPaths(OpenAPI openApi, Map<String, String> schemaNames) {
        var paths = openApi.getPaths();
        if (paths == null) return;

        paths.forEach((path, pathItem) -> {
            pathItem.readOperations().forEach(operation -> {
                if (operation.getResponses() != null) {
                    operation.getResponses().forEach((code, response) -> {
                        if (response.getContent() != null) {
                            response.getContent().forEach((type, media) -> {
                                if (media.getSchema() != null) {
                                    normalizeSchemaReferences(media.getSchema(), schemaNames);
                                }
                            });
                        }
                    });
                }

                if (operation.getRequestBody() != null
                        && operation.getRequestBody().getContent() != null) {
                    operation.getRequestBody().getContent().forEach((type, media) -> {
                        if (media.getSchema() != null) {
                            normalizeSchemaReferences(media.getSchema(), schemaNames);
                        }
                    });
                }

                if (operation.getTags() != null) {
                    operation.setTags(operation.getTags().stream()
                            .map(tag -> tag.endsWith("-controller") ? tag.substring(0, tag.length() - 11) : tag)
                            .collect(Collectors.toList()));
                }

                if (operation.getParameters() != null) {
                    operation.getParameters().stream()
                            .filter(parameter -> "itemId".equals(parameter.getName())
                                    || "repositoryItemId".equals(parameter.getName()))
                            .forEach(parameter -> parameter.setSchema(
                                    new StringSchema().pattern("^[1-9][0-9]*$").maxLength(19)));
                    operation.setParameters(operation.getParameters().stream()
                            .filter(p -> !isWorkspaceContextParam(p))
                            .collect(Collectors.toCollection(ArrayList::new)));
                }

                if (path.contains("{workspaceSlug}")) {
                    ensureWorkspaceSlugParam(operation);
                }
            });
        });
    }

    /*
     * Labels every BigDecimal on the API as an exact decimal. springdoc emits
     * format: double/float for the binary types, so a bare formatless type: number
     * can only have come from a BigDecimal.
     */
    private void declareExactDecimals(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        openApi.getComponents().getSchemas().values().forEach(schema -> declareExactDecimals(schema, new HashSet<>()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void declareExactDecimals(Schema schema, Set<Schema> seen) {
        if (schema == null || !seen.add(schema)) {
            return;
        }

        if (isFormatlessNumber(schema)) {
            schema.setFormat(DECIMAL_FORMAT);
        }

        Map<String, Schema> props = schema.getProperties();
        if (props != null) {
            props.values().forEach(property -> declareExactDecimals(property, seen));
        }
        declareExactDecimals(schema.getItems(), seen);
        declareExactDecimals(schema.getNot(), seen);
        Stream.of(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .forEach(member -> declareExactDecimals((Schema) member, seen));
        if (schema.getAdditionalProperties() instanceof Schema additionalSchema) {
            declareExactDecimals(additionalSchema, seen);
        }
    }

    /*
     * OpenAPI 3.1 moved the type into the types set (a nullable number is [number, null])
     * and leaves getType() populated only in the 3.0 shape, so both have to be read.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean isFormatlessNumber(Schema schema) {
        if (schema.getFormat() != null) {
            return false;
        }
        Set<String> types = schema.getTypes();
        return NUMBER_TYPE.equals(schema.getType()) || (types != null && types.contains(NUMBER_TYPE));
    }

    private boolean isWorkspaceContextParam(Parameter param) {
        if (param == null) return false;
        if ("workspaceContext".equals(param.getName())) return true;
        if (param.getSchema() != null && param.getSchema().get$ref() != null) {
            return param.getSchema().get$ref().endsWith("/WorkspaceContext");
        }
        return false;
    }

    private void ensureWorkspaceSlugParam(Operation operation) {
        var params = operation.getParameters();
        if (params == null) {
            params = new ArrayList<>();
            operation.setParameters(params);
        } else {
            // `Stream#toList()` returns an unmodifiable list; also external sources may provide immutable lists.
            // Ensure we can safely insert the workspaceSlug parameter.
            params = new ArrayList<>(params);
            operation.setParameters(params);
        }

        boolean exists = params.stream().anyMatch(p -> "workspaceSlug".equals(p.getName()) && "path".equals(p.getIn()));

        if (!exists) {
            params.add(
                    0,
                    new Parameter()
                            .name("workspaceSlug")
                            .in("path")
                            .required(true)
                            .description("Workspace slug")
                            .schema(new StringSchema()
                                    .pattern(WorkspaceSlugValidator.LABEL_PATTERN)
                                    .minLength(WorkspaceSlugValidator.MIN_LENGTH)
                                    .maxLength(WorkspaceSlugValidator.MAX_LENGTH)));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void normalizeSchemaReferences(Schema schema, Map<String, String> schemaNames) {
        if (schema == null) return;

        if (schema.get$ref() != null) {
            schema.set$ref(normalizeSchemaReference(schema.get$ref(), schemaNames));
        }

        if (schema.getDiscriminator() != null && schema.getDiscriminator().getMapping() != null) {
            schema.getDiscriminator().getMapping().replaceAll((key, ref) -> normalizeSchemaReference(ref, schemaNames));
        }
        Map<String, Schema> props = schema.getProperties();
        if (props != null) {
            props.values().forEach(property -> normalizeSchemaReferences(property, schemaNames));
        }

        if (schema.getItems() != null) {
            normalizeSchemaReferences(schema.getItems(), schemaNames);
        }

        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(s -> normalizeSchemaReferences((Schema) s, schemaNames));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(s -> normalizeSchemaReferences((Schema) s, schemaNames));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> normalizeSchemaReferences((Schema) s, schemaNames));
        }

        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Schema additionalSchema) {
            normalizeSchemaReferences(additionalSchema, schemaNames);
        }

        if (schema.getNot() != null) {
            normalizeSchemaReferences(schema.getNot(), schemaNames);
        }
    }

    private static String normalizeSchemaReference(String ref, Map<String, String> schemaNames) {
        String prefix = "#/components/schemas/";
        if (!ref.startsWith(prefix)) return ref;
        String original = ref.substring(prefix.length());
        return prefix + schemaNames.getOrDefault(original, original);
    }

    private static void declareProviderIdentityKinds(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
        Schema<?> identity = openApi.getComponents().getSchemas().get("ProviderIdentityRef");
        if (identity == null || identity.getProperties() == null) return;
        Schema<String> provider = new StringSchema();
        provider.setEnum(
                Stream.of(IntegrationKind.values()).map(IntegrationKind::name).toList());
        identity.addProperty("provider", provider);
    }

    private static void normalizeDiscriminatedUnions(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
        var schemas = openApi.getComponents().getSchemas();
        schemas.forEach((parentName, rawParent) -> {
            Schema<?> parent = rawParent;
            if (parent.getDiscriminator() == null || parent.getOneOf() == null) return;
            String parentRef = "#/components/schemas/" + parentName;
            parent.getOneOf().forEach(alternative -> {
                String childRef = alternative.get$ref();
                if (childRef == null || !childRef.startsWith("#/components/schemas/")) return;
                Schema<?> child = schemas.get(childRef.substring("#/components/schemas/".length()));
                if (child == null
                        || child.getAllOf() == null
                        || child.getAllOf().stream().noneMatch(branch -> parentRef.equals(branch.get$ref()))) return;
                // A concrete alternative cannot also inherit the union that contains it.
                // Keep common fields and their required keys without the reverse union reference.
                var concrete = child.getAllOf().stream()
                        .filter(branch -> !parentRef.equals(branch.get$ref()))
                        .toList();
                if (parent.getProperties() != null) {
                    parent.getProperties().forEach((name, property) -> {
                        boolean declared = (child.getProperties() != null
                                        && child.getProperties().containsKey(name))
                                || concrete.stream()
                                        .anyMatch(branch -> branch.getProperties() != null
                                                && branch.getProperties().containsKey(name));
                        if (!declared) child.addProperty(name, property);
                    });
                }
                if (parent.getRequired() != null)
                    parent.getRequired().forEach(field -> {
                        if (child.getRequired() == null || !child.getRequired().contains(field))
                            child.addRequiredItem(field);
                    });
                child.setAllOf(concrete.isEmpty() ? null : concrete);
            });
        });
    }
}
