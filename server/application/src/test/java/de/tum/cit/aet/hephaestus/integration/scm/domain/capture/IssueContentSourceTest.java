package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentProvenance;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository.StoredComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueEvidenceVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                new IssueEvidenceVersion(
                        issueCommentRepository,
                        new IssueCommentProvenance(mock(DeliveredIssueCommentLookup.class)),
                        issueRepository));
        lenient().when(issueCommentRepository.countByIssueId(ISSUE_ID)).thenReturn(0L);
        lenient().when(issueCommentRepository.findStoredByIssueId(ISSUE_ID)).thenReturn(List.of());
    }

    @Test
    void shouldReportTheDiscussionPartialWhenTheProviderCountsMoreCommentsThanTheMirrorHolds() {
        Issue issue = richIssue();
        stubComments(List.of(comment("bob", "first", Instant.parse("2025-06-01T10:00:00Z"))));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

        var captured = provider.capture(request(sampleMetadata(), issue), Set.of(COMMENTS));

        assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.PARTIAL);
    }

    @Test
    void shouldCaptureAnEmptyCompleteDiscussionWhenTheProviderCountsOnlyHephaestusOwnFeedback() throws Exception {
        Issue issue = richIssue();
        StoredComment firstFeedback =
                comment("hephaestus", "Feedback on this issue.", Instant.parse("2025-06-01T10:00:00Z"));
        StoredComment secondFeedback = comment("hephaestus", "More feedback.", Instant.parse("2025-06-02T10:00:00Z"));
        stubComments(List.of(firstFeedback, secondFeedback));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

        var captured = excludingDelivered(firstFeedback, secondFeedback)
                .capture(request(sampleMetadata(), issue, firstFeedback, secondFeedback), Set.of(CORE, COMMENTS));

        assertThat(objectMapper.readTree(captured.files().get(COMMENTS_KEY))).isEmpty();
        assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
        assertThat(captured.contentStates()).containsEntry(COMMENTS, SourceContentState.EMPTY);
        assertThat(objectMapper.readTree(captured.files().get(METADATA_KEY)).has("comments_count"))
                .as("the provider's total, which counts the excluded feedback, is not shown" + " beside the capture")
                .isFalse();
        assertThat(issue.getCommentsCount()).isEqualTo(2);
    }

    @Test
    void shouldCaptureOnlyThePersonsCommentWhenHephaestusOwnFeedbackSurroundsIt() throws Exception {
        Issue issue = richIssue();
        issue.setCommentsCount(3);
        StoredComment firstFeedback =
                comment("hephaestus", "Feedback on this issue.", Instant.parse("2025-06-01T10:00:00Z"));
        StoredComment human =
                comment("bob", "The export should cover archived items too.", Instant.parse("2025-06-02T10:00:00Z"));
        StoredComment secondFeedback = comment("hephaestus", "More feedback.", Instant.parse("2025-06-03T10:00:00Z"));
        stubComments(List.of(firstFeedback, human, secondFeedback));
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

        var captured = excludingDelivered(firstFeedback, secondFeedback)
                .capture(request(sampleMetadata(), issue, firstFeedback, secondFeedback), Set.of(CORE, COMMENTS));

        JsonNode comments = objectMapper.readTree(captured.files().get(COMMENTS_KEY));
        assertThat(comments).singleElement().satisfies(only -> {
            assertThat(only.get("author").asString()).isEqualTo("bob");
            assertThat(only.get("body").asString()).isEqualTo("The export should cover archived items too.");
        });
        assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
        assertThat(captured.contentStates()).containsEntry(COMMENTS, SourceContentState.NON_EMPTY);
        assertThat(objectMapper.readTree(captured.files().get(METADATA_KEY)).has("comments_count"))
                .isFalse();
        assertThat(issue.getCommentsCount()).isEqualTo(3);
    }

    private static DeliveredIssueCommentLookup deliveries(List<DeliveredIssueCommentLookup.DeliveredComment> notes) {
        DeliveredIssueCommentLookup deliveries = mock(DeliveredIssueCommentLookup.class);
        lenient()
                .when(deliveries.findForIssue(anyLong(), eq(ScmItemEvents.ISSUE)))
                .thenReturn(notes);
        return deliveries;
    }

    private IssueContentSource excludingDelivered(StoredComment... delivered) {
        return new IssueContentSource(objectMapper, issueRepository, issueCommentRepository, versions(delivered));
    }

    private IssueEvidenceVersion versions(StoredComment... delivered) {
        List<DeliveredIssueCommentLookup.DeliveredComment> notes = new ArrayList<>();
        for (StoredComment comment : delivered)
            notes.add(new DeliveredIssueCommentLookup.DeliveredComment(
                    "gid://gitlab/Note/" + comment.getNativeId(), null));
        return new IssueEvidenceVersion(
                issueCommentRepository, new IssueCommentProvenance(deliveries(notes)), issueRepository);
    }

    @Test
    void shouldReportUnavailableRatherThanCaptureChangedMetadataUnderTheAdmittedRevision() {
        Issue issue = richIssue();
        when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
        ObjectNode metadata = sampleMetadata();
        metadata.put("itemVersion", "digest~old");

        var captured = provider.capture(request(metadata, issue), Set.of(CORE, COMMENTS));

        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(CORE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND))
                .containsEntry(COMMENTS, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("issue_id", ISSUE_ID);
        metadata.put("repository_id", 1L);
        return metadata;
    }

    private ScmCaptureContext request(ObjectNode metadata, @Nullable Issue pinned, StoredComment... delivered) {
        var context = ScmCaptureFixtures.context(metadata, 1L, true);
        if (metadata.has("itemVersion") || pinned == null) return context;
        return ScmCaptureFixtures.withVersion(context, versions(delivered).of(ScmEventPayload.IssueData.from(pinned)));
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
            public @Nullable Long getAuthorNativeId() {
                return authorLogin == null ? null : 71L;
            }

            @Override
            public User.@Nullable Type getAuthorType() {
                return authorLogin == null ? null : User.Type.USER;
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

    private void stubComments(List<StoredComment> chronological) {
        when(issueCommentRepository.countByIssueId(ISSUE_ID)).thenReturn((long) chronological.size());
        when(issueCommentRepository.findStoredByIssueId(ISSUE_ID)).thenReturn(chronological);
    }

    private Issue richIssue() {
        return richIssue(4, 3);
    }

    private Issue richIssue(@Nullable Integer total, @Nullable Integer completed) {
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
        if (total != null) issue.setSubIssuesTotal(total);
        if (completed != null) issue.setSubIssuesCompleted(completed);
        issue.setAuthor(user("jordan"));

        IssueType issueType = new IssueType();
        issueType.setName("Task");
        issue.setIssueType(issueType);

        Repository repo = new Repository();
        repo.setId(1L);
        repo.setNameWithOwner("owner/repo");
        IdentityProvider capturedProvider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        capturedProvider.setId(2L);
        repo.setProvider(capturedProvider);
        issue.setRepository(repo);
        issue.setProvider(capturedProvider);

        Milestone milestone = new Milestone();
        milestone.setTitle("v1.0");
        issue.setMilestone(milestone);

        issue.setLabels(new LinkedHashSet<>(List.of(label("zeta"), label("alpha"))));
        issue.setAssignees(new LinkedHashSet<>(List.of(user("bob"), user("alice"))));

        return issue;
    }

    @Nested
    class Metadata {

        @Test
        void writesIssueMetadataFields() throws Exception {
            var issue = richIssue();
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            assertThat(files).containsKey(METADATA_KEY);
            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("issue_number").asInt()).isEqualTo(123);
            assertThat(meta.get("title").asString()).isEqualTo("Tighten the practice catalogue");
            assertThat(meta.get("state").asString()).isEqualTo("CLOSED");
            assertThat(meta.get("state_reason").asString()).isEqualTo("COMPLETED");
            assertThat(meta.get("repository_full_name").asString()).isEqualTo("owner/repo");
            assertThat(meta.get("repository_id").asLong()).isEqualTo(1L);
            assertThat(meta.get("provider_id").asLong()).isEqualTo(2L);
            assertThat(meta.get("author").asString()).isEqualTo("jordan");
            assertThat(meta.get("issue_type").asString()).isEqualTo("Task");
            assertThat(meta.get("is_locked").asBoolean()).isTrue();
            assertThat(meta.has("comments_count")).isFalse();
            assertThat(meta.get("milestone").asString()).isEqualTo("v1.0");
        }

        @Test
        void preservesAdmittedRepositoryWithoutBorrowingMovedProvider() throws Exception {
            var issue = richIssue();
            var repository = issue.getRepository();
            assertThat(repository).isNotNull();
            repository.setId(3L);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
            JsonNode meta = objectMapper.readTree(provider.capture(request(sampleMetadata(), issue), Set.of(CORE))
                    .files()
                    .get(METADATA_KEY));
            assertThat(meta.path("repository_id").asLong()).isEqualTo(1L);
            assertThat(meta.has("provider_id")).isFalse();
        }

        @Test
        void rollsUpSubIssueCounts() throws Exception {
            var issue = richIssue();
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("sub_issues_total").asInt()).isEqualTo(4);
            assertThat(meta.get("sub_issues_completed").asInt()).isEqualTo(3);
            assertThat(meta.get("sub_issues_source").asString()).isEqualTo("provider");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 4})
        void shouldKeepTheProviderCountWhenItsPairIsComplete(int total) throws Exception {
            Issue issue = richIssue();
            issue.setSubIssuesTotal(total);
            issue.setSubIssuesCompleted(0);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            JsonNode meta = objectMapper.readTree(provider.capture(request(sampleMetadata(), issue), Set.of(CORE))
                    .files()
                    .get(METADATA_KEY));

            assertThat(meta.get("sub_issues_total").asInt()).isEqualTo(total);
            assertThat(meta.get("sub_issues_completed").asInt()).isZero();
            assertThat(meta.get("sub_issues_source").asString()).isEqualTo("provider");
        }

        @ParameterizedTest
        @ValueSource(strings = {"total", "completed", "both"})
        void shouldCapturePositiveStoredChildrenWhenTheProviderPairIsIncomplete(String missing) throws Exception {
            Issue issue = richIssue("completed".equals(missing) ? 4 : null, "total".equals(missing) ? 3 : null);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
            IssueRepository.ChildRollup children = mock(IssueRepository.ChildRollup.class);
            when(children.getTotal()).thenReturn(2L);
            when(children.getCompleted()).thenReturn(1L);
            when(issueRepository.countChildrenByParentIssueId(ISSUE_ID, Issue.State.CLOSED))
                    .thenReturn(children);

            JsonNode meta = objectMapper.readTree(provider.capture(request(sampleMetadata(), issue), Set.of(CORE))
                    .files()
                    .get(METADATA_KEY));

            assertThat(meta.get("sub_issues_total").asInt()).isEqualTo(2);
            assertThat(meta.get("sub_issues_completed").asInt()).isEqualTo(1);
            assertThat(meta.get("sub_issues_source").asString()).isEqualTo("children");
        }

        @ParameterizedTest
        @ValueSource(strings = {"total", "completed", "both"})
        void shouldLeaveTheCountUnknownWhenAnIncompleteProviderPairHasNoStoredChildren(String missing)
                throws Exception {
            Issue issue = richIssue("completed".equals(missing) ? 0 : null, "total".equals(missing) ? 0 : null);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));
            IssueRepository.ChildRollup children = mock(IssueRepository.ChildRollup.class);
            when(children.getTotal()).thenReturn(0L);
            when(issueRepository.countChildrenByParentIssueId(ISSUE_ID, Issue.State.CLOSED))
                    .thenReturn(children);

            JsonNode meta = objectMapper.readTree(provider.capture(request(sampleMetadata(), issue), Set.of(CORE))
                    .files()
                    .get(METADATA_KEY));

            assertThat(meta.get("sub_issues_total").isNull()).isTrue();
            assertThat(meta.get("sub_issues_completed").isNull()).isTrue();
            assertThat(meta.has("sub_issues_source")).isFalse();
        }

        @Test
        void sortsLabelsAlphabetically() throws Exception {
            var issue = richIssue();
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            JsonNode labels = objectMapper.readTree(files.get(METADATA_KEY)).get("labels");
            assertThat(labels).hasSize(2);
            assertThat(labels.get(0).asString()).isEqualTo("alpha");
            assertThat(labels.get(1).asString()).isEqualTo("zeta");
        }

        @Test
        void sortsAssigneeLoginsAlphabetically() throws Exception {
            var issue = richIssue();
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

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
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            JsonNode meta = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(meta.get("state_reason").isNull()).isTrue();
            assertThat(meta.get("milestone").asString()).isEqualTo("v1.0");
        }
    }

    @Nested
    class Comments {

        @Test
        void emitsNullAuthorWhenCommentHasNoAuthor() throws Exception {
            Issue issue = richIssue();
            stubComments(List.of(comment(null, "anon", Instant.parse("2025-06-01T10:00:00Z"))));
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            JsonNode comments = objectMapper.readTree(files.get(COMMENTS_KEY));
            assertThat(comments).hasSize(1);
            assertThat(comments.get(0).get("author").isNull()).isTrue();
        }
    }

    @Nested
    class StagedFiles {}

    @Nested
    class Abstention {

        @Test
        void stagesAnEmptyBodyAsAnEmptyString() {
            Issue issue = richIssue();
            issue.setBody(null);
            when(issueRepository.findByIdWithRepository(ISSUE_ID)).thenReturn(Optional.of(issue));

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.putAll(provider.capture(request(sampleMetadata(), issue), provider.sourceKinds())
                    .files());

            // An empty body still produces a valid metadata body field (empty string, not null).
            JsonNode metadata = objectMapper.readTree(files.get(METADATA_KEY));
            assertThat(metadata.get("body").isString()).isTrue();
            assertThat(metadata.get("body").asString()).isEmpty();
        }
    }
}
