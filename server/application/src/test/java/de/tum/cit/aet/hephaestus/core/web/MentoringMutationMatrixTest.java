package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class MentoringMutationMatrixTest {
    private static final String WORKSPACE_PATH = "/workspaces/{workspaceSlug}";
    private static final List<String> WRITE_METHODS = List.of("post", "patch", "put", "delete");
    private static final Pattern ROW = Pattern.compile("^\\| (POST|PATCH|PUT|DELETE) \\| `([^`]+)` \\| ([^|]+) \\|");

    private record Mutation(String method, String path, boolean createKey) {}

    @Test
    void shouldDocumentEveryConversationMutationAndItsCreateHeader() throws IOException {
        var spec = Files.readString(Path.of("..", "openapi.yaml"));
        var doc = Files.readString(Path.of("..", "..", "docs", "contributor", "mentoring-framework.mdx"));
        List<Mutation> published = matrix(doc);
        assertThat(published).isNotEmpty();
        assertThat(mutations(spec)).containsExactlyInAnyOrderElementsOf(published);
        assertThat(wrongCreateHeaders(published)).isEmpty();
    }

    @Test
    void shouldDetectAnUnlistedMutationAndAnIdempotencyHeaderOnPut() {
        List<Mutation> published = matrix("""
            | PUT | `/conversation/read` | `Idempotency-Key` | Repeat the read marker | 200 |
            """);
        var spec = """
            paths:
              /workspaces/{workspaceSlug}/conversation/bookmarks:
                post:
                  operationId: bookmarkConversationEntry
                  parameters:
                    - { in: query, name: Idempotency-Key, required: true }
                    - { in: header, name: Idempotency-Key, required: false }
            """;
        var unkeyed = new Mutation("POST", "/conversation/bookmarks", false);
        assertThat(mutations(spec)).containsExactly(unkeyed);
        assertThat(published).doesNotContain(unkeyed);
        assertThat(wrongCreateHeaders(mutations(spec))).containsExactly(unkeyed);
        assertThat(wrongCreateHeaders(published)).containsExactly(new Mutation("PUT", "/conversation/read", true));
    }

    private static List<Mutation> wrongCreateHeaders(List<Mutation> rows) {
        return rows.stream()
                .filter(row -> row.method().equals("POST") != row.createKey())
                .toList();
    }

    private static List<Mutation> matrix(String doc) {
        return doc.lines()
                .map(ROW::matcher)
                .filter(match -> match.find())
                .map(match -> new Mutation(
                        match.group(1), match.group(2), match.group(3).contains(ClientInputs.HEADER)))
                .toList();
    }

    private static List<Mutation> mutations(String yaml) {
        JsonNode spec = JsonMapper.shared().valueToTree(new Yaml().load(yaml));
        List<Mutation> result = new ArrayList<>();
        spec.path("paths")
                .properties()
                .forEach(path -> WRITE_METHODS.forEach(method -> {
                    var operation = path.getValue().path(method);
                    var scope = WORKSPACE_PATH + "/conversation";
                    boolean conversation =
                            path.getKey().equals(scope) || path.getKey().startsWith(scope + "/");
                    if (operation.isObject() && conversation) {
                        boolean key = operation
                                .path("parameters")
                                .valueStream()
                                .anyMatch(parameter -> parameter
                                                .path("in")
                                                .asString("")
                                                .equals("header")
                                        && parameter.path("name").asString("").equalsIgnoreCase(ClientInputs.HEADER)
                                        && parameter.path("required").asBoolean(false));
                        result.add(new Mutation(
                                method.toUpperCase(Locale.ROOT),
                                path.getKey().replaceFirst("^" + Pattern.quote(WORKSPACE_PATH), ""),
                                key));
                    }
                }));
        return result;
    }
}
