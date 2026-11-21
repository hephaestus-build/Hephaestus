package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OpenAPIConfigurationTest {
    @Test
    void shouldDescribeProblemExtensionsAtTheTopLevel() {
        var problem = objectWithFields("detail", "instance", "status", "title", "type", "properties");
        var api = api(new Components().addSchemas("ProblemDetail", problem), responseOperation("ProblemDetail"));

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(problem.getProperties()).containsKeys("detail", "requestId").doesNotContainKey("properties");
        // The extension members are listed: `additionalProperties: true` breaks OpenAPI Generator's Kotlin client.
        assertThat(problem.getAdditionalProperties()).isNull();
        assertThat(problem.getProperties()).containsKeys("errors", "maxAgeSeconds", "withholdingReason");
        assertThat(Objects.requireNonNull(problem.getProperties().get("instance"))
                        .getFormat())
                .isEqualTo("uri-reference");
        // Spring leaves out `type` for about:blank, and always writes `title` and `status`.
        assertThat(problem.getRequired()).containsExactly("status", "title");
    }

    @Test
    void shouldPublishEveryProblemTypeAsAnOpenEnumThatTheTypeMemberNames() {
        var problem = objectWithFields("type");
        var api = api(new Components().addSchemas("ProblemDetail", problem), responseOperation("ProblemDetail"));

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(Objects.requireNonNull(problem.getProperties().get("type")).get$ref())
                .isEqualTo("#/components/schemas/ProblemType");
        Schema<?> type = Objects.requireNonNull(api.getComponents().getSchemas().get("ProblemType"));
        assertThat(type.getEnum()).isNull();
        assertThat(type.getFormat()).isEqualTo("uri-reference");
        assertThat(type.getDescription()).startsWith(OpenAPIConfiguration.OPEN_ENUM_NOTE);
        List<Object> known = Stream.concat(
                        Stream.of("about:blank"),
                        Stream.of(ProblemType.values()).map(kind -> kind.uri().toString()))
                .map(Object.class::cast)
                .toList();
        assertThat(List.copyOf(type.getExamples())).isEqualTo(known);
    }

    @Test
    void shouldAnswerEveryErrorOfEveryOperationWithAProblem() {
        var listed = responseOperation("ProblemDetail");
        listed.getResponses()
                .addApiResponse(
                        "409",
                        new ApiResponse()
                                .description("Conflict")
                                .content(new Content()
                                        .addMediaType(
                                                "application/json",
                                                new MediaType()
                                                        .schema(new Schema<>()
                                                                .$ref("#/components/schemas/ProblemDetail")))))
                .addApiResponse("404", new ApiResponse().description("Not found"));
        var api = api(new Components().addSchemas("ProblemDetail", objectWithFields("type")), listed);

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        var responses = listed.getResponses();
        assertThat(responses).containsKeys("200", "404", "409", "default");
        assertThat(Objects.requireNonNull(responses.get("200")).getContent()).containsOnlyKeys("application/json");
        for (String code : List.of("404", "409", "default")) {
            var content = Objects.requireNonNull(responses.get(code)).getContent();
            assertThat(content).containsOnlyKeys("application/problem+json");
            assertThat(Objects.requireNonNull(content.get("application/problem+json"))
                            .getSchema()
                            .get$ref())
                    .isEqualTo("#/components/schemas/ProblemDetail");
        }
    }

    @Test
    void shouldKeepResponseAndAuthoredSchemaIdentitiesWhenTheirTrimmedNamesCollide() {
        Schema<Object> authored = new Schema<>();
        authored.addProperty("goingWell", new Schema<>().type("string"));
        Schema<Object> response = new Schema<>();
        response.addProperty("practiceSlug", new Schema<>().type("string"));
        Schema<Object> definition = new Schema<>();
        definition.addProperty("guidance", new Schema<>().$ref("#/components/schemas/PracticeGuidance"));
        Operation operation = responseOperation("PracticeGuidanceDTO");
        OpenAPI api = api(
                new Components()
                        .addSchemas("PracticeGuidance", authored)
                        .addSchemas("PracticeGuidanceDTO", response)
                        .addSchemas("PracticeDefinition", definition)
                        .addSchemas("ProviderIdentityRefDTO", objectWithFields("provider")),
                operation);

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(api.getComponents().getSchemas()).containsKeys("PracticeGuidance", "PracticeGuidanceDTO");
        Schema<?> authoredResponse =
                Objects.requireNonNull(api.getComponents().getSchemas().get("PracticeGuidance"));
        Schema<?> guidanceResponse =
                Objects.requireNonNull(api.getComponents().getSchemas().get("PracticeGuidanceDTO"));
        assertThat(authoredResponse.getProperties()).containsKey("goingWell").doesNotContainKey("practiceSlug");
        assertThat(guidanceResponse.getProperties()).containsKey("practiceSlug").doesNotContainKey("goingWell");
        assertThat(responseSchema(operation).get$ref()).isEqualTo("#/components/schemas/PracticeGuidanceDTO");
        Schema<?> identity =
                Objects.requireNonNull(api.getComponents().getSchemas().get("ProviderIdentityRef"));
        Schema<?> provider = Objects.requireNonNull(identity.getProperties().get("provider"));
        assertThat(provider.getEnum())
                .isEqualTo(Stream.of(IntegrationKind.values())
                        .map(IntegrationKind::name)
                        .toList());
    }

    @Test
    void shouldDeclareCanonicalItemIdsAndKeepQueryParametersOptional() {
        Operation operation = responseOperation("PageDTO");
        operation.addParametersItem(new Parameter().name("size").in("query").required(false));
        operation.addParametersItem(new Parameter()
                .name("itemId")
                .in("query")
                .required(false)
                .schema(new Schema<>().type("integer").format("int64")));
        OpenAPI api = api(new Components().addSchemas("PageDTO", objectWithFields("content")), operation);

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);
        assertThat(Objects.requireNonNull(operation.getParameters()).getFirst().getRequired())
                .isFalse();
        assertThat(operation.getParameters().get(1).getSchema().getType()).isEqualTo("string");
        assertThat(operation.getParameters().get(1).getRequired()).isFalse();
        assertThat(responseSchema(operation).get$ref()).isEqualTo("#/components/schemas/Page");
    }

    @Test
    void shouldPublishFiniteConcreteAlternativesForDiscriminatedUnions() {
        Schema<Object> union = objectWithFields("item");
        union.addRequiredItem("item");
        union.setDiscriminator(new Discriminator().propertyName("status"));
        union.addOneOfItem(new Schema<>().$ref("#/components/schemas/JudgedScanReportItemDTO"));
        Schema<Object> child = new Schema<>();
        child.addAllOfItem(new Schema<>().$ref("#/components/schemas/ScanReportItemDTO"));
        Schema<Object> fields = objectWithFields("status", "result");
        Schema<String> status = new Schema<>();
        status.addEnumItemObject("JUDGED");
        fields.addProperty("status", status);
        child.addAllOfItem(fields);
        OpenAPI api = api(
                new Components().addSchemas("ScanReportItemDTO", union).addSchemas("JudgedScanReportItemDTO", child),
                responseOperation("ScanReportItemDTO"));

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(union.getOneOf())
                .singleElement()
                .satisfies(
                        branch -> assertThat(branch.get$ref()).isEqualTo("#/components/schemas/JudgedScanReportItem"));
        assertThat(child.getAllOf()).singleElement().satisfies(branch -> {
            assertThat(branch.get$ref()).isNull();
            Schema<?> concrete = branch;
            assertThat(concrete.getProperties()).containsKeys("status", "result");
        });
        assertThat(child.getProperties()).containsKey("item");
        assertThat(child.getRequired()).contains("item");
    }

    @Test
    void shouldListEachOperationTagOnceWithItsOneDescription() {
        var account = new Operation().tags(List.of("Account"));
        // The operation carries the stripped name; springdoc's tag list keeps the controller suffix.
        var preflight = new Operation().tags(List.of("git-lab-preflight"));
        var api = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/user", new PathItem().get(account))
                        .addPathItem("/user/sessions", new PathItem().get(new Operation().tags(List.of("Account"))))
                        .addPathItem("/workspaces/gitlab", new PathItem().get(preflight)))
                .tags(List.of(
                        new io.swagger.v3.oas.models.tags.Tag().name("Account").description("The signed-in account"),
                        new io.swagger.v3.oas.models.tags.Tag().name("Account"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("git-lab-preflight-controller")
                                .description("GitLab checks"),
                        new io.swagger.v3.oas.models.tags.Tag().name("Unused").description("No operation")));

        OpenAPIConfiguration.normalizeTags(api);

        assertThat(api.getTags())
                .extracting(
                        io.swagger.v3.oas.models.tags.Tag::getName, io.swagger.v3.oas.models.tags.Tag::getDescription)
                .containsExactly(
                        tuple("Account", "The signed-in account"), tuple("git-lab-preflight", "GitLab checks"));
    }

    @Test
    void shouldRefuseATagWithTwoDescriptions() {
        var api = new OpenAPI()
                .paths(new Paths().addPathItem("/user", new PathItem().get(new Operation().tags(List.of("Admin")))))
                .tags(List.of(
                        new io.swagger.v3.oas.models.tags.Tag().name("Admin").description("Instance administration"),
                        new io.swagger.v3.oas.models.tags.Tag().name("Admin").description("Account management")));

        assertThatThrownBy(() -> OpenAPIConfiguration.normalizeTags(api))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Admin");
    }

    @Test
    void shouldKeepAnEnumThatOnlyAQueryParameterReferences() {
        var operation = responseOperation("ResultListDTO")
                .addParametersItem(new Parameter()
                        .name("sort")
                        .in("query")
                        .schema(new Schema<>().$ref("#/components/schemas/ResultSort")));
        var api = api(
                new Components()
                        .addSchemas("ResultListDTO", objectWithFields("practiceSlug"))
                        .addSchemas("ResultSort", new Schema<>().type("string")),
                operation);

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(api.getComponents().getSchemas()).containsKeys("ResultList", "ResultSort");
    }

    @Test
    void shouldNotPublishTheWorkspaceContextParameterSchema() {
        var operation = responseOperation("ResultListDTO")
                .addParametersItem(new Parameter()
                        .name("workspaceContext")
                        .in("query")
                        .schema(new Schema<>().$ref("#/components/schemas/WorkspaceContext")));
        var api = api(
                new Components()
                        .addSchemas("ResultListDTO", objectWithFields("practiceSlug"))
                        .addSchemas("WorkspaceContext", objectWithFields("installationId")),
                operation);

        new OpenAPIConfiguration().schemaCustomizer("test").customise(api);

        assertThat(api.getComponents().getSchemas()).containsKey("ResultList").doesNotContainKey("WorkspaceContext");
    }

    @Test
    void shouldRefuseAnEnumThatTakesTheNameOfADto() {
        Schema<Object> standing = new Schema<>();
        standing.addEnumItemObject("STRENGTH");
        var api = api(
                new Components()
                        .addSchemas("PracticeStandingDTO", objectWithFields("standing"))
                        .addSchemas("PracticeStanding", standing),
                responseOperation("PracticeStandingDTO"));

        assertThatThrownBy(() ->
                        new OpenAPIConfiguration().schemaCustomizer("test").customise(api))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PracticeStanding");
    }

    @Test
    void shouldOpenTheEnumsThatAPublicOperationReturnsAndKeepTheClosedOnes() {
        var listed = objectWithFields();
        listed.addProperty("status", new Schema<>().$ref("#/components/schemas/ReviewStatus"));
        listed.addProperty("outcome", new Schema<>().$ref("#/components/schemas/ResultOutcome"));
        var internal = objectWithFields();
        internal.addProperty("state", new Schema<>().$ref("#/components/schemas/QueueState"));
        var api = api(
                new Components()
                        .addSchemas("ResultList", listed)
                        .addSchemas("Internal", internal)
                        .addSchemas("ReviewStatus", enumSchema("RUNNING", "DONE"))
                        .addSchemas("ResultOutcome", enumSchema("MET", "NOT_MET"))
                        .addSchemas("QueueState", enumSchema("WAITING")),
                responseOperation("ResultList"));
        api.getPaths().addPathItem("/internal", new PathItem().get(responseOperation("Internal")));

        OpenAPIConfiguration.openPublicResponseEnums(api, Set.of("GET /practices"));

        Schema<?> status = component(api, "ReviewStatus");
        assertThat(status.getEnum()).isNull();
        List<Object> known = List.copyOf(status.getExamples());
        assertThat(known).containsExactly("RUNNING", "DONE");
        assertThat(status.getDescription()).startsWith(OpenAPIConfiguration.OPEN_ENUM_NOTE);
        assertThat(component(api, "ResultOutcome").getEnum()).isNotNull();
        assertThat(component(api, "QueueState").getEnum()).isNotNull();
    }

    private static Schema<?> responseSchema(Operation operation) {
        var response = Objects.requireNonNull(operation.getResponses().get("200"));
        var media = Objects.requireNonNull(response.getContent().get("application/json"));
        return Objects.requireNonNull(media.getSchema());
    }

    private static Schema<?> component(OpenAPI api, String name) {
        Schema<?> schema = api.getComponents().getSchemas().get(name);
        return Objects.requireNonNull(schema);
    }

    private static Schema<Object> enumSchema(String... values) {
        Schema<Object> schema = new Schema<>();
        schema.setType("string");
        for (String value : values) schema.addEnumItemObject(value);
        return schema;
    }

    private static Schema<Object> objectWithFields(String... fields) {
        Schema<Object> object = new Schema<>();
        for (String field : fields) object.addProperty(field, new Schema<>().type("string"));
        return object;
    }

    private static Operation responseOperation(String name) {
        return new Operation()
                .responses(new ApiResponses()
                        .addApiResponse(
                                "200",
                                new ApiResponse()
                                        .content(new Content()
                                                .addMediaType(
                                                        "application/json",
                                                        new MediaType()
                                                                .schema(new Schema<>()
                                                                        .$ref("#/components/schemas/" + name))))));
    }

    private static OpenAPI api(Components components, Operation operation) {
        return new OpenAPI()
                .info(new Info())
                .components(components)
                .paths(new Paths().addPathItem("/practices", new PathItem().get(operation)));
    }
}
