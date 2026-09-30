package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWorkFixtures;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the mentor is told about the version a recorded review read, over real rows: the review's own capture on one
 * side, the stored work on the workspace's connected instance on the other.
 */
class ReviewedWorkCoverageIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String INSTANCE = "https://gitlab.course.example";
    private static final String PATH = "course/intro";
    private static final String HEAD = "b".repeat(40);
    private static final Instant CAPTURED = NOW.minus(2, ChronoUnit.HOURS);

    @Autowired
    private ObservationHistoryContentSource historySource;

    @Autowired
    private ReviewedWorkCoverage coverage;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ArtifactSourceCatalogRegistry sourceCatalogs;

    @Autowired
    private MentorContextQueryRepository queryRepository;

    @Autowired
    private ConversationConsentGate consentGate;

    @Autowired
    private ObservationVisibilityPolicy visibilityPolicy;

    @Autowired
    private WorkspaceActorSelector actorSelector;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicLong nativeIds = new AtomicLong(190_000);

    private IdentityProvider instance;
    private Workspace workspace;
    private Repository course;
    private User student;
    private Practice practice;

    @BeforeEach
    void setUp() {
        instance = gitLabInstance(INSTANCE);
        student = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "student", instance));
        workspace =
                createWorkspace("truth-" + nativeIds.incrementAndGet(), "Intro", "course", AccountType.ORG, student);
        connect(workspace);
        course = repository(instance);
        monitor(workspace);
        practice = persistPractice(workspace, null, "links-the-issue", "Links the issue", null);
    }

    /** A recheck that is waiting or failed has recorded nothing, so it cannot stand in for a review. */
    @ParameterizedTest
    @EnumSource(
            value = AgentJobStatus.class,
            names = {"QUEUED", "FAILED"})
    void shouldKeepTheLatestNegativeResultAboutTheVersionItReadWhenTheRepairWasNotReviewed(AgentJobStatus recheck) {
        PullRequest mr = mergeRequest(course, "MR !2", "Closes #12");
        AgentJob reviewed = review(mr, "MR !2", "Plans the work", HEAD);
        // Recorded after the repair was stored: when a review completes says nothing about what it read.
        observe(practice, reviewed, mr.getId(), student, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        AgentJob pending = persistPullRequestReview(workspace, mr.getNumber(), mr.getId(), null);
        pending.setStatus(recheck);
        agentJobRepository.save(pending);

        JsonNode entry = history().path("recentObservations").get(0);

        assertThat(entry.path("outcome").asString()).isEqualTo("NEGATIVE");
        assertThat(entry.path("presence").asString()).isEqualTo("ABSENT");
        assertThat(entry.has("assessment")).isFalse();
        assertThat(entry.path("reviewId").asString()).isEqualTo(reviewed.getId().toString());
        JsonNode work = entry.path("reviewedWork");
        assertThat(work.path("coreCoverage").asString()).isEqualTo("DIFFERS_FROM_STORED_WORK");
        assertThat(work.path("checkedFields").valueStream().map(JsonNode::asString))
                .containsExactly("title", "description", "head");
        assertThat(work.path("providerFreshness").asString()).isEqualTo("UNKNOWN");
        assertThat(work.path("capturedAt").asString()).isEqualTo(CAPTURED.toString());
        assertThat(work.toString()).doesNotContain("Closes", "Plans", "dig~", HEAD);
    }

    /**
     * Citing the diff makes the observation usable in conversation; it does not make the pull request's own record
     * usable. Under a contract that denies that for the core source, nothing about the captured core is told.
     */
    @Test
    void shouldTellNothingOfTheCapturedCoreWhenOnlyTheDiffMayBeUsedInConversation() {
        PullRequest mr = mergeRequest(course, "MR !2", "Closes #12");
        AgentJob reviewed = review(mr, "MR !2", "Plans the work", HEAD);
        observe(practice, reviewed, mr.getId(), student, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        ArtifactSourceCatalogRegistry coreDenied =
                mock(ArtifactSourceCatalogRegistry.class, AdditionalAnswers.delegatesTo(sourceCatalogs));
        doReturn(false)
                .when(coreDenied)
                .isSourceUsePermitted(
                        any(), eq(PullRequestContentSource.CORE), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING));
        ObservationHistoryContentSource underCoreDenial = new ObservationHistoryContentSource(
                userRepository,
                observationRepository,
                queryRepository,
                consentGate,
                visibilityPolicy,
                new ReviewedWorkCoverage(agentJobRepository, queryRepository, actorSelector, coreDenied, objectMapper),
                objectMapper);

        assertThat(history()
                        .path("recentObservations")
                        .get(0)
                        .path("reviewedWork")
                        .path("coreCoverage")
                        .asString())
                .isEqualTo("DIFFERS_FROM_STORED_WORK");
        JsonNode entry = Objects.requireNonNull(new TransactionTemplate(transactionManager)
                        .execute(tx -> underCoreDenial.buildPayload(workspace.getId(), student.getId())))
                .path("recentObservations")
                .get(0);

        assertThat(entry.path("reviewId").asString()).isEqualTo(reviewed.getId().toString());
        assertThat(entry.path("outcome").asString()).isEqualTo("NEGATIVE");
        JsonNode work = entry.path("reviewedWork");
        assertThat(work.path("coreCoverage").asString()).isEqualTo("UNKNOWN");
        assertThat(work.path("checkedFields")).isEmpty();
        assertThat(work.has("capturedAt")).isFalse();
        assertThat(work.toString()).doesNotContain("Closes", "Plans", "dig~", HEAD);
    }

    @Test
    void shouldTellALaterPositiveResultOnTheStoredVersionFromTheEarlierNegativeOne() {
        PullRequest mr = mergeRequest(course, "MR !2", "Closes #12");
        observe(
                practice,
                review(mr, "MR !2", "Plans the work", HEAD),
                mr.getId(),
                student,
                ObservationKind.OMISSION_GAP,
                Severity.MINOR,
                NOW.minus(1, ChronoUnit.HOURS));
        observe(
                practice,
                review(mr, "MR !2", "Closes #12", HEAD),
                mr.getId(),
                student,
                ObservationKind.DEMONSTRATED_STRENGTH,
                null,
                NOW);

        JsonNode payload = history();
        assertThat(result(payload.path("recentObservations").get(0)))
                .containsExactly("POSITIVE", "MATCHES_STORED_WORK");
        assertThat(result(payload.path("earlierObservations").get(0)))
                .containsExactly("NEGATIVE", "DIFFERS_FROM_STORED_WORK");

        mr.setBody("Closes #12 and #13");
        pullRequestRepository.save(mr);

        assertThat(result(history().path("recentObservations").get(0)))
                .containsExactly("POSITIVE", "DIFFERS_FROM_STORED_WORK");
    }

    @Test
    void shouldKeepALaterAbstentionAnAbstention() {
        PullRequest mr = mergeRequest(course, "MR !2", "Closes #12");
        AgentJob abstained = review(mr, "MR !2", "Closes #12", HEAD);
        observe(practice, abstained, mr.getId(), student, ObservationKind.NOT_APPLICABLE, null, NOW);

        JsonNode entry = history().path("abstentions").get(0);

        assertThat(entry.path("reviewId").asString())
                .isEqualTo(abstained.getId().toString());
        assertThat(entry.path("assessmentStatus").asString()).isEqualTo("NOT_APPLICABLE");
        assertThat(entry.path("outcome").isNull()).isTrue();
    }

    @Test
    void shouldProveOnlyADifferenceFromAReviewCapturedBeforeTheIdentityWasRecorded() {
        PullRequest repaired = mergeRequest(course, "MR !2", "Closes #12");
        PullRequest unchanged = mergeRequest(course, "MR !3", "Closes #14");

        assertThat(coverageOf(repaired, legacyReview(repaired, "Plans the work")))
                .containsExactly("DIFFERS_FROM_STORED_WORK", "description,head");
        assertThat(coverageOf(unchanged, legacyReview(unchanged, "Closes #14")))
                .containsExactly("UNKNOWN", "description,head");
    }

    @Test
    void shouldCompareAnIssueByTitleAndDescription() {
        Issue issue = issue(course, "Plan issue 12", "- [ ] Link the issue");
        AgentJob reviewed = persistIssueReview(workspace, issue.getNumber(), NOW);
        reviewed.setMetadata(objectMapper.createObjectNode().put("issue_id", issue.getId()));
        reviewed.setEvidenceSnapshot(snapshot(
                ReviewedWorkFixtures.issueManifest(CAPTURED, "- [ ] Plan"),
                new ReviewedWork(
                        ArtifactKinds.ISSUE.value(),
                        issue.getId(),
                        ReviewedWork.revision(ArtifactKinds.ISSUE, "Plan issue 12", "- [ ] Plan"),
                        null,
                        CAPTURED)));
        agentJobRepository.save(reviewed);
        UUID id = observe(
                practice,
                reviewed,
                ArtifactKinds.ISSUE.value(),
                issue.getId(),
                student,
                null,
                ObservationKind.OMISSION_GAP,
                Severity.MINOR,
                NOW,
                DIFF_EVIDENCE_JSON,
                null);

        JsonNode work = Objects.requireNonNull(
                coverage.of(workspace.getId(), List.of(observation(id))).get(id));
        assertThat(work.path("coreCoverage").asString()).isEqualTo("DIFFERS_FROM_STORED_WORK");
        assertThat(work.path("checkedFields").valueStream().map(JsonNode::asString))
                .containsExactly("title", "description");
    }

    @Test
    void shouldEstablishNothingAboutWorkItCannotBindToTheReviewOrSee() {
        PullRequest deleted = mergeRequest(course, "MR !4", "Closes #15");
        UUID deletedObservation = observed(deleted, review(deleted, "MR !4", "Closes #15", HEAD));
        deleted.setDeletedAt(NOW);
        pullRequestRepository.save(deleted);

        IdentityProvider otherInstance = gitLabInstance("https://gitlab.other.example");
        PullRequest samePath = mergeRequest(repository(otherInstance), "MR !5", "Closes #16");
        UUID samePathObservation = observed(samePath, review(samePath, "MR !5", "Closes #16", HEAD));

        PullRequest mr = mergeRequest(course, "MR !6", "Closes #17");
        ObjectNode incomplete = capture(mr, "MR !6", "Closes #17", HEAD);
        incomplete
                .putObject(ReviewedWork.SNAPSHOT_KEY)
                .put("artifactKind", ArtifactKinds.PULL_REQUEST.value())
                .put("artifactId", mr.getId());
        UUID malformedObservation = observed(mr, reviewWith(mr, incomplete));

        ObjectNode misboundCapture = capture(mr, "MR !6", "Closes #17", HEAD);
        ((ObjectNode) misboundCapture.get(ReviewedWork.SNAPSHOT_KEY)).put("artifactId", samePath.getId());
        UUID misboundObservation = observed(mr, reviewWith(mr, misboundCapture));

        Workspace foreign = createWorkspace(
                "foreign-" + nativeIds.incrementAndGet(), "Foreign", "foreign", AccountType.ORG, student);
        AgentJob foreignJob = persistPullRequestReview(foreign, mr.getNumber(), mr.getId(), NOW);
        foreignJob.setEvidenceSnapshot(capture(mr, "MR !6", "Closes #17", HEAD));
        agentJobRepository.save(foreignJob);
        Practice foreignPractice = persistPractice(foreign, null, "links-the-issue", "Links the issue", null);
        UUID foreignObservation = observe(
                foreignPractice, foreignJob, mr.getId(), student, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);

        List<UUID> ids = List.of(
                deletedObservation, samePathObservation, malformedObservation, misboundObservation, foreignObservation);
        var nodes = coverage.of(
                workspace.getId(), ids.stream().map(this::observation).toList());

        assertThat(ids).allSatisfy(id -> {
            JsonNode work = Objects.requireNonNull(nodes.get(id));
            assertThat(work.path("coreCoverage").asString()).isEqualTo("UNKNOWN");
            assertThat(work.path("checkedFields")).isEmpty();
            assertThat(work.has("capturedAt")).isFalse();
        });
    }

    private List<String> result(JsonNode entry) {
        return List.of(
                entry.path("outcome").asString(),
                entry.path("reviewedWork").path("coreCoverage").asString());
    }

    private List<String> coverageOf(PullRequest mr, AgentJob job) {
        UUID id = observed(mr, job);
        JsonNode work = Objects.requireNonNull(
                coverage.of(workspace.getId(), List.of(observation(id))).get(id));
        return List.of(
                work.path("coreCoverage").asString(),
                String.join(
                        ",",
                        work.path("checkedFields")
                                .valueStream()
                                .map(JsonNode::asString)
                                .toList()));
    }

    private UUID observed(PullRequest mr, AgentJob job) {
        return observe(practice, job, mr.getId(), student, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
    }

    private Observation observation(UUID id) {
        return observationRepository.findById(id).orElseThrow();
    }

    /** A completed review of {@code mr} that captured {@code title} and {@code body} at {@code head}. */
    private AgentJob review(PullRequest mr, String title, String body, String head) {
        return reviewWith(mr, capture(mr, title, body, head));
    }

    private AgentJob reviewWith(PullRequest mr, ObjectNode snapshot) {
        AgentJob job = persistPullRequestReview(workspace, mr.getNumber(), mr.getId(), NOW);
        job.setEvidenceSnapshot(snapshot);
        return agentJobRepository.save(job);
    }

    private JsonNode history() {
        Map<String, byte[]> files = new HashMap<>();
        historySource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), student.getId(), UUID.randomUUID()), files);
        return objectMapper.readTree(files.get(ObservationHistoryContentSource.OUTPUT_KEY));
    }

    /** A review from before {@code reviewedWork} was recorded: only its manifest remains. */
    private AgentJob legacyReview(PullRequest mr, String body) {
        AgentJob job = persistPullRequestReview(workspace, mr.getNumber(), mr.getId(), NOW);
        job.setEvidenceSnapshot(snapshot(ReviewedWorkFixtures.pullRequestManifest(CAPTURED, body, HEAD), null));
        return agentJobRepository.save(job);
    }

    private ObjectNode capture(PullRequest mr, String title, String body, String head) {
        return snapshot(
                ReviewedWorkFixtures.pullRequestManifest(CAPTURED, body, head),
                new ReviewedWork(
                        ArtifactKinds.PULL_REQUEST.value(),
                        mr.getId(),
                        ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, title, body),
                        head,
                        CAPTURED));
    }

    private ObjectNode snapshot(ArtifactSourceManifest manifest, @Nullable ReviewedWork work) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("manifest", objectMapper.valueToTree(manifest));
        if (work != null) {
            snapshot.set(ReviewedWork.SNAPSHOT_KEY, objectMapper.valueToTree(work));
        }
        return snapshot;
    }

    private IdentityProvider gitLabInstance(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

    private void connect(Workspace target) {
        Connection connection = new Connection(
                target,
                IntegrationKind.GITLAB,
                "GITLAB",
                new ConnectionConfig.GitLabConfig(
                        INSTANCE, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null));
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.saveAndFlush(connection);
    }

    private Repository repository(IdentityProvider provider) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(provider);
        repository.setName("intro");
        repository.setNameWithOwner(PATH);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + PATH);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(NOW);
        repository.setUpdatedAt(NOW);
        repository.setPushedAt(NOW);
        return repositoryRepository.save(repository);
    }

    private void monitor(Workspace target) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(target);
        monitor.setNameWithOwner(PATH);
        monitorRepository.save(monitor);
    }

    private PullRequest mergeRequest(Repository repository, String title, String body) {
        PullRequest mr = new PullRequest();
        mr.setNativeId(nativeIds.incrementAndGet());
        mr.setProvider(repository.getProvider());
        mr.setRepository(repository);
        mr.setNumber((int) nativeIds.incrementAndGet());
        mr.setTitle(title);
        mr.setBody(body);
        mr.setState(PullRequest.State.OPEN);
        mr.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + mr.getNumber());
        mr.setAuthor(student);
        mr.setHeadRefName("feature");
        mr.setBaseRefName("main");
        mr.setHeadRefOid(HEAD);
        return pullRequestRepository.save(mr);
    }

    private Issue issue(Repository repository, String title, String body) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber((int) nativeIds.incrementAndGet());
        issue.setTitle(title);
        issue.setBody(body);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/-/issues/" + issue.getNumber());
        issue.setAuthor(student);
        return issueRepository.save(issue);
    }
}
