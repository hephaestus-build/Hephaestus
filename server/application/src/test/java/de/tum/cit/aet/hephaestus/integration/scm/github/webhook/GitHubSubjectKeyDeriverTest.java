package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GitHubSubjectKeyDeriverTest extends BaseUnitTest {

    private final GitHubSubjectKeyDeriver deriver = new GitHubSubjectKeyDeriver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsRepositorySubject() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"repository\":{\"name\":\"web\",\"owner\":{\"login\":\"acme\"}}}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "pull_request"));

        assertThat(subject).isEqualTo("github.acme.web.pull_request");
    }

    @Test
    void buildsOrganizationSubjectWhenNoRepository() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"organization\":{\"login\":\"acme\"}}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "member"));

        assertThat(subject).isEqualTo("github.acme.?.member");
    }

    @Test
    void buildsInstallationSubjectWhenNeitherPresent() throws Exception {
        JsonNode payload = objectMapper.readTree("{}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "installation"));

        assertThat(subject).isEqualTo("github.?.?.installation");
    }

    @Test
    void repositoryLifecycleEventIsOrgScopedSoRenamesStillRoute() throws Exception {
        // The payload carries the NEW name; a repo-tier subject built from it would match neither the
        // monitored-repo filter (pinned to the OLD name) nor the org filter, so the event — and every
        // later event for that repo — would be silently ACK-dropped.
        JsonNode payload = objectMapper.readTree(
                "{\"action\":\"renamed\",\"repository\":{\"name\":\"web-renamed\",\"owner\":{\"login\":\"acme\"}}}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "repository"));

        assertThat(subject).isEqualTo("github.acme.?.repository");
    }

    @Test
    void repositoryLifecycleEventFallsBackToOrganizationLoginForOwner() throws Exception {
        JsonNode payload =
                objectMapper.readTree("{\"repository\":{\"name\":\"web\"},\"organization\":{\"login\":\"acme\"}}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "repository"));

        assertThat(subject).isEqualTo("github.acme.?.repository");
    }

    @Test
    void nonLifecycleEventsKeepTheRepositoryTier() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"repository\":{\"name\":\"web\",\"owner\":{\"login\":\"acme\"}}}");

        assertThat(deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "issues")))
                .isEqualTo("github.acme.web.issues");
        // `repositories` / `installation_repositories` must NOT be caught by the prefix-free match.
        assertThat(deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "installation_repositories")))
                .isEqualTo("github.acme.web.installation_repositories");
    }

    @Test
    void sanitizesDotsInTokens() throws Exception {
        JsonNode payload =
                objectMapper.readTree("{\"repository\":{\"name\":\"my.repo\",\"owner\":{\"login\":\"team.x\"}}}");
        String subject = deriver.deriveSubject(payload, Map.of("X-GitHub-Event", "push"));

        assertThat(subject).isEqualTo("github.team~x.my~repo.push");
    }

    @Test
    void missingEventHeaderBecomesPlaceholder() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"repository\":{\"name\":\"web\",\"owner\":{\"login\":\"acme\"}}}");
        String subject = deriver.deriveSubject(payload, Map.of());

        assertThat(subject).isEqualTo("github.acme.web.?");
    }

    @Test
    void headerLookupIsCaseInsensitive() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"repository\":{\"name\":\"web\",\"owner\":{\"login\":\"acme\"}}}");
        String subject = deriver.deriveSubject(payload, Map.of("x-github-event", "ping"));

        assertThat(subject).isEqualTo("github.acme.web.ping");
    }

    @Test
    void dedupKeyUsesDeliveryHeaderWhenPresent() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String key = deriver.deriveDedupKey(body, Map.of("X-GitHub-Delivery", "00000000-1111-2222-3333-444444444444"));

        assertThat(key).isEqualTo("github-00000000-1111-2222-3333-444444444444");
    }

    @Test
    void dedupKeyFallsBackToSha256WhenDeliveryMissing() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String key = deriver.deriveDedupKey(body, Map.of("X-GitHub-Event", "push"));

        assertThat(key).startsWith("github-");
        // Suffix is SHA-256 truncated to 32 hex characters.
        assertThat(key.substring("github-".length())).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void dedupKeyIsDeterministicWhenFallingBack() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("X-GitHub-Event", "push");

        assertThat(deriver.deriveDedupKey(body, headers)).isEqualTo(deriver.deriveDedupKey(body, headers));
    }

    @Test
    void deriverIdentifiesAsGithubKind() {
        assertThat(deriver.kind()).isEqualTo(IntegrationKind.GITHUB);
    }
}
