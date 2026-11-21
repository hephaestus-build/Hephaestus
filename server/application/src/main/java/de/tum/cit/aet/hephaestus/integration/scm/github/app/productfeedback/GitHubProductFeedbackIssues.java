package de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.array;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.body;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.bool;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.incomplete;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.nullableText;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.number;
import static de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback.GitHubProductFeedbackApi.text;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnServerRole
@EgressExempt(EgressExemption.PRODUCT_FEEDBACK)
public class GitHubProductFeedbackIssues implements ProductFeedbackIssues, ProductFeedbackIssueUpdates {

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

    private final GitHubProductFeedbackApi github;
    private volatile @Nullable String appLogin;

    public GitHubProductFeedbackIssues(
            GitHubAppTokenService tokens,
            OutboundEgressGuard egress,
            RestClient.Builder restClients,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this.github = new GitHubProductFeedbackApi(tokens, egress, restClients, apiBaseUrl);
    }

    @Override
    public Optional<Repository> repository(String fullName) {
        if (!fullName.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) {
            throw new Refused("A GitHub repository must have an owner and a name.");
        }
        String jwt = github.appJwt();
        var installation = github.getOptional("/repos/" + fullName + "/installation", jwt);
        if (installation.isEmpty()) return Optional.empty();
        long installationId = number(installation.get(), "id");
        var token = github.reachableInstallationToken(installationId);
        if (token.isEmpty()) return Optional.empty();
        var repository = github.getOptional("/repos/" + fullName, token.get());
        return repository.map(node -> new Repository(
                number(node, "id"),
                text(node, "node_id"),
                text(node, "full_name"),
                installationId,
                bool(node, "private")));
    }

    @Override
    public Optional<Boolean> isPrivate(Repository repository) {
        var token = github.reachableInstallationToken(repository.installationId());
        if (token.isEmpty()) return Optional.empty();
        return github.getOptional(repositoryPath(repository), token.get()).map(node -> {
            requireRepository(node, repository);
            return bool(node, "private");
        });
    }

    @Override
    public List<String> issueTypes(Repository repository) {
        String token = github.installationToken(repository.installationId());
        var result = new ArrayList<String>();
        String cursor = null;
        var cursors = new HashSet<String>();
        do {
            var variables = variables("id", repository.nodeId(), cursor);
            JsonNode node = github.graphQl(token, ISSUE_TYPES, variables, false).path("node");
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
        String token = github.installationToken(repository.installationId());
        String path = repositoryPath(repository) + "/issues?state=open&sort=updated&direction=desc&per_page=100";
        var result = new ArrayList<OpenIssue>();
        var pages = new HashSet<String>();
        while (path != null) {
            requireNewPage(path, pages);
            var response = github.request(HttpMethod.GET, path, token, null);
            for (JsonNode issue : array(body(response))) {
                if (!issue.has("pull_request")) {
                    result.add(new OpenIssue(number(issue, "number"), text(issue, "title")));
                    if (result.size() == limit) return List.copyOf(result);
                }
            }
            path = github.nextPage(response.getHeaders(), path);
        }
        return List.copyOf(result);
    }

    @Override
    public Issue create(Repository repository, String title, String body, @Nullable String type) {
        github.allowWrite("product-feedback.create");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("body", body);
        if (type != null) payload.put("type", type);
        return restIssue(body(github.request(
                HttpMethod.POST,
                repositoryPath(repository) + "/issues",
                github.installationToken(repository.installationId()),
                payload)));
    }

    @Override
    public Optional<Issue> findByMarker(Repository repository, String marker, Instant since) {
        if (marker.isBlank()) throw new Refused("The GitHub reconciliation marker must not be empty.");
        try {
            String creator = login();
            String token = github.installationToken(repository.installationId());
            String path = repositoryPath(repository) + "/issues?creator=" + creator + "&state=all&since=" + since
                    + "&per_page=100";
            Issue found = null;
            var pages = new HashSet<String>();
            while (path != null) {
                requireNewPage(path, pages);
                var response = github.request(HttpMethod.GET, path, token, null);
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
                path = github.nextPage(response.getHeaders(), path);
            }
            return Optional.ofNullable(found);
        } catch (Refused failure) {
            throw new Unknown("GitHub reconciliation did not read every page.", failure);
        }
    }

    @Override
    public Issue transfer(Issue issue, Repository target) {
        github.allowWrite("product-feedback.transfer");
        JsonNode transferred = github.graphQl(
                        github.installationToken(target.installationId()),
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
        github.allowWrite("product-feedback.edit");
        github.request(
                HttpMethod.PATCH,
                issuePath(repository, number),
                github.installationToken(repository.installationId()),
                Map.of("title", title, "body", body));
    }

    @Override
    public void close(Repository repository, long number, String reason) {
        github.allowWrite("product-feedback.close");
        if (!Set.of("completed", "not_planned").contains(reason))
            throw new Refused("Invalid GitHub issue close reason.");
        github.request(
                HttpMethod.PATCH,
                issuePath(repository, number),
                github.installationToken(repository.installationId()),
                Map.of("state", "closed", "state_reason", reason));
    }

    @Override
    public void comment(Repository repository, long number, String body) {
        github.allowWrite("product-feedback.comment");
        github.request(
                HttpMethod.POST,
                issuePath(repository, number) + "/comments",
                github.installationToken(repository.installationId()),
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
            String token = github.installationToken(installation);
            var pending = List.copyOf(remaining);
            for (int start = 0; start < pending.size(); start += 100) {
                var batch = pending.subList(start, Math.min(start + 100, pending.size()));
                JsonNode data = github.graphQl(token, STATES, variables("ids", batch, null), true);
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
        var response = github.getOptional(encodedPath, github.installationToken(repository.installationId()));
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
                connection = github.graphQl(token, MORE_COMMENTS, variables("id", text(issue, "id"), cursor), false)
                        .path("node")
                        .path("comments");
            }
        } while (cursor != null);
        return List.copyOf(result);
    }

    private List<Long> installations() {
        var result = new ArrayList<Long>();
        String jwt = github.appJwt();
        String path = "/app/installations?per_page=100";
        var pages = new HashSet<String>();
        while (path != null) {
            requireNewPage(path, pages);
            var response = github.request(HttpMethod.GET, path, jwt, null);
            for (JsonNode installation : array(body(response))) result.add(number(installation, "id"));
            path = github.nextPage(response.getHeaders(), path);
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
                cached = text(body(github.request(HttpMethod.GET, "/app", github.appJwt(), null)), "slug") + "[bot]";
                appLogin = cached;
            }
            return cached;
        }
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
}
