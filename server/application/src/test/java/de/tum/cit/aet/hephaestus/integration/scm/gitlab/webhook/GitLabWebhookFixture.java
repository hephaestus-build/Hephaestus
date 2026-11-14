package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the disposable GitLab resources of one live webhook proof: a group, its project, a non-admin group token, one
 * project hook and one entry of the instance's local-network allowlist. Closing it removes each of them and restores
 * the allowlist it found.
 */
final class GitLabWebhookFixture implements AutoCloseable {
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String server;
    private final String setupToken;
    private final String unique = "heph-webhook-" + UUID.randomUUID().toString().substring(0, 8);
    private String connectionToken = "";
    private long groupId;
    private long groupTokenId;
    private long projectId;
    private String projectPath = "";
    private long hookId;
    private @Nullable List<String> allowlistBefore;

    record Created(long id, int iid) {}

    GitLabWebhookFixture(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        server = Objects.requireNonNull(System.getenv("HEPHAESTUS_LIVE_GITLAB_URL"));
        setupToken = Files.readString(
                        Path.of(Objects.requireNonNull(System.getenv("HEPHAESTUS_LIVE_GITLAB_TOKEN_FILE"))))
                .trim();
    }

    void create() throws IOException, InterruptedException {
        groupId = request("POST", "/groups", Map.of("name", groupPath(), "path", groupPath()), 201)
                .path("id")
                .asLong();
        var issued = request(
                "POST",
                "/groups/" + groupId + "/access_tokens",
                Map.of(
                        "name",
                        "Webhook proof",
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
        // The connection's credential is not an instance administrator.
        requestWithToken("GET", "/application/settings", null, 403, connectionToken);
        var project = request(
                "POST",
                "/projects",
                Map.of(
                        "name",
                        "webhook",
                        "namespace_id",
                        groupId,
                        "initialize_with_readme",
                        true,
                        "visibility",
                        "private"),
                201);
        projectId = project.path("id").asLong();
        projectPath = project.path("path_with_namespace").asString();
    }

    /**
     * Lets GitLab post to {@code hostAndPort} on its local network, and nowhere else that it could not reach before.
     * GitLab refuses a hook to a local address unless an administrator allows it.
     */
    void allowLocal(String hostAndPort) throws IOException, InterruptedException {
        List<String> before = new ArrayList<>();
        for (JsonNode entry : request("GET", "/application/settings", null, 200).path("outbound_local_requests_whitelist"))
            before.add(entry.asString());
        allowlistBefore = before;
        List<String> allowed = new ArrayList<>(before);
        allowed.add(hostAndPort);
        request("PUT", "/application/settings", Map.of("outbound_local_requests_whitelist", allowed), 200);
    }

    /**
     * A project hook to {@code url}. Hephaestus registers a group hook, which GitLab offers only in its Premium and
     * Ultimate tiers; a project hook delivers the same project events to the same connection route.
     */
    void hook(String url, String token) throws IOException, InterruptedException {
        hookId = request(
                        "POST",
                        "/projects/" + projectId + "/hooks",
                        Map.of(
                                "url",
                                url,
                                "token",
                                token,
                                "issues_events",
                                true,
                                "merge_requests_events",
                                true,
                                "push_events",
                                false,
                                "enable_ssl_verification",
                                false),
                        201)
                .path("id")
                .asLong();
    }

    Created issue(String title) throws IOException, InterruptedException {
        var issue = request("POST", "/projects/" + projectId + "/issues", Map.of("title", title), 201);
        return new Created(issue.path("id").asLong(), issue.path("iid").asInt());
    }

    Created mergeRequest(String title) throws IOException, InterruptedException {
        request("POST", "/projects/" + projectId + "/repository/branches", Map.of("branch", "proof", "ref", "main"), 201);
        request(
                "POST",
                "/projects/" + projectId + "/repository/commits",
                Map.of(
                        "branch",
                        "proof",
                        "commit_message",
                        "Webhook proof",
                        "actions",
                        List.of(Map.of("action", "create", "file_path", "proof.txt", "content", "proof\n"))),
                201);
        var created = request(
                "POST",
                "/projects/" + projectId + "/merge_requests",
                Map.of("source_branch", "proof", "target_branch", "main", "title", title),
                201);
        return new Created(created.path("id").asLong(), created.path("iid").asInt());
    }

    void retitleMergeRequest(int iid, String title) throws IOException, InterruptedException {
        request("PUT", "/projects/" + projectId + "/merge_requests/" + iid, Map.of("title", title), 200);
    }

    /** GitLab's own record of the hook's deliveries, newest first. */
    JsonNode deliveries() throws IOException, InterruptedException {
        return request("GET", "/projects/" + projectId + "/hooks/" + hookId + "/events", null, 200);
    }

    String version() throws IOException, InterruptedException {
        return request("GET", "/version", null, 200).path("version").asString();
    }

    private JsonNode request(String method, String path, @Nullable Map<String, Object> body, int expected)
            throws IOException, InterruptedException {
        return requestWithToken(method, path, body, expected, setupToken);
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
        return expected == 204 || expected == 202 ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    String server() {
        return server;
    }

    String token() {
        return connectionToken;
    }

    String groupPath() {
        return unique;
    }

    long groupId() {
        return groupId;
    }

    long projectId() {
        return projectId;
    }

    String projectPath() {
        return projectPath;
    }

    @Override
    public void close() throws IOException {
        try {
            try {
                try {
                    if (hookId != 0) request("DELETE", "/projects/" + projectId + "/hooks/" + hookId, null, 204);
                } finally {
                    List<String> before = allowlistBefore;
                    if (before != null)
                        request(
                                "PUT",
                                "/application/settings",
                                Map.of("outbound_local_requests_whitelist", before),
                                200);
                }
            } finally {
                try {
                    if (groupTokenId != 0)
                        request("DELETE", "/groups/" + groupId + "/access_tokens/" + groupTokenId, null, 204);
                } finally {
                    if (groupId != 0) request("DELETE", "/groups/" + groupId, null, 202);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("GitLab webhook fixture cleanup was interrupted", interrupted);
        } finally {
            http.close();
        }
    }
}
