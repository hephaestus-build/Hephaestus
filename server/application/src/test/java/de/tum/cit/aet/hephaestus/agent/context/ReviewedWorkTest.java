package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ReviewedWorkTest extends BaseUnitTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-30T00:05:00Z");
    private static final String HEAD = "b".repeat(40);
    private static final String METADATA = SandboxLayout.CONTEXT_PREFIX + "metadata.json";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldIdentifyWhatThePullRequestCaptureStagedAndPinned() {
        byte[] manifest =
                mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "No issue link", HEAD));
        var staged = Map.of(
                SandboxLayout.MANIFEST_PATH,
                manifest,
                METADATA,
                ReviewedWorkFixtures.metadata(mapper, "MR !2", "No issue link", HEAD));

        ReviewedWork work = ReviewedWork.captured(manifest, staged, 42, mapper).orElseThrow();

        assertThat(work.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST.value());
        assertThat(work.artifactId()).isEqualTo(42);
        assertThat(work.head()).isEqualTo(HEAD);
        assertThat(work.capturedAt()).isEqualTo(CAPTURED_AT);
        assertThat(work.titleAndDescriptionRevision())
                .isEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "MR !2", "No issue link"))
                .isNotEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "MR !2", "Closes #12"));
    }

    @Test
    void shouldRefuseACaptureWhoseMetadataNamesAnotherCommitThanThePinnedChange() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "body", HEAD));
        var staged = Map.of(METADATA, ReviewedWorkFixtures.metadata(mapper, "MR !2", "body", "c".repeat(40)));

        assertThatThrownBy(() -> ReviewedWork.captured(manifest, staged, 42, mapper))
                .isInstanceOf(JobPreparationException.class);
    }

    @Test
    void shouldIdentifyNothingWithoutStagedCoreValues() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.issueManifest(CAPTURED_AT, "body"));

        assertThat(ReviewedWork.captured(manifest, Map.of(), 7, mapper)).isEmpty();
        assertThat(ReviewedWork.captured(manifest, Map.of(METADATA, "{\"body\":\"x\"}".getBytes()), 7, mapper))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":null,"
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}",
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":\"sha~abc\","
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}",
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":\"dig~00000000000000000000000000000000\"}",
                "{\"artifactKind\":\"scm.issue\",\"titleAndDescriptionRevision\":\"dig~00000000000000000000000000000000\","
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}"
            })
    void shouldNotReadAMalformedStoredIdentityAsOne(String stored) {
        assertThatThrownBy(() -> mapper.readValue(stored, ReviewedWork.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void shouldFrameAMissingDescriptionAsEachCaptureStagesIt() {
        assertThat(ReviewedWork.revision(ArtifactKinds.ISSUE, "Plan", null))
                .isEqualTo(ReviewedWork.revision(ArtifactKinds.ISSUE, "Plan", ""));
        assertThat(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Plan", null))
                .isNotEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Plan", ""));
    }
}
