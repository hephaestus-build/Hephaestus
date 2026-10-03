package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A closed issue's discussion is part of the evidence its review reads: written, edited and removed comments
 * move its snapshot and occasion an update, Hephaestus's own feedback does not, a synced comment retires claims
 * without occasioning a live review, and a reopened issue is no longer that closed record.
 */
@Import(DeferredIssueEventIntegrationTest.Configuration.class)
class ClosedIssueDiscussionIntegrationTest extends BaseIntegrationTest {

    private static final String OWN_FEEDBACK = "<!-- hephaestus:practice-review:00000000 -->\nRecord the export.";

    @Autowired
    private IssueRepository issues;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private IssueEvidenceRevision revisions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeferredIssueEventIntegrationTest.Fixture fixture;

    private Workspace workspace;
    private IdentityProvider provider;
    private Repository repository;
    private User commenter;
    private long issueId;
    private Instant closedAt;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("closed-discussion"));
        provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        User author = users.save(TestUserFactory.createUser(410L, "issue-author", provider));
        commenter = users.save(TestUserFactory.createUser(411L, "closer", provider));

        Repository repo = new Repository();
        repo.setNativeId(4101L);
        repo.setProvider(provider);
        repo.setName("project");
        repo.setNameWithOwner("org/project");
        repo.setHtmlUrl("https://github.com/org/project");
        repo.setDefaultBranch("main");
        repository = repositories.save(repo);

        closedAt = Instant.parse("2026-10-01T10:00:00Z");
        Issue issue = new Issue();
        issue.setNativeId(41001L);
        issue.setProvider(provider);
        issue.setNumber(7);
        issue.setTitle("Export the reading list");
        issue.setBody("- [ ] CSV export\n- [x] JSON export");
        issue.setState(Issue.State.CLOSED);
        issue.setStateReason(Issue.StateReason.COMPLETED);
        issue.setClosedAt(closedAt);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/7");
        issue.setRepository(repository);
        issue.setAuthor(author);
        issue.setCreatedAt(closedAt.minusSeconds(3600));
        issue.setUpdatedAt(closedAt);
        issueId = issues.save(issue).getId();

        when(fixture.resolver().resolveAllForRepository("org/project")).thenReturn(List.of(workspace));
        when(fixture.gate().evaluateIssue(any(Issue.class), any(Workspace.class), any(SignalName.class), any()))
                .thenReturn(new GateDecision.Skip("not under test"));
        transactions.executeWithoutResult(status -> events.publishEvent(new ScmDomainEvent.IssueClosed(
                ScmEventPayload.IssueData.from(issues.findById(issueId).orElseThrow()), "completed", webhook())));
    }

    @Test
    void shouldTreatAWrittenEditedOrRemovedCommentOnAClosedIssueAsNewEvidence() {
        UUID observation = recordObservation();
        String closedRecord = storedDigest();
        UUID closedSnapshot = storedSnapshot();

        long written = comment(500L, "CSV export moved to #12.", webhook());

        assertThat(storedDigest()).isEqualTo(currentRevision()).isNotEqualTo(closedRecord);
        assertThat(isSuperseded(observation)).isTrue();
        assertThat(deferredUpdateRevisions()).contains(currentRevision());
        UUID afterComment = storedSnapshot();

        edit(written, "CSV export dropped: the backend cannot stream it.");

        assertThat(storedDigest()).isEqualTo(currentRevision());
        assertThat(deferredUpdateRevisions()).contains(currentRevision());
        UUID afterEdit = storedSnapshot();

        delete(written);

        assertThat(storedDigest()).isEqualTo(closedRecord);
        assertThat(List.of(closedSnapshot, afterComment, afterEdit, storedSnapshot()))
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldIgnoreItsOwnFeedbackButCountAHumanCommentThatTurnsIntoIt() {
        String closedRecord = storedDigest();
        UUID closedSnapshot = storedSnapshot();
        int occasions = updates().size();

        comment(600L, OWN_FEEDBACK, webhook());

        assertThat(storedSnapshot()).isEqualTo(closedSnapshot);
        assertThat(updates()).hasSize(occasions);

        long human = comment(601L, "Both exports are done.", webhook());
        assertThat(storedSnapshot()).isNotEqualTo(closedSnapshot);

        edit(human, OWN_FEEDBACK);

        assertThat(storedDigest()).isEqualTo(closedRecord);
        assertThat(deferredUpdateRevisions()).contains(closedRecord);
    }

    @Test
    void shouldRetireClaimsForASyncedCommentWithoutOccasioningALiveReview() {
        UUID observation = recordObservation();

        comment(700L, "Not doing the CSV export; out of scope for this release.", sync());

        assertThat(isSuperseded(observation)).isTrue();
        assertThat(storedDigest()).isEqualTo(currentRevision());
        assertThat(updates())
                .filteredOn(signal -> signal.key().revision().value().equals(currentRevision()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.getDiscoveredVia()).isEqualTo(DiscoveredVia.SYNC);
                    assertThat(signal.getState()).isNotEqualTo(SignalState.DEFERRED);
                });
    }

    @Test
    void shouldStopReadingTheClosedRecordOnReopenAndReadItAgainUnderASecondClose() {
        UUID observation = recordObservation();

        transactions.executeWithoutResult(status -> {
            Issue issue = issues.findById(issueId).orElseThrow();
            issue.setState(Issue.State.OPEN);
            issue.setStateReason(null);
            issue.setClosedAt(null);
            issues.save(issue);
            events.publishEvent(
                    new ScmDomainEvent.IssueUpdated(ScmEventPayload.IssueData.from(issue), Set.of("state"), webhook()));
        });
        assertThat(isSuperseded(observation)).isTrue();
        UUID reopened = storedSnapshot();

        comment(800L, "CSV export moved to #12.", webhook());
        assertThat(storedSnapshot()).isEqualTo(reopened);

        Instant secondClose = closedAt.plusSeconds(7200);
        transactions.executeWithoutResult(status -> {
            Issue issue = issues.findById(issueId).orElseThrow();
            issue.setState(Issue.State.CLOSED);
            issue.setStateReason(Issue.StateReason.COMPLETED);
            issue.setClosedAt(secondClose);
            issues.save(issue);
            events.publishEvent(
                    new ScmDomainEvent.IssueClosed(ScmEventPayload.IssueData.from(issue), "completed", webhook()));
        });
        assertThat(storedSnapshot()).isNotEqualTo(reopened);
        assertThat(storedDigest()).isEqualTo(currentRevision());
        assertThat(ScmSignals.issueClosedKey(workspace.getId(), issueId, secondClose))
                .isNotEqualTo(ScmSignals.issueClosedKey(workspace.getId(), issueId, closedAt));
    }

    @Test
    void shouldRecordTheCommittedBodyAndBothCommentsWhenAnotherWriterHeldTheIssue() throws Exception {
        CountDownLatch firstHoldsTheIssue = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondLoaded = new CountDownLatch(1);
        AtomicInteger secondBackend = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                Issue issue = issues.findById(issueId).orElseThrow();
                issue.setBody("- [x] CSV export\n- [x] JSON export");
                issues.save(issue);
                saveAndAnnounce(901L, "Ticked the CSV export.", webhook());
                firstHoldsTheIssue.countDown();
                await(releaseFirst);
            }));
            await(firstHoldsTheIssue);
            Future<?> second = threads.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                // Loaded before the wait, so this transaction's managed issue still has the old body.
                issues.findById(issueId).orElseThrow();
                secondBackend.set(
                        Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                secondLoaded.countDown();
                saveAndAnnounce(902L, "Confirmed both exports.", webhook());
            }));
            await(secondLoaded);
            assertThat(backendWaitsOnALock(secondBackend.get())).isTrue();
            releaseFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            threads.shutdownNow();
        }

        assertThat(comments.findRecentByIssueIdWithAuthor(issueId, org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(2);
        assertThat(storedDigest()).isEqualTo(currentRevision());
        assertThat(issues.findById(issueId).orElseThrow().getBody()).startsWith("- [x] CSV export");
    }

    @Test
    void shouldSerializeTwoCommentInsertsWithoutUpgradingTheirForeignKeyLocks() throws Exception {
        CountDownLatch inserted = new CountDownLatch(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> first = threads.submit(() -> insertThenAnnounce(903L, inserted));
            Future<?> second = threads.submit(() -> insertThenAnnounce(904L, inserted));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        assertThat(comments.findRecentByIssueIdWithAuthor(issueId, org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(2);
        assertThat(storedDigest()).isEqualTo(currentRevision());
    }

    private void insertThenAnnounce(long nativeId, CountDownLatch inserted) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            IssueComment saved = comments.saveAndFlush(newComment(nativeId, "Confirmed the export."));
            inserted.countDown();
            await(inserted);
            events.publishEvent(
                    new ScmDomainEvent.CommentCreated(ScmEventPayload.CommentData.from(saved), issueId, webhook()));
        });
    }

    private long comment(long nativeId, String body, EventContext context) {
        return Objects.requireNonNull(transactions.execute(status -> saveAndAnnounce(nativeId, body, context)));
    }

    private long saveAndAnnounce(long nativeId, String body, EventContext context) {
        IssueComment saved = comments.save(newComment(nativeId, body));
        events.publishEvent(
                new ScmDomainEvent.CommentCreated(ScmEventPayload.CommentData.from(saved), issueId, context));
        return saved.getId();
    }

    private IssueComment newComment(long nativeId, String body) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeId);
        comment.setProvider(provider);
        comment.setBody(body);
        comment.setHtmlUrl(repository.getHtmlUrl() + "/issues/7#issuecomment-" + nativeId);
        comment.setAuthorAssociation(AuthorAssociation.CONTRIBUTOR);
        comment.setAuthor(commenter);
        comment.setIssue(issues.findById(issueId).orElseThrow());
        comment.setCreatedAt(Instant.now());
        comment.setUpdatedAt(Instant.now());
        return comment;
    }

    private void edit(long commentId, String body) {
        transactions.executeWithoutResult(status -> {
            IssueComment comment = comments.findById(commentId).orElseThrow();
            comment.setBody(body);
            comment.setUpdatedAt(Instant.now());
            IssueComment saved = comments.save(comment);
            events.publishEvent(new ScmDomainEvent.CommentUpdated(
                    ScmEventPayload.CommentData.from(saved), issueId, Set.of("body"), webhook()));
        });
    }

    private void delete(long commentId) {
        transactions.executeWithoutResult(status -> {
            IssueComment comment = comments.findById(commentId).orElseThrow();
            issues.findById(issueId).orElseThrow().getComments().remove(comment);
            comments.delete(comment);
            events.publishEvent(new ScmDomainEvent.CommentDeleted(comment.getNativeId(), issueId, webhook()));
        });
    }

    private String storedDigest() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT review_snapshot_digest FROM issue WHERE id = ?", String.class, issueId));
    }

    private UUID storedSnapshot() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT review_snapshot_id FROM issue WHERE id = ?", UUID.class, issueId));
    }

    /** The revision of the issue and its discussion as committed now. */
    private String currentRevision() {
        return Objects.requireNonNull(transactions.execute(status -> revisions
                .of(ScmEventPayload.IssueData.from(issues.findById(issueId).orElseThrow()))
                .value()));
    }

    private List<ArtifactSignal> updates() {
        return signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), issueId).stream()
                .filter(signal -> ScmSignals.ISSUE_UPDATED.value().equals(signal.getSignalName()))
                .toList();
    }

    private List<String> deferredUpdateRevisions() {
        return updates().stream()
                .filter(signal -> signal.getState() == SignalState.DEFERRED)
                .map(signal -> signal.key().revision().value())
                .toList();
    }

    private boolean isSuperseded(UUID observationId) {
        return observations.findById(observationId).orElseThrow().getSupersededAt() != null;
    }

    private UUID recordObservation() {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.ISSUE));
        practice.setWorkspace(workspace);
        practice.setSlug("closed-record");
        practice.setName("Closed record");
        practice.setCriteria("Review the closed issue's record");
        practice.setSignals(PracticeTestEvidence.signals(ScmSignals.ISSUE_UPDATED));
        practice.setEvidenceRequirements(PracticeTestEvidence.needsFor(ScmSignals.ISSUE));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
        practice = practices.save(practice);
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(new ObjectMapper().valueToTree(Map.of("model", "test")));
        job = jobs.save(job);
        UUID id = UUID.randomUUID();
        Issue issue = issues.findById(issueId).orElseThrow();
        assertThat(observations.insertIfAbsent(
                        id,
                        "issue-" + id,
                        job.getId(),
                        workspace.getId(),
                        practice.getId(),
                        null,
                        "scm.issue",
                        issueId,
                        Objects.requireNonNull(issue.getAuthor()).getId(),
                        "The closed issue leaves the CSV export unaccounted for",
                        "NOT_MET",
                        "MINOR",
                        null,
                        null,
                        null,
                        Instant.now(),
                        "LIVE"))
                .isOne();
        return id;
    }

    private EventContext webhook() {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                new RepositoryRef(repository.getId(), repository.getNameWithOwner(), "main"),
                DataSource.WEBHOOK,
                "created",
                UUID.randomUUID().toString(),
                null);
    }

    private EventContext sync() {
        return EventContext.forSync(
                workspace.getId(),
                new RepositoryRef(repository.getId(), repository.getNameWithOwner(), "main"),
                IdentityProviderType.GITHUB);
    }

    private boolean backendWaitsOnALock(int backendPid) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (!jdbc.queryForList("""
                            SELECT pid FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid = ?
                            """, Integer.class, backendPid).isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
