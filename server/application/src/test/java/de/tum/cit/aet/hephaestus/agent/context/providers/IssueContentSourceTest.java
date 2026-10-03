package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository.StoredComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class IssueContentSourceTest extends BaseUnitTest {

    private static final long ISSUE_ID = 777L;
    private static final String METADATA_KEY = "context/metadata.json";
    private static final String COMMENTS_KEY = "context/comments.json";
    private static final SourceKind CORE = new SourceKind("scm.issue.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.issue.comments");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueCommentRepository issueCommentRepository;

    private IssueContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new IssueContentSource(
                objectMapper,
                issueRepository,
                issueCommentRepository,
                new IssueEvidenceRevision(issueCommentRepository));
        lenient().when(issueCommentRepository.countByIssueId(ISSUE_ID)).thenReturn(0L);
        lenient()
                .when(issueCommentRepository.findStoredHumanByIssueId(eq(ISSUE_ID), any()))
                .thenReturn(List.of());
    }

    @Test
    void shouldReadNothingForACloseReviewWhoseSnapshotMovedOnSinceItWasAdmitted() {
        Issue issue = richIssue();
        issue.setReviewSnapshotId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
        ObjectNode stale = sampleMetadata();
        stale.put("signal", "scm.issue.closed");
        stale.put("review_snapshot_id", "00000000-0000-0000-0000-0000000000b1");
        ObjectNode unidentified = sampleMetadata();
        unidentified.put("signal", "scm.issue.closed");

        for (ObjectNode metadata : List.of(stale, unidentified)) {
            var captured = provider.capture(request(metadata), Set.of(CORE, COMMENTS));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsEntry(CORE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND))
                    .containsEntry(COMMENTS, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        }
        verifyNoInteractions(issueCommentRepository);
    }

    @Test
    void shouldCaptureACloseReviewWhoseSnapshotIsStillCurrent() {
        Issue issue = richIssue();
        UUID snapshot = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        issue.setReviewSnapshotId(snapshot);
        stubComments(List.of(
                comment("bob", "first", Instant.parse("2025-06-01T10:00:00Z")),
                comment("alice", "Export moved to #12.", Instant.parse("2025-06-02T10:00:00Z"))));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
        ObjectNode metadata = sampleMetadata();
        metadata.put("signal", "scm.issue.closed");
        metadata.put("review_snapshot_id", snapshot.toString());

        var captured = provider.capture(request(metadata), Set.of(CORE, COMMENTS));

        assertThat(captured.files()).containsKeys(METADATA_KEY, COMMENTS_KEY);
        assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
    }

    @Test
    void shouldReportTheDiscussionPartialWhenTheProviderCountsMoreCommentsThanTheMirrorHolds() {
        Issue issue = richIssue();
        stubComments(List.of(comment("bob", "first", Instant.parse("2025-06-01T10:00:00Z"))));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

        var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

        assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.PARTIAL);
    }

    @Test
    void shouldReportUnavailableRatherThanCaptureChangedMetadataUnderTheAdmittedRevision() {
        Issue issue = richIssue();
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
        ObjectNode metadata = sampleMetadata();
        metadata.put("signal", "scm.issue.updated");
        metadata.put(AgentJob.SIGNAL_REVISION_METADATA_KEY, "digest~old");

        var captured = provider.capture(request(metadata), Set.of(CORE, COMMENTS));

        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(CORE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND))
                .containsEntry(COMMENTS, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }

    @Test
    void shouldReportUnavailableForAnUpdateWithoutAdmissionIdentityWithoutClaimingTheIssueChanged() {
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));
        ObjectNode metadata = sampleMetadata();
        metadata.put("signal", "scm.issue.updated");

        var captured = provider.capture(request(metadata), Set.of(CORE));

        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(CORE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }

    @Test
    void shouldCaptureMetadataWhenItStillMatchesTheAdmittedRevision() {
        Issue issue = richIssue();
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
        ObjectNode metadata = sampleMetadata();
        metadata.put("signal", "scm.issue.updated");
        metadata.put(
                AgentJob.SIGNAL_REVISION_METADATA_KEY,
                ScmSignals.issueUpdatedRevision(ScmEventPayload.IssueData.from(issue))
                        .value());
        Map<String, byte[]> files = new java.util.HashMap<>();
        provider.contribute(request(metadata), files);
        assertThat(files).containsKey(METADATA_KEY);
    }

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("issue_id", ISSUE_ID);
        return metadata;
    }

    private AgentJob jobWith(ObjectNode metadata) {
        var job = new AgentJob();
        job.setId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        job.setMetadata(metadata);
        return job;
    }

    private ContextRequest.IssueReviewRequest request(ObjectNode metadata) {
        return new ContextRequest.IssueReviewRequest(jobWith(metadata));
    }

    private User user(String login) {
        User u = new User();
        u.setLogin(login);
        return u;
    }

    private Label label(String name) {
        Label l = new Label();
        l.setName(name);
        return l;
    }

    private StoredComment comment(@Nullable String authorLogin, String body, Instant createdAt) {
        return new StoredComment() {
            @Override
            public Long getId() {
                return createdAt.toEpochMilli();
            }

            @Override
            public Long getNativeId() {
                return createdAt.toEpochMilli();
            }

            @Override
            public @Nullable String getAuthorLogin() {
                return authorLogin;
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }

            @Override
            public Instant getUpdatedAt() {
                return createdAt;
            }

            @Override
            public String getBody() {
                return body;
            }
        };
    }

    /** The stored comments as the projection returns them, oldest first, and that many mirrored in all. */
    private void stubComments(List<StoredComment> chronological) {
        when(issueCommentRepository.countByIssueId(ISSUE_ID)).thenReturn((long) chronological.size());
        when(issueCommentRepository.findStoredHumanByIssueId(eq(ISSUE_ID), any()))
                .thenReturn(chronological);
    }

    private Issue richIssue() {
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setNumber(123);
        issue.setTitle("Tighten the practice catalogue");
        issue.setBody("Make the catalogue honest.");
        issue.setState(Issue.State.CLOSED);
        issue.setStateReason(Issue.StateReason.COMPLETED);
        issue.setHtmlUrl("https://github.com/owner/repo/issues/123");
        issue.setLocked(true);
        issue.setCommentsCount(2);
        issue.setSubIssuesTotal(4);
        issue.setSubIssuesCompleted(3);
        issue.setAuthor(user("felix"));

        IssueType issueType = new IssueType();
        issueType.setName("Task");
        issue.setIssueType(issueType);

        Repository repo = new Repository();
        repo.setId(1L);
        repo.setNameWithOwner("owner/repo");
        issue.setRepository(repo);

        Milestone milestone = new Milestone();
        milestone.setTitle("v1.0");
        issue.setMilestone(milestone);

        issue.setLabels(new LinkedHashSet<>(List.of(label("zeta"), label("alpha"))));
        issue.setAssignees(new LinkedHashSet<>(List.of(user("bob"), user("alice"))));

        return issue;
    }

    @Nested
    class Supports {

        @Test
        void supportsIssueReviewRequest() {
            assertThat(provider.supports(request(sampleMetadata()))).isTrue();
        }
    }

    @Nested
    class Metadata {

        @Test
        void writesIssueMetadataFields() throws Exception {
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            assertThat(files).containsKey(METADATA_KEY);
            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("issue_number").asInt()).isEqualTo(123);
            assertThat(meta.get("title").asString()).isEqualTo("Tighten the practice catalogue");
            assertThat(meta.get("state").asString()).isEqualTo("CLOSED");
            assertThat(meta.get("state_reason").asString()).isEqualTo("COMPLETED");
            assertThat(meta.get("repository_full_name").asString()).isEqualTo("owner/repo");
            assertThat(meta.get("author").asString()).isEqualTo("felix");
            assertThat(meta.get("issue_type").asString()).isEqualTo("Task");
            assertThat(meta.get("is_locked").asBoolean()).isTrue();
            assertThat(meta.get("comments_count").asInt()).isEqualTo(2);
            assertThat(meta.get("milestone").asString()).isEqualTo("v1.0");
        }

        @Test
        void rollsUpSubIssueCounts() throws Exception {
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("sub_issues_total").asInt()).isEqualTo(4);
            assertThat(meta.get("sub_issues_completed").asInt()).isEqualTo(3);
        }

        @Test
        void sortsLabelsAlphabetically() throws Exception {
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode labels = objectMapper.readTree(files.get(METADATA_KEY)).get("labels");
            assertThat(labels).hasSize(2);
            assertThat(labels.get(0).asString()).isEqualTo("alpha");
            assertThat(labels.get(1).asString()).isEqualTo("zeta");
        }

        @Test
        void sortsAssigneeLoginsAlphabetically() throws Exception {
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode assignees = objectMapper.readTree(files.get(METADATA_KEY)).get("assignees");
            assertThat(assignees).hasSize(2);
            assertThat(assignees.get(0).asString()).isEqualTo("alice");
            assertThat(assignees.get(1).asString()).isEqualTo("bob");
        }

        @Test
        void emitsNullStateReasonWhenUnset() throws Exception {
            Issue issue = richIssue();
            issue.setStateReason(null);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("state_reason").isNull()).isTrue();
            assertThat(meta.get("milestone").asString()).isEqualTo("v1.0");
        }
    }

    @Nested
    class Comments {

        @Test
        void ordersThreadByCreatedAtAscending() throws Exception {
            Issue issue = richIssue();
            StoredComment newer = comment("alice", "second", Instant.parse("2025-06-02T10:00:00Z"));
            StoredComment older = comment("bob", "first", Instant.parse("2025-06-01T10:00:00Z"));
            stubComments(List.of(older, newer));
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode comments = objectMapper.readTree(files.get(COMMENTS_KEY));
            assertThat(comments).hasSize(2);
            assertThat(comments.get(0).get("body").asString()).isEqualTo("first");
            assertThat(comments.get(0).get("author").asString()).isEqualTo("bob");
            assertThat(comments.get(0).get("created_at").asString()).isEqualTo("2025-06-01T10:00:00Z");
            assertThat(comments.get(1).get("body").asString()).isEqualTo("second");
        }

        @Test
        void emitsNullAuthorWhenCommentHasNoAuthor() throws Exception {
            Issue issue = richIssue();
            stubComments(List.of(comment(null, "anon", Instant.parse("2025-06-01T10:00:00Z"))));
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode comments = objectMapper.readTree(files.get(COMMENTS_KEY));
            assertThat(comments).hasSize(1);
            assertThat(comments.get(0).get("author").isNull()).isTrue();
        }

        @Test
        void shouldKeepAllCommentsAboveTheFormerCaptureLimit() throws Exception {
            Issue issue = richIssue();
            var thread = new ArrayList<StoredComment>();
            int overflow = 10_000 + 50;
            Instant base = Instant.parse("2025-01-01T00:00:00Z");
            for (int i = 0; i < overflow; i++) {
                thread.add(comment("u" + i, "Comment " + i, base.plusSeconds(i)));
            }
            stubComments(thread);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            JsonNode comments = objectMapper.readTree(files.get(COMMENTS_KEY));
            assertThat(comments).hasSize(overflow);
            assertThat(comments.get(0).get("body").asString()).isEqualTo("Comment 0");
            assertThat(comments.get(comments.size() - 1).get("body").asString()).isEqualTo("Comment " + (overflow - 1));
        }

        @Test
        void shouldReportCompleteWithoutACommentLimit() {
            Issue issue = richIssue();
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
            Instant base = Instant.parse("2025-01-01T00:00:00Z");
            List<StoredComment> comments = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                comments.add(comment("u" + i, "Comment " + i, base.plusSeconds(i)));
            }
            stubComments(comments);
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.COMPLETE);

            comments.add(comment("overflow", "Overflow", base.plusSeconds(comments.size())));
            stubComments(comments);
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.COMPLETE);
        }

        @Test
        void captureReadsIssueAndCommentsOnce() {
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(richIssue()));

            provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            verify(issueRepository).findByIdWithRepository(ISSUE_ID);
            verify(issueCommentRepository).countByIssueId(ISSUE_ID);
            verify(issueCommentRepository).findStoredHumanByIssueId(eq(ISSUE_ID), any());
        }
    }

    @Nested
    class StagedFiles {

        @Test
        void stagesTheIssueAsMetadataAndCommentsOnly() {
            Issue issue = richIssue();
            stubComments(List.of(comment("bob", "first", Instant.parse("2025-06-01T10:00:00Z"))));
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            assertThat(files).containsOnlyKeys(METADATA_KEY, "context/description.md", COMMENTS_KEY);
            assertThat(new String(files.get("context/description.md"), StandardCharsets.UTF_8))
                    .isEqualTo("Make the catalogue honest.");

            JsonNode comments = objectMapper.readTree(files.get(COMMENTS_KEY));
            assertThat(comments).hasSize(1);
            assertThat(comments.get(0).path("author").asString()).isEqualTo("bob");
        }
    }

    @Nested
    class Abstention {

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            assertThatThrownBy(() ->
                            provider.contribute(new ContextRequest.IssueReviewRequest(job), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Job has no metadata");
        }

        @Test
        void throwsWhenIssueIdAbsentFromMetadata() {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("something_else", 1);

            assertThatThrownBy(() -> provider.contribute(request(metadata), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("metadata field: issue_id");
        }

        @Test
        void throwsWhenIssueIdIsExplicitNull() {
            // {"issue_id": null}: has("issue_id") is true but the field is null. The strict reader must
            // reject it rather than letting NullNode.asLong() default to 0 and surface as the misleading
            // "Issue not found: issueId=0".
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.putNull("issue_id");

            assertThatThrownBy(() -> provider.contribute(request(metadata), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("metadata field: issue_id");
        }

        @Test
        void throwsWhenIssueIdIsNonNumeric() {
            // A non-numeric issue_id must fail at metadata parse with the "metadata field" message, not
            // resolve to 0 and surface downstream as "Issue not found: issueId=0".
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("issue_id", "not-a-number");

            assertThatThrownBy(() -> provider.contribute(request(metadata), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("metadata field: issue_id");
        }

        @Test
        void stagesAnEmptyBodyAsAnEmptyString() {
            Issue issue = richIssue();
            issue.setBody(null);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            // An empty body still produces a valid metadata body field (empty string, not null).
            JsonNode metadata = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(metadata.get("body").isString()).isTrue();
            assertThat(metadata.get("body").asString()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns(boolean tombstoned) {
        var artifact = new Issue();
        artifact.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(issueRepository.findByIdWithRepository(ISSUE_ID))
                .thenReturn(tombstoned ? Optional.of(artifact) : Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(sampleMetadata()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(issueCommentRepository);
        artifact.setDeletedAt(null);
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }
}
