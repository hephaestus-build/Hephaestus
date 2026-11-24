package de.tum.cit.aet.hephaestus;

import static de.tum.cit.aet.hephaestus.config.NamedEnumModelConverter.CLOSED_PUBLIC_ENUMS;
import static de.tum.cit.aet.hephaestus.config.NamedEnumModelConverter.OPEN_ENUM_NOTE;

import de.tum.cit.aet.hephaestus.config.NamedEnumModelConverter;
import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.core.web.OpenWireCode;
import de.tum.cit.aet.hephaestus.core.web.RequestReference;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
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
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
                        description = "API documentation for Hephaestus. Practice review bigint ids are decimal "
                                + "strings. Monetary fields are exact decimals in USD; clients read "
                                + "server-computed totals and budget decisions. Provider mirror ids retain "
                                + "their numeric contract. Required nullable response fields are present with null.",
                        version = "0.0.0-development",
                        contact =
                                @Contact(
                                        name = "Hephaestus",
                                        url = "https://github.com/hephaestus-build/Hephaestus",
                                        email = "security@hephaestus.build"),
                        license =
                                @License(
                                        name = "MIT License",
                                        url = "https://github.com/hephaestus-build/Hephaestus/blob/main/LICENSE")),
        tags = {
            @Tag(
                    name = "Account",
                    description = "The signed-in account: identity, linked accounts, sessions, preferences, export and"
                            + " deletion"),
            @Tag(name = "Admin", description = "Instance administration"),
            @Tag(
                    name = "Auth discovery",
                    description = "Public sign-in discovery: identity providers and signing keys"),
            @Tag(
                    name = "Practice reviews",
                    description = "Workspace-admin access to reviews, results, and feedback delivery history"),
            @Tag(
                    name = "Practice review trace",
                    description =
                            "Why each practice did or did not run on one piece of work. Any workspace member can read"
                                    + " it."),
        },
        servers = {@Server(url = ApiPath.PREFIX, description = "Public API")},
        security = {@SecurityRequirement(name = "bearerAuth")})
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Hephaestus-native JWT bearer authentication. The SPA normally authenticates through the "
                + "`__Host-HEPHAESTUS_AT` session cookie. This scheme documents the equivalent bearer token.")
public class OpenAPIConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAPIConfiguration.class);

    static {
        // A JsonNode is any JSON value. Without this, springdoc publishes Jackson's bean getters as its schema,
        // and a schema without types is published as an object.
        SpringDocUtils.getConfig()
                .replaceWithSchema(
                        JsonNode.class,
                        new Schema<>()
                                .types(new LinkedHashSet<>(
                                        List.of("string", "number", "boolean", "object", "array", "null"))));
    }

    /**
     * Domain objects to include even without DTO suffix
     */
    private static final List<String> ALLOWED_DOMAIN_OBJECTS = List.of(
            "ProblemDetail",
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
    NamedEnumModelConverter namedEnumModelConverter(JsonMapper mapper) {
        return new NamedEnumModelConverter(mapper, type -> type.isAnnotationPresent(OpenWireCode.class));
    }

    @Bean
    public OpenApiCustomizer schemaCustomizer(
            @Value("${spring.application.version:0.0.0-development}") String appVersion) {
        return openApi -> {
            openApi.getInfo().setVersion(appVersion);
            relativeToServer(openApi);
            Map<String, String> schemaNames = processApplicationServerSchemas(openApi);
            processAllPaths(openApi, schemaNames);
            normalizeTags(openApi);
            normalizeDiscriminatedUnions(openApi);
            declareExactDecimals(openApi);
            declareProviderIdentityKinds(openApi);
            declareProblemShape(openApi);
            openPublicResponseEnums(openApi, readPublicOperations());
        };
    }

    private static final String COMPONENTS = "#/components/schemas/";

    /** Each public operation as {@code METHOD /path}, from server/public-api-operations.json in the JAR. */
    static Set<String> readPublicOperations() {
        try (var in = new ClassPathResource("openapi/public-api-operations.json").getInputStream()) {
            return Set.copyOf(JsonMapper.shared().readValue(in, new TypeReference<List<String>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("The JAR lacks openapi/public-api-operations.json", e);
        }
    }

    /**
     * ADR 0103 rule 3: an enum that a public operation returns is open. JSON Schema's {@code enum} is closed, and a
     * strict generator fails on a value it does not list. So the spec writes such an enum as a string with its known
     * values as {@code examples}, everywhere it appears. The closed enums keep {@code enum}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void openPublicResponseEnums(OpenAPI openApi, Set<String> operations) {
        if (openApi.getPaths() == null) return;
        var components = openApi.getComponents();
        Map<String, Schema> schemas =
                components == null || components.getSchemas() == null ? Map.of() : components.getSchemas();
        Set<Schema> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        CLOSED_PUBLIC_ENUMS.stream().map(schemas::get).filter(Objects::nonNull).forEach(closed::add);
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Schema> pending = new ArrayDeque<>();
        openApi.getPaths()
                .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                    if (operations.contains(method.name() + " " + path))
                        OpenApiResponseSchemas.visit(openApi, operation, pending::add);
                }));
        while (!pending.isEmpty()) {
            Schema schema = pending.pop();
            if (!seen.add(schema)) continue;
            if (schema.get$ref() != null && schema.get$ref().startsWith(COMPONENTS)) {
                Schema target = schemas.get(schema.get$ref().substring(COMPONENTS.length()));
                if (target != null) pending.add(target);
            }
            if ((schema.getEnum() != null || schema.getConst() instanceof String) && !closed.contains(schema))
                open(schema);
            Stream.of(schema.getProperties(), schema.getPatternProperties(), schema.getDependentSchemas())
                    .filter(Objects::nonNull)
                    .forEach(properties -> pending.addAll(properties.values()));
            Stream.of(
                            schema.getItems(),
                            schema.getNot(),
                            schema.getContains(),
                            schema.getPropertyNames(),
                            schema.getContentSchema(),
                            schema.getUnevaluatedProperties(),
                            schema.getUnevaluatedItems(),
                            schema.getIf(),
                            schema.getThen(),
                            schema.getElse())
                    .filter(Objects::nonNull)
                    .forEach(pending::add);
            Stream.of(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf(), schema.getPrefixItems())
                    .filter(Objects::nonNull)
                    .forEach(pending::addAll);
            if (schema.getAdditionalProperties() instanceof Schema values) pending.add(values);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void open(Schema schema) {
        List<Object> allowed = schema.getEnum();
        List<Object> known = allowed == null
                ? List.of(Objects.requireNonNull(schema.getConst()))
                : allowed.stream().filter(Objects::nonNull).toList();
        boolean nullable = allowed != null && allowed.contains(null);
        schema.setConst(null);
        NamedEnumModelConverter.openWireSchema(schema, known);
        if (nullable && schema.getTypes() == null) schema.setTypes(new LinkedHashSet<>(List.of("string", "null")));
    }

    /**
     * The server serves the API under {@link ApiPath#PREFIX}, and the description's one server names that prefix. OpenAPI
     * paths are relative to the server, so the prefix leaves each path here and the clients keep {@code /api} as their
     * base URL.
     */
    private static void relativeToServer(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        Paths relative = new Paths();
        openApi.getPaths()
                .forEach((path, item) -> relative.addPathItem(
                        path.startsWith(ApiPath.PREFIX + "/") ? path.substring(ApiPath.PREFIX.length()) : path, item));
        relative.setExtensions(openApi.getPaths().getExtensions());
        openApi.setPaths(relative);
    }

    private static final String PROBLEM_MEDIA = "application/problem+json";

    static final String PROBLEM_TYPE = "ProblemType";

    /**
     * One error contract: every error answer is a {@code ProblemDetail}. Spring writes extension properties at the
     * top level. Every problem names its request ({@link RequestReference}). With tracing off there is no id, so the
     * field is optional. Spring leaves {@code type} out for {@code about:blank} (RFC 9457 §3.1.1), so it is optional,
     * and {@code title} and {@code status} are always written.
     */
    private static void declareProblemShape(OpenAPI openApi) {
        Schema<?> problem = openApi.getComponents().getSchemas().get("ProblemDetail");
        if (problem == null) return;
        var properties = problem.getProperties();
        if (properties != null) {
            properties.remove("properties");
            // RFC 9457 makes it a URI reference, and Spring fills a missing instance with the request path.
            Schema<?> instance = properties.get("instance");
            if (instance != null) instance.setFormat("uri-reference");
        }
        openApi.getComponents().addSchemas(PROBLEM_TYPE, problemTypeSchema());
        problem.addProperty("type", new Schema<>().$ref(COMPONENTS + PROBLEM_TYPE));
        problem.setRequired(List.of("status", "title"));
        problem.addProperty(
                RequestReference.PROPERTY,
                new StringSchema()
                        .pattern("^[0-9a-f]{32}$")
                        .description("The request's trace id, the same as the X-Request-Id header"));
        declareProblemExtensions(problem);
        declareProblemResponses(openApi);
    }

    /**
     * {@link ProblemType} is the registry, so the spec lists its URIs from the enum. A server adds a type in a minor
     * release, so the list is open (ADR 0103 rule 3).
     */
    static Schema<String> problemTypeSchema() {
        List<String> known = new ArrayList<>();
        known.add("about:blank");
        for (ProblemType type : ProblemType.values()) known.add(type.uri().toString());
        StringSchema schema = new StringSchema();
        schema.setFormat("uri-reference");
        schema.setDescription(OPEN_ENUM_NOTE
                + " The problem's kind. A client branches on it, never on the title or the detail. A problem"
                + " without a type is about:blank: its status is its kind.");
        schema.setExamples(known);
        return schema;
    }

    /**
     * Every 4xx and 5xx answer is a problem. An operation lists the statuses that it documents, and the {@code 4XX} and
     * {@code 5XX} ranges cover the rest. A list of every status for each operation would be a claim that nothing
     * checks, and {@code default} would also claim the redirects. An error body that an operation declares itself,
     * such as an OAuth error, stays.
     */
    private static void declareProblemResponses(OpenAPI openApi) {
        if (openApi.getPaths() == null) return;
        openApi.getPaths()
                .values()
                .forEach(item -> item.readOperations().forEach(operation -> {
                    if (operation.getResponses() == null) operation.setResponses(new ApiResponses());
                    operation.getResponses().forEach((code, response) -> {
                        if (isError(code) && isProblemOrEmpty(response.getContent()))
                            response.setContent(problemContent());
                    });
                    for (String range : List.of("4XX", "5XX")) {
                        operation
                                .getResponses()
                                .putIfAbsent(
                                        range,
                                        new ApiResponse()
                                                .description("A problem. Its type names the kind.")
                                                .content(problemContent()));
                    }
                }));
    }

    private static boolean isError(String code) {
        return code.startsWith("4") || code.startsWith("5");
    }

    private static boolean isProblemOrEmpty(Content content) {
        return content == null
                || content.values().stream()
                        .allMatch(media -> media.getSchema() != null
                                && (COMPONENTS + "ProblemDetail")
                                        .equals(media.getSchema().get$ref()));
    }

    private static Content problemContent() {
        return new Content()
                .addMediaType(PROBLEM_MEDIA, new MediaType().schema(new Schema<>().$ref(COMPONENTS + "ProblemDetail")));
    }

    /**
     * The extension members that a problem type can carry (RFC 9457 §3.2). Each is optional, and a client ignores a
     * member that it does not know. They are listed rather than left to {@code additionalProperties: true}, which
     * OpenAPI Generator's Kotlin client cannot compile.
     */
    private static void declareProblemExtensions(Schema<?> problem) {
        Map<String, Schema<?>> members = new LinkedHashMap<>();
        members.put(
                "errors",
                new MapSchema()
                        .additionalProperties(new ArraySchema().items(new StringSchema()))
                        .description("Validation messages for each field that needs a change"));
        members.put(
                "problems",
                new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/PracticeFileProblem"))
                        .description("Why a practice folder cannot become a practice: each problem with its file and,"
                                + " where known, its line and column"));
        for (String number : List.of(
                "maxAgeSeconds",
                "retryAfterSeconds",
                "connectionId",
                "jobId",
                "workspaceBytes",
                "workspaceByteBudget")) {
            members.put(number, new IntegerSchema().format("int64"));
        }
        for (String id : List.of("scanId", "activeScanId")) {
            members.put(id, new StringSchema().format("uuid"));
        }
        for (String text : List.of(
                "callbackError",
                "currentVersion",
                "currentVersionId",
                "declaredTier",
                "slackError",
                "state",
                "stop",
                "supportedRef",
                "withholdingReason")) {
            members.put(text, new StringSchema());
        }
        members.forEach(problem::addProperty);
    }

    /**
     * OpenAPI requires unique tag names. springdoc writes one tag for each distinct description and names an untagged
     * controller after its class, so the list is rebuilt from the operations: one entry for each name that an operation
     * uses, with the one description that the annotations give it.
     */
    static void normalizeTags(OpenAPI openApi) {
        Map<String, String> descriptions = new HashMap<>();
        if (openApi.getTags() != null) {
            for (var tag : openApi.getTags()) {
                String name = withoutControllerSuffix(tag.getName());
                String description = tag.getDescription();
                if (description == null || description.isBlank()) continue;
                String earlier = descriptions.putIfAbsent(name, description);
                if (earlier != null && !earlier.equals(description)) {
                    throw new IllegalStateException("Tag \"" + name + "\" has two descriptions: \"" + earlier
                            + "\" and \"" + description + "\". Describe it once in OpenAPIConfiguration.");
                }
            }
        }
        Set<String> used = new TreeSet<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths()
                    .values()
                    .forEach(item -> item.readOperations().forEach(operation -> {
                        if (operation.getTags() != null) used.addAll(operation.getTags());
                    }));
        }
        openApi.setTags(used.stream()
                .map(name -> new io.swagger.v3.oas.models.tags.Tag().name(name).description(descriptions.get(name)))
                .toList());
    }

    private static String withoutControllerSuffix(String tag) {
        return tag.endsWith("-controller") ? tag.substring(0, tag.length() - "-controller".length()) : tag;
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
            Schema<?> taken = components.getSchemas().get(candidate);
            if (!candidate.equals(original)
                    && taken != null
                    && (taken.getEnum() != null
                            || (taken.getDescription() != null
                                    && taken.getDescription().startsWith(OPEN_ENUM_NOTE)))) {
                throw new IllegalStateException("The enum " + candidate + " takes the name of " + original
                        + ". Give the enum @Schema(name = ...).");
            }
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
        // Operations reference schemas too, such as an enum that only a query parameter uses.
        remaining.addAll(operationSchemas(openApi));
        Set<Schema> seen = new HashSet<>();
        for (int index = 0; index < remaining.size(); index++) {
            retainReferencedSchemas(
                    remaining.get(index), components.getSchemas(), filteredSchemas, remaining, seen, schemaNames);
        }
        filteredSchemas.values().forEach(schema -> normalizeSchemaReferences(schema, schemaNames));
        components.setSchemas(filteredSchemas);
        return schemaNames;
    }

    @SuppressWarnings("rawtypes")
    private static List<Schema> operationSchemas(OpenAPI openApi) {
        List<Schema> schemas = new ArrayList<>();
        if (openApi.getPaths() == null) return schemas;
        openApi.getPaths()
                .values()
                .forEach(item -> item.readOperations().forEach(operation -> {
                    if (operation.getParameters() != null) {
                        operation.getParameters().stream()
                                .filter(parameter -> !isWorkspaceContextParam(parameter))
                                .map(Parameter::getSchema)
                                .filter(Objects::nonNull)
                                .forEach(schemas::add);
                    }
                    if (operation.getRequestBody() != null
                            && operation.getRequestBody().getContent() != null) {
                        operation.getRequestBody().getContent().values().stream()
                                .map(media -> media.getSchema())
                                .filter(Objects::nonNull)
                                .forEach(schemas::add);
                    }
                    OpenApiResponseSchemas.visit(openApi, operation, schemas::add);
                }));
        return schemas;
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
                OpenApiResponseSchemas.visit(
                        openApi, operation, schema -> normalizeSchemaReferences(schema, schemaNames));

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
                            .map(OpenAPIConfiguration::withoutControllerSuffix)
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
     * and leaves getType() populated only in the 3.0 shape, so both have to be read. A set with
     * other types, such as any JSON value, is not a decimal.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean isFormatlessNumber(Schema schema) {
        if (schema.getFormat() != null) {
            return false;
        }
        Set<String> types = schema.getTypes();
        return NUMBER_TYPE.equals(schema.getType())
                || (types != null
                        && types.contains(NUMBER_TYPE)
                        && types.stream().allMatch(type -> NUMBER_TYPE.equals(type) || "null".equals(type)));
    }

    private static boolean isWorkspaceContextParam(Parameter param) {
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
