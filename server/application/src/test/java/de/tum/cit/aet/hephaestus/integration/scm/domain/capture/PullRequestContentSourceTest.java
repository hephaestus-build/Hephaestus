package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
    private static final String HEAD = "b".repeat(40);
    private static final String BASE = "a".repeat(40);

    @Mock
    private DeliveredPullRequestCommentLookup deliveredCommentLookup;

    private PullRequestContentSource provider;
    private @Nullable PullRequest mirroredPullRequest;
    private List<PullRequestReviewComment> mirroredComments = List.of();
    private DeliveredPullRequestCommentLookup.CommentIds deliveredComments =
            new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of());

    @BeforeEach
    void setUp() {
        mirroredPullRequest = pullRequest();
        lenient()
                .when(deliveredCommentLookup.findForPullRequest(anyLong(), anyLong(), eq(ScmItemEvents.PULL_REQUEST)))
                .thenAnswer(invocation -> deliveredComments);
        lenient()
                .when(pullRequestRepository.findByIdForReviewContext(456L))
                .thenAnswer(invocation -> Optional.ofNullable(mirroredPullRequest));
        lenient()
                .when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                .thenAnswer(invocation -> mirroredComments);
        provider = new PullRequestContentSource(
                objectMapper,
                gitRepositoryManager,
                pullRequestRepository,
                reviewCommentRepository,
                repositoryPreparer,
                deliveredCommentLookup);
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
        // ScmItemService reads these words at capture, after it verified them against the pinned
        // version.
        metadata.put("title", "Fix authentication bug");
        metadata.put("body", "This PR fixes the login issue");
        return metadata;
    }

    private ScmCaptureContext request(ObjectNode metadata) {
        return ScmCaptureFixtures.context(metadata, WORKSPACE_ID, false);
    }

    private static Label label(String name) {
        Label label = new Label();
        label.setName(name);
        return label;
    }

    private static PullRequest pullRequest() {
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        var pr = new PullRequest();
        pr.setId(456L);
        pr.setProvider(provider);
        return pr;
    }

    private static User user(String login) {
        User user = new User();
        user.setLogin(login);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        user.setProvider(provider);
        user.setNativeId(1L);
        return user;
    }

    private void stubGit() {
        lenient().when(gitRepositoryManager.read(any(), any())).thenReturn(Map.of());
        lenient().when(gitRepositoryManager.unifiedDiff(REPOSITORY, BASE, HEAD)).thenReturn("+captured change\n");
        lenient().when(repositoryPreparer.prepare(any())).thenReturn(PREPARED);
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        lenient().when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(true);
        lenient()
                .when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD))
                .thenReturn(Set.of("a.txt"));
    }

    private Map<String, byte[]> captureFiles(ScmCaptureContext request) {
        return provider.capture(request, provider.sourceKinds()).files();
    }

    /** Reads the exported manifest back and binds one source's file and quoted words to their staged bytes. */
    private static void assertExported(
            JsonMapper mapper,
            PreparedEvidence prepared,
            ItemKind item,
            SourceKind source,
            String file,
            String citation,
            String words) {
        JsonNode manifest =
                mapper.readTree(Objects.requireNonNull(prepared.files().get(SandboxLayout.MANIFEST_PATH)));
        assertThat(manifest.path("itemKind").asString()).isEqualTo(item.value());
        assertThat(manifest.path("sources")).singleElement().satisfies(captured -> {
            assertThat(captured.path("kind").asString()).isEqualTo(source.value());
            assertThat(captured.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
            assertThat(captured.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
            assertThat(captured.path("state").path("content").asString()).isEqualTo("NON_EMPTY");
        });
        byte[] exported = Objects.requireNonNull(prepared.files().get(file));
        assertThat(manifest.path("files")).anySatisfy(entry -> {
            assertThat(entry.path("kind").asString()).isEqualTo(source.value());
            assertThat(entry.path("file").path("path").asString()).isEqualTo(file);
            assertThat(entry.path("file").path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(exported));
        });
        assertThat(manifest.path("citableTexts")).anySatisfy(text -> {
            assertThat(text.path("source").asString()).isEqualTo(source.value());
            assertThat(text.path("path").asString()).isEqualTo(citation);
            String staged = text.path("stagedPath").asString();
            assertThat(prepared.files()).containsKey(staged);
            byte[] quoted = Objects.requireNonNull(prepared.files().get(staged));
            assertThat(new String(quoted, StandardCharsets.UTF_8)).isEqualTo(words);
            assertThat(text.path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(quoted));
        });
    }

    @Nested
    class SourceKinds {

        @Test
        void shouldMapEveryStagedFileToItsSourceKind() {
            assertThat(provider.sourceKindFor("context/metadata.json")).isEqualTo(CORE);
            assertThat(provider.sourceKindFor("context/comments.json")).isEqualTo(COMMENTS);
            assertThat(provider.sourceKindFor(PullRequestContentSource.CHANGE_FILE))
                    .isEqualTo(DIFF);
            assertThat(PullRequestContentSource.CHANGE_FILE).isEqualTo("context/change.json");
            assertThat(provider.sourceKindFor(PullRequestContentSource.COMMITS_FILE))
                    .isEqualTo(CORE);
        }
    }

    @Nested
    class MetadataAndComments {

        @Test
        void writesMetadataJson() throws Exception {
            stubGit();

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            assertThat(files).containsKey("context/metadata.json");
            JsonNode metadataJson = objectMapper.readTree(files.get("context/metadata.json"));
            assertThat(metadataJson.get("pr_number").asInt()).isEqualTo(42);
            assertThat(metadataJson.get("repository_full_name").asString()).isEqualTo("owner/repo");
        }

        @Test
        void shouldStateThePinnedRepositoryAndOnlyItsOwnProviderWhenTheMirrorStillHoldsIt() throws Exception {
            var provider9 = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example");
            provider9.setId(9L);
            var pinned = new Repository();
            pinned.setId(123L);
            pinned.setProvider(provider9);
            var moved = new Repository();
            moved.setId(124L);
            moved.setProvider(provider9);

            for (var repository : List.of(pinned, moved)) {
                PullRequest pr = pullRequest();
                pr.setRepository(repository);
                mirroredPullRequest = pr;

                JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                        .files()
                        .get("context/metadata.json"));

                assertThat(metadataJson.get("repository_id").asLong()).isEqualTo(123L);
                assertThat(metadataJson.path("basis").has("admission_fields")).isFalse();
                if (Objects.equals(repository.getId(), pinned.getId())) {
                    assertThat(metadataJson.get("provider_id").asLong()).isEqualTo(9L);
                } else {
                    assertThat(metadataJson.has("provider_id")).isFalse();
                }
            }
        }

        @Test
        void shouldStageTheCapturedWordsBesideSyncedStatusAndDateEachToItsRead() throws Exception {
            PullRequest pr = pullRequest();
            pr.setTitle("Current title");
            pr.setBody("CURRENT-BODY-SENTINEL");
            pr.setState(Issue.State.CLOSED);
            pr.setMerged(true);
            pr.setMergedAt(Instant.parse("2026-04-10T09:00:00Z"));
            pr.setReviewDecision(ReviewDecision.APPROVED);
            pr.setLastSyncAt(Instant.parse("2026-04-10T10:00:00Z"));
            mirroredPullRequest = pr;
            ScmCaptureContext original = request(sampleMetadata());
            ScmCaptureContext job = new ScmCaptureContext(
                    original.pinned(),
                    original.request(),
                    original.work(),
                    original.preparation(),
                    Instant.parse("2026-04-10T10:05:00Z"));

            var captured = provider.capture(job, Set.of(CORE));

            JsonNode metadataJson = objectMapper.readTree(captured.files().get("context/metadata.json"));
            assertThat(metadataJson.get("title").asString()).isEqualTo("Fix authentication bug");
            assertThat(metadataJson.get("body").asString()).isEqualTo("This PR fixes the login issue");
            assertThat(new String(
                            captured.files().get(PullRequestContentSource.DESCRIPTION_FILE), StandardCharsets.UTF_8))
                    .isEqualTo("This PR fixes the login issue");
            assertThat(new String(captured.files().get("context/metadata.json"), StandardCharsets.UTF_8))
                    .doesNotContain("CURRENT-BODY-SENTINEL", "Current title");
            // Status stays the mirror's as last synced. The record dates its read to the capture and
            // claims no admission or event time.
            assertThat(metadataJson.get("state").asString()).isEqualTo("CLOSED");
            assertThat(metadataJson.get("is_merged").asBoolean()).isTrue();
            assertThat(metadataJson.get("review_decision").asString()).isEqualTo("APPROVED");
            JsonNode basis = metadataJson.get("basis");
            assertThat(basis.propertyNames())
                    .containsExactlyInAnyOrder("other_fields", "captured_at", "mirror_synced_at");
            assertThat(basis.get("other_fields").asString()).isEqualTo("CAPTURE");
            assertThat(basis.get("captured_at").asString()).isEqualTo("2026-04-10T10:05:00Z");
            assertThat(basis.get("mirror_synced_at").asString()).isEqualTo("2026-04-10T10:00:00Z");
            assertThat(captured.completeness()).containsEntry(CORE, SourceCompleteness.COMPLETE);
            assertThat(captured.observedAt()).doesNotContainKey(CORE);
        }

        @Test
        void shouldWriteTheRecordsStateBesideItsPeopleAndLabelsWhenThePullRequestCarriesThem() throws Exception {
            PullRequest pr = pullRequest();
            pr.setTitle("Fix authentication bug");
            pr.setState(Issue.State.CLOSED);
            pr.setMerged(true);
            pr.setLabels(Set.of(label("security"), label("backend")));
            pr.setAssignees(Set.of(user("zoe"), user("adam")));
            Milestone milestone = new Milestone();
            milestone.setTitle("v1.2");
            pr.setMilestone(milestone);
            pr.setMergeStateStatus(MergeStateStatus.CLEAN);
            pr.setReviewDecision(ReviewDecision.APPROVED);

            stubGit();
            mirroredPullRequest = pr;

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));

            assertThat(metadataJson.path("subject_role").asString()).isEqualTo("AUTHOR");
            var reviewerMetadata = sampleMetadata();
            reviewerMetadata.put("subject_role", "REVIEWER");
            reviewerMetadata.put("review_id", 31L);
            reviewerMetadata.put("about_user_id", 32L);
            JsonNode reviewerCore = objectMapper.readTree(provider.capture(request(reviewerMetadata), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(reviewerCore.path("subject_role").asString()).isEqualTo("REVIEWER");
            assertThat(metadataJson.get("state").asString()).isEqualTo("CLOSED");
            assertThat(metadataJson.get("is_merged").asBoolean()).isTrue();
            assertThat(metadataJson.get("labels").valueStream().map(JsonNode::asString))
                    .containsExactly("backend", "security");
            assertThat(metadataJson.get("assignees").valueStream().map(JsonNode::asString))
                    .containsExactly("adam", "zoe");
            assertThat(metadataJson.get("milestone").asString()).isEqualTo("v1.2");
            assertThat(metadataJson.get("merge_state_status").asString()).isEqualTo("CLEAN");
            assertThat(metadataJson.get("review_decision").asString()).isEqualTo("APPROVED");
        }

        @Test
        void shouldMarkTheAuthorBotWhenTheAdapterStoredItAsOne() throws Exception {
            PullRequest pr = pullRequest();
            User token = user("group_12_bot_9f3a");
            token.setType(User.Type.BOT);
            pr.setAuthor(token);
            stubGit();
            mirroredPullRequest = pr;

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));

            assertThat(metadataJson.get("author").asString()).isEqualTo("group_12_bot_9f3a");
            assertThat(metadataJson.get("author_bot").asBoolean()).isTrue();
        }

        @Test
        void shouldWriteTheHeadChecksOnlyWhenTheyWereObservedForTheReviewedHead() throws Exception {
            String newer = "f".repeat(40);
            PullRequest reviewed = pullRequest();
            reviewed.setHeadRefOid(newer);
            reviewed.observeHeadChecks(HEAD, CheckState.FAILURE, true);
            PullRequest moved = pullRequest();
            moved.setHeadRefOid(newer);
            moved.observeHeadChecks(newer, CheckState.SUCCESS, true);
            PullRequest stale = pullRequest();
            stale.setHeadRefOid(HEAD);
            stale.observeHeadChecks("e".repeat(40), CheckState.SUCCESS, true);
            stubGit();

            mirroredPullRequest = reviewed;
            JsonNode fresh = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(fresh.get("commit_sha").asString()).isEqualTo(HEAD);
            assertThat(fresh.get("head_checks").asString()).isEqualTo("FAILURE");

            mirroredPullRequest = moved;
            JsonNode newerHead = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(newerHead.get("commit_sha").asString()).isEqualTo(HEAD);
            assertThat(newerHead.has("head_checks")).isFalse();

            mirroredPullRequest = stale;
            JsonNode outdated = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(outdated.has("head_checks")).isFalse();
        }

        @Test
        void shouldWriteTheCheckRunsOnlyWhenTheyWereListedForTheReviewedHead() throws Exception {
            Instant readAt = Instant.parse("2026-10-26T12:00:00Z");
            var run = new HeadCheckRuns.Run(
                    "server tests",
                    "CI",
                    "failure",
                    true,
                    "https://github.com/acme/web/actions/runs/1",
                    Instant.parse("2026-10-26T11:50:00Z"),
                    Instant.parse("2026-10-26T11:58:00Z"));
            PullRequest reviewed = pullRequest();
            reviewed.observeHeadCheckRuns(new HeadCheckRuns(HEAD, List.of(run), false, readAt, null));
            PullRequest other = pullRequest();
            other.observeHeadCheckRuns(new HeadCheckRuns("e".repeat(40), List.of(run), true, readAt, null));
            stubGit();

            mirroredPullRequest = reviewed;
            JsonNode listed = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            JsonNode runs = listed.get("head_check_runs");
            assertThat(runs.get("complete").asBoolean()).isFalse();
            assertThat(runs.get("observed_at").asString()).isEqualTo("2026-10-26T12:00:00Z");
            JsonNode entry = runs.get("runs").get(0);
            assertThat(entry.get("name").asString()).isEqualTo("server tests");
            assertThat(entry.get("group").asString()).isEqualTo("CI");
            assertThat(entry.get("state").asString()).isEqualTo("failure");
            assertThat(entry.get("finished").asBoolean()).isTrue();
            assertThat(entry.get("finished_at").asString()).isEqualTo("2026-10-26T11:58:00Z");

            mirroredPullRequest = other;
            JsonNode unlisted = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(unlisted.has("head_check_runs")).isFalse();
        }

        @Test
        void shouldWriteEmptyListsAndNoKeysWhenThePullRequestCarriesNoneOfThem() throws Exception {
            stubGit();

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));

            assertThat(metadataJson.get("is_merged").asBoolean()).isFalse();
            assertThat(metadataJson.has("author_bot")).isFalse();
            assertThat(metadataJson.get("labels")).isEmpty();
            assertThat(metadataJson.get("assignees")).isEmpty();
            assertThat(metadataJson.has("milestone")).isFalse();
            assertThat(metadataJson.has("merge_state_status")).isFalse();
            assertThat(metadataJson.has("review_decision")).isFalse();
            assertThat(metadataJson.has("head_checks")).isFalse();
        }

        @Test
        void shouldExcludeRecordedMarkerlessInlineFeedbackWithoutDroppingOtherComments() throws Exception {
            PullRequestReviewComment own = new PullRequestReviewComment();
            own.setNativeId(81L);
            own.setBody("Explain how to try the timer.");
            PullRequestReviewComment human = new PullRequestReviewComment();
            human.setNativeId(82L);
            human.setBody(own.getBody());
            PullRequestReviewComment unknownId = new PullRequestReviewComment();
            unknownId.setBody(own.getBody());
            deliveredComments = new DeliveredPullRequestCommentLookup.CommentIds(Set.of(82L), Set.of(81L));
            mirroredComments = List.of(own, human, unknownId);

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
            JsonNode comments = objectMapper.readTree(captured.files().get("context/comments.json"));

            assertThat(comments).hasSize(2);
            assertThat(comments.get(0).path("native_id").asLong()).isEqualTo(82L);
            assertThat(comments.get(1).path("body").asString()).isEqualTo(own.getBody());
            assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
        }

        @Test
        void shouldKeepCompleteEmptyCaptureWhenOnlyRecordedInlineFeedbackExists() throws Exception {
            PullRequestReviewComment own = new PullRequestReviewComment();
            own.setNativeId(81L);
            own.setBody("Explain how to try the timer.");
            deliveredComments = new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(81L));
            mirroredComments = List.of(own);

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(objectMapper.readTree(captured.files().get("context/comments.json")))
                    .isEmpty();
            assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
            assertThat(captured.contentStates()).containsEntry(COMMENTS, SourceContentState.EMPTY);
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
            PullRequestReviewComment automated = new PullRequestReviewComment();
            automated.setPath("src/Other.java");
            automated.setLine(6);
            automated.setBody("Coverage dropped.");
            automated.setCreatedAt(Instant.parse("2025-06-02T12:00:00Z"));
            User token = user("project_7_bot_a1b2");
            token.setType(User.Type.BOT);
            automated.setAuthor(token);

            stubGit();
            mirroredComments = List.of(full, minimal, automated);

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode comments = objectMapper.readTree(files.get("context/comments.json"));
            assertThat(comments).hasSize(3);
            assertThat(comments.get(0).get("created_at").asString()).isEqualTo("2025-06-01T12:00:00Z");
            assertThat(comments.get(0).get("author").asString()).isEqualTo("reviewer");
            assertThat(comments.get(0).has("bot")).isFalse();
            assertThat(comments.get(1).get("author").asString()).isEqualTo("project_7_bot_a1b2");
            assertThat(comments.get(1).get("bot").asBoolean()).isTrue();
            assertThat(comments.get(2).has("author")).isFalse();
        }

        @Test
        void shouldGiveEachBundledReviewerPracticeTheReviewersInlineWordsInTheCommentsSource() {
            String words = "Could this loop stop at the first match?";
            PullRequestReviewComment ownWords = new PullRequestReviewComment();
            ownWords.setNativeId(91L);
            ownWords.setPath("src/Main.java");
            ownWords.setLine(10);
            ownWords.setBody(words);
            ownWords.setCreatedAt(Instant.parse("2025-06-01T12:00:00Z"));
            var reviewer = user("reviewer");
            ReflectionTestUtils.setField(reviewer, "id", 32L);
            reviewer.setNativeId(33L);
            ownWords.setAuthor(reviewer);
            stubGit();
            mirroredComments = List.of(ownWords);
            ObjectNode metadata = sampleMetadata();
            metadata.put("subject_role", "REVIEWER");
            metadata.put("review_id", 31L);
            metadata.put("about_user_id", 32L);
            ScmCaptureContext reviewItem = request(metadata);

            var reviewerPractices = ScmCaptureFixtures.bundledPractices().entrySet().stream()
                    .filter(entry -> entry.getValue().definition().about() == AboutRole.REVIEWER)
                    .toList();

            assertThat(reviewerPractices)
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "leaves-useful-specific-review-comments",
                            "reviews-respectfully-asks-rather-than-demands",
                            "reviews-substantively-with-understanding");
            var mapper = JsonMapper.builder().build();
            var exporter = new JobFolderIndexBuilder(
                    new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()), mapper, Clock.systemUTC());
            assertThat(provider.sourceKinds()).contains(COMMENTS);
            for (var entry : reviewerPractices) {
                var practice = entry.getValue();
                assertThat(practice.kind())
                        .as(entry.getKey())
                        .isEqualTo(reviewItem.pinned().item().kind());
                var reads = practice.definition().reads().sources();
                assertThat(reads).as(entry.getKey()).anySatisfy(read -> {
                    assertThat(read.source()).isEqualTo(COMMENTS);
                    assertThat(read.need()).isEqualTo(ReadNeed.COMPLETE);
                });
                var captured = provider.capture(
                        reviewItem, reads.stream().map(read -> read.source()).collect(Collectors.toSet()));

                JsonNode core = objectMapper.readTree(captured.files().get("context/metadata.json"));
                assertThat(core.path("subject_role").asString())
                        .as(entry.getKey())
                        .isEqualTo("REVIEWER");
                JsonNode comments = objectMapper.readTree(captured.files().get("context/comments.json"));
                assertThat(comments).as(entry.getKey()).singleElement().satisfies(comment -> {
                    assertThat(comment.path("author").asString()).isEqualTo("reviewer");
                    assertThat(comment.path("author_id").asLong()).isEqualTo(33L);
                    assertThat(comment.path("body").asString()).isEqualTo(ownWords.getBody());
                });
                assertThat(captured.completeness())
                        .as(entry.getKey())
                        .containsEntry(COMMENTS, SourceCompleteness.COMPLETE);

                // Preparation captures one source at a time and exports each contribution under that source.
                try (var prepared = exporter.build(
                        reviewItem.pinned().item().kind(),
                        Map.of(COMMENTS, provider.capture(reviewItem, Set.of(COMMENTS))))) {
                    assertThat(prepared.files()).as(entry.getKey()).doesNotContainKey("context/metadata.json");
                    JsonNode exported = mapper.readTree(
                            Objects.requireNonNull(prepared.files().get("context/comments.json")));
                    assertThat(exported).as(entry.getKey()).singleElement().satisfies(comment -> {
                        assertThat(comment.path("author").asString()).isEqualTo("reviewer");
                        assertThat(comment.path("author_id").asLong()).isEqualTo(33L);
                    });
                    assertExported(
                            mapper,
                            prepared,
                            reviewItem.pinned().item().kind(),
                            COMMENTS,
                            "context/comments.json",
                            "context/comments.json#/0/body",
                            words);
                }
            }
        }

        @Test
        void shouldNameTheThreadTheParentAndTheSideWhenACommentHasThem() throws Exception {
            PullRequestReviewThread thread = new PullRequestReviewThread();
            ReflectionTestUtils.setField(thread, "id", 70L);
            PullRequestReviewComment root = new PullRequestReviewComment();
            ReflectionTestUtils.setField(root, "id", 1L);
            root.setThread(thread);
            root.setPath("src/Main.java");
            root.setLine(10);
            root.setSide(PullRequestReviewComment.Side.LEFT);
            root.setOutdated(true);
            root.setNativeId(61L);
            root.setCommitId("original-comment-head");
            root.setUpdatedAt(Instant.parse("2025-06-01T12:30:00Z"));
            root.setBody("This branch was removed on purpose?");
            root.setCreatedAt(Instant.parse("2025-06-01T12:00:00Z"));
            PullRequestReviewComment reply = new PullRequestReviewComment();
            ReflectionTestUtils.setField(reply, "id", 2L);
            reply.setThread(thread);
            reply.setInReplyTo(root);
            reply.setPath("src/Main.java");
            reply.setLine(10);
            reply.setSide(PullRequestReviewComment.Side.UNKNOWN);
            reply.setOutdated(false);
            reply.setBody("Yes, see the description.");
            reply.setCreatedAt(Instant.parse("2025-06-01T13:00:00Z"));

            mirroredComments = List.of(reply, root);

            JsonNode comments = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                    .files()
                    .get("context/comments.json"));

            JsonNode first = comments.get(0);
            assertThat(first.propertyNames())
                    .containsExactlyInAnyOrder(
                            "id",
                            "thread",
                            "path",
                            "line",
                            "side",
                            "outdated",
                            "native_id",
                            "commit_id",
                            "body",
                            "created_at",
                            "updated_at");
            assertThat(first.get("id").asLong()).isEqualTo(1L);
            assertThat(first.path("native_id").asLong()).isEqualTo(61L);
            assertThat(first.path("commit_id").asString()).isEqualTo("original-comment-head");
            assertThat(first.path("updated_at").asString()).isEqualTo("2025-06-01T12:30:00Z");
            assertThat(first.get("thread").asLong()).isEqualTo(70L);
            assertThat(first.get("side").asString()).isEqualTo("LEFT");
            assertThat(first.get("outdated").asBoolean()).isTrue();
            JsonNode second = comments.get(1);
            assertThat(second.get("in_reply_to").asLong()).isEqualTo(1L);
            assertThat(second.get("thread").asLong()).isEqualTo(70L);
            assertThat(second.has("side")).isFalse();
            assertThat(second.has("outdated")).isFalse();
        }

        @ParameterizedTest
        @CsvSource(
                value = {
                    "GITHUB, original-head, current-head, original-head",
                    "GITHUB, NULL, current-head, current-head",
                    "GITHUB, '', current-head, current-head",
                    "GITHUB, NULL, NULL, NULL",
                    "GITHUB, '', '', NULL",
                    "GITHUB, '   ', '   ', NULL",
                    "GITLAB, comparison-base, reviewed-head, reviewed-head",
                    "GITLAB, comparison-base, NULL, NULL"
                },
                nullValues = "NULL")
        void shouldKeepTheProvidersActualCommentRevision(
                IdentityProviderType type,
                @Nullable String original,
                @Nullable String current,
                @Nullable String expected)
                throws Exception {
            var comment = new PullRequestReviewComment();
            comment.setProvider(new IdentityProvider(type, "https://scm.example"));
            comment.setOriginalCommitId(original);
            comment.setCommitId(current);
            comment.setPath("src/Main.java");
            comment.setBody("Use the shared helper here.");
            mirroredComments = List.of(comment);

            JsonNode captured = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                    .files()
                    .get("context/comments.json"));
            if (expected == null) {
                assertThat(captured.get(0).has("commit_id")).isFalse();
            } else {
                assertThat(captured.get(0).path("commit_id").asString()).isEqualTo(expected);
            }
        }
    }

    @Nested
    class ReplyParents {

        private static PullRequestReviewComment comment(long id, long nativeId, String body, String login) {
            PullRequestReviewComment comment = new PullRequestReviewComment();
            ReflectionTestUtils.setField(comment, "id", id);
            comment.setNativeId(nativeId);
            comment.setPath("src/RetryPolicy.java");
            comment.setLine(25);
            comment.setBody(body);
            comment.setAuthor(user(login));
            comment.setCreatedAt(Instant.parse("2026-10-18T05:00:00Z").plusSeconds(id));
            return comment;
        }

        private static PullRequestReviewComment reply(long id, long nativeId, PullRequestReviewComment parent) {
            PullRequestReviewComment reply = comment(id, nativeId, "Confirmed with the boundary probe.", "author");
            reply.setInReplyTo(parent);
            return reply;
        }

        private JsonNode capturedComments() throws Exception {
            return objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                    .files()
                    .get("context/comments.json"));
        }

        @Test
        void shouldNameDeliveredFeedbackAsTheReasonAReplysParentIsLeftOut() throws Exception {
            PullRequestReviewComment delivered = comment(1L, 81L, "Throw instead of returning zero.", "hephaestus-app");
            PullRequestReviewComment answer = reply(2L, 82L, delivered);
            deliveredComments = new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(81L));
            mirroredComments = List.of(answer, delivered);

            JsonNode comments = capturedComments();

            assertThat(comments).hasSize(1);
            JsonNode kept = comments.get(0);
            assertThat(kept.path("native_id").asLong()).isEqualTo(82L);
            assertThat(kept.path("in_reply_to").asLong()).isEqualTo(1L);
            assertThat(kept.path("in_reply_to_omitted_reason").asString()).isEqualTo("DELIVERED_FEEDBACK");
            assertThat(kept.path("author").asString()).isEqualTo("author");
            assertThat(kept.path("body").asString()).isEqualTo("Confirmed with the boundary probe.");
            assertThat(comments.toString()).doesNotContain("Throw instead of returning zero.");
        }

        @Test
        void shouldNameTheFeedbackMarkerAsTheReasonWhenTheQueryLeftTheParentOut() throws Exception {
            PullRequestReviewComment marked =
                    comment(1L, 81L, "Throw instead.\n" + WorkspaceScmProjection.HEPHAESTUS_MARKER + " -->", "someone");
            PullRequestReviewComment answer = reply(2L, 82L, marked);
            // The repository query leaves marked bodies out; only the reply comes back.
            mirroredComments = List.of(answer);

            JsonNode kept = capturedComments().get(0);

            assertThat(kept.path("in_reply_to").asLong()).isEqualTo(1L);
            assertThat(kept.path("in_reply_to_omitted_reason").asString()).isEqualTo("FEEDBACK_MARKER");
            assertThat(kept.path("author").asString()).isEqualTo("author");
        }

        @Test
        void shouldPreferTheDeliveredLedgerWhenTheParentIsBothDeliveredAndMarked() throws Exception {
            PullRequestReviewComment both =
                    comment(1L, 81L, "Note " + WorkspaceScmProjection.HEPHAESTUS_MARKER + " -->", "hephaestus-app");
            PullRequestReviewComment answer = reply(2L, 82L, both);
            User bot = user("ci-helper");
            bot.setType(User.Type.BOT);
            answer.setAuthor(bot);
            deliveredComments = new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(81L));
            mirroredComments = List.of(answer);

            JsonNode kept = capturedComments().get(0);

            assertThat(kept.path("in_reply_to_omitted_reason").asString()).isEqualTo("DELIVERED_FEEDBACK");
            assertThat(kept.path("bot").asBoolean()).isTrue();
        }

        @Test
        void shouldStateOnlyTheMarkerPolicyWhenAPersonQuotedTheMarkerInTheParent() throws Exception {
            // The marker in a body says which policy left it out; it does not prove Hephaestus wrote it.
            PullRequestReviewComment quoting = comment(
                    1L,
                    91L,
                    "Our bot writes `" + WorkspaceScmProjection.HEPHAESTUS_MARKER + "` here; please drop the cast.",
                    "reviewer");
            PullRequestReviewComment answer = reply(2L, 92L, quoting);
            mirroredComments = List.of(answer);

            JsonNode kept = capturedComments().get(0);

            assertThat(kept.path("in_reply_to_omitted_reason").asString()).isEqualTo("FEEDBACK_MARKER");
            assertThat(kept.path("body").asString()).isEqualTo("Confirmed with the boundary probe.");
        }

        @Test
        void shouldNameNoReasonWhenTheParentIsAKeptReviewOrThereIsNoParent() throws Exception {
            PullRequestReviewComment review = comment(1L, 71L, "This catch hides the parse failure.", "reviewer");
            PullRequestReviewComment answer = reply(2L, 72L, review);
            PullRequestReviewComment standalone = comment(3L, 73L, "Why zero here?", "reviewer");
            deliveredComments = new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(81L));
            mirroredComments = List.of(standalone, answer, review);

            JsonNode comments = capturedComments();

            assertThat(comments).hasSize(3);
            assertThat(comments.get(0).path("body").asString()).isEqualTo("This catch hides the parse failure.");
            assertThat(comments.get(1).path("in_reply_to").asLong()).isEqualTo(1L);
            for (JsonNode kept : comments) {
                assertThat(kept.has("in_reply_to_omitted_reason")).isFalse();
            }
        }
    }

    private ScmCaptureContext coreWithDiffPlan() {
        var original = request(sampleMetadata());
        var plan = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(CORE), "2.0.0", 3, null, Set.of(CORE, DIFF));
        return new ScmCaptureContext(
                original.pinned(), plan, original.work(), new ScmCapturePreparation(), original.capturedAt());
    }

    @Nested
    class Commits {

        @ParameterizedTest
        @ValueSource(strings = {"GITHUB", "GITLAB"})
        void shouldCaptureCommitRecordsThroughTheSingletonSourcePlan(String providerKind) throws Exception {
            stubGit();
            emitCommit("Explain the exact change");
            var original = request(sampleMetadata());
            var integration = new IntegrationRef(IntegrationKind.valueOf(providerKind), WORKSPACE_ID, null);
            var pinned = ItemFixtures.snapshot(
                            original.pinned().item(), original.pinned().state())
                    .version(original.pinned().version())
                    .integration(integration)
                    .roles(original.pinned().roles())
                    .label(original.pinned().label())
                    .build();
            var owner = mock(ItemSource.class);
            when(owner.supports(pinned.item())).thenReturn(true);
            when(owner.capture(eq(pinned), any())).thenAnswer(invocation -> {
                CaptureRequest selected = invocation.getArgument(1);
                assertThat(selected.sources()).hasSize(1);
                var context = new ScmCaptureContext(
                        pinned, selected, original.work(), new ScmCapturePreparation(), original.capturedAt());
                return provider.capture(context, selected.sources());
            });
            var mapper = new JsonMapper();
            var registry = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
            var builder = new WorkspaceContextBuilder(
                    List.of(),
                    List.of(owner),
                    registry,
                    new JobFolderIndexBuilder(registry, mapper, Clock.systemUTC()));
            var plan = new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(CORE, DIFF), "2.0.0", 3);
            try (var captured = builder.prepare(pinned, plan)) {
                assertThat(captured.files()).containsKey(PullRequestContentSource.COMMITS_FILE);
                var commits = objectMapper
                        .readTree(Objects.requireNonNull(captured.files().get(PullRequestContentSource.COMMITS_FILE)))
                        .path("commits");
                assertThat(commits.size()).isEqualTo(1);
                assertThat(commits.path(0).path("sha").asString()).isEqualTo(HEAD);
                assertThat(commits.path(0).path("message").asString()).isEqualTo("Explain the exact change");
                assertThat(Objects.requireNonNull(captured.manifest()).citableTexts())
                        .anySatisfy(text -> {
                            assertThat(text.source()).isEqualTo(CORE.value());
                            assertThat(text.path())
                                    .isEqualTo(PullRequestContentSource.COMMITS_FILE + "#/commits/0/message");
                        });
            }
        }

        @Test
        void shouldKeepCoreMetadataWithoutPreparingARepositoryWhenDiffIsNotInThePlan() {
            var captured = provider.capture(request(sampleMetadata()), Set.of(CORE));
            assertThat(captured.files())
                    .containsKey("context/metadata.json")
                    .doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
            assertThat(captured.completeness()).containsEntry(CORE, SourceCompleteness.COMPLETE);
            verify(repositoryPreparer, never()).prepare(any());
        }

        @Test
        void shouldKeepCoreMetadataWhenThePlannedRepositoryCaptureIsDisabled() {
            var captured = provider.capture(coreWithDiffPlan(), Set.of(CORE));
            assertThat(captured.files())
                    .containsKey("context/metadata.json")
                    .doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
            assertThat(captured.completeness()).containsEntry(CORE, SourceCompleteness.COMPLETE);
            assertThat(captured.stateOverrides()).isEmpty();
            verify(repositoryPreparer, never()).prepare(any());
        }

        @Test
        void shouldKeepCoreMetadataWhenThePinnedRangeIsUnavailable() {
            stubGit();
            when(repositoryPreparer.prepare(any()))
                    .thenThrow(
                            new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned range unavailable"));
            var captured = provider.capture(coreWithDiffPlan(), Set.of(CORE));
            assertThat(captured.files())
                    .containsKey("context/metadata.json")
                    .doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
            assertThat(captured.completeness()).containsEntry(CORE, SourceCompleteness.COMPLETE);
            assertThat(captured.stateOverrides()).isEmpty();
            var unavailable = provider.capture(request(sampleMetadata()), Set.of(DIFF));
            assertThat(unavailable.stateOverrides())
                    .containsEntry(
                            DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE));
        }

        @Test
        void shouldNotPrepareCommitRecordsWhenDiffGovernanceIsBlocked() {
            stubGit();
            var original = request(sampleMetadata());
            var owner = mock(ItemSource.class);
            when(owner.supports(original.pinned().item())).thenReturn(true);
            when(owner.capture(eq(original.pinned()), any())).thenAnswer(invocation -> {
                CaptureRequest selected = invocation.getArgument(1);
                assertThat(selected.sources()).containsExactly(CORE);
                assertThat(selected.permittedSources()).containsExactly(CORE);
                return provider.capture(
                        new ScmCaptureContext(
                                original.pinned(),
                                selected,
                                original.work(),
                                new ScmCapturePreparation(),
                                original.capturedAt()),
                        selected.sources());
            });
            var mapper = new JsonMapper();
            var catalog = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
            var registry = mock(ClasspathSourceCatalogRegistry.class);
            when(registry.current()).thenReturn(catalog.current());
            when(registry.catalogDigest()).thenReturn(catalog.catalogDigest());
            when(registry.requireSource(CORE)).thenReturn(catalog.requireSource(CORE));
            when(registry.requireSource(DIFF)).thenReturn(catalog.requireSource(DIFF));
            when(registry.isSourceUsePermitted(CORE, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .thenReturn(true);
            var builder = new WorkspaceContextBuilder(
                    List.of(),
                    List.of(owner),
                    registry,
                    new JobFolderIndexBuilder(registry, mapper, Clock.systemUTC()));
            var plan = new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(CORE, DIFF), "2.0.0", 3);
            try (var captured = builder.prepare(original.pinned(), plan)) {
                assertThat(captured.files())
                        .containsKey("context/metadata.json")
                        .doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
                assertThat(Objects.requireNonNull(captured.manifest()).sources())
                        .anySatisfy(source -> {
                            assertThat(source.kind()).isEqualTo(DIFF);
                            assertThat(source.state())
                                    .isEqualTo(new SourceCaptureState.Unavailable(
                                            SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                        });
            }
            verify(repositoryPreparer, never()).prepare(any());
        }

        @Test
        void shouldCaptureTheExactCommitMessageParentsAndFileChangesAsCitableText() throws Exception {
            stubGit();
            String message = "feat: preserve words\n\n\nQuoted β\twords\n";
            emitCommit(message);
            var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));
            var commits = objectMapper
                    .readTree(Objects.requireNonNull(captured.files().get(PullRequestContentSource.COMMITS_FILE)))
                    .path("commits");
            assertThat(commits.size()).isEqualTo(1);
            var commit = Objects.requireNonNull(commits.get(0));
            assertThat(commit.path("sha").asString()).isEqualTo(HEAD);
            assertThat(commit.path("message").asString()).isEqualTo(message);
            assertThat(commit.path("parents").path(0).asString()).isEqualTo(BASE);
            assertThat(commit.path("files").path(0).path("status").asString()).isEqualTo("R");
            assertThat(commit.path("files").path(0).path("path").asString()).isEqualTo("after.ts");
            assertThat(commit.path("files").path(0).path("oldPath").asString()).isEqualTo("before.ts");
            assertThat(captured.citableTexts())
                    .filteredOn(
                            text -> text.path().equals(PullRequestContentSource.COMMITS_FILE + "#/commits/0/message"))
                    .singleElement()
                    .satisfies(text -> {
                        assertThat(text.source()).isEqualTo(CORE.value());
                        byte[] staged = Objects.requireNonNull(captured.files().get(text.stagedPath()));
                        assertThat(new String(staged, StandardCharsets.UTF_8)).isEqualTo(message);
                        assertThat(text.sha256()).isEqualTo(ScmVersion.contentHash(staged));
                    });
        }

        @Test
        void shouldCaptureAnEmptyCommitRangeAsAnExplicitEmptyRecord() throws Exception {
            stubGit();
            var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));
            var record = objectMapper.readTree(
                    Objects.requireNonNull(captured.files().get(PullRequestContentSource.COMMITS_FILE)));
            assertThat(record.path("commits").isArray()).isTrue();
            assertThat(record.path("commits").isEmpty()).isTrue();
        }

        @Test
        void shouldFailCaptureWhenThePinnedCommitRecordsExceedTheByteLimit() {
            stubGit();
            emitCommit("x".repeat(4 * 1024 * 1024 + 1));
            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF)))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CAPTURE_FAILED));
        }

        @Test
        void shouldNotExportPersonalCommitRecordsWhenOnlyCodeIsSelected() {
            stubGit();
            var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));
            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
        }

        private void emitCommit(String message) {
            var commit = new CommitDetails(
                    HEAD,
                    "feat: preserve words",
                    "Quoted β\twords",
                    "Author",
                    "author@example.test",
                    Instant.EPOCH,
                    "Committer",
                    "committer@example.test",
                    Instant.EPOCH,
                    2,
                    1,
                    1,
                    List.of(new CommitDetails.FileChange("after.ts", ChangeType.RENAMED, 2, 1, 3, "before.ts")),
                    List.of(BASE),
                    message);
            doAnswer(call -> {
                        Consumer<CommitDetails> consumer = call.getArgument(3);
                        consumer.accept(commit);
                        return null;
                    })
                    .when(gitRepositoryManager)
                    .forEachCommitBetween(eq(REPOSITORY), eq(BASE), eq(HEAD), any());
        }

        @Test
        void shouldStageNoCommitsWhenTheCloneIsNotPrepared() {

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
        }
    }

    @Nested
    class ReviewedChange {

        @Test
        void shouldCaptureThePinnedRangePathsAndDiffForApplicability() throws Exception {
            stubGit();
            var paths = Set.of("before.ts", "after.ts");
            var diff = "diff --git a/before.ts b/after.ts\n-@Test\n+changed();\n";
            when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD)).thenReturn(paths);
            when(gitRepositoryManager.unifiedDiff(REPOSITORY, BASE, HEAD)).thenReturn(diff);

            var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

            JsonNode change = objectMapper.readTree(captured.files().get(PullRequestContentSource.CHANGE_FILE));
            assertThat(change.path("base_sha").asString()).isEqualTo(BASE);
            assertThat(change.path("head_sha").asString()).isEqualTo(HEAD);
            assertThat(change.path("changed_paths"))
                    .isEqualTo(objectMapper.valueToTree(List.of("after.ts", "before.ts")));
            assertThat(change.path("diff").asString()).isEqualTo(diff);
            assertThat(captured.files().keySet()).contains(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.filesOnDisk()).isEmpty();
            assertThat(captured.cleanup()).isNull();
            assertThat(captured.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
            assertThat(captured.contentStates().get(DIFF)).isEqualTo(SourceContentState.NON_EMPTY);
        }

        @Test
        void shouldCaptureEmptyDiffAsCompleteEmptyEvidence() {
            stubGit();
            when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD)).thenReturn(Set.of());
            when(gitRepositoryManager.unifiedDiff(REPOSITORY, BASE, HEAD)).thenReturn("");

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
                    .thenThrow(new ItemCaptureException(
                            ItemCaptureCode.CAPTURE_FAILED, "The pinned review diff range is unavailable"));
            assertThatThrownBy(() -> captureFiles(request(sampleMetadata())))
                    .isInstanceOf(ItemCaptureException.class)
                    .hasMessageContaining("pinned review diff range is unavailable");
        }

        @Test
        void shouldNotPinAChangeWhenOnlyCommentsAreSelected() {

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.immutableIdentities()).isEmpty();
        }
    }

    @Nested
    class RepositoryAvailability {

        @Test
        void shouldCaptureMetadataAndRecordDisabledDiffWithoutClaimingCodeEvidence() {
            var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));

            assertThat(captured.files())
                    .containsKeys("context/metadata.json", PullRequestContentSource.DESCRIPTION_FILE);
            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.completeness())
                    .containsEntry(CORE, SourceCompleteness.COMPLETE)
                    .doesNotContainKey(DIFF);
            assertThat(captured.immutableIdentities()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactly(
                            Map.entry(DIFF, new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED)));
        }
    }

    @Test
    void shouldPinCoreAndDiffToTheSameReviewRange() {
        stubGit();

        var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));

        var recordPaths = Set.of(
                "context/metadata.json",
                PullRequestContentSource.DESCRIPTION_FILE,
                PullRequestContentSource.CHANGE_FILE,
                PullRequestContentSource.COMMITS_FILE);
        assertThat(captured.files().keySet())
                .containsAll(recordPaths)
                .allSatisfy(path -> assertThat(recordPaths.contains(path)
                                || path.startsWith("context/citable-records/")
                                || path.startsWith("context/citable/"))
                        .isTrue());
        assertThat(captured.citableTexts())
                .isNotEmpty()
                .allSatisfy(text -> assertThat(captured.files()).containsKey(text.stagedPath()));
        assertThat(provider.sourceKindFor(PullRequestContentSource.DESCRIPTION_FILE))
                .isEqualTo(CORE);
        assertThat(captured.immutableIdentities()).doesNotContainKey(CORE);
        assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
    }

    @Test
    void shouldPinAgainstThePreparedTargetRatherThanJobMetadata() {
        stubGit();
        var metadata = sampleMetadata();
        metadata.remove("base_ref_oid");

        var captured = provider.capture(request(metadata), Set.of(DIFF));

        assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.files()).containsKey(PullRequestContentSource.CHANGE_FILE);
    }
}
