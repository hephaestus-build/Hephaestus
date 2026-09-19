package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PullRequestContentSourceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewCommentRepository reviewCommentRepository;

    @Mock
    private ReviewRepositoryPreparer repositoryPreparer;

    private static final Long WORKSPACE_ID = 99L;
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");
    private static final String HEAD = "abc123def456";
    private static final String BASE = "a".repeat(40);

    private PullRequestContentSource provider;

    @BeforeEach
    void setUp() {
        lenient()
                .when(pullRequestRepository.findByIdWithAuthorAndRepository(456L))
                .thenReturn(Optional.of(new PullRequest()));
        provider = new PullRequestContentSource(
                objectMapper, gitRepositoryManager, pullRequestRepository, reviewCommentRepository, repositoryPreparer);
    }

    private static final RepositoryKey REPOSITORY = new RepositoryKey(WORKSPACE_ID, 123L);
    private static final ReviewRepositoryPreparer.PreparedReview PREPARED =
            new ReviewRepositoryPreparer.PreparedReview(REPOSITORY, HEAD, BASE);

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pull_request_id", 456L);
        metadata.put("pr_number", 42);
        metadata.put("pr_url", "https://github.com/owner/repo/pull/42");
        metadata.put("commit_sha", HEAD);
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        metadata.put("base_ref_oid", BASE);
        return metadata;
    }

    private AgentJob jobWith(ObjectNode metadata) {
        var job = new AgentJob();
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private ContextRequest.PracticeReviewRequest request(ObjectNode metadata) {
        return new ContextRequest.PracticeReviewRequest(jobWith(metadata));
    }

    private void stubGit() {
        lenient().when(repositoryPreparer.prepare(any())).thenReturn(PREPARED);
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        lenient().when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(true);
        lenient()
                .when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD))
                .thenReturn(Set.of("a.txt"));
    }

    private Map<String, byte[]> captureFiles(ContextRequest request) {
        return provider.capture(request, provider.sourceKinds()).files();
    }

    @Nested
    class Supports {

        @Test
        void supportsPracticeReview() {
            assertThat(provider.supports(request(sampleMetadata()))).isTrue();
        }
    }

    @Nested
    class SourceKinds {

        @Test
        void shouldMapEveryStagedFileToItsSourceKind() {
            assertThat(provider.sourceKindFor("inputs/context/metadata.json")).isEqualTo(CORE);
            assertThat(provider.sourceKindFor("inputs/context/comments.json")).isEqualTo(COMMENTS);
            assertThat(provider.sourceKindFor(PullRequestContentSource.CHANGE_FILE))
                    .isEqualTo(DIFF);
            assertThat(PullRequestContentSource.CHANGE_FILE).isEqualTo("inputs/context/change.json");
        }
    }

    @Nested
    class MetadataAndComments {

        @Test
        void shouldRefuseCoreWithoutGitBecauseItsCommitHistoryIsUnavailable() {
            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), Set.of(CORE)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Git local storage is disabled");
        }

        @Test
        void writesMetadataJson() throws Exception {
            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            assertThat(files).containsKey("inputs/context/metadata.json");
            JsonNode metadataJson = objectMapper.readTree(files.get("inputs/context/metadata.json"));
            assertThat(metadataJson.get("pr_number").asInt()).isEqualTo(42);
            assertThat(metadataJson.get("repository_full_name").asString()).isEqualTo("owner/repo");
        }

        @Test
        void enrichesFromDb() throws Exception {
            PullRequest pr = new PullRequest();
            pr.setTitle("Fix authentication bug");
            pr.setBody("This PR fixes the login issue");
            pr.setState(Issue.State.MERGED);
            pr.setAdditions(10);
            pr.setCreatedAt(Instant.parse("2026-04-09T12:39:13Z"));
            pr.setMergedAt(Instant.parse("2026-04-09T14:47:18Z"));
            User author = new User();
            author.setLogin("testuser");
            pr.setAuthor(author);

            stubGit();
            when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(pr));
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode metadataJson = objectMapper.readTree(files.get("inputs/context/metadata.json"));
            assertThat(metadataJson.get("title").asString()).isEqualTo("Fix authentication bug");
            assertThat(metadataJson.get("author").asString()).isEqualTo("testuser");
            assertThat(metadataJson.get("additions").asInt()).isEqualTo(10);
            // The dated moments a review places the merge against; a moment the provider never
            // recorded is left out rather than written as null.
            assertThat(metadataJson.get("created_at").asString()).isEqualTo("2026-04-09T12:39:13Z");
            assertThat(metadataJson.get("merged_at").asString()).isEqualTo("2026-04-09T14:47:18Z");
            assertThat(metadataJson.has("closed_at")).isFalse();
        }

        @Test
        void writesCommentsJson() throws Exception {
            PullRequestReviewComment full = new PullRequestReviewComment();
            full.setPath("src/Main.java");
            full.setLine(10);
            full.setBody("Fix this");
            full.setCreatedAt(Instant.parse("2025-06-01T12:00:00Z"));
            User reviewer = new User();
            reviewer.setLogin("reviewer");
            full.setAuthor(reviewer);

            PullRequestReviewComment minimal = new PullRequestReviewComment();
            minimal.setPath("src/Other.java");
            minimal.setLine(5);
            minimal.setBody("Old comment");

            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of(full, minimal));

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode comments = objectMapper.readTree(files.get("inputs/context/comments.json"));
            assertThat(comments).hasSize(2);
            assertThat(comments.get(0).get("created_at").asString()).isEqualTo("2025-06-01T12:00:00Z");
            assertThat(comments.get(0).get("author").asString()).isEqualTo("reviewer");
            assertThat(comments.get(1).has("author")).isFalse();
        }

        @Test
        void truncatesComments() throws Exception {
            var comments = new ArrayList<PullRequestReviewComment>();
            for (int i = 0; i < PullRequestContentSource.MAX_COMMENTS + 100; i++) {
                PullRequestReviewComment c = new PullRequestReviewComment();
                c.setPath("file.java");
                c.setLine(i);
                c.setBody("Comment " + i);
                c.setCreatedAt(Instant.EPOCH.plusSeconds(i));
                comments.add(c);
            }
            Collections.reverse(comments);

            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(comments);

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode commentsJson = objectMapper.readTree(files.get("inputs/context/comments.json"));
            assertThat(commentsJson).hasSize(PullRequestContentSource.MAX_COMMENTS);
            assertThat(commentsJson.get(0).get("body").asString()).isEqualTo("Comment 100");
        }

        @Test
        void reportsExactLimitAsCompleteAndOverflowAsPartial() {
            List<PullRequestReviewComment> comments = new ArrayList<>();
            for (int i = 0; i < PullRequestContentSource.MAX_COMMENTS; i++) {
                PullRequestReviewComment comment = new PullRequestReviewComment();
                comment.setBody("Comment " + i);
                comments.add(comment);
            }
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(comments);
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.COMPLETE);

            comments.add(new PullRequestReviewComment());
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.PARTIAL);
        }
    }

    @Nested
    class ReviewedChange {

        @Test
        void shouldPinTheReviewedChangeAsBaseAndHeadWithoutStagingAnyPatch() throws Exception {
            stubGit();

            var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

            JsonNode change = objectMapper.readTree(captured.files().get(PullRequestContentSource.CHANGE_FILE));
            assertThat(change.path("base_sha").asString()).isEqualTo(BASE);
            assertThat(change.path("head_sha").asString()).isEqualTo(HEAD);
            assertThat(captured.files().keySet()).containsExactly(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.filesOnDisk()).isEmpty();
            assertThat(captured.cleanup()).isNull();
            assertThat(captured.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
            assertThat(captured.contentStates().get(DIFF)).isEqualTo(SourceContentState.NON_EMPTY);
            verify(gitRepositoryManager, never()).unifiedDiff(any(), any(), any());
        }

        @Test
        void shouldCaptureEmptyDiffAsCompleteEmptyEvidence() {
            stubGit();
            when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD)).thenReturn(Set.of());

            var contribution = provider.capture(request(sampleMetadata()), Set.of(DIFF));

            assertThat(contribution.files()).containsKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(contribution.contentStates().get(DIFF)).isEqualTo(SourceContentState.EMPTY);
            assertThat(contribution.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            assertThat(contribution.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
        }

        @Test
        void shouldRefuseCaptureWhenPinnedRangeCannotBeResolved() {
            stubGit();
            when(repositoryPreparer.prepare(any()))
                    .thenThrow(new JobPreparationException("The pinned review diff range is unavailable"));
            assertThatThrownBy(() -> captureFiles(request(sampleMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("pinned review diff range is unavailable");
        }

        @Test
        void shouldNotPinAChangeWhenOnlyCommentsAreSelected() {
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.immutableIdentities()).isEmpty();
            verify(repositoryPreparer).authorize(any());
            verify(repositoryPreparer, never()).prepare(any());
            verifyNoInteractions(gitRepositoryManager);
        }
    }

    @Nested
    class RepositoryAvailability {

        @Test
        void throwsWhenRepositoryMissing() {
            lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(false);

            assertThatThrownBy(() -> captureFiles(request(sampleMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Repository is not available locally for evidence capture");
        }

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            assertThatThrownBy(() -> captureFiles(new ContextRequest.PracticeReviewRequest(job)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("no metadata");
        }
    }

    @Test
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns() {
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(sampleMetadata()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(reviewCommentRepository, gitRepositoryManager, repositoryPreparer);
        var artifact = new PullRequest();
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }

    @Test
    void shouldSkipGitPreparationWhenTheParentIsUnavailable() {
        var deleted = new PullRequest();
        deleted.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(deleted));

        var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

        assertThat(captured.stateOverrides())
                .containsEntry(DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        verifyNoInteractions(gitRepositoryManager, repositoryPreparer);
    }

    @Test
    void shouldPrepareTheRepositoryOnceForEverySourceOfOneRequest() {
        stubGit();
        var request = request(sampleMetadata());

        for (var kind : provider.sourceKinds()) provider.capture(request, Set.of(kind));

        verify(repositoryPreparer, times(1)).prepare(any());
    }

    @Test
    void shouldHandTheSamePreparationFailureToEverySourceOfOneRequest() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(repositoryPreparer.prepare(any()))
                .thenThrow(new JobPreparationException("SCM credentials are unavailable"));
        var request = request(sampleMetadata());

        for (var kind : List.of(CORE, DIFF)) {
            assertThatThrownBy(() -> provider.capture(request, Set.of(kind)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessage("SCM credentials are unavailable");
        }
        verify(repositoryPreparer, times(1)).prepare(any());
    }

    @Test
    void shouldPinCoreAndDiffToTheSameReviewRange() {
        stubGit();

        var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));

        assertThat(captured.files().keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/metadata.json",
                        PullRequestContentSource.DESCRIPTION_FILE,
                        PullRequestContentSource.CHANGE_FILE);
        assertThat(provider.sourceKindFor(PullRequestContentSource.DESCRIPTION_FILE))
                .isEqualTo(CORE);
        assertThat(captured.immutableIdentities().get(CORE)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.immutableIdentities().get(CORE))
                .isEqualTo(captured.immutableIdentities().get(DIFF));
    }

    @Test
    void shouldPinAgainstThePreparedTargetRatherThanJobMetadata() {
        stubGit();
        var metadata = sampleMetadata();
        metadata.remove("base_ref_oid");

        var captured = provider.capture(request(metadata), Set.of(CORE));

        assertThat(captured.immutableIdentities().get(CORE)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
    }
}
