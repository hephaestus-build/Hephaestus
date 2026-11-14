package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns only the disposable users and group tree of one live identity proof. */
final class GitLabIdentityFixture implements AutoCloseable {
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String server;
    private final String token;
    private final String unique = "ask-identity-" + UUID.randomUUID().toString().substring(0, 8);
    private String connectionToken = "";
    private long groupTokenId;
    private long userId;
    private long parentId;
    private long sourceId;
    private long targetId;
    private long projectId;
    private String username = unique;
    private String email = unique + "@example.invalid";
    private String projectPath = "";

    GitLabIdentityFixture(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        server = Objects.requireNonNull(System.getenv("HEPHAESTUS_LIVE_GITLAB_URL"));
        token = Files.readString(Path.of(Objects.requireNonNull(System.getenv("HEPHAESTUS_LIVE_GITLAB_TOKEN_FILE"))))
                .trim();
    }

    void create() throws IOException, InterruptedException {
        userId = request(
                        "POST",
                        "/users",
                        Map.ofEntries(
                                Map.entry("username", username),
                                Map.entry("name", "Identity proof"),
                                Map.entry("email", email),
                                Map.entry("password", UUID.randomUUID() + "aA1!"),
                                Map.entry("skip_confirmation", true)),
                        201)
                .path("id")
                .asLong();
        parentId = group(groupPath(), null);
        var issued = request(
                "POST",
                "/groups/" + parentId + "/access_tokens",
                Map.of(
                        "name",
                        "Identity proof",
                        "scopes",
                        List.of("api"),
                        "access_level",
                        40,
                        "expires_at",
                        LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()),
                201);
        groupTokenId = issued.path("id").asLong();
        connectionToken = issued.path("token").asString();
        assertThat(connectionToken).isNotBlank();
        assertThat(requestWithToken("GET", "/user", null, 200, connectionToken)
                        .path("is_admin")
                        .asBoolean())
                .isFalse();
        requestWithToken("GET", "/application/settings", null, 403, connectionToken);
        sourceId = group("before", parentId);
        targetId = group("after", parentId);
        var project = request(
                "POST",
                "/projects",
                Map.of(
                        "name",
                        "identity",
                        "namespace_id",
                        sourceId,
                        "initialize_with_readme",
                        true,
                        "visibility",
                        "private"),
                201);
        projectId = project.path("id").asLong();
        projectPath = project.path("path_with_namespace").asString();
    }

    private long group(String path, @Nullable Long parent) throws IOException, InterruptedException {
        var data = parent == null
                ? Map.<String, Object>of("name", path, "path", path)
                : Map.<String, Object>of("name", path, "path", path, "parent_id", parent);
        long id = request("POST", "/groups", data, 201).path("id").asLong();
        return id;
    }

    record Commit(String sha, String authorEmail, String committerEmail) {}

    Commit commit(String file) throws IOException, InterruptedException {
        var commit = request(
                "POST",
                "/projects/" + projectId + "/repository/commits",
                Map.of(
                        "branch",
                        "main",
                        "commit_message",
                        "Identity proof",
                        "author_name",
                        "Identity proof",
                        "author_email",
                        email,
                        "actions",
                        List.of(Map.of("action", "create", "file_path", file, "content", "proof\n"))),
                201);
        return new Commit(
                commit.path("id").asString(),
                commit.path("author_email").asString(),
                commit.path("committer_email").asString());
    }

    void rename() throws IOException, InterruptedException {
        username += "-renamed";
        var user = request("PUT", "/users/" + userId, Map.of("username", username), 200);
        assertThat(user.path("id").asLong()).isEqualTo(userId);
    }

    void changeEmail() throws IOException, InterruptedException {
        String old = email;
        email = unique + "-changed@example.invalid";
        request("PUT", "/users/" + userId, Map.of("email", email, "skip_reconfirmation", true), 200);
        request("PUT", "/users/" + userId, Map.of("public_email", email), 200);
        for (var address : request("GET", "/users/" + userId + "/emails", null, 200)) {
            if (old.equals(address.path("email").asString())) {
                request(
                        "DELETE",
                        "/users/" + userId + "/emails/" + address.path("id").asLong(),
                        null,
                        204);
            }
        }
    }

    void transfer() throws IOException, InterruptedException {
        var project = request("PUT", "/projects/" + projectId + "/transfer", Map.of("namespace", targetId), 200);
        assertThat(project.path("id").asLong()).isEqualTo(projectId);
        // GitLab returns the pre-transfer project while its asynchronous transfer runs.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var moved = request("GET", "/projects/" + projectId, null, 200);
            assertThat(moved.path("id").asLong()).isEqualTo(projectId);
            assertThat(moved.path("namespace").path("id").asLong()).isEqualTo(targetId);
            String movedPath = moved.path("path_with_namespace").asString();
            assertThat(movedPath).isNotEqualTo(projectPath);
            projectPath = movedPath;
        });
    }

    String version() throws IOException, InterruptedException {
        return request("GET", "/version", null, 200).path("version").asString();
    }

    private JsonNode request(String method, String path, @Nullable Map<String, Object> body, int expected)
            throws IOException, InterruptedException {
        return requestWithToken(method, path, body, expected, token);
    }

    private JsonNode requestWithToken(
            String method, String path, @Nullable Map<String, Object> body, int expected, String credential)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(server + "/api/v4" + path))
                .timeout(Duration.ofSeconds(30))
                .header("PRIVATE-TOKEN", credential)
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("GitLab fixture %s %s", method, path)
                .isEqualTo(expected);
        return expected == 204 ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    String server() {
        return server;
    }

    String token() {
        return connectionToken;
    }

    String groupPath() {
        return unique + "-group";
    }

    long groupId() {
        return parentId;
    }

    long userId() {
        return userId;
    }

    long projectId() {
        return projectId;
    }

    String username() {
        return username;
    }

    String email() {
        return email;
    }

    String projectPath() {
        return projectPath;
    }

    @Override
    public void close() throws IOException {
        try {
            try {
                try {
                    if (groupTokenId != 0)
                        request("DELETE", "/groups/" + parentId + "/access_tokens/" + groupTokenId, null, 204);
                } finally {
                    if (parentId != 0) request("DELETE", "/groups/" + parentId, null, 202);
                }
            } finally {
                if (userId != 0) request("DELETE", "/users/" + userId + "?hard_delete=true", null, 204);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("GitLab identity fixture cleanup was interrupted", interrupted);
        } finally {
            http.close();
        }
    }
}
