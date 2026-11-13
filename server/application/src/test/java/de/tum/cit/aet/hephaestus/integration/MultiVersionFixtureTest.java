package de.tum.cit.aet.hephaestus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the tolerant-reader contract for the SCM webhook DTOs through the REAL production path:
 * {@link EventPayloadDeserializer} over the Spring-Boot-autoconfigured Jackson&nbsp;3
 * {@code tools.jackson.databind.ObjectMapper}.
 *
 * <p>Booting the real auto-config (rather than hand-building a mapper) is the point: unknown-field
 * tolerance is mapper <em>configuration</em> ({@code spring.jackson.fail-on-unknown-properties:
 * false}), not an annotation effect, so only the autoconfigured bean tests what production actually
 * does. A hand-rolled mapper would re-encode the config under test and drift silently.
 *
 * <p>v1 fixtures are minimal "as-shipped" examples; v2 fixtures inject synthetic unknown keys at the
 * top level and every nesting level. Each v2 test asserts (a) the fixture really carries unknown keys
 * (so the tolerance check can't go vacuous), (b) deserialization does not throw (the regression guard
 * against a future flip to {@code fail-on-unknown-properties: true}), and (c) known fields — including
 * deep paths and a typed {@code Instant} coercion — still map correctly.
 */
@Tag("unit")
@JsonTest
@ContextConfiguration(
        classes = JacksonAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class MultiVersionFixtureTest {

    @Autowired
    private ObjectMapper jackson3;

    private @Nullable EventPayloadDeserializer deserializer;

    private EventPayloadDeserializer deserializer() {
        if (deserializer == null) {
            deserializer = new EventPayloadDeserializer(jackson3);
        }
        return deserializer;
    }

    @Test
    void githubPullRequestV1_extractsKnownFields() throws Exception {
        GitHubPullRequestEventDTO dto = deserialize(
                "/integration-fixtures/github/v1/pull_request.opened.json", GitHubPullRequestEventDTO.class);
        assertKnownGithubFields(dto);
    }

    @Test
    void githubPullRequestV2_tolerantOnRealEngineAndExtractsKnownFields() throws Exception {
        String classpath = "/integration-fixtures/github/v2/pull_request.opened.json";

        // Guard: the fixture must actually inject unknown fields the DTO does not declare,
        // otherwise the tolerance assertion below would be vacuous.
        JsonNode raw = jackson3.readTree(readBytes(classpath));
        assertThat(raw.has("_future_top_level"))
                .as("fixture must carry an injected unknown top-level key")
                .isTrue();
        assertThat(raw.path("repository").has("_visibility_v2"))
                .as("fixture must carry an injected unknown nested key")
                .isTrue();

        // (1) Tolerance on the REAL mapper: deserializing the unknown-field payload must not throw.
        // This is the live regression guard — it fails if production's mapper ever flips to
        // fail-on-unknown-properties=true, and it would still pass if the DTO dropped
        // @JsonIgnoreProperties (the mapper ignores unknowns by config, not by annotation).
        GitHubPullRequestEventDTO dto = deserialize(classpath, GitHubPullRequestEventDTO.class);

        // (2) Correctness: every known field — including deep paths — is still mapped, unaffected
        // by the unknown keys.
        assertKnownGithubFields(dto);
    }

    @Test
    void gitlabMergeRequestV1_extractsKnownFields() throws Exception {
        GitLabMergeRequestEventDTO dto = deserialize(
                "/integration-fixtures/gitlab/v1/merge_request.open.json", GitLabMergeRequestEventDTO.class);
        assertKnownGitlabFields(dto);
    }

    @Test
    void gitlabMergeRequestV2_tolerantOnRealEngineAndExtractsKnownFields() throws Exception {
        String classpath = "/integration-fixtures/gitlab/v2/merge_request.open.json";

        // Guard: the fixture must actually inject unknown fields the DTO does not declare.
        JsonNode raw = jackson3.readTree(readBytes(classpath));
        assertThat(raw.has("_event_uuid_v2"))
                .as("fixture must carry an injected unknown top-level key")
                .isTrue();
        assertThat(raw.path("object_attributes").has("_blocking_discussions_resolved_v2"))
                .as("fixture must carry an injected unknown nested key")
                .isTrue();

        // (1) Tolerance on the REAL mapper.
        GitLabMergeRequestEventDTO dto = deserialize(classpath, GitLabMergeRequestEventDTO.class);

        // (2) Correctness.
        assertKnownGitlabFields(dto);
    }

    /**
     * Asserts the GitHub DTO mapped the right JSON path to the right field, including a deep nested
     * path and a typed coercion — the assertions that have production meaning (a mis-wired
     * {@code @JsonProperty} or a removed JavaTime handling would break them), as opposed to literal
     * echoes.
     */
    private static void assertKnownGithubFields(GitHubPullRequestEventDTO dto) {
        assertThat(dto.action()).isEqualTo("opened");
        assertThat(dto.number()).isEqualTo(42);
        assertThat(dto.pullRequest()).isNotNull();
        assertThat(required(dto.pullRequest()).title()).isEqualTo("Add feature X");
        // Deep path: pull_request.base.ref / head.ref — proves nested @JsonProperty wiring.
        assertThat(dto.pullRequest().base()).isNotNull();
        assertThat(required(required(dto.pullRequest()).base()).ref()).isEqualTo("main");
        assertThat(required(required(dto.pullRequest()).head()).ref()).isEqualTo("feature-x");
        // Typed coercion: created_at string -> Instant. A removed JavaTime handling or a bad
        // @JsonProperty would surface here, not in a literal echo of a String.
        assertThat(required(dto.pullRequest()).createdAt()).isEqualTo("2026-05-25T10:00:00Z");
        assertThat(required(dto.repository()).fullName()).isEqualTo("HephaestusTest/repo");
    }

    private static void assertKnownGitlabFields(GitLabMergeRequestEventDTO dto) {
        assertThat(dto.objectKind()).isEqualTo("merge_request");
        assertThat(dto.objectAttributes()).isNotNull();
        assertThat(dto.objectAttributes().title()).isEqualTo("Add feature X");
        // Underscored JSON keys -> camelCase fields via @JsonProperty.
        assertThat(dto.objectAttributes().sourceBranch()).isEqualTo("feature-x");
        assertThat(dto.objectAttributes().targetBranch()).isEqualTo("main");
        assertThat(dto.objectAttributes().authorId()).isEqualTo(1L);
        assertThat(dto.project()).isNotNull();
        assertThat(dto.project().pathWithNamespace()).isEqualTo("hephaestustest/smoke");
    }

    /**
     * Drives the real production deserialization path: wrap the fixture bytes in a provider inbox
     * {@link Message} and call {@link EventPayloadDeserializer#deserialize}, exactly as the live
     * message handlers do.
     */
    private <T> T deserialize(String classpath, Class<T> type) throws Exception {
        byte[] payload = readBytes(classpath);
        ProviderEvent message = mock(ProviderEvent.class);
        when(message.payload()).thenReturn(payload);
        return deserializer().deserialize(message, type);
    }

    private byte[] readBytes(String classpath) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(classpath)) {
            assertThat(in).as("fixture %s must be on the classpath", classpath).isNotNull();
            return in.readAllBytes();
        }
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
