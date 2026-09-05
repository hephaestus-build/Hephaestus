package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PullRequestCommitContentSourceTest extends BaseUnitTest {

    private static final String FILE_KEY = "inputs/context/commits.json";
    private static final Long PR_ID = 456L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    private PullRequestCommitContentSource provider;

    @BeforeEach
    void setUp() {
        lenient()
                .when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID))
                .thenReturn(true);
        lenient()
                .when(commitRepository.findByAssociatedPullRequestId(any(), any()))
                .thenReturn(List.of());
        provider = new PullRequestCommitContentSource(objectMapper, commitRepository, pullRequestRepository);
    }

    private ObjectNode metadataWithPr() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("pull_request_id", PR_ID);
        return metadata;
    }

    private ContextRequest.PracticeReviewRequest request(ObjectNode metadata) {
        AgentJob job = new AgentJob();
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(99L);
        job.setWorkspace(workspace);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private static Commit commit(String sha, String message, @Nullable String body, Instant authoredAt) {
        Commit c = new Commit();
        c.setSha(sha);
        c.setMessage(message);
        c.setMessageBody(body);
        c.setAuthoredAt(authoredAt);
        c.setCommittedAt(authoredAt.plusSeconds(60));
        c.setAdditions(10);
        c.setDeletions(2);
        c.setChangedFiles(3);
        return c;
    }

    private JsonNode stage() {
        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(request(metadataWithPr()), files);
        assertThat(files).containsKey(FILE_KEY);
        return objectMapper.readTree(files.get(FILE_KEY));
    }

    @Test
    void shouldFailLoudWhenTheJobNamesNoPullRequest() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);

        // Writing nothing would be misread downstream as "no commits", a claim about the work rather
        // than the collection — so a missing pull_request_id must throw instead.
        assertThatThrownBy(() -> provider.contribute(request(metadata), new HashMap<>()))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessage("Commit collection has no pull_request_id");
    }

    /**
     * A pull request whose commits the mirror has not linked yet still stages the file, holding an empty
     * list, and records the capture as EMPTY. No pull request has zero commits, so the source contract
     * demands COMPLETE_AND_NON_EMPTY and readiness turns that EMPTY into SOURCE_EMPTY: the practices that
     * read this source are refused rather than asked to judge commits they cannot see.
     */
    @Test
    void shouldRecordAnEmptyCaptureWhenNoCommitIsLinked() {
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        assertThat(captured.files()).containsKey(FILE_KEY);
        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.get("commits")).isEmpty();
        assertThat(out.get("count").asInt()).isZero();
        assertThat(out.get("truncated").asBoolean()).isFalse();
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        assertThat(captured.completeness()).containsValue(SourceCompleteness.COMPLETE);
    }

    /** A message that arrives with Windows line endings splits on the same first line, carriage return dropped. */
    @Test
    void shouldSplitACrlfMessageAtItsFirstLine() {
        when(commitRepository.findByAssociatedPullRequestId(any(), any()))
                .thenReturn(List.of(commit(
                        "f".repeat(40),
                        "Wire up the depth buffer\r\n\r\nNeeded before the shadow pass lands.\r\n",
                        null,
                        Instant.parse("2026-06-01T10:00:00Z"))));

        JsonNode first = stage().get("commits").get(0);

        assertThat(first.get("subject").asString()).isEqualTo("Wire up the depth buffer");
        assertThat(first.get("body").asString()).isEqualTo("Needed before the shadow pass lands.");
    }

    @Test
    void shouldStageEachCommitInTheOrderTheFinderReturnsWithTheSubjectSplitFromTheBody() {
        when(commitRepository.findByAssociatedPullRequestId(any(), any()))
                .thenReturn(List.of(
                        commit(
                                "a".repeat(40),
                                "Extract the retry logic into a helper",
                                "The upload and the download paths duplicated it.",
                                Instant.parse("2026-06-01T10:00:00Z")),
                        commit(
                                "b".repeat(40),
                                "Add a timeout to the upload call",
                                null,
                                Instant.parse("2026-06-01T11:00:00Z"))));

        JsonNode out = stage();

        assertThat(out.get("count").asInt()).isEqualTo(2);
        assertThat(out.get("truncated").asBoolean()).isFalse();
        JsonNode first = out.get("commits").get(0);
        assertThat(first.get("sha").asString()).isEqualTo("a".repeat(40));
        assertThat(first.get("subject").asString()).isEqualTo("Extract the retry logic into a helper");
        assertThat(first.get("body").asString()).isEqualTo("The upload and the download paths duplicated it.");
        assertThat(first.get("authoredAt").asString()).isEqualTo("2026-06-01T10:00:00Z");
        assertThat(first.get("committedAt").asString()).isEqualTo("2026-06-01T10:01:00Z");
        assertThat(first.get("additions").asInt()).isEqualTo(10);
        assertThat(first.get("deletions").asInt()).isEqualTo(2);
        assertThat(first.get("changedFiles").asInt()).isEqualTo(3);
        JsonNode second = out.get("commits").get(1);
        assertThat(second.get("subject").asString()).isEqualTo("Add a timeout to the upload call");
        // A commit with no body has no body key, never a JSON null.
        assertThat(second.has("body")).isFalse();
    }

    /**
     * Every sync path stores the subject alone in {@code message}, but the column admits a newline, so a
     * message that arrives whole is still split at its first line and the remainder joins the stored body.
     */
    @Test
    void shouldTakeTheFirstLineAsTheSubjectWhenTheMessageHoldsMore() {
        when(commitRepository.findByAssociatedPullRequestId(any(), any()))
                .thenReturn(List.of(commit(
                        "c".repeat(40),
                        "Cache detection results\n\nAvoids re-running the detector per file.",
                        "Co-authored-by: Ada <ada@example.com>",
                        Instant.parse("2026-06-01T10:00:00Z"))));

        JsonNode first = stage().get("commits").get(0);

        assertThat(first.get("subject").asString()).isEqualTo("Cache detection results");
        assertThat(first.get("body").asString())
                .isEqualTo("Avoids re-running the detector per file.\n\nCo-authored-by: Ada <ada@example.com>");
    }

    @Test
    void shouldCarryEnrichmentFactsOnlyOnceTheyAreKnown() {
        Commit bare = commit("d".repeat(40), "wip", null, Instant.parse("2026-06-01T10:00:00Z"));
        Commit enriched =
                commit("e".repeat(40), "Merge branch 'main' into feature", null, Instant.parse("2026-06-01T11:00:00Z"));
        enriched.setAuthoredByCommitter(true);
        enriched.setCommittedViaWeb(false);
        enriched.setParentCount(2);
        when(commitRepository.findByAssociatedPullRequestId(any(), any())).thenReturn(List.of(bare, enriched));

        JsonNode out = stage();

        JsonNode first = out.get("commits").get(0);
        assertThat(first.has("authoredByCommitter")).isFalse();
        assertThat(first.has("committedViaWeb")).isFalse();
        assertThat(first.has("parentCount")).isFalse();
        JsonNode second = out.get("commits").get(1);
        assertThat(second.get("authoredByCommitter").asBoolean()).isTrue();
        assertThat(second.get("committedViaWeb").asBoolean()).isFalse();
        assertThat(second.get("parentCount").asInt()).isEqualTo(2);
    }

    @Test
    void shouldKeepTheFirstCommitsAndFlagTruncationPastTheCap() {
        int total = PullRequestCommitContentSource.MAX_COMMITS + 5;
        List<Commit> commits = new ArrayList<>();
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        for (int i = 0; i < total; i++) {
            commits.add(commit(String.format("%040d", i), "commit-" + i, null, base.plusSeconds(i)));
        }
        // The finder is asked for one past the cap, which is how the cap is told from an exact fit.
        when(commitRepository.findByAssociatedPullRequestId(any(), any()))
                .thenReturn(commits.subList(0, PullRequestCommitContentSource.MAX_COMMITS + 1));

        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());

        JsonNode out = objectMapper.readTree(captured.files().get(FILE_KEY));
        assertThat(out.get("count").asInt()).isEqualTo(PullRequestCommitContentSource.MAX_COMMITS);
        assertThat(out.get("truncated").asBoolean()).isTrue();
        JsonNode staged = out.get("commits");
        assertThat(staged.get(0).get("subject").asString()).isEqualTo("commit-0");
        assertThat(staged.get(staged.size() - 1).get("subject").asString())
                .isEqualTo("commit-" + (PullRequestCommitContentSource.MAX_COMMITS - 1));
        assertThat(captured.completeness()).containsValue(SourceCompleteness.PARTIAL);
    }

    @Test
    void shouldReportARepositoryFailureAsACollectionError() {
        when(commitRepository.findByAssociatedPullRequestId(any(), any())).thenThrow(new RuntimeException("db down"));

        Map<String, byte[]> files = new HashMap<>();
        assertThatThrownBy(() -> provider.contribute(request(metadataWithPr()), files))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessageContaining("Commit collection failed");
        assertThat(files).doesNotContainKey(FILE_KEY);
    }

    @Test
    void shouldRefuseATombstonedPullRequestAndStageItAgainWhenItReturns() {
        when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID)).thenReturn(false);
        var captured = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsValue(new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        verifyNoInteractions(commitRepository);

        when(pullRequestRepository.existsByIdAndDeletedAtIsNull(PR_ID)).thenReturn(true);
        var restored = provider.capture(request(metadataWithPr()), provider.sourceKinds());
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).containsKey(FILE_KEY);
    }

    @Test
    void shouldBeBestEffort() {
        assertThat(provider.required()).isFalse();
    }
}
