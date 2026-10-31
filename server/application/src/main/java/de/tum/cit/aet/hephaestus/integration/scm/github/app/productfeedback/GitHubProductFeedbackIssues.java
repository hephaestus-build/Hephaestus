package de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnServerRole
@EgressExempt(EgressExemption.PRODUCT_FEEDBACK)
public class GitHubProductFeedbackIssues implements ProductFeedbackIssues, ProductFeedbackIssueUpdates {

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final String ISSUE_TYPES = """
            query($id: ID!, $after: String) {
              node(id: $id) { ... on Repository {
                owner { __typename ... on Organization {
                  issueTypes(first: 100, after: $after) {
                    nodes { name } pageInfo { hasNextPage endCursor }
                  }
                } }
              } }
            }
            """;
    private static final String TRANSFER = """
            mutation($issue: ID!, $repository: ID!) {
              transferIssue(input: {issueId: $issue, repositoryId: $repository}) {
                issue { number id url repository { id } }
              }
            }
            """;
    private static final String COMMENTS = """
            fragment Comments on Issue {
              comments(first: 100, after: $after) {
                nodes { fullDatabaseId author { login } body createdAt }
                pageInfo { hasNextPage endCursor }
              }
            }
            """;
    private static final String STATES = """
            query($ids: [ID!]!, $after: String) {
              nodes(ids: $ids) { __typename ... on Issue {
                id state stateReason
                timelineItems(last: 1, itemTypes: [MARKED_AS_DUPLICATE_EVENT, UNMARKED_AS_DUPLICATE_EVENT]) {
                  nodes { __typename ... on MarkedAsDuplicateEvent { canonical { ... on Issue { id } } } }
                }
                ...Comments
              } }
            }
            """ + COMMENTS;
    private static final String MORE_COMMENTS = """
            query($id: ID!, $after: String) {
              node(id: $id) { ... on Issue { ...Comments } }
            }
            """ + COMMENTS;

    private final GitHubAppTokenService tokens;
    private final OutboundEgressGuard egress;
    private final RestClient api;
    private final URI apiBase;
    private volatile @Nullable String appLogin;

    public GitHubProductFeedbackIssues(
            GitHubAppTokenService tokens,
            OutboundEgressGuard egress,
            RestClient.Builder restClients,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this.tokens = tokens;
        this.egress = egress;
        this.apiBase = URI.create(apiBaseUrl);
        this.api = restClients
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(20))
                                .withRedirects(HttpRedirects.DONT_FOLLOW)))
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Override
    public Optional<Repository> repository(String fullName) {
        if (!fullName.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) {
            throw new Refused("A GitHub repository must have an owner and a name.");
        }
        String jwt = appJwt();
        var installation = getOptional("/repos/" + fullName + "/installation", jwt);
        if (installation.isEmpty()) return Optional.empty();
        long installationId = number(installation.get(), "id");
        var token = reachableInstallationToken(installationId);
        if (token.isEmpty()) return Optional.empty();
        var repository = getOptional("/repos/" + fullName, token.get());
        return repository.map(node -> new Repository(
                number(node, "id"),
                text(node, "node_id"),
                text(node, "full_name"),
                installationId,
                bool(node, "private")));
    }

    @Override
    public Optional<Boolean> isPrivate(Repository repository) {
        var token = reachableInstallationToken(repository.installationId());
        if (token.isEmpty()) return Optional.empty();
        return getOptional(repositoryPath(repository), token.get()).map(node -> {
            requireRepository(node, repository);
            return bool(node, "private");
        });
    }

    @Override
    public List<String> issueTypes(Repository repository) {
        String token = installationToken(repository.installationId());
        var result = new ArrayList<String>();
        String cursor = null;
        var cursors = new HashSet<String>();
        do {
            var variables = variables("id", repository.nodeId(), cursor);
            JsonNode node = graphQl(token, ISSUE_TYPES, variables, false).path("node");
            if (node.isNull() || node.isMissingNode()) throw incomplete();
            JsonNode owner = node.path("owner");
            if (text(owner, "__typename").equals("User")) return List.of();
            if (!text(owner, "__typename").equals("Organization")) throw incomplete();
            JsonNode connection = owner.path("issueTypes");
            for (JsonNode type : array(connection.path("nodes"))) result.add(text(type, "name"));
            cursor = nextCursor(connection, cursors);
        } while (cursor != null);
        return List.copyOf(result);
    }

    @Override
    public List<OpenIssue> openIssues(Repository repository, int limit) {
        if (limit <= 0) return List.of();
        String token = installationToken(repository.installationId());
        String path = repositoryPath(repository) + "/issues?state=open&sort=updated&direction=desc&per_page=100";
        var result = new ArrayList<OpenIssue>();
        var pages = new HashSet<String>();
        while (path != null) {
            requireNewPage(path, pages);
            var response = request(HttpMethod.GET, path, token, null);
            for (JsonNode issue : array(body(response))) {
                if (!issue.has("pull_request")) {
                    result.add(new OpenIssue(number(issue, "number"), text(issue, "title")));
                    if (result.size() == limit) return List.copyOf(result);
                }
            }
            path = nextPage(response.getHeaders(), path);
        }
        return List.copyOf(result);
    }

    @Override
    public Issue create(Repository repository, String title, String body, @Nullable String type) {
        allowWrite("product-feedback.create");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("body", body);
        if (type != null) payload.put("type", type);
        return restIssue(body(request(
                HttpMethod.POST,
                repositoryPath(repository) + "/issues",
                installationToken(repository.installationId()),
                payload)));
    }

    @Override
    public Optional<Issue> findByMarker(Repository repository, String marker, Instant since) {
        if (marker.isBlank()) throw new Refused("The GitHub reconciliation marker must not be empty.");
        try {
            String creator = login();
            String token = installationToken(repository.installationId());
            String path = repositoryPath(repository) + "/issues?creator=" + creator + "&state=all&since=" + since
                    + "&per_page=100";
            Issue found = null;
            var pages = new HashSet<String>();
            while (path != null) {
                requireNewPage(path, pages);
                var response = request(HttpMethod.GET, path, token, null);
                for (JsonNode issue : array(body(response))) {
                    if (issue.has("pull_request")) continue;
                    JsonNode issueBody = issue.path("body");
                    if (!issueBody.isNull() && !issueBody.isString()) throw incomplete();
                    if (issueBody.isString() && issueBody.asString().contains(marker)) {
                        if (!text(issue.path("user"), "login").equals(creator)) continue;
                        Issue match = restIssue(issue);
                        if (found != null && !found.nodeId().equals(match.nodeId())) {
                            throw new Unknown("More than one GitHub issue has the reconciliation marker.", null);
                        }
                        found = match;
                    }
                }
                path = nextPage(response.getHeaders(), path);
            }
            return Optional.ofNullable(found);
        } catch (Refused failure) {
            throw new Unknown("GitHub reconciliation did not read every page.", failure);
        }
    }

    @Override
    public Issue transfer(Issue issue, Repository target) {
        allowWrite("product-feedback.transfer");
        JsonNode transferred = graphQl(
                        installationToken(target.installationId()),
                        TRANSFER,
                        Map.of("issue", issue.nodeId(), "repository", target.nodeId()),
                        false)
                .path("transferIssue")
                .path("issue");
        if (!text(transferred.path("repository"), "id").equals(target.nodeId())) throw incomplete();
        return new Issue(number(transferred, "number"), text(transferred, "id"), text(transferred, "url"));
    }

    @Override
    public void edit(Repository repository, long number, String title, String body) {
        allowWrite("product-feedback.edit");
        request(
                HttpMethod.PATCH,
                issuePath(repository, number),
                installationToken(repository.installationId()),
                Map.of("title", title, "body", body));
    }

    @Override
    public void close(Repository repository, long number, String reason) {
        allowWrite("product-feedback.close");
        if (!Set.of("completed", "not_planned").contains(reason))
            throw new Refused("Invalid GitHub issue close reason.");
        request(
                HttpMethod.PATCH,
                issuePath(repository, number),
                installationToken(repository.installationId()),
                Map.of("state", "closed", "state_reason", reason));
    }

    @Override
    public void comment(Repository repository, long number, String body) {
        allowWrite("product-feedback.comment");
        request(
                HttpMethod.POST,
                issuePath(repository, number) + "/comments",
                installationToken(repository.installationId()),
                Map.of("body", body));
    }

    @Override
    public List<IssueState> states(List<String> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        String ownLogin = login();
        var remaining = new HashSet<>(nodeIds);
        var found = new LinkedHashMap<String, IssueState>();
        // GraphQL does not accept App JWTs, and this SPI has no installation argument.
        // Enumerate the app's installations so following also works after a server restart.
        for (long installation : installations()) {
            if (remaining.isEmpty()) break;
            String token = installationToken(installation);
            var pending = List.copyOf(remaining);
            for (int start = 0; start < pending.size(); start += 100) {
                var batch = pending.subList(start, Math.min(start + 100, pending.size()));
                JsonNode data = graphQl(token, STATES, variables("ids", batch, null), true);
                JsonNode nodes = array(data.path("nodes"));
                if (nodes.size() != batch.size()) throw incomplete();
                for (JsonNode node : nodes) {
                    if (node.isNull()) continue;
                    if (!text(node, "__typename").equals("Issue")) throw incomplete();
                    String id = text(node, "id");
                    if (!batch.contains(id)) throw incomplete();
                    var comments = comments(token, node, ownLogin);
                    String state = text(node, "state");
                    if (!state.equals("OPEN") && !state.equals("CLOSED")) throw incomplete();
                    String reason = nullableText(node, "stateReason");
                    if (state.equals("OPEN")) reason = null;
                    if (reason != null) {
                        reason = reason.toLowerCase(Locale.ROOT);
                        if (!Set.of("completed", "not_planned", "duplicate").contains(reason)) throw incomplete();
                    }
                    JsonNode events = array(node.path("timelineItems").path("nodes"));
                    String duplicate = null;
                    if ("duplicate".equals(reason) && !events.isEmpty()) {
                        JsonNode event = Objects.requireNonNull(events.get(events.size() - 1));
                        if (text(event, "__typename").equals("MarkedAsDuplicateEvent")) {
                            JsonNode canonical = event.path("canonical");
                            if (!canonical.isNull()) duplicate = nullableText(canonical, "id");
                        }
                    }
                    found.put(id, new IssueState(id, state.equals("OPEN"), reason, comments, duplicate));
                    remaining.remove(id);
                }
            }
        }
        var result = new ArrayList<IssueState>();
        for (String id : nodeIds.stream().distinct().toList()) {
            IssueState state = found.get(id);
            if (state != null) result.add(state);
        }
        return List.copyOf(result);
    }

    @Override
    public String permission(Repository repository, String login) {
        String encodedPath = UriComponentsBuilder.fromPath(
                        repositoryPath(repository) + "/collaborators/{login}/permission")
                .encode()
                .buildAndExpand(login)
                .toUriString();
        var response = getOptional(encodedPath, installationToken(repository.installationId()));
        if (response.isEmpty()) return "none";
        String permission = text(response.get(), "permission");
        if (!Set.of("admin", "write", "read", "none").contains(permission)) throw incomplete();
        return permission;
    }

    private List<Comment> comments(String token, JsonNode issue, String ownLogin) {
        var result = new ArrayList<Comment>();
        var cursors = new HashSet<String>();
        JsonNode connection = issue.path("comments");
        String cursor;
        do {
            for (JsonNode comment : array(connection.path("nodes"))) {
                String author = comment.path("author").isNull() ? "" : text(comment.path("author"), "login");
                Instant createdAt;
                try {
                    createdAt = Instant.parse(text(comment, "createdAt"));
                } catch (DateTimeException failure) {
                    throw new Unknown("GitHub returned an invalid comment date.", failure);
                }
                result.add(new Comment(
                        commentId(comment), author, author.equals(ownLogin), text(comment, "body"), createdAt));
            }
            cursor = nextCursor(connection, cursors);
            if (cursor != null) {
                connection = graphQl(token, MORE_COMMENTS, variables("id", text(issue, "id"), cursor), false)
                        .path("node")
                        .path("comments");
            }
        } while (cursor != null);
        return List.copyOf(result);
    }

    private List<Long> installations() {
        var result = new ArrayList<Long>();
        String jwt = appJwt();
        String path = "/app/installations?per_page=100";
        var pages = new HashSet<String>();
        while (path != null) {
            requireNewPage(path, pages);
            var response = request(HttpMethod.GET, path, jwt, null);
            for (JsonNode installation : array(body(response))) result.add(number(installation, "id"));
            path = nextPage(response.getHeaders(), path);
        }
        if (result.isEmpty()) throw new Unknown("The GitHub App has no installation to read issue states.", null);
        return result;
    }

    private String login() {
        String cached = appLogin;
        if (cached != null) return cached;
        synchronized (this) {
            cached = appLogin;
            if (cached == null) {
                cached = text(body(request(HttpMethod.GET, "/app", appJwt(), null)), "slug") + "[bot]";
                appLogin = cached;
            }
            return cached;
        }
    }

    private String appJwt() {
        try {
            return tokens.generateAppJWT();
        } catch (RuntimeException failure) {
            throw new Unknown("GitHub App authentication is unavailable.", failure);
        }
    }

    /** Silent Mode holds every write; the caller retries later and reconciles by the marker first. */
    private void allowWrite(String operation) {
        if (!egress.deliveryAllowed(operation)) throw new Unknown("Silent Mode holds product feedback writes.", null);
    }

    private Optional<String> reachableInstallationToken(long installationId) {
        if (tokens.isInstallationMarkedSuspended(installationId)) return Optional.empty();
        try {
            return Optional.of(tokens.getInstallationToken(installationId));
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof WebClientResponseException response) {
                    if (response.getStatusCode().value() == 404) return Optional.empty();
                    throw httpFailure(response.getStatusCode().value(), response.getHeaders());
                }
            }
            throw new Unknown("GitHub installation authentication is unavailable.", failure);
        }
    }

    private String installationToken(long installationId) {
        return reachableInstallationToken(installationId)
                .orElseThrow(() -> new Refused("The frozen GitHub installation is unavailable."));
    }

    private Optional<JsonNode> getOptional(String path, String token) {
        var response = request(HttpMethod.GET, path, token, null, true);
        if (response.getStatusCode().value() == 404) return Optional.empty();
        return Optional.of(body(response));
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String token, @Nullable Object payload) {
        return request(method, path, token, payload, false);
    }

    private ResponseEntity<JsonNode> request(
            HttpMethod method, String path, String token, @Nullable Object payload, boolean allowMissing) {
        try {
            var request = api.method(method).uri(checkedUri(path)).headers(headers -> headers.setBearerAuth(token));
            if (payload != null) request.contentType(MediaType.APPLICATION_JSON).body(payload);
            return request.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                if (allowMissing && status == 404)
                    return ResponseEntity.status(404).build();
                HttpHeaders headers = response.getHeaders();
                if (status < 200 || status >= 300) throw httpFailure(status, headers);
                return ResponseEntity.status(status).headers(headers).body(response.bodyTo(JsonNode.class));
            });
        } catch (RestClientException failure) {
            throw new Unknown("GitHub request outcome is unknown after a transport or response error.", failure);
        }
    }

    private static RuntimeException httpFailure(int status, HttpHeaders headers) {
        if (status == 429
                || (status == 403
                        && (headers.containsHeader(HttpHeaders.RETRY_AFTER)
                                || "0".equals(headers.getFirst("x-ratelimit-remaining"))))) {
            return new Unknown("GitHub rate limit reached. Retry at " + retryAt(headers) + ".", null);
        }
        if (status >= 400 && status < 500) return new Refused("GitHub refused the request (HTTP " + status + ").");
        return new Unknown("GitHub request outcome is unknown (HTTP " + status + ").", null);
    }

    private JsonNode graphQl(String token, String query, Map<String, Object> variables, boolean allowMissingNodes) {
        var httpResponse = request(HttpMethod.POST, "/graphql", token, Map.of("query", query, "variables", variables));
        JsonNode response = body(httpResponse);
        JsonNode errors = response.path("errors");
        if (!errors.isMissingNode()) {
            for (JsonNode error : array(errors)) {
                String type = error.path("type").asString("");
                if (type.equals("RATE_LIMITED")) throw httpFailure(429, httpResponse.getHeaders());
                if (allowMissingNodes
                        && type.equals("NOT_FOUND")
                        && error.path("path").path(0).asString("").equals("nodes")) continue;
                if (Set.of("FORBIDDEN", "UNPROCESSABLE", "NOT_FOUND", "GRAPHQL_VALIDATION_FAILED")
                        .contains(type)) {
                    throw new Refused("GitHub refused the GraphQL request (" + type + ").");
                }
                throw new Unknown("GitHub GraphQL request outcome is unknown.", null);
            }
        }
        JsonNode data = response.path("data");
        if (!data.isObject()) throw incomplete();
        return data;
    }

    private URI checkedUri(String path) {
        URI uri;
        try {
            uri = apiBase.resolve(path);
        } catch (IllegalArgumentException failure) {
            throw new Unknown("GitHub returned an invalid API link.", failure);
        }
        if (!Objects.equals(uri.getScheme(), apiBase.getScheme())
                || !Objects.equals(uri.getAuthority(), apiBase.getAuthority())
                || uri.getUserInfo() != null) {
            throw new Unknown("GitHub returned a pagination link outside its API.", null);
        }
        return uri;
    }

    private @Nullable String nextPage(HttpHeaders headers, String currentPath) {
        for (String link : headers.getOrEmpty(HttpHeaders.LINK)) {
            var matcher = NEXT_LINK.matcher(link);
            if (matcher.find()) {
                URI next = checkedUri(matcher.group(1));
                String query = next.getRawQuery();
                if (query == null) throw incomplete();
                return checkedUri(currentPath).getRawPath() + "?" + query;
            }
            if (link.contains("rel=\"next\"")) throw incomplete();
        }
        return null;
    }

    private static void requireNewPage(String path, Set<String> pages) {
        if (!pages.add(path)) throw new Unknown("GitHub repeated a pagination page.", null);
    }

    private static @Nullable String nextCursor(JsonNode connection, Set<String> cursors) {
        JsonNode pageInfo = connection.path("pageInfo");
        if (!bool(pageInfo, "hasNextPage")) return null;
        String cursor = text(pageInfo, "endCursor");
        if (cursor.isBlank() || !cursors.add(cursor)) throw incomplete();
        return cursor;
    }

    private static Map<String, Object> variables(String name, Object value, @Nullable String cursor) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put(name, value);
        if (cursor != null) variables.put("after", cursor);
        return variables;
    }

    private static Instant retryAt(HttpHeaders headers) {
        Instant now = Instant.now();
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        String reset = headers.getFirst("x-ratelimit-reset");
        try {
            if (retryAfter != null) {
                try {
                    return now.plusSeconds(Math.max(0, Long.parseLong(retryAfter)));
                } catch (NumberFormatException ignored) {
                    return ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                }
            }
            if (reset != null) return Instant.ofEpochSecond(Long.parseLong(reset));
        } catch (DateTimeException | NumberFormatException | ArithmeticException ignored) {
            // A malformed header must not turn a rate limit into a permanent refusal.
        }
        return now.plusSeconds(60);
    }

    private static String repositoryPath(Repository repository) {
        // GitHub's numeric repository routes keep a renamed or reused name from retargeting a frozen report.
        return "/repositories/" + repository.id();
    }

    private static String issuePath(Repository repository, long number) {
        return repositoryPath(repository) + "/issues/" + number;
    }

    private static void requireRepository(JsonNode node, Repository repository) {
        if (number(node, "id") != repository.id() || !text(node, "node_id").equals(repository.nodeId()))
            throw incomplete();
    }

    private static Issue restIssue(JsonNode issue) {
        return new Issue(number(issue, "number"), text(issue, "node_id"), text(issue, "html_url"));
    }

    private static JsonNode body(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        if (body == null || body.isNull()) throw incomplete();
        return body;
    }

    private static JsonNode array(JsonNode node) {
        if (!node.isArray()) throw incomplete();
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw incomplete();
        return value.asString();
    }

    private static @Nullable String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull()) return null;
        return text(node, field);
    }

    private static long commentId(JsonNode comment) {
        JsonNode value = comment.path("fullDatabaseId");
        if (value.isIntegralNumber()) return number(comment, "fullDatabaseId");
        if (!value.isString()) throw incomplete();
        try {
            long id = Long.parseLong(value.asString());
            if (id <= 0) throw incomplete();
            return id;
        } catch (NumberFormatException failure) {
            throw new Unknown("GitHub returned an invalid comment id.", failure);
        }
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw incomplete();
        return value.asLong();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) throw incomplete();
        return value.asBoolean();
    }

    private static Unknown incomplete() {
        return new Unknown("GitHub returned an incomplete response.", null);
    }
}
