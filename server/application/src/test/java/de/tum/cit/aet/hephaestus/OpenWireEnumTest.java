package de.tum.cit.aet.hephaestus;

import static de.tum.cit.aet.hephaestus.config.NamedEnumModelConverter.CLOSED_PUBLIC_ENUMS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class OpenWireEnumTest {

    @Test
    void shouldKeepPublishedPublicResponseEnumsOpen() throws IOException {
        var specification = read(Files.readString(Path.of("..", "openapi.yaml")));

        assertThat(closedEnums(specification, OpenAPIConfiguration.readPublicOperations()))
                .isEmpty();
        assertThat(CLOSED_PUBLIC_ENUMS).containsExactlyInAnyOrder("ResultOutcome", "Severity");
        var schemas = specification.path("components").path("schemas");
        for (String name : CLOSED_PUBLIC_ENUMS) {
            assertThat(schemas.path(name).path("enum").size()).as(name).isPositive();
        }
        var role = schemas.path("AboutRole");
        assertThat(role.path("type").asString()).isEqualTo("string");
        assertThat(role.has("enum")).isFalse();
        assertThat(role.path("examples").size()).isPositive();
    }

    @Test
    void shouldDetectAClosedEnumInsideAReferencedPublicResponse() {
        var specification = read("""
            openapi: 3.1.0
            paths:
              /items:
                get:
                  responses:
                    '200':
                      $ref: '#/components/responses/ItemList'
            components:
              responses:
                ItemList:
                  description: Current items
                  headers:
                    X-Status:
                      $ref: '#/components/headers/ItemStatus'
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          $ref: '#/components/schemas/ItemState'
              headers:
                ItemStatus:
                  schema:
                    $ref: '#/components/schemas/ResponseState'
              schemas:
                ItemState:
                  type: string
                  enum: [WAITING, READY]
                ResponseState:
                  type: string
                  const: QUEUED
            """);

        assertThat(closedEnums(specification, Set.of("GET /items")))
                .containsExactlyInAnyOrder("#/components/schemas/ItemState", "#/components/schemas/ResponseState");
    }

    private static JsonNode read(String yaml) {
        Object data = new Yaml().load(yaml);
        return JsonMapper.shared().valueToTree(data);
    }

    private static Set<String> closedEnums(JsonNode spec, Set<String> operations) {
        Set<String> closed = new HashSet<>();
        Set<String> seenReferences = new HashSet<>();
        for (String operation : operations) {
            String[] parts = operation.split(" ", 2);
            var responses = spec.path("paths")
                    .path(parts[1])
                    .path(parts[0].toLowerCase(Locale.ROOT))
                    .path("responses");
            assertThat(responses.isObject()).as(operation).isTrue();
            responses
                    .properties()
                    .forEach(response -> inspect(
                            spec, response.getValue(), operation + " " + response.getKey(), seenReferences, closed));
        }
        return closed;
    }

    private static void inspect(
            JsonNode spec, JsonNode node, String path, Set<String> seenReferences, Set<String> closed) {
        String ref = node.path("$ref").asString("");
        if (ref.startsWith("#/") && seenReferences.add(ref)) {
            inspect(spec, spec.at(ref.substring(1)), ref, seenReferences, closed);
        }
        if ((node.has("enum") || node.path("const").isString())
                && CLOSED_PUBLIC_ENUMS.stream().noneMatch(name -> path.equals("#/components/schemas/" + name))) {
            closed.add(path);
        }
        for (var entry : node.properties()) {
            String location = path + "." + entry.getKey();
            var child = entry.getValue();
            switch (entry.getKey()) {
                case "content", "headers", "properties", "patternProperties", "dependentSchemas" ->
                    child.properties()
                            .forEach(member -> inspect(
                                    spec, member.getValue(), location + "." + member.getKey(), seenReferences, closed));
                case "allOf", "anyOf", "oneOf", "prefixItems" ->
                    child.forEach(schema -> inspect(spec, schema, location, seenReferences, closed));
                case "schema",
                        "items",
                        "not",
                        "contains",
                        "propertyNames",
                        "contentSchema",
                        "additionalProperties",
                        "unevaluatedProperties",
                        "unevaluatedItems",
                        "if",
                        "then",
                        "else" -> inspect(spec, child, location, seenReferences, closed);
                default -> {
                    // Metadata and example values carry no response schemas.
                }
            }
        }
    }
}
