package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class SchemaVocabularyTest {
    private static final Pattern OLD_TERM = Pattern.compile("(?i:(?:^|_)developer)|Developer");

    @Test
    void shouldUsePersonInDatabaseColumns() {
        assertThat(oldColumnNames(NativePracticeFixture.database("prod"))).isEmpty();
    }

    @Test
    void shouldDetectAnOldColumnName() {
        var database = NativePracticeFixture.database("prod");
        PracticeMigrationFixture.execute(database, "CREATE TABLE vocabulary_fixture(developer_alias text)");
        assertThat(oldColumnNames(database)).containsExactly("vocabulary_fixture.developer_alias");
    }

    @Test
    void shouldUsePersonInTheHttpContract() throws IOException {
        assertThat(oldWireNames(Files.readString(Path.of("..", "openapi.yaml"))))
                .isEmpty();
    }

    @Test
    void shouldDetectAnOldPropertyAndQueryParameter() {
        assertThat(oldWireNames("""
            paths:
              /example:
                get:
                  parameters:
                    - in: query
                      name: developerAccount
            components:
              schemas:
                Observation:
                  type: object
                  properties:
                    items:
                      type: array
                      items:
                        type: object
                        properties:
                          developerAlias:
                            type: string
            """)).containsExactlyInAnyOrder("developerAccount", "developerAlias");
    }

    private static List<@Nullable String> oldColumnNames(TestDatabase database) {
        return PracticeMigrationFixture.column(database, """
            SELECT table_name || '.' || column_name
            FROM information_schema.columns
            WHERE table_schema='public'
              AND column_name ~* '(^|_)developer'
            ORDER BY table_name,column_name
            """);
    }

    private static List<String> oldWireNames(String yaml) {
        JsonNode spec = JsonMapper.shared().valueToTree(new Yaml().load(yaml));
        List<String> names = new ArrayList<>();
        var components = spec.path("components");
        components.path("schemas").propertyNames().forEach(name -> addOldName(name, names));
        components
                .path("parameters")
                .forEach(parameter -> addOldName(parameter.path("name").asString(""), names));
        inspectWire(spec.path("paths"), names);
        inspectWire(components, names);
        return names;
    }

    private static void inspectWire(JsonNode node, List<String> names) {
        node.path("properties").propertyNames().forEach(name -> addOldName(name, names));
        if (node.path("parameters").isArray()) {
            node.path("parameters")
                    .forEach(parameter -> addOldName(parameter.path("name").asString(""), names));
        }
        node.properties().forEach(entry -> {
            switch (entry.getKey()) {
                case "example", "examples", "default", "enum" -> {}
                default -> inspectWire(entry.getValue(), names);
            }
        });
        if (node.isArray()) {
            node.forEach(item -> inspectWire(item, names));
        }
    }

    private static void addOldName(String name, List<String> names) {
        if (OLD_TERM.matcher(name).find()) {
            names.add(name);
        }
    }
}
