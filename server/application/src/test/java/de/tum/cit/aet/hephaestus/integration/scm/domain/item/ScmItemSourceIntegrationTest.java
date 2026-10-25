package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemMergeTimeQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateValue;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.GitLabStoppedProjectService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.ResultCurrentness;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ScmItemSourceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ReviewIntegrationData resultData;

    @Autowired
    private ResultRepository results;

    @Autowired
    private LabelRepository labels;

    @Autowired
    private ItemRepository itemRows;

    @Autowired
    private PracticeVersionService practiceVersions;

    @MockitoSpyBean
    private ResultCurrentness currentness;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private ScmItemService sources;

    @Autowired
    private ItemIndexService writer;

    @Autowired
    private ItemDescriptorRegistry descriptors;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private UserRepository users;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ItemEventRepository events;

    @Autowired
    private MonitoredRepositoryQuery monitored;

    @Autowired
    private ScmItemSourceAccess sourceAccess;

    @Autowired
    private ProductItemQuery productItems;

    @Autowired
    private ItemMergeTimeQuery mergeTimes;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepositoryMonitorService monitorService;

    @Autowired
    private GitLabStoppedProjectService stoppedProjects;

    @Autowired
    private FeedbackRepository feedbackRows;

    @Autowired
    private FeedbackTargetResolver targets;

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldResolveOnlyTheExactMonitoredNativeRepositoryAndAuthorizeWithoutGit(IdentityProviderType type) {
        var fixture = fixture(type);
        long workspaceId = fixture.workspace().getId();
        var root = writer.upsert(new ItemRegistration(
                workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
        var monitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        workspaceId, fixture.repository().getNameWithOwner())
                .orElseThrow();
        var reference =
                Objects.requireNonNull(monitored.repositories(workspaceId).get(root.itemId()));
        assertThat(reference.repositoryItemId()).isEqualTo(root.itemId());
        assertThat(reference.monitorId()).isEqualTo(monitor.getId());
        assertThat(reference.nameWithOwner()).isEqualTo(fixture.repository().getNameWithOwner());
        var file =
                writer.upsert(new ItemRegistration(workspaceId, ScmItemEvents.FILE, null, root.itemId(), "src/a.ts"));
        assertThat(sourceAccess.permits(file, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        var foreign = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(
                "foreign-access-" + UUID.randomUUID().toString().substring(0, 12)));
        assertThat(monitored.repositories(foreign.getId())).isEmpty();
        assertThat(sourceAccess.permits(
                        new ItemRef(foreign.getId(), file.itemId(), file.kind(), null, root.itemId(), file.path()),
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        var otherProvider = providers.saveAndFlush(
                new IdentityProvider(type, "https://other-" + UUID.randomUUID() + ".example.test"));
        var other = new Repository();
        other.setProvider(otherProvider);
        other.setNativeId(fixture.repository().getNativeId());
        other.setName("source");
        other.setNameWithOwner(fixture.repository().getNameWithOwner());
        other.setDefaultBranch("main");
        other.setVisibility(Repository.Visibility.PUBLIC);
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        other.setPushedAt(Instant.now());
        other = repositories.saveAndFlush(other);
        var otherItem =
                writer.upsert(new ItemRegistration(workspaceId, ScmItemEvents.REPOSITORY, other.getId(), null, null));
        assertThat(monitored.repositories(workspaceId)).containsOnlyKeys(root.itemId());
        assertThat(sourceAccess.permits(otherItem, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        monitor.setNativeId(fixture.repository().getNativeId() == 13L ? 14L : 13L);
        monitors.saveAndFlush(monitor);
        assertThat(monitored.repositories(workspaceId)).isEmpty();
        assertThat(sourceAccess.permits(file, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        monitor.setNativeId(fixture.repository().getNativeId());
        monitors.saveAndFlush(monitor);
        writer.remove(workspaceId, root.itemId(), Instant.now());
        assertThat(monitored.repositories(workspaceId)).isEmpty();
        assertThat(sourceAccess.permits(file, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        writer.restore(workspaceId, root.itemId());
        assertThat(monitored.repositories(workspaceId)).containsOnlyKeys(root.itemId());
        assertThat(sourceAccess.permits(file, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepKindsParentsAndProviderIdentitiesDistinct(IdentityProviderType type) throws Exception {
        var fixture = fixture(type);
        var issue = new Issue();
        issue.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        issue.setProvider(fixture.provider());
        issue.setRepository(fixture.repository());
        issue.setNumber(7);
        issue.setTitle("An issue");
        issue.setBody("Issue body");
        issue.setState(Issue.State.OPEN);
        issue.setAuthor(fixture.author());
        issue.setHtmlUrl(fixture.repository().getHtmlUrl() + "/issues/7");
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setCommentsCount(2);
        issue = issues.saveAndFlush(issue);
        jdbc.update(
                """
                INSERT INTO issue_comment(native_id,provider_id,issue_id,body,html_url,author_association,created_at,author_id)
                VALUES (?,?,?,'second','https://example.test/comment/second','MEMBER',?,?),
                       (?,?,?,'first','https://example.test/comment/first','MEMBER',?,?)
                """,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                fixture.provider().getId(),
                issue.getId(),
                Timestamp.from(Instant.parse("2026-10-02T10:00:00Z")),
                fixture.author().getId(),
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                fixture.provider().getId(),
                issue.getId(),
                Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")),
                fixture.author().getId());
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(7);
        pull.setTitle("A pull request");
        pull.setBody("Description");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/7");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        var secondAssignee = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                "assignee-" + UUID.randomUUID(),
                fixture.provider()));
        pull.getAssignees().add(fixture.author());
        pull.getAssignees().add(secondAssignee);
        pull.replaceClosingIssues(Set.of(issue));
        pull = pulls.saveAndFlush(pull);
        ItemRef holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        ItemRef issueRef = writer.upsert(
                new ItemRegistration(holder.workspaceId(), ScmItemEvents.ISSUE, issue.getId(), holder.itemId(), null));
        ItemRef pullRef = writer.upsert(new ItemRegistration(
                holder.workspaceId(), ScmItemEvents.PULL_REQUEST, pull.getId(), holder.itemId(), null));
        var issueSnapshot = sources.pin(issueRef, null);
        var pullSnapshot = sources.pin(pullRef, null);
        // The stored-work version is the pinned one, with the issue's discussion and the closing-issue context.
        assertThat(sources.currentVersion(issueRef)).contains(issueSnapshot.version());
        assertThat(sources.currentVersion(pullRef)).contains(pullSnapshot.version());
        assertThat(issueSnapshot.label().noun()).isEqualTo("issue");
        assertThat(issueSnapshot.label().label()).isEqualTo("#7");
        assertThat(pullSnapshot.label().label()).isEqualTo(type == IdentityProviderType.GITLAB ? "!7" : "#7");
        assertThat(issueSnapshot.state()).doesNotContainKey("baseBranch");
        assertThat(pullSnapshot.state()).containsEntry("baseBranch", "main");
        // Practices match reviewOn.state against these keys, so every declared dimension carries a declared value.
        assertThat(pullSnapshot.state()).containsEntry("state", "OPEN").containsEntry("draftStatus", "NOT_DRAFT");
        assertPublishesDeclaredState(issueSnapshot);
        assertPublishesDeclaredState(pullSnapshot);
        assertPublishesDeclaredState(sources.current(pullRef).orElseThrow());
        assertThat(pullSnapshot.label().noun())
                .isEqualTo(type == IdentityProviderType.GITLAB ? "merge request" : "pull request");
        assertThat(pullSnapshot.roles()).anySatisfy(role -> {
            assertThat(role.role()).isEqualTo(AboutRole.AUTHOR);
            assertThat(role.identity().providerId())
                    .isEqualTo(fixture.provider().getId());
            assertThat(role.identity().subject())
                    .isEqualTo(Long.toString(fixture.author().getNativeId()));
        });
        assertThat(pullSnapshot.roles().stream()
                        .filter(role -> role.role() == AboutRole.ASSIGNEE)
                        .map(role -> role.identity().subject())
                        .toList())
                .containsExactlyInAnyOrder(
                        Long.toString(fixture.author().getNativeId()), Long.toString(secondAssignee.getNativeId()));
        var named = sources.labels(holder.workspaceId(), List.of(issueRef, pullRef));
        assertThat(named.get(issueRef.itemId())).isEqualTo(issueSnapshot.label());
        assertThat(named.get(pullRef.itemId())).isEqualTo(pullSnapshot.label());
        var expectedBodies = Map.of(issueRef.itemId(), "Issue body", pullRef.itemId(), "Description");
        for (var snapshot : List.of(issueSnapshot, pullSnapshot)) {
            var core = snapshot.item().kind().equals(ScmItemEvents.ISSUE)
                    ? IssueContentSource.CORE
                    : PullRequestContentSource.CORE;
            var selected = snapshot.item().kind().equals(ScmItemEvents.ISSUE)
                    ? Set.of(core, IssueContentSource.COMMENTS)
                    : Set.of(core);
            var captured = sources.capture(
                    snapshot, new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, selected, "2.0.0", 3));
            if (snapshot.item().kind().equals(ScmItemEvents.ISSUE)) {
                var discussion = new JsonMapper().readTree(captured.files().get("context/comments.json"));
                assertThat(discussion).hasSize(2);
                assertThat(discussion.get(0).path("body").asString()).isEqualTo("first");
                assertThat(discussion.get(1).path("body").asString()).isEqualTo("second");
            }
            assertThat(captured.files()).containsKey("context/metadata.json");
            var staged = sources.record(snapshot, captured.files());
            assertThat(staged.path("title").asString())
                    .isEqualTo(snapshot.label().title());
            assertThat(staged.path("body").asString())
                    .isEqualTo(expectedBodies.get(snapshot.item().itemId()));
            assertThat(staged.path("author").asString())
                    .isEqualTo(fixture.author().getLogin());
            assertThat(staged.path("provider_id").asLong())
                    .isEqualTo(fixture.provider().getId());
            assertThat(staged.path("repository_id").asLong())
                    .isEqualTo(fixture.repository().getId());
            assertThat(captured.citableTexts())
                    .isNotEmpty()
                    .allSatisfy(text -> assertThat(text.sha256())
                            .isEqualTo(ScmVersion.contentHash(
                                    Objects.requireNonNull(captured.files().get(text.stagedPath())))));
        }
        var linked = sources.capture(
                pullSnapshot,
                new CaptureRequest(
                        UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(LinkedWorkItemContentSource.KIND), "2.0.0", 3));
        var linkedWords = Objects.requireNonNull(linked.files().get("context/linked_work_items/7.md"));
        assertThat(new String(linkedWords, StandardCharsets.UTF_8)).contains("An issue", "Issue body");
        assertThat(linked.citableTexts())
                .filteredOn(text -> text.path().equals("context/linked_work_items/7.md"))
                .singleElement()
                .satisfies(text -> {
                    assertThat(text.source()).isEqualTo(LinkedWorkItemContentSource.KIND.value());
                    assertThat(linked.files().get(text.stagedPath())).isEqualTo(linkedWords);
                    assertThat(text.sha256()).isEqualTo(ScmVersion.contentHash(linkedWords));
                });
        var wrongKind = writer.upsert(
                new ItemRegistration(holder.workspaceId(), ScmItemEvents.ISSUE, pull.getId(), holder.itemId(), null));
        assertThatThrownBy(() -> sources.pin(wrongKind, null))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class, e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.ITEM_REMOVED));
        var review = new PullRequestReview();
        review.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        review.setProvider(fixture.provider());
        review.setPullRequest(pull);
        review.setAuthor(fixture.author());
        review.setBody("Review words");
        review.setState(PullRequestReview.State.COMMENTED);
        review.setHtmlUrl(pull.getHtmlUrl() + "#review");
        review.setCommitId("a".repeat(40));
        review.setSubmittedAt(Instant.now());
        review = reviews.saveAndFlush(review);
        var reviewRef = writer.upsert(new ItemRegistration(
                holder.workspaceId(), ScmItemEvents.PULL_REQUEST_REVIEW, review.getId(), pullRef.itemId(), null));
        var reviewSnapshot = sources.pin(reviewRef, null);
        assertThat(reviewSnapshot.roles()).extracting(RoleHolder::role).containsExactly(AboutRole.REVIEWER);
        var second = connectedWorkspace(type, fixture.provider().getServerUrl(), "second-" + UUID.randomUUID());
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                second, fixture.repository().getNameWithOwner()));
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                fixture.workspace().getId(),
                null,
                DataSource.WEBHOOK,
                "submitted",
                UUID.randomUUID().toString(),
                type,
                fixture.author().getId());
        var submitted = new ScmDomainEvent.ReviewSubmitted(
                ScmEventPayload.ReviewData.from(review).orElseThrow(), context);
        transactions.executeWithoutResult(status -> publisher.publishEvent(submitted));
        transactions.executeWithoutResult(status -> publisher.publishEvent(submitted));
        for (long workspaceId : List.of(holder.workspaceId(), second.getId())) {
            var repositoryItem = writer.upsert(new ItemRegistration(
                    workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
            var parent = writer.upsert(new ItemRegistration(
                    workspaceId, ScmItemEvents.PULL_REQUEST, pull.getId(), repositoryItem.itemId(), null));
            var child = writer.upsert(new ItemRegistration(
                    workspaceId, ScmItemEvents.PULL_REQUEST_REVIEW, review.getId(), parent.itemId(), null));
            var event = events.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                            workspaceId,
                            child.itemId(),
                            ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED.value(),
                            sources.pin(child, null).version().value())
                    .orElseThrow();
            assertThat(event.getActorUserId()).isEqualTo(fixture.author().getId());
            assertThat(event.getRequestedById()).isNull();
            assertThat(event.getOccurredAt()).isEqualTo(context.occurredAt());
        }
        var otherFixture = fixture(type);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                otherFixture.workspace(), fixture.repository().getNameWithOwner()));
        var otherRoot = writer.upsert(new ItemRegistration(
                otherFixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                otherFixture.repository().getId(),
                null,
                null));
        var wrongIssueParent = writer.upsert(new ItemRegistration(
                otherRoot.workspaceId(), ScmItemEvents.ISSUE, issue.getId(), otherRoot.itemId(), null));
        var wrongPullParent = writer.upsert(new ItemRegistration(
                otherRoot.workspaceId(), ScmItemEvents.PULL_REQUEST, pull.getId(), otherRoot.itemId(), null));
        var wrongReviewAncestor = writer.upsert(new ItemRegistration(
                otherRoot.workspaceId(),
                ScmItemEvents.PULL_REQUEST_REVIEW,
                review.getId(),
                wrongPullParent.itemId(),
                null));
        for (var misparented : List.of(wrongIssueParent, wrongPullParent, wrongReviewAncestor)) {
            assertThatThrownBy(() -> sources.pin(misparented, null))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.ITEM_NOT_VISIBLE));
            assertThat(sourceAccess.permits(misparented, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                    .isFalse();
        }
        assertThat(reviewSnapshot.state()).containsEntry("baseBranch", "main");
        var historyAuthor = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                "history-" + UUID.randomUUID(),
                fixture.provider()));
        var historicalCommit = new Commit();
        historicalCommit.setSha("e".repeat(40));
        historicalCommit.setMessage("Earlier repository work");
        historicalCommit.setAuthoredAt(Instant.now());
        historicalCommit.setCommittedAt(Instant.now());
        historicalCommit.setRepository(fixture.repository());
        historicalCommit.setAuthor(historyAuthor);
        commits.saveAndFlush(historicalCommit);
        UUID restriction = databaseTestUtils.erase(
                Objects.requireNonNull(fixture.provider().getId()), Long.toString(historyAuthor.getNativeId()), "");
        try {
            assertThatThrownBy(() -> sources.capture(
                            pullSnapshot,
                            new CaptureRequest(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    1,
                                    Set.of(RepositoryTreeContentSource.KIND),
                                    "2.0.0",
                                    3)))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        } finally {
            jdbc.update("DELETE FROM identity_restriction WHERE id=?", restriction);
        }
        assertThat(mergeTimes.mergedAt(holder.workspaceId(), List.of(issueRef, pullRef, reviewRef)))
                .isEmpty();
        Instant mergedAt = Instant.parse("2026-10-14T10:00:00Z");
        pull.setMerged(true);
        pull.setMergedAt(mergedAt);
        pulls.saveAndFlush(pull);
        assertThat(mergeTimes.mergedAt(holder.workspaceId(), List.of(issueRef, pullRef, reviewRef)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(pullRef.itemId(), mergedAt, reviewRef.itemId(), mergedAt));
        assertThatThrownBy(() -> mergeTimes.mergedAt(holder.workspaceId() + 1, List.of(pullRef)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.ITEM_NOT_VISIBLE));
        pull.setMergedAt(null);
        pulls.saveAndFlush(pull);
        assertThat(mergeTimes.mergedAt(holder.workspaceId(), List.of(pullRef, reviewRef)))
                .isEmpty();
        pull.setMergedAt(mergedAt);
        pulls.saveAndFlush(pull);
        var timingMonitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        holder.workspaceId(), fixture.repository().getNameWithOwner())
                .orElseThrow();
        timingMonitor.setNativeId(fixture.repository().getNativeId() == 13L ? 14L : 13L);
        monitors.saveAndFlush(timingMonitor);
        assertThatThrownBy(() -> mergeTimes.mergedAt(holder.workspaceId(), List.of(pullRef)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.ITEM_NOT_VISIBLE));
        timingMonitor.setNativeId(fixture.repository().getNativeId());
        monitors.saveAndFlush(timingMonitor);
        writer.remove(holder.workspaceId(), pullRef.itemId(), Instant.now());
        assertThat(mergeTimes.mergedAt(holder.workspaceId(), List.of(pullRef, reviewRef)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(pullRef.itemId(), mergedAt, reviewRef.itemId(), mergedAt));
        writer.restore(holder.workspaceId(), pullRef.itemId());
        var missingTiming = writer.upsert(new ItemRegistration(
                holder.workspaceId(), ScmItemEvents.PULL_REQUEST, Long.MAX_VALUE - 1, holder.itemId(), null));
        assertThatThrownBy(() -> mergeTimes.mergedAt(holder.workspaceId(), List.of(missingTiming)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.SOURCE_NOT_READY));

        var changed = pulls.findById(pull.getId()).orElseThrow();
        changed.setBaseRefOid("c".repeat(40));
        pulls.saveAndFlush(changed);
        assertThat(sources.pin(pullRef, null).version()).isNotEqualTo(pullSnapshot.version());
        assertThatThrownBy(() -> sources.pin(pullRef, pullSnapshot.version()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        e -> assertThat(e.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        var foreign = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("foreign-" + UUID.randomUUID()));
        var spoof = new ItemRef(
                foreign.getId(), issueRef.itemId(), issueRef.kind(), issueRef.sourceId(), issueRef.parentId(), null);
        assertThat(sources.current(spoof)).isEmpty();
        writer.remove(holder.workspaceId(), issueRef.itemId(), Instant.now());
        var removedLabel = Objects.requireNonNull(
                sources.labels(holder.workspaceId(), List.of(issueRef)).get(issueRef.itemId()));
        assertThat(removedLabel.title()).isEqualTo("An issue");
        assertThat(removedLabel.url()).isNull();
        assertThat(sources.current(issueRef)).isEmpty();
        writer.restore(holder.workspaceId(), issueRef.itemId());
        assertThat(sources.current(issueRef)).isPresent();

        // It follows the pinned version through a base change, a closing-issue edit and a description-only edit.
        var rebased = sources.pin(pullRef, null).version();
        assertThat(sources.currentVersion(pullRef)).contains(rebased);
        jdbc.update("UPDATE issue SET body=? WHERE id=?", "A changed issue body", issue.getId());
        var relinked = sources.pin(pullRef, null).version();
        assertThat(relinked).isNotEqualTo(rebased);
        assertThat(sources.currentVersion(pullRef)).contains(relinked);
        assertThat(sources.currentVersion(issueRef))
                .contains(sources.pin(issueRef, null).version());
        jdbc.update("UPDATE issue SET body=? WHERE id=?", "A changed description", pull.getId());
        var described = sources.pin(pullRef, null).version();
        assertThat(described).isNotEqualTo(relinked);
        assertThat(sources.currentVersion(pullRef)).contains(described);
        assertThat(sources.currentVersion(reviewRef)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldLeaveTheStoredVersionUnknownWhileTheWorkHasNoTitle(IdentityProviderType type) {
        var fixture = fixture(type);
        var issue = new Issue();
        issue.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        issue.setProvider(fixture.provider());
        issue.setRepository(fixture.repository());
        issue.setNumber(8);
        issue.setTitle("A titled issue");
        issue.setBody("Issue body");
        issue.setState(Issue.State.OPEN);
        issue.setAuthor(fixture.author());
        issue.setHtmlUrl(fixture.repository().getHtmlUrl() + "/issues/8");
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        long issueId = issues.saveAndFlush(issue).getId();
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(9);
        pull.setTitle("A titled pull request");
        pull.setBody("Description");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/9");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        long pullId = pulls.saveAndFlush(pull).getId();
        ItemRef holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var titledWork = Map.of(
                writer.upsert(new ItemRegistration(
                        holder.workspaceId(), ScmItemEvents.ISSUE, issueId, holder.itemId(), null)),
                "A titled issue",
                writer.upsert(new ItemRegistration(
                        holder.workspaceId(), ScmItemEvents.PULL_REQUEST, pullId, holder.itemId(), null)),
                "A titled pull request");
        for (var work : titledWork.entrySet()) {
            ItemRef ref = work.getKey();
            long source = Objects.requireNonNull(ref.sourceId());
            var titled = new ProductItemQuery.CurrentWorkVersion.Known(
                    sources.pin(ref, null).version().value());
            assertThat(productItems.currentWorkVersion(ref.workspaceId(), ref.itemId()))
                    .isEqualTo(titled);
            for (String missing : List.of("NULL", "'   '")) {
                jdbc.update("UPDATE issue SET title=" + missing + " WHERE id=?", source);
                assertThatThrownBy(() -> sources.currentVersion(ref))
                        .isInstanceOfSatisfying(
                                ItemCaptureException.class,
                                failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
                assertThat(productItems.currentWorkVersion(ref.workspaceId(), ref.itemId()))
                        .isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
            }
            jdbc.update("UPDATE issue SET title=? WHERE id=?", work.getValue(), source);
            assertThat(productItems.currentWorkVersion(ref.workspaceId(), ref.itemId()))
                    .isEqualTo(titled);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepTheItemWithoutAnEventUntilItsProviderVersionCanBeProved(IdentityProviderType type) {
        var fixture = fixture(type);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(7);
        pull.setTitle("A pull request");
        pull.setBody("Description");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/7");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        pull = pulls.saveAndFlush(pull);
        var context = EventContext.forSync(fixture.workspace().getId(), null, type, Instant.now());
        long pullId = pull.getId();
        transactions.executeWithoutResult(status -> {
            var current = pulls.findById(pullId).orElseThrow();
            publisher.publishEvent(
                    new ScmDomainEvent.PullRequestCreated(ScmEventPayload.PullRequestData.from(current), context));
        });
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.PULL_REQUEST, pull.getId(), holder.itemId(), null));
        assertThat(jdbc.queryForList(
                        "SELECT item_version FROM item_event WHERE workspace_id=? AND item_id=?",
                        String.class,
                        item.workspaceId(),
                        item.itemId()))
                .isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT (removed_at IS NULL)::text FROM item WHERE workspace_id=? AND id=?",
                        String.class,
                        item.workspaceId(),
                        item.itemId()))
                .containsExactly("true");
        pull.setHeadRefOid("a".repeat(40));
        pulls.saveAndFlush(pull);
        var captured = sources.pin(item, null).version();
        var event = Objects.requireNonNull(transactions.execute(status -> {
            var current = pulls.findById(pullId).orElseThrow();
            var published =
                    new ScmDomainEvent.PullRequestCreated(ScmEventPayload.PullRequestData.from(current), context);
            publisher.publishEvent(published);
            return published;
        }));
        transactions.executeWithoutResult(status -> publisher.publishEvent(event));
        assertThat(jdbc.queryForList(
                        "SELECT name || '|' || item_version || '|' || found_by FROM item_event WHERE workspace_id=?"
                                + " AND item_id=?",
                        String.class,
                        item.workspaceId(),
                        item.itemId()))
                .containsExactly("scm.pull_request.opened|" + captured.value() + "|SYNC");
    }

    @Autowired
    private GitHubSubIssueSyncService subIssues;

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldChangeParentEvidenceAndRecordItsOccasionWhenATrackedChildClosesAndReopens(IdentityProviderType type) {
        var fixture = fixture(type);
        var unrelated = fixture(type);
        var parent = progressIssue(fixture, 20, null);
        var child = progressIssue(fixture, 21, parent);
        progressIssue(fixture, 22, parent);
        if (type == IdentityProviderType.GITHUB) {
            parent.setSubIssuesTotal(2);
            parent.setSubIssuesCompleted(0);
            parent.setSubIssuesPercentCompleted(0);
            issues.saveAndFlush(parent);
        }
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var originalSnapshot = sources.pin(item, null);
        var original = originalSnapshot.version();
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                fixture.workspace().getId(),
                null,
                DataSource.WEBHOOK,
                "closed",
                UUID.randomUUID().toString(),
                type,
                fixture.author().getId());

        changeChildState(child.getId(), Issue.State.CLOSED, context, type);

        var closed = sources.pin(item, null).version();
        assertThat(closed).isNotEqualTo(original);
        assertThatThrownBy(() -> sources.pin(item, original))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        assertThatThrownBy(() -> sources.capture(
                        originalSnapshot,
                        new CaptureRequest(
                                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(IssueContentSource.CORE), "2.0.0", 3)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        assertThat(jdbc.queryForList(
                        "SELECT item_version || '|' || found_by FROM item_event WHERE workspace_id=? AND item_id=? AND name=?",
                        String.class,
                        item.workspaceId(),
                        item.itemId(),
                        ScmItemEvents.ISSUE_UPDATED.value()))
                .containsExactly(closed.value() + "|WEBHOOK");
        assertThat(jdbc.queryForList(
                        "SELECT id FROM item_event WHERE workspace_id=?",
                        UUID.class,
                        unrelated.workspace().getId()))
                .isEmpty();

        changeChildState(child.getId(), Issue.State.OPEN, context, type);

        var reopened = sources.pin(item, null).version();
        assertThat(reopened).isNotEqualTo(closed);
        if (type == IdentityProviderType.GITLAB) assertThat(reopened).isEqualTo(original);
        assertThatThrownBy(() -> sources.pin(item, closed))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        assertThat(jdbc.queryForList(
                        "SELECT item_version FROM item_event WHERE workspace_id=? AND item_id=? AND name=?",
                        String.class,
                        item.workspaceId(),
                        item.itemId(),
                        ScmItemEvents.ISSUE_UPDATED.value()))
                .containsExactlyInAnyOrder(reopened.value(), closed.value());
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldVersionAuthoritativeProgressWithoutTreatingAnEmptyMirrorAsACompleteInventory(IdentityProviderType type) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, 30, null);
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var unknown = sources.pin(item, null).version();
        parent.setLastSyncAt(Instant.now());
        issues.saveAndFlush(parent);
        assertThat(sources.pin(item, null).version()).isEqualTo(unknown);
        parent.setSubIssuesTotal(2);
        parent.setSubIssuesCompleted(0);
        issues.saveAndFlush(parent);
        var before = sources.pin(item, null).version();
        assertThat(before).isNotEqualTo(unknown);
        parent.setSubIssuesCompleted(1);
        issues.saveAndFlush(parent);
        assertThat(sources.pin(item, null).version()).isNotEqualTo(before);
        assertThatThrownBy(() -> sources.pin(item, before)).isInstanceOf(ItemCaptureException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRetireCurrentParentResultsThroughTheNativeChildFlow(IdentityProviderType type) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, 40, null);
        var child = progressIssue(fixture, 41, parent);
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var scenario = parentScenario(fixture, item);
        var other = resultData.scenario();
        var untouched =
                resultData.result(other, ResultOutcome.MET, Instant.now().minusSeconds(30));
        var prior =
                resultData.result(scenario, ResultOutcome.NOT_MET, Instant.now().minusSeconds(30));
        var closedContext = progressContext(fixture, type);
        changeChildState(child.getId(), Issue.State.CLOSED, closedContext, type);
        assertThat(results.findById(prior.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(results.findById(untouched.getId()).orElseThrow().getEndedAt())
                .isNull();
        var closedResult =
                resultData.result(scenario, ResultOutcome.MET, Instant.now().minusSeconds(1));
        changeChildState(child.getId(), Issue.State.OPEN, progressContext(fixture, type), type);
        assertThat(results.findById(closedResult.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepPartialChildInventoryAsALowerBoundAfterAChildCloses(IdentityProviderType type) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, 50, null);
        var child = progressIssue(fixture, 51, parent);
        if (type == IdentityProviderType.GITHUB) {
            parent.setSubIssuesTotal(3);
            parent.setSubIssuesCompleted(0);
            issues.saveAndFlush(parent);
        }
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        changeChildState(child.getId(), Issue.State.CLOSED, progressContext(fixture, type), type);
        var snapshot = sources.pin(item, null);
        var captured = sources.capture(
                snapshot,
                new CaptureRequest(
                        UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(IssueContentSource.CORE), "2.0.0", 3));
        var metadata = new JsonMapper().readTree(captured.files().get("context/metadata.json"));
        assertThat(metadata.get("sub_issues_source").asString()).isEqualTo("children");
        assertThat(metadata.get("sub_issues_total").asLong()).isEqualTo(1);
        assertThat(metadata.get("sub_issues_completed").asLong()).isEqualTo(1);
        if (type == IdentityProviderType.GITHUB) {
            var stored = issues.findById(parent.getId()).orElseThrow();
            assertThat(stored.getSubIssuesTotal()).isEqualTo(3);
            assertThat(stored.getSubIssuesCompleted()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRetireAReopenedParentResultWhenTheSameChildClosesAgain(IdentityProviderType type) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, 60, null);
        var child = progressIssue(fixture, 61, parent);
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var scenario = parentScenario(fixture, item);
        changeChildState(child.getId(), Issue.State.CLOSED, progressContext(fixture, type), type);
        var closedVersion = sources.pin(item, null).version();
        changeChildState(child.getId(), Issue.State.OPEN, progressContext(fixture, type), type);
        var reopenedVersion = sources.pin(item, null).version();
        assertThat(reopenedVersion).isNotEqualTo(closedVersion);
        var at = Instant.now();
        var reopened = resultData.result(scenario, ResultOutcome.MET, at, at, reopenedVersion.value());
        assertThat(results.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .containsExactly(reopened.getId());

        changeChildState(child.getId(), Issue.State.CLOSED, progressContext(fixture, type), type);

        assertThat(sources.pin(item, null).version()).isEqualTo(closedVersion);
        assertThat(results.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .isEmpty();
        assertThat(results.findById(reopened.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
    }

    @Autowired
    private ResultSourceAuthorization authorization;

    @Autowired
    private ResultVisibilityPolicy visibility;

    @Autowired
    private IdentityRepository identityRows;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private PracticeStandingService standings;

    @Test
    void shouldReadAStandingThroughTheRealSourceAtAFixedStatementCountWhenMoreResultsArriveOnTheWork() {
        var fixture = fixture(IdentityProviderType.GITHUB);
        long workspaceId = fixture.workspace().getId();
        var issue = progressIssue(fixture, 150, null);
        var holder = writer.upsert(new ItemRegistration(
                workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
        var item = writer.upsert(
                new ItemRegistration(workspaceId, ScmItemEvents.ISSUE, issue.getId(), holder.itemId(), null));
        var scenario = parentScenario(fixture, item);
        long developer = Objects.requireNonNull(scenario.account().getId());
        var start = Instant.now().minus(1, ChronoUnit.HOURS);
        List<Result> recorded = new ArrayList<>();
        var json = new JsonMapper();
        var completeCustody = json.createObjectNode()
                .set("custody", json.valueToTree(new HistoryCustody(true, false, List.of(), List.of(), List.of())))
                .toString();
        Runnable more = () -> {
            for (int index = 0; index < 3; index++) {
                var at = start.plusSeconds(60L * recorded.size());
                if (!recorded.isEmpty()) resultData.end(recorded.getLast(), at);
                var result = resultData.result(
                        scenario, recorded.size() % 3 == 0 ? ResultOutcome.NOT_MET : ResultOutcome.MET, at);
                recorded.add(result);
                // Only capture writes custody receipts; the guard that refuses other writers stays on.
                transactions.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL session_replication_role=replica");
                    jdbc.update(
                            "UPDATE review_item SET detail=detail||CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                            completeCustody,
                            workspaceId,
                            result.getId());
                });
            }
        };
        Supplier<Long> cost = () -> {
            var read = reads.measure(() -> standings
                    .getStandingSnapshots(workspaceId, developer, List.of(Instant.now()))
                    .getFirst());
            assertThat(read.value().practices().get(scenario.practice().getSlug()))
                    .satisfies(standing -> assertThat(standing.dto().standing())
                            .isNotIn(PracticeStanding.NOT_OBSERVED, PracticeStanding.NO_OPPORTUNITY));
            return read.cost().statements();
        };

        more.run();
        long few = cost.get();
        more.run();
        more.run();
        more.run();

        assertThat(cost.get()).isEqualTo(few);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldCountAHumanStandingOverANoAiAuthorThroughTheRealSourceWhileEveryAiReadStaysRefused(
            IdentityProviderType type) {
        var fixture = fixture(type);
        long workspaceId = fixture.workspace().getId();
        var issue = progressIssue(fixture, 130, null);
        var holder = writer.upsert(new ItemRegistration(
                workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
        var item = writer.upsert(
                new ItemRegistration(workspaceId, ScmItemEvents.ISSUE, issue.getId(), holder.itemId(), null));
        var scenario = parentScenario(fixture, item);
        var recorded =
                resultData.result(scenario, ResultOutcome.NOT_MET, Instant.now().minusSeconds(30));
        // The issue's author is another member, who chose No AI; the developer did not.
        var author = resultData.member(workspaceId);
        var identity = new Identity();
        identity.setAccount(author);
        identity.setProviderId(Objects.requireNonNull(fixture.provider().getId()));
        identity.setProviderUserKey(String.valueOf(fixture.author().getNativeId()));
        identityRows.saveAndFlush(identity);
        jdbc.update("UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?", author.getId());
        // Only migration writes historical rows; this one is carried over as an earlier review.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE result SET evidence_version=0 WHERE workspace_id=? AND id=?",
                    workspaceId,
                    recorded.getId());
        });
        var row = results.findByIdAndWorkspaceId(recorded.getId(), workspaceId).orElseThrow();

        for (var purpose : SourceUsePurpose.values()) {
            assertThat(sourceAccess.permits(item, purpose)).isFalse();
            assertThat(authorization.permitsHistory(workspaceId, row.getId(), purpose))
                    .isFalse();
        }
        assertThat(sourceAccess.permits(
                        item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .isTrue();
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(row), Instant.now()))
                .containsExactly(row.getId());
        assertThat(visibility.permitsAt(
                        workspaceId, List.of(row), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, Instant.now()))
                .isEmpty();

        // The work is gone from the source: the human standing loses it too.
        jdbc.update("UPDATE issue SET deleted_at=now() WHERE id=?", issue.getId());
        assertThat(sourceAccess.permits(
                        item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .isFalse();
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(row), Instant.now()))
                .isEmpty();
    }

    private ReviewIntegrationData.Scenario parentScenario(Fixture fixture, ItemRef item) {
        var account = resultData.member(item.workspaceId());
        var practice = practiceVersions.create(new PracticeVersionService.NewPractice(
                fixture.workspace(),
                "progress-" + UUID.randomUUID(),
                ScmItemEvents.ISSUE,
                DefinitionTestFixtures.definition(
                        ScmItemEvents.ISSUE, "Track parent progress", "Check tracked progress.", null),
                null,
                0,
                account.getId()));
        var scenario = new ReviewIntegrationData.Scenario(
                fixture.workspace(),
                account,
                practice,
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        return scenario;
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepUnavailableSourceVisibilityDistinctFromAnItemTombstone(IdentityProviderType type) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, 70, null);
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var canonical = sources.pin(item, null).version();
        var scenario = parentScenario(fixture, item);
        var at = Instant.now();
        var current = resultData.result(scenario, ResultOutcome.MET, at, at, canonical.value());
        var monitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        item.workspaceId(), fixture.repository().getNameWithOwner())
                .orElseThrow();
        monitor.setNativeId(fixture.repository().getNativeId() == 13L ? 14L : 13L);
        monitors.saveAndFlush(monitor);
        assertThat(sources.current(item)).isEmpty();

        transactions.executeWithoutResult(
                ignored -> publisher.publishEvent(new ItemSourceObserved(item.workspaceId(), item.itemId())));

        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
        assertThat(itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow()
                        .getRemovedAt())
                .isNull();
        monitor.setNativeId(fixture.repository().getNativeId());
        monitors.saveAndFlush(monitor);
    }

    @Test
    void shouldRefuseSourceAndDeliveryOfAStoppedGitLabProjectAndKeepItsHistoryThroughRemove() {
        var fixture = fixture(IdentityProviderType.GITLAB);
        long workspaceId = fixture.workspace().getId();
        var issue = progressIssue(fixture, 72, null);
        var holder = writer.upsert(new ItemRegistration(
                workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
        var item = writer.upsert(
                new ItemRegistration(workspaceId, ScmItemEvents.ISSUE, issue.getId(), holder.itemId(), null));
        var canonical = sources.pin(item, null).version();
        var scenario = parentScenario(fixture, item);
        var at = Instant.now();
        var result = resultData.result(scenario, ResultOutcome.MET, at, at, canonical.value());
        var pending = feedbackRows.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .reviewId(result.getReviewId())
                .itemId(item.itemId())
                .developerId(Objects.requireNonNull(scenario.account().getId()))
                .destination(FeedbackDestination.ON_WORK)
                .state(FeedbackState.READY)
                .body("Track the remaining child work.")
                .createdAt(at)
                .build());
        targets.resolve(pending);

        monitorService.removeRepositoryFromMonitor(
                fixture.workspace().getWorkspaceSlug(), fixture.repository().getNameWithOwner());

        assertThat(monitored.repositories(workspaceId)).doesNotContainKey(holder.itemId());
        assertThatThrownBy(() -> sources.pin(item, null))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ItemCaptureCode.ITEM_NOT_VISIBLE));
        assertThatThrownBy(() -> targets.resolve(pending))
                .isInstanceOfSatisfying(
                        FeedbackTargetResolver.TargetUnavailable.class,
                        refused ->
                                assertThat(refused.reason()).isEqualTo(FeedbackStopReason.APPROVAL_NO_LONGER_ELIGIBLE));
        assertThat(itemRows.findByIdAndWorkspaceId(holder.itemId(), workspaceId))
                .hasValueSatisfying(kept -> assertThat(kept.getRemovedAt()).isNull());

        var stopped = stoppedProjects.getStoppedProjects(workspaceId);
        assertThat(stopped)
                .singleElement()
                .satisfies(
                        project -> assertThat(project.repositoryItemId()).isEqualTo(String.valueOf(holder.itemId())));
        stoppedProjects.removeStoppedProject(
                workspaceId,
                holder.itemId(),
                stopped.getFirst().stopReceipt(),
                Objects.requireNonNull(scenario.account().getId()));

        assertThat(itemRows.findByIdAndWorkspaceId(holder.itemId(), workspaceId))
                .hasValueSatisfying(
                        removed -> assertThat(removed.getRemovedAt()).isNotNull());
        assertThat(repositories.findById(fixture.repository().getId())).isEmpty();
        assertThat(results.findById(result.getId()))
                .hasValueSatisfying(kept -> assertThat(kept.getEndedAt()).isNull());
        assertThat(feedbackRows.findById(pending.getId()))
                .hasValueSatisfying(kept -> assertThat(kept.getState()).isEqualTo(FeedbackState.READY));
        assertThat(itemRows.findByIdAndWorkspaceId(item.itemId(), workspaceId)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRedeliverACoalescedSourceObservationAgainstCurrentFacts(IdentityProviderType type) {
        var setup = progressFixture(type, 80);
        var item = setup.item();
        changeChildState(setup.child().getId(), Issue.State.CLOSED, progressContext(setup.fixture(), type), type);
        var closed = sources.pin(item, null).version();
        changeChildState(setup.child().getId(), Issue.State.OPEN, progressContext(setup.fixture(), type), type);
        var reopened = sources.pin(item, null).version();
        var at = Instant.now();
        var current = resultData.result(setup.scenario(), ResultOutcome.MET, at, at, reopened.value());
        var occasions = jdbc.queryForList(
                "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                UUID.class,
                item.workspaceId(),
                item.itemId());
        transactions.executeWithoutResult(
                ignored -> doThrow(new IllegalStateException("Planned source observation failure"))
                        .doCallRealMethod()
                        .when(currentness)
                        .reconcile(item.workspaceId(), item.itemId()));

        changeChildState(setup.child().getId(), Issue.State.CLOSED, progressContext(setup.fixture(), type), type);

        assertThat(sourcePublications(item))
                .singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
        // The old failed publication must reread the now-reopened source, not replay its former closed version.
        changeChildState(setup.child().getId(), Issue.State.OPEN, progressContext(setup.fixture(), type), type);
        failedPublications.resubmit(ResubmissionOptions.defaults()
                .withMinAge(Duration.ZERO)
                .withMaxInFlight(1)
                .withFilter(publication -> publication.getEvent() instanceof ItemSourceObserved observed
                        && observed.workspaceId() == item.workspaceId()
                        && observed.itemId() == item.itemId()));
        assertThat(sourcePublications(item)).isEmpty();
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();

        changeChildState(setup.child().getId(), Issue.State.CLOSED, progressContext(setup.fixture(), type), type);
        var endedAt = results.findById(current.getId()).orElseThrow().getEndedAt();
        assertThat(endedAt).isNotNull();
        at = Instant.now();
        var matching = resultData.result(setup.scenario(), ResultOutcome.MET, at, at, closed.value());
        transactions.executeWithoutResult(
                ignored -> publisher.publishEvent(new ItemSourceObserved(item.workspaceId(), item.itemId())));
        assertThat(results.findById(matching.getId()).orElseThrow().getEndedAt())
                .isNull();
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isEqualTo(endedAt);
        assertThat(jdbc.queryForList(
                        "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                        UUID.class,
                        item.workspaceId(),
                        item.itemId()))
                .containsExactlyInAnyOrderElementsOf(occasions);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRollBackTheSourceMutationAndItsCoalescedPublicationTogether(IdentityProviderType type) {
        var setup = progressFixture(type, 90);
        var item = setup.item();
        changeChildState(setup.child().getId(), Issue.State.CLOSED, progressContext(setup.fixture(), type), type);
        changeChildState(setup.child().getId(), Issue.State.OPEN, progressContext(setup.fixture(), type), type);
        var reopened = sources.pin(item, null).version();
        var at = Instant.now();
        var current = resultData.result(setup.scenario(), ResultOutcome.MET, at, at, reopened.value());

        transactions.executeWithoutResult(status -> {
            writer.lock(item.workspaceId(), item.itemId());
            var child = issues.findById(setup.child().getId()).orElseThrow();
            child.setState(Issue.State.CLOSED);
            issues.saveAndFlush(child);
            var version = sources.pin(item, null).version();
            writer.record(new ItemEventInput(
                    item.workspaceId(),
                    item.itemId(),
                    ScmItemEvents.ISSUE_UPDATED,
                    version.value(),
                    Instant.now(),
                    FoundBy.SYNC,
                    null,
                    null));
            assertThat(sourcePublications(item)).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(sourcePublications(item)).isEmpty();
        assertThat(issues.findById(setup.child().getId()).orElseThrow().getState())
                .isEqualTo(Issue.State.OPEN);
        assertThat(sources.pin(item, null).version()).isEqualTo(reopened);
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldReconcileAnExpiredOccasionWhenItsDurablePublicationIsRetried(IdentityProviderType type) {
        var setup = progressFixture(type, 100);
        var item = setup.item();
        var version = sources.pin(item, null).version();
        var at = Instant.now();
        var current = resultData.result(setup.scenario(), ResultOutcome.MET, at, at, version.value());
        transactions.executeWithoutResult(
                ignored -> doThrow(new IllegalStateException("Planned recorded occasion failure"))
                        .doCallRealMethod()
                        .when(currentness)
                        .reconcile(item.workspaceId(), item.itemId()));

        changeChildState(setup.child().getId(), Issue.State.CLOSED, progressContext(setup.fixture(), type), type);

        var occasionId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                UUID.class,
                item.workspaceId(),
                item.itemId()));
        assertThat(occasionId).isNotNull();
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM event_publication WHERE event_type=?"
                                + " AND serialized_event::jsonb->>'eventId'=?",
                        String.class,
                        ItemEventRecorded.class.getName(),
                        occasionId.toString()))
                .isEqualTo("FAILED");
        // Apply the reaper's terminal transition only to this fixture's delayed occasion.
        transactions.executeWithoutResult(ignored -> assertThat(events.decide(
                        item.workspaceId(),
                        occasionId,
                        new ItemEventRepository.Decision(
                                ItemEventState.RECORDED,
                                ItemEventState.EXPIRED,
                                ReviewRequestReason.PENDING_DEADLINE_EXCEEDED,
                                Instant.now(),
                                null)))
                .isEqualTo(1));

        failedPublications.resubmit(ResubmissionOptions.defaults()
                .withMinAge(Duration.ZERO)
                .withMaxInFlight(1)
                .withFilter(publication -> publication.getEvent() instanceof ItemEventRecorded recorded
                        && recorded.workspaceId() == item.workspaceId()
                        && recorded.eventId().equals(occasionId)));

        assertThat(results.findById(current.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(events.findByIdAndWorkspaceId(occasionId, item.workspaceId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(ItemEventState.EXPIRED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review WHERE workspace_id=? AND event_id=?",
                        Long.class,
                        item.workspaceId(),
                        occasionId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE event_type=?"
                                + " AND serialized_event::jsonb->>'eventId'=?",
                        Long.class,
                        ItemEventRecorded.class.getName(),
                        occasionId.toString()))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepChangedLabelEvidenceHistoricalWithoutInventingANewOccasion(IdentityProviderType type) {
        var setup = progressFixture(type, 110);
        var item = setup.item();
        var before = sources.pin(item, null).version();
        var at = Instant.now().minusSeconds(10);
        var current = resultData.result(setup.scenario(), ResultOutcome.MET, at, at, before.value());
        transactions.executeWithoutResult(ignored -> {
            var parent =
                    issues.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
            var label = new Label();
            label.setProvider(setup.fixture().provider());
            label.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
            label.setRepository(setup.fixture().repository());
            label.setName("triaged");
            label.setColor("abcdef");
            label = labels.saveAndFlush(label);
            parent.getLabels().add(label);
            issues.saveAndFlush(parent);
            publisher.publishEvent(new ScmDomainEvent.IssueLabeled(
                    ScmEventPayload.IssueData.from(parent),
                    ScmEventPayload.LabelData.from(label),
                    progressContext(setup.fixture(), type)));
        });
        assertThat(sources.pin(item, null).version()).isNotEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM item_event WHERE workspace_id=? AND item_id=?",
                        Long.class,
                        item.workspaceId(),
                        item.itemId()))
                .isZero();

        var reason = transactions.execute(
                ignored -> currentness.prepareClaim(item.workspaceId(), item.itemId(), before.value(), Instant.now()));

        assertThat(reason).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(results.findById(current.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldDatePullRequestWordsToTheirCaptureWithoutClaimingAdmission(IdentityProviderType type) {
        var fixture = fixture(type);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(21);
        pull.setTitle("A bounded change");
        pull.setBody("The description before the edit.");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/21");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("c".repeat(40));
        pull.setBaseRefOid("d".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        long pullId = pulls.saveAndFlush(pull).getId();
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(
                new ItemRegistration(holder.workspaceId(), ScmItemEvents.PULL_REQUEST, pullId, holder.itemId(), null));
        var pinned = sources.pin(item, null);
        var request = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(PullRequestContentSource.CORE), "2.0.0", 3);

        var before = Instant.now();
        var captured = sources.capture(pinned, request);
        var after = Instant.now();
        var staged = sources.record(pinned, captured.files());

        // The pin retains only a version digest, and capture reads the words from the verified mirror.
        var basis = staged.path("basis");
        assertThat(basis.propertyNames())
                .contains("other_fields", "captured_at")
                .isSubsetOf("other_fields", "captured_at", "mirror_synced_at");
        assertThat(basis.path("other_fields").asString()).isEqualTo("CAPTURE");
        assertThat(Instant.parse(basis.path("captured_at").asString())).isBetween(before, after);
        assertThat(staged.path("body").asString()).isEqualTo("The description before the edit.");
        assertThat(staged.path("commit_sha").asString()).isEqualTo("c".repeat(40));

        var edited = pulls.findById(pullId).orElseThrow();
        edited.setBody("The description after the edit.");
        pulls.saveAndFlush(edited);
        assertThatThrownBy(() -> sources.capture(pinned, request))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
    }

    private List<Map<String, @Nullable Object>> sourcePublications(ItemRef item) {
        return jdbc.queryForList(
                "SELECT id,status FROM event_publication WHERE event_type=?"
                        + " AND (serialized_event::jsonb->>'workspaceId')::bigint=? AND (serialized_event::jsonb->>'itemId')::bigint=?",
                ItemSourceObserved.class.getName(),
                item.workspaceId(),
                item.itemId());
    }

    private ProgressFixture progressFixture(IdentityProviderType type, int number) {
        var fixture = fixture(type);
        var parent = progressIssue(fixture, number, null);
        var child = progressIssue(fixture, number + 1, parent);
        var holder = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var item = writer.upsert(new ItemRegistration(
                fixture.workspace().getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        return new ProgressFixture(fixture, child, item, parentScenario(fixture, item));
    }

    private record ProgressFixture(
            Fixture fixture, Issue child, ItemRef item, ReviewIntegrationData.Scenario scenario) {}

    private EventContext progressContext(Fixture fixture, IdentityProviderType type) {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                fixture.workspace().getId(),
                null,
                DataSource.WEBHOOK,
                "updated",
                UUID.randomUUID().toString(),
                type,
                fixture.author().getId());
    }

    private void changeChildState(long childId, Issue.State state, EventContext context, IdentityProviderType type) {
        transactions.executeWithoutResult(ignored -> {
            var child = issues.findById(childId).orElseThrow();
            child.setState(state);
            issues.saveAndFlush(child);
            if (type == IdentityProviderType.GITHUB) subIssues.invalidateParentCompletionOf(childId);
            publisher.publishEvent(
                    state == Issue.State.CLOSED
                            ? new ScmDomainEvent.IssueClosed(
                                    ScmEventPayload.IssueData.from(child), "completed", context)
                            : new ScmDomainEvent.IssueReopened(ScmEventPayload.IssueData.from(child), context));
        });
    }

    private Issue progressIssue(Fixture fixture, int number, @Nullable Issue parent) {
        var issue = new Issue();
        issue.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        issue.setProvider(fixture.provider());
        issue.setRepository(fixture.repository());
        issue.setNumber(number);
        issue.setTitle("Tracked work " + number);
        issue.setBody("The requested work.");
        issue.setState(Issue.State.OPEN);
        issue.setAuthor(fixture.author());
        issue.setHtmlUrl(fixture.repository().getHtmlUrl() + "/issues/" + number);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setParentIssue(parent);
        return issues.saveAndFlush(issue);
    }

    @Autowired
    private ProductReferenceQuery productReferences;

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldNameRegisteredWorkByItsProvidersLabelOnlyInsideItsOwnWorkspace(IdentityProviderType type) {
        var fixture = fixture(type);
        var elsewhere = fixture(type);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(21);
        pull.setTitle("Explain the change");
        pull.setBody("Why this change is needed.");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/21");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        long pullId = pulls.saveAndFlush(pull).getId();
        long workspaceId = fixture.workspace().getId();
        var holder = writer.upsert(new ItemRegistration(
                workspaceId, ScmItemEvents.REPOSITORY, fixture.repository().getId(), null, null));
        var item = writer.upsert(
                new ItemRegistration(workspaceId, ScmItemEvents.PULL_REQUEST, pullId, holder.itemId(), null));

        var references = productReferences.items(workspaceId, List.of(item.itemId(), holder.itemId()));
        var named = Objects.requireNonNull(references.get(item.itemId()));
        var repositoryReference = Objects.requireNonNull(references.get(holder.itemId()));
        assertThat(repositoryReference.kind()).isEqualTo("scm.repository");
        assertThat(repositoryReference.label()).isEqualTo(fixture.repository().getNameWithOwner());
        assertThat(repositoryReference.url()).isEqualTo(fixture.repository().getHtmlUrl());
        // GitHub numbers pull requests with #, GitLab merge requests with !.
        assertThat(named.label()).isEqualTo(type == IdentityProviderType.GITHUB ? "#21" : "!21");
        assertThat(named.title()).isEqualTo("Explain the change");
        assertThat(named.url()).isEqualTo(pull.getHtmlUrl());
        assertThat(named.container()).isEqualTo(fixture.repository().getNameWithOwner());
        assertThat(named.provider()).isEqualTo(IntegrationKind.valueOf(type.name()));
        assertThat(named.removedAt()).isNull();

        // Another workspace learns nothing about this one's work, not even that it exists.
        assertThat(productReferences.items(elsewhere.workspace().getId(), List.of(item.itemId(), holder.itemId())))
                .isEmpty();

        // Removed work keeps its own name while its address is withdrawn.
        jdbc.update(
                "UPDATE item SET removed_at=? WHERE workspace_id=? AND id=?",
                Timestamp.from(Instant.now()),
                workspaceId,
                item.itemId());
        var removed = Objects.requireNonNull(
                productReferences.items(workspaceId, List.of(item.itemId())).get(item.itemId()));
        assertThat(removed.label()).isEqualTo(named.label());
        assertThat(removed.url()).isNull();
        assertThat(removed.removedAt()).isNotNull();

        long unavailableId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,parent_id,created_at)"
                        + " VALUES (?,'scm.pull_request',?,?,?) RETURNING id",
                Long.class,
                workspaceId,
                Long.MAX_VALUE,
                holder.itemId(),
                Timestamp.from(Instant.now())));
        var unavailable = Objects.requireNonNull(
                productReferences.items(workspaceId, List.of(unavailableId)).get(unavailableId));
        assertThat(unavailable.label()).isEqualTo("Pull or merge request");
        assertThat(unavailable.provider()).isNull();
        assertThat(unavailable.title()).isNull();
        assertThat(unavailable.url()).isNull();
    }

    private Fixture fixture(IdentityProviderType type) {
        String slug = "scm-source-" + UUID.randomUUID();
        String url = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test";
        var provider = providers
                .findByTypeAndServerUrl(type, url)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, url)));
        Workspace workspace = type == IdentityProviderType.GITHUB
                ? WorkspaceTestFixtures.persistInstallationWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.installationWorkspace(
                                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug),
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                : WorkspaceTestFixtures.persistGitLabWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                        url);
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("source");
        repository.setNameWithOwner(slug + "/source");
        repository.setHtmlUrl(url + "/" + slug + "/source");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(Instant.now());
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner()));
        var author = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug + "-author", provider));
        return new Fixture(workspace, provider, repository, author);
    }

    private record Fixture(Workspace workspace, IdentityProvider provider, Repository repository, User author) {}

    private Workspace connectedWorkspace(IdentityProviderType type, String url, String slug) {
        return type == IdentityProviderType.GITHUB
                ? WorkspaceTestFixtures.persistInstallationWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.installationWorkspace(
                                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug),
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                : WorkspaceTestFixtures.persistGitLabWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                        url);
    }

    private void assertPublishesDeclaredState(ItemSnapshot snapshot) {
        var dimensions = descriptors
                .descriptorFor(snapshot.item().kind())
                .orElseThrow()
                .reviewCapabilities()
                .reviewOnStateDimensions();
        assertThat(dimensions)
                .isNotEmpty()
                .allSatisfy(dimension -> assertThat(dimension.values())
                        .extracting(ReviewStateValue::value)
                        .contains(snapshot.state().get(dimension.key())));
    }
}
