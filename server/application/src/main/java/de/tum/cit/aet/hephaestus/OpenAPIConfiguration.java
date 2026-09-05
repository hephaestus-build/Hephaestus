package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.achievement.AchievementRegistry;
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
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.Collections;
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
                        description = "API documentation for the Hephaestus application server.\n\n"
                                + "### Money and exact decimals\n\n"
                                + "Every monetary amount and per-unit rate is a JSON number carrying `format: decimal`. It is an "
                                + "exact decimal on the server (`BigDecimal`, backed by a `NUMERIC` column) and MUST NOT be parsed "
                                + "into a binary floating-point type in a language that has an exact decimal one: bind it to "
                                + "`BigDecimal` / `decimal` / `Decimal`, not to `double`.\n\n"
                                + "JavaScript has no exact decimal type, so the generated TypeScript client necessarily binds these "
                                + "to `number` (IEEE-754 binary64). That is lossless for every value this API produces, and the "
                                + "margin is large: a binary64 round-trips any decimal of at most 15 significant digits exactly "
                                + "(`DBL_DIG`). Amounts are quantised to 6 decimal places, so they are exact below **$1,000,000,000**; "
                                + "per-1M-token rates are quantised to 8 places, so they are exact below **$10,000,000** per 1M "
                                + "tokens; budget caps are quantised to 2 places and their column tops out at $99,999,999.99. Every "
                                + "reachable value sits orders of magnitude inside those bounds.\n\n"
                                + "What that margin does NOT license is arithmetic. Totals, remaining budget and cap verdicts are "
                                + "computed on the server in exact decimal and shipped as fields; a client that re-derives them by "
                                + "summing rows is accumulating binary rounding error the server does not have. Read the totals, do "
                                + "not add them up.\n\n"
                                + "Amounts are USD throughout — the ledger, the caps and the gates all meter in USD, and the "
                                + "currency is therefore part of the field name (`...Usd`) rather than a per-amount field. Where a "
                                + "response also carries `FxRateInfo`, that is a display-only estimate in a second currency and is "
                                + "never an input to a budget decision.",
                        version = "0.0.0-development",
                        contact = @Contact(name = "Felix T.J. Dietrich", email = "felixtj.dietrich@tum.de"),
                        license =
                                @License(
                                        name = "MIT License",
                                        url = "https://github.com/hephaestus-build/Hephaestus/blob/main/LICENSE")),
        tags = {
            @Tag(
                    name = "Practice reviews",
                    description = "Workspace-admin access to review runs, observations, and feedback delivery history"),
            @Tag(
                    name = "Practice review trace",
                    description =
                            "Why each practice did or did not run on one piece of work; readable by any workspace member"),
        },
        servers = {@Server(url = "/", description = "Default Server URL")},
        security = {@SecurityRequirement(name = "bearerAuth")})
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Hephaestus-native JWT bearer authentication. The SPA normally authenticates via the "
                + "`__Host-HEPHAESTUS_AT` session cookie; this scheme documents the equivalent bearer token.")
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
            "PracticeAutomatedReviewPolicy",
            // The binding is the shape a practice is authored in, so a client that cannot see it cannot
            // create one. It has no DTO suffix because it is the domain type the API deliberately exposes.
            "PracticeBinding",
            "PracticeSubject",
            "PracticeSubjectClause",
            "SubjectEvidenceCollection",
            "PracticeEvidenceRequirement",
            "PracticeOptionalContextSource",
            "PracticeInsufficientEvidenceAction",
            "PracticeEvidenceLimitation",
            "PracticeAutomatedReviewValidation",
            "PracticeAutomatedReviewValidationStatus",
            "PracticeAutomatedReview",
            "PracticeAutomatedReviewMode",
            "PracticeEvidenceSufficiency",
            "EvidenceCompletenessRequirement",
            "EvidenceFreshnessRequirement",
            "ReviewClaimCurrentness",
            "SourceContractVersion",
            "SourceKind",
            // The workspace review scope is the shape an admin edits directly on the settings resource; a
            // client that cannot see it cannot render, let alone change, the scope. Domain type by choice,
            // like PracticeBinding: it is the same value the gate reads, not a transport copy of it.
            "WorkspaceReviewScope",
            "ReviewRepositoryTarget",
            "DeliveryPolicyFactsSnapshot",
            "PracticeFact",
            "SubjectStatus");
    /**
     * Domain objects to include by specific suffix (like AchievementProgress records)
     */
    private static final List<String> SAFE_DOMAIN_SUFFIXES = List.of("AchievementProgress");

    /**
     * Zalando's non-standard-but-conventional {@code format} for an exact decimal, which stops a client
     * generator binding the value to {@code double}.
     */
    private static final String DECIMAL_FORMAT = "decimal";

    private static final String NUMBER_TYPE = "number";

    @Bean
    public OpenApiCustomizer schemaCustomizer(
            AchievementRegistry registry, @Value("${spring.application.version:0.0.0-development}") String appVersion) {
        return openApi -> {
            openApi.getInfo().setVersion(appVersion);
            includeSpringDataPageMetadata(openApi);
            processApplicationServerSchemas(openApi);
            processAllPaths(openApi);
            declareExactDecimals(openApi);

            // Inject AchievementId enum based on registry keys
            if (openApi.getComponents() != null) {
                // Collect and sort IDs for deterministic output
                List<String> achievementIds = new ArrayList<>(registry.getAchievementIds());
                Collections.sort(achievementIds);

                log.info("Injected {} achievement IDs into OpenAPI", achievementIds.size());
                if (achievementIds.isEmpty()) {
                    log.error(
                            "Achievement registry is empty during OpenAPI generation! This will cause frontend type errors.");
                }

                StringSchema idSchema = new StringSchema();
                idSchema.setEnum(achievementIds);
                openApi.getComponents().addSchemas("AchievementId", idSchema);
            }
        };
    }

    private void includeSpringDataPageMetadata(OpenAPI openApi) {
        ModelConverters.getInstance()
                .read(PagedModel.PageMetadata.class)
                .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
    }

    /**
     * Process server schemas: include DTOs and remove suffix.
     */
    private void processApplicationServerSchemas(OpenAPI openApi) {
        var components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            log.warn("No schemas found in server OpenAPI spec");
            return;
        }

        @SuppressWarnings("rawtypes")
        Map<String, Schema> filteredSchemas = new HashMap<>();

        // Include DTOs with suffix removed
        components.getSchemas().entrySet().stream()
                .filter(e -> e.getKey().endsWith("DTO"))
                .forEach(e -> {
                    String nameWithoutDto = e.getKey().substring(0, e.getKey().length() - 3);
                    e.getValue().setName(nameWithoutDto);
                    filteredSchemas.put(nameWithoutDto, e.getValue());
                });

        // Include allowed domain objects
        components.getSchemas().entrySet().stream()
                .filter(e -> ALLOWED_DOMAIN_OBJECTS.contains(e.getKey())
                        || SAFE_DOMAIN_SUFFIXES.stream()
                                .anyMatch(s -> e.getKey().endsWith(s)))
                .forEach(e -> filteredSchemas.put(e.getKey(), e.getValue()));

        // Update $ref to remove DTO suffix
        filteredSchemas.values().forEach(this::removeDtoSuffixFromRefs);

        components.setSchemas(filteredSchemas);
    }

    /**
     * Process all paths: clean up tags, parameters, ensure workspaceSlug.
     */
    private void processAllPaths(OpenAPI openApi) {
        var paths = openApi.getPaths();
        if (paths == null) return;

        paths.forEach((path, pathItem) -> {
            pathItem.readOperations().forEach(operation -> {
                // Remove DTO suffix from response/request schemas
                if (operation.getResponses() != null) {
                    operation.getResponses().forEach((code, response) -> {
                        if (response.getContent() != null) {
                            response.getContent().forEach((type, media) -> {
                                if (media.getSchema() != null) {
                                    removeDtoSuffixFromRefs(media.getSchema());
                                }
                            });
                        }
                    });
                }

                if (operation.getRequestBody() != null
                        && operation.getRequestBody().getContent() != null) {
                    operation.getRequestBody().getContent().forEach((type, media) -> {
                        if (media.getSchema() != null) {
                            removeDtoSuffixFromRefs(media.getSchema());
                        }
                    });
                }

                // Clean up controller suffix from tags
                if (operation.getTags() != null) {
                    operation.setTags(operation.getTags().stream()
                            .map(tag -> tag.endsWith("-controller") ? tag.substring(0, tag.length() - 11) : tag)
                            .collect(Collectors.toList()));
                }

                // Filter out WorkspaceContext parameter
                if (operation.getParameters() != null) {
                    operation.setParameters(operation.getParameters().stream()
                            .filter(p -> !isWorkspaceContextParam(p))
                            .collect(Collectors.toCollection(ArrayList::new)));
                }

                // Ensure workspaceSlug parameter for workspace paths
                if (path.contains("{workspaceSlug}")) {
                    ensureWorkspaceSlugParam(operation);
                }
            });
        });

        // Also normalize refs inside component schemas (not only request/response bodies)
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(this::removeDtoSuffixFromRefs);
        }
    }

    /**
     * Labels every {@code BigDecimal} on the API as an exact decimal. springdoc emits
     * {@code format: double}/{@code float} for the binary types, so a bare formatless {@code type: number}
     * can only have come from a {@code BigDecimal}.
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

    /**
     * OpenAPI 3.1 moved the type into the {@code types} set (a nullable number is {@code [number, null]})
     * and leaves {@code getType()} populated only in the 3.0 shape, so both have to be read.
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
                            .schema(new StringSchema().pattern("^[a-z0-9][a-z0-9-]{2,50}$")));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void removeDtoSuffixFromRefs(Schema schema) {
        if (schema == null) return;

        if (schema.get$ref() != null && schema.get$ref().endsWith("DTO")) {
            schema.set$ref(schema.get$ref().substring(0, schema.get$ref().length() - 3));
        }

        Map<String, Schema> props = schema.getProperties();
        if (props != null) {
            props.values().forEach(this::removeDtoSuffixFromRefs);
        }

        if (schema.getItems() != null) {
            removeDtoSuffixFromRefs(schema.getItems());
        }

        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(s -> removeDtoSuffixFromRefs((Schema) s));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(s -> removeDtoSuffixFromRefs((Schema) s));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> removeDtoSuffixFromRefs((Schema) s));
        }

        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Schema additionalSchema) {
            removeDtoSuffixFromRefs(additionalSchema);
        }

        if (schema.getNot() != null) {
            removeDtoSuffixFromRefs(schema.getNot());
        }
    }
}
