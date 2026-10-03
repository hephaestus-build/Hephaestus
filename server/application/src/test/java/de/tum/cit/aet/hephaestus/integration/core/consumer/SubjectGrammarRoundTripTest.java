package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.github.webhook.GitHubSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * For every committed fixture, asserts the publisher-side subject built by the per-kind
 * {@code SubjectKeyDeriver} starts with the consumer-side prefix that
 * {@link ConsumerSubjectMath#buildSubjectPrefix(String, String)} subscribes to. This is the
 * producer↔consumer agreement guard documented in CLAUDE.md §8 — it catches the drift class
 * that per-side unit tests cannot see in isolation (a deriver that emits a subject no consumer
 * filter matches publishes into the stream but is never delivered).
 *
 * <p>Where namespace/project can't be reliably extracted (instance-level fallback to {@code ?}),
 * the consumer prefix doesn't apply; those fixtures are skipped.
 */
class SubjectGrammarRoundTripTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GITLAB_DIR = Paths.get("src/test/resources/gitlab");
    private static final Path GITHUB_DIR = Paths.get("src/test/resources/github");
    private static final Path OUTLINE_DIR = Paths.get("src/test/resources/outline");

    private static final GitHubSubjectKeyDeriver GITHUB = new GitHubSubjectKeyDeriver();
    private static final GitLabSubjectKeyDeriver GITLAB = new GitLabSubjectKeyDeriver();
    private static final OutlineSubjectKeyDeriver OUTLINE = new OutlineSubjectKeyDeriver(MAPPER);

    static Stream<Path> gitlabFixtures() throws IOException {
        return listJson(GITLAB_DIR);
    }

    static Stream<Path> githubFixtures() throws IOException {
        return listJson(GITHUB_DIR);
    }

    static Stream<Path> outlineFixtures() throws IOException {
        return listJson(OUTLINE_DIR);
    }

    private static Stream<Path> listJson(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "GitLab fixture {0}")
    @MethodSource("gitlabFixtures")
    void gitlabFixtureSubjectMatchesConsumerPrefix(Path fixture) throws IOException {
        JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
        // Mirror the deriver's rootGroupToken path sources that carry a namespace: a project's
        // path_with_namespace, then a subgroup's group.full_path / full_path. The full_path fallbacks are
        // what let subgroup.create (which has no path_with_namespace) reach the org-filter assertion instead
        // of being skipped by the '/' guard below.
        String pathWithNamespace =
                payload.path("project").path("path_with_namespace").asString("");
        if (pathWithNamespace.isEmpty()) {
            pathWithNamespace = payload.path("path_with_namespace").asString("");
        }
        if (pathWithNamespace.isEmpty()) {
            pathWithNamespace = payload.path("group").path("full_path").asString("");
        }
        if (pathWithNamespace.isEmpty()) {
            pathWithNamespace = payload.path("full_path").asString("");
        }
        // A leaf-only segment (e.g. member.add's group_path on a subgroup) cannot yield a root group and
        // is documented-unroutable from payload alone — legitimately skipped.
        if (!pathWithNamespace.contains("/")) {
            return;
        }
        String publisherSubject = GITLAB.deriveSubject(payload, Map.of());
        // Group-tier lifecycle events (project/subgroup/member) are ORG-scoped: they carry the '?'
        // placeholder in the project slot and route via the workspace's organizationFilter
        // (gitlab.<rootGroup>.?.>), not the repo prefix. Bind the derived subject to that filter through a
        // real NATS token match so a drift in EITHER buildSubjectPrefix/organizationFilter or the deriver's
        // group-tier subject format fails here.
        String[] subjectParts = publisherSubject.split("\\.", -1);
        if (subjectParts.length >= 3 && "?".equals(subjectParts[2])) {
            String rootGroup = pathWithNamespace.substring(0, pathWithNamespace.indexOf('/'));
            String orgFilter = ConsumerSubjectMath.organizationFilter("gitlab", rootGroup);
            assertThat(subjectMatchesFilter(publisherSubject, orgFilter))
                    .as(
                            "group-tier publisher %s (%s) should be matched by org filter '%s'",
                            fixture.getFileName(), publisherSubject, orgFilter)
                    .isTrue();
            return;
        }
        String consumerPrefix = ConsumerSubjectMath.buildSubjectPrefix("gitlab", pathWithNamespace);
        assertThat(publisherSubject)
                .as("publisher %s should start with consumer prefix '%s.'", fixture.getFileName(), consumerPrefix)
                .startsWith(consumerPrefix + ".");
    }

    /**
     * NATS subject/filter token match: {@code *} matches exactly one token, {@code >} matches one or more
     * trailing tokens, every other token must match literally (the {@code ?} org-scope placeholder is a
     * literal token here, not a wildcard). This is the delivery predicate a JetStream consumer applies, so
     * matching through it — rather than a prefix string compare — is what genuinely proves the derived
     * subject would be routed by the filter.
     */
    private static boolean subjectMatchesFilter(String subject, String filter) {
        String[] s = subject.split("\\.", -1);
        String[] f = filter.split("\\.", -1);
        for (int i = 0; i < f.length; i++) {
            if (">".equals(f[i])) {
                return i < s.length; // '>' requires at least one remaining subject token
            }
            if (i >= s.length) {
                return false;
            }
            if ("*".equals(f[i])) {
                continue;
            }
            if (!f[i].equals(s[i])) {
                return false;
            }
        }
        return s.length == f.length;
    }

    @ParameterizedTest(name = "GitHub fixture {0}")
    @MethodSource("githubFixtures")
    void githubFixtureSubjectMatchesConsumerPrefix(Path fixture) throws IOException {
        JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
        JsonNode repository = payload.path("repository");
        String owner = repository.path("owner").path("login").asString("");
        String repo = repository.path("name").asString("");
        if (owner.isEmpty() || repo.isEmpty()) {
            return;
        }
        String publisherSubject =
                GITHUB.deriveSubject(payload, Map.of("X-GitHub-Event", eventTypeFromFilename(fixture)));
        // Repository-lifecycle events (repository.renamed/.transferred/…) are ORG-scoped for the same
        // reason GitLab's group-tier events are: the repo-name token is unstable across a
        // rename/transfer, so the subject carries the '?' placeholder in the repo slot and must route
        // via the workspace's organizationFilter (github.<owner>.?.>), not the repo prefix. Bind that
        // through a real NATS token match so a drift in EITHER organizationFilter/buildSubjectPrefix or
        // the deriver's org-scoping fails here — the freeze-after-rename regression this guards.
        String[] subjectParts = publisherSubject.split("\\.", -1);
        if (subjectParts.length >= 3 && "?".equals(subjectParts[2])) {
            String orgFilter = ConsumerSubjectMath.organizationFilter("github", owner);
            assertThat(subjectMatchesFilter(publisherSubject, orgFilter))
                    .as(
                            "org-tier publisher %s (%s) should be matched by org filter '%s'",
                            fixture.getFileName(), publisherSubject, orgFilter)
                    .isTrue();
            return;
        }
        String consumerPrefix = ConsumerSubjectMath.buildSubjectPrefix("github", owner + "/" + repo);
        assertThat(publisherSubject)
                .as("publisher %s should start with consumer prefix '%s.'", fixture.getFileName(), consumerPrefix)
                .startsWith(consumerPrefix + ".");
    }

    /**
     * Anchors that the {@code ?}-slot branch above is actually exercised by GitHub fixtures. Without
     * this, re-tiering {@code repository} events back onto the repository tier (or losing the fixtures)
     * would make the org-filter assertion vacuous and the guard would pass while the rename freeze
     * silently returned.
     */
    @Test
    void githubRepositoryLifecycleFixturesAreOrgScoped() throws IOException {
        for (String name : List.of("repository.renamed.json", "repository.transferred.json")) {
            Path fixture = GITHUB_DIR.resolve(name);
            assertThat(Files.exists(fixture)).as("fixture %s must exist", name).isTrue();
            JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
            String owner =
                    payload.path("repository").path("owner").path("login").asString("");
            String subject = GITHUB.deriveSubject(payload, Map.of("X-GitHub-Event", "repository"));

            assertThat(subject).isEqualTo("github." + owner + ".?.repository");
            assertThat(subjectMatchesFilter(subject, ConsumerSubjectMath.organizationFilter("github", owner)))
                    .as("%s must be routed by the workspace org filter, not a (stale) repo filter", name)
                    .isTrue();
            // The repo filter built from the OLD name cannot match — which is why only the org tier heals a
            // rename in real time.
            assertThat(subjectMatchesFilter(
                            subject, ConsumerSubjectMath.repositoryFilter("github", owner + "/anything")))
                    .isFalse();
        }
    }

    @ParameterizedTest(name = "Outline fixture {0}")
    @MethodSource("outlineFixtures")
    void outlineFixtureSubjectMatchesConsumerPrefix(Path fixture) throws IOException {
        JsonNode payload = MAPPER.readTree(Files.readAllBytes(fixture));
        String subscriptionId = payload.path("webhookSubscriptionId").asString("");
        if (subscriptionId.isEmpty()) {
            return;
        }
        String publisherSubject = OUTLINE.deriveSubject(payload, Map.of());
        String consumerPrefix = consumerSubscriptionPrefix(subscriptionId);
        assertThat(publisherSubject)
                .as("publisher %s should start with consumer prefix '%s'", fixture.getFileName(), consumerPrefix)
                .startsWith(consumerPrefix);
    }

    /**
     * Explicit mixed-case guard for the Outline subscription id. Outline sends lowercase UUIDs today, so no
     * fixture carries uppercase, but the producer must pass the id through byte-for-byte to match the
     * consumer's subscription filter. If the deriver ever case-folds the id while the consumer (fed the
     * case-preserved stored id) does not, this fails — the exact producer↔consumer drift this suite guards.
     */
    @Test
    void outlineMixedCaseSubscriptionRoundTrips() throws IOException {
        String subscriptionId = "Sub-ABC-123";
        JsonNode payload = MAPPER.readTree(
                "{\"webhookSubscriptionId\":\"" + subscriptionId + "\",\"event\":\"documents.update\"}");
        String publisherSubject = OUTLINE.deriveSubject(payload, Map.of());
        assertThat(publisherSubject).startsWith(consumerSubscriptionPrefix(subscriptionId));
    }

    /** The consumer's {@code outline.<sub>.>} filter with its trailing wildcard stripped to a prefix. */
    private static String consumerSubscriptionPrefix(String subscriptionId) {
        String filter = ConsumerSubjectMath.subscriptionFilter("outline", subscriptionId);
        return filter.substring(0, filter.length() - 1); // drop the trailing '>'
    }

    /**
     * Explicit mixed-case guard: no committed fixture is guaranteed to carry uppercase, but GitLab
     * paths are case-sensitive and may. If the producer ever lowercases path segments while the
     * consumer (fed the case-preserved stored {@code nameWithOwner}) does not, this fails.
     */
    @Test
    void gitlabMixedCasePathRoundTrips() throws IOException {
        String pathWithNamespace = "MyGroup/Sub-Group/My.Project";
        JsonNode payload = MAPPER.readTree("{\"object_kind\":\"merge_request\",\"project\":{\"path_with_namespace\":\""
                + pathWithNamespace + "\"}}");
        String publisherSubject = GITLAB.deriveSubject(payload, Map.of());
        String consumerPrefix = ConsumerSubjectMath.buildSubjectPrefix("gitlab", pathWithNamespace);
        assertThat(publisherSubject).startsWith(consumerPrefix + ".");
    }

    private static String eventTypeFromFilename(Path fixture) {
        String name = fixture.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
