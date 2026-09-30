package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges.PullRequestRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class CapturedReviewedWorkChangesTest extends BaseUnitTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final String HEAD = "1".repeat(40);
    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturedReviewedWorkChanges changes = new CapturedReviewedWorkChanges(jobs, mapper);

    @ParameterizedTest
    @ValueSource(strings = {"title", "body", "head"})
    void shouldAdmitOnlyAKnownMaterialDifferenceWhenOneCapturedFieldChanged(String field) {
        PullRequest current = current();
        switch (field) {
            case "title" -> current.setTitle("Repaired title");
            case "body" -> current.setBody("Repaired description");
            case "head" -> current.setHeadRefOid("2".repeat(40));
            default -> throw new IllegalArgumentException(field);
        }
        stored(capture(42, HEAD));
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).containsExactly(RUN);
    }

    @Test
    void shouldRefuseARepairWhenTheCapturedFieldsMatchDespiteDifferentTimesOrMetadata() {
        stored(capture(42, HEAD));
        PullRequest current = current();
        current.setUpdatedAt(Instant.now());
        current.setCommentsCount(12);
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "not json", "{\"head\":\"invalid\"}"})
    void shouldRefuseUnknownCaptureProvenanceWhenTheStoredIdentityIsAbsentOrMalformed(@Nullable String json) {
        stored(json);
        PullRequest current = current();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldRefuseAnotherArtifactsCaptureEvenWhenTheTextChanged() {
        stored(capture(43, HEAD));
        PullRequest current = current();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldNotInferAPushFromAnUnpinnedCaptureButStillRecognizeChangedText() {
        stored(capture(42, null));
        PullRequest current = current();
        current.setHeadRefOid("2".repeat(40));
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).containsExactly(RUN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unknown"})
    void shouldNotInferAChangeWhenTheCurrentHeadIsUnknown(@Nullable String head) {
        stored(capture(42, HEAD));
        PullRequest current = current();
        current.setHeadRefOid(head);
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldNotReadAnyCapturesWhenNoAuthorizedNegativeRunsExist() {
        assertThat(changes.materiallyChanged(7, Set.of(), revision(current()))).isEmpty();
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldRefuseRunsNotReturnedByTheWorkspaceScopedLookup() {
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of());
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current())))
                .isEmpty();
    }

    private String capture(long id, @Nullable String head) {
        return mapper.writeValueAsString(new ReviewedWork(
                ArtifactKinds.PULL_REQUEST.value(),
                id,
                ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Title", "Body"),
                head,
                Instant.EPOCH));
    }

    private void stored(@Nullable String json) {
        var row = mock(AgentJobRepository.CapturedReviewedWorkRow.class);
        when(row.getReviewedWork()).thenReturn(json);
        org.mockito.Mockito.lenient().when(row.getId()).thenReturn(RUN);
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of(row));
    }

    private static PullRequestRevision revision(PullRequest current) {
        return new PullRequestRevision(current.getId(), current.getHeadRefOid(), current.getTitle(), current.getBody());
    }

    private static PullRequest current() {
        PullRequest current = new PullRequest();
        current.setId(42L);
        current.setTitle("Title");
        current.setBody("Body");
        current.setHeadRefOid(HEAD);
        return current;
    }
}
