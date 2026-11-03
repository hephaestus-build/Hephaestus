package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OpenAPIConfigurationTest {
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

    private static Schema<?> responseSchema(Operation operation) {
        var response = Objects.requireNonNull(operation.getResponses().get("200"));
        var media = Objects.requireNonNull(response.getContent().get("application/json"));
        return Objects.requireNonNull(media.getSchema());
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
