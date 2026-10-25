package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retained review runs read for Heph's context from real review rows. Each run is created as it was retained, never
 * repaired or edited afterwards: its practice was pinned to a version but no executable method, so execution refuses
 * it while its review row still says whose work it was. Rows are seeded directly; a judged member carries only the
 * result row its recorded state requires, no evidence or delivery is created, and no review service ran to produce them.
 */
class ReviewAttemptsContentSourceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ScanReportTestData scans;

    @Autowired
    ReviewAttemptsContentSource attempts;

    @Autowired
    ReviewAssignmentQuery assignments;

    @Autowired
    ReviewJobAssignments execution;

    @Autowired
    ReviewJobRepository jobs;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    ReviewPracticeRepository practices;

    @Autowired
    ReviewItemRepository items;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    RepositoryRepository repositories;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    ItemIndexService itemIndex;

    @Autowired
    ItemRepository productItems;

    Scenario scenario;
    long developerId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        wireScmWork();
        developerId = Objects.requireNonNull(scenario.account().getId());
    }

    /** A real monitored source and its canonical item lineage, so current mentoring permission is exercised. */
    private void wireScmWork() {
        var workspace = scenario.workspace();
        long nativeId = Objects.requireNonNull(scenario.item().getSourceId());
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(workspace.getId()),
                new ConnectionConfig.GitHubAppConfig(workspace.getId(), workspace.getAccountLogin(), null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(provider);
        repository.setName("retained-work");
        repository.setNameWithOwner(workspace.getAccountLogin() + "/retained-work");
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repositories.saveAndFlush(repository);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var pull = new PullRequest();
        pull.setNativeId(nativeId);
        pull.setProvider(provider);
        pull.setRepository(repository);
        pull.setNumber(1);
        pull.setTitle("Retained reviewed work");
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/1");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pulls.saveAndFlush(pull);
        var parent = itemIndex.upsert(
                new ItemRegistration(workspace(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        var work = itemIndex.upsert(
                new ItemRegistration(workspace(), ScmItemEvents.PULL_REQUEST, pull.getId(), parent.itemId(), null));
        scenario = new Scenario(
                workspace,
                scenario.account(),
                scenario.practice(),
                productItems.findByIdAndWorkspaceId(work.itemId(), workspace()).orElseThrow());
    }

    private long workspace() {
        return scenario.workspace().getId();
    }

    /**
     * A retained ITEM review about the scenario's developer whose practice has no executable method. With
     * {@code boundJob}, a retained run that recorded no assignments is created first and the review's row is bound to
     * it, as the review binds the run it hands an assignment to.
     */
    private ReviewAssignmentId retainedReview(@Nullable UUID boundJob) {
        return retainedReview(boundJob, json.createObjectNode().put("developerId", developerId));
    }

    /** As {@link #retainedReview(UUID)}, with the row's attribution detail as it was retained. */
    private ReviewAssignmentId retainedReview(@Nullable UUID boundJob, JsonNode detail) {
        var now = Instant.now();
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace())
                .itemId(scenario.item().getId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(developerId)
                .status(ReviewStatus.PARTIAL)
                .stopReason(ReviewStopReason.LEGACY_SCOPE_UNKNOWN)
                .itemVersion("retained-work-version")
                .orderedAt(now)
                .createdAt(now)
                .admittedAt(now)
                .completedAt(now)
                .build());
        practices.saveAndFlush(ReviewPractice.builder()
                .workspaceId(workspace())
                .reviewId(review.getId())
                .practiceId(scenario.practice().getId())
                .practiceVersionId(scenario.practice().getCurrentVersion().getId())
                .build());
        if (boundJob != null) retainedRun(boundJob, review.getId(), List.of());
        items.saveAndFlush(ReviewItem.builder()
                .workspaceId(workspace())
                .reviewId(review.getId())
                .practiceId(scenario.practice().getId())
                .itemId(scenario.item().getId())
                .itemVersion(review.getItemVersion())
                .jobId(boundJob)
                .detail(detail)
                .build());
        return new ReviewAssignmentId(
                review.getId(), scenario.practice().getId(), scenario.item().getId());
    }

    /** A retained failed run of the review, recording {@code recorded} as its frozen assignments. */
    private UUID retainedRun(UUID jobId, UUID reviewId, List<ReviewAssignmentId> recorded) {
        return retainedRun(jobId, reviewId, "FAILED", recorded);
    }

    /** A retained run of the review that ended in {@code status} and was processed. */
    private UUID retainedRun(UUID jobId, UUID reviewId, String status, List<ReviewAssignmentId> recorded) {
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,attempt,processing_status,trace_id,input,config_snapshot,
                submitted_at,available_at,started_at,completed_at)
            VALUES(:job,:review,:ws,:status,1,'DONE',:trace,CAST(:input AS jsonb),'{}'::jsonb,now(),now(),now(),now())
            """)
                .param("job", jobId)
                .param("review", reviewId)
                .param("ws", workspace())
                .param("status", status)
                .param("trace", jobId.toString().replace("-", ""))
                .param(
                        "input",
                        json.writeValueAsString(json.createObjectNode()
                                .put("itemId", scenario.item().getId())
                                .set("assignments", json.valueToTree(recorded))))
                .update();
        return jobId;
    }

    /** An ended ITEM review of the scenario's work, as its owner recorded how it ended. */
    private UUID endedReview(ReviewStatus status, @Nullable ReviewStopReason stopReason) {
        var now = Instant.now();
        return reviews.saveAndFlush(Review.builder()
                        .id(UUID.randomUUID())
                        .workspaceId(workspace())
                        .itemId(scenario.item().getId())
                        .kind(ReviewKind.ITEM)
                        .origin(ReviewOrigin.REQUEST)
                        .requestedById(developerId)
                        .status(status)
                        .stopReason(stopReason)
                        .itemVersion("retained-work-version")
                        .orderedAt(now)
                        .createdAt(now)
                        .admittedAt(now)
                        .completedAt(now)
                        .build())
                .getId();
    }

    /** Another practice of the workspace, so that one review can hold one member per practice. */
    private Practice practice() {
        return versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "retained-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                developerId));
    }

    /**
     * The review's member for {@code practice} on the scenario's work, attributed to {@code about} and recorded in
     * {@code state}. A judged member is first listed, then judged with the result row its state requires.
     */
    private ReviewAssignmentId member(
            UUID reviewId,
            Practice practice,
            @Nullable UUID job,
            ReviewItemState state,
            @Nullable String reason,
            long about) {
        practices.saveAndFlush(ReviewPractice.builder()
                .workspaceId(workspace())
                .reviewId(reviewId)
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .build());
        var member = items.saveAndFlush(ReviewItem.builder()
                .workspaceId(workspace())
                .reviewId(reviewId)
                .practiceId(practice.getId())
                .itemId(scenario.item().getId())
                .itemVersion("retained-work-version")
                .state(state == ReviewItemState.JUDGED ? ReviewItemState.LISTED : state)
                .reason(reason)
                .jobId(job)
                .attemptedAt(Instant.now())
                .detail(json.createObjectNode().put("developerId", about))
                .build());
        if (state == ReviewItemState.JUDGED) {
            var result = UUID.randomUUID();
            jdbc.sql("""
                INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,
                    outcome,evidence,coverage,recorded_at)
                VALUES(:id,:ws,:review,:practice,:version,:item,:itemVersion,:developer,'MET','{}'::jsonb,'{}'::jsonb,now())
                """)
                    .param("id", result)
                    .param("ws", workspace())
                    .param("review", reviewId)
                    .param("practice", practice.getId())
                    .param("version", practice.getCurrentVersion().getId())
                    .param("item", scenario.item().getId())
                    .param("itemVersion", "retained-work-version")
                    .param("developer", about)
                    .update();
            member.setState(ReviewItemState.JUDGED);
            member.setResultId(result);
            items.saveAndFlush(member);
        }
        return new ReviewAssignmentId(
                reviewId, practice.getId(), scenario.item().getId());
    }

    /** The members of one review about the developer on the reviewed item, as the run's row states them. */
    private record Counts(long total, long judged, long reused, long withheld, long pending) {}

    /**
     * The run reads alike in the overview and in its reviewed item's own resource: its own state, beside how its review
     * ended and what that review recorded for each of its members.
     */
    private void assertReadAsRecorded(UUID jobId, String reviewStatus, @Nullable String stopReason, Counts counts) {
        var listed = rowOf(attempts.overview(workspace(), developerId), jobId);
        var resource = attempts.read(
                        workspace(), developerId, listed.path("reviewsResource").asString())
                .orElseThrow();
        for (var row : List.of(listed, rowOf(resource, jobId))) {
            assertThat(row.path("status").asString())
                    .as("the run's own state is kept apart from its review's")
                    .isEqualTo("COMPLETED");
            assertThat(row.path("processingStatus").asString()).isEqualTo("DONE");
            assertThat(row.path("reviewStatus").asString()).isEqualTo(reviewStatus);
            if (stopReason == null)
                assertThat(row.has("reviewStopReason"))
                        .as("a review that recorded no stop reason states none")
                        .isFalse();
            else assertThat(row.path("reviewStopReason").asString()).isEqualTo(stopReason);
            var recorded = row.path("assignmentCounts");
            assertThat(recorded.propertyNames())
                    .containsExactlyInAnyOrder("scope", "total", "judged", "reused", "withheld", "pending");
            assertThat(recorded.path("scope").asString()).isEqualTo("REVIEWED_ITEM");
            assertThat(new Counts(
                            recorded.path("total").asLong(),
                            recorded.path("judged").asLong(),
                            recorded.path("reused").asLong(),
                            recorded.path("withheld").asLong(),
                            recorded.path("pending").asLong()))
                    .isEqualTo(counts);
        }
    }

    /** The run's one row in a rendered context. */
    private static JsonNode rowOf(JsonNode context, UUID jobId) {
        var rows = new ArrayList<JsonNode>();
        for (var row : context.path("attempts")) {
            if (row.path("jobId").asString().equals(jobId.toString())) rows.add(row);
        }
        assertThat(rows).as("the run is listed once").hasSize(1);
        return rows.getFirst();
    }

    @Autowired
    SqlReadMeasurement reads;

    @Test
    void shouldReadTheDevelopersAttemptsInAFixedNumberOfStatementsHoweverManyRunsTheWorkspaceRetains() {
        long colleague = Objects.requireNonNull(data.member(workspace()).getId());
        Runnable runs = () -> {
            for (int run = 0; run < 3; run++) {
                var own = retainedReview(null);
                retainedRun(UUID.randomUUID(), own.reviewId(), List.of(own));
                var theirs = retainedReview(null, json.createObjectNode().put("developerId", colleague));
                retainedRun(UUID.randomUUID(), theirs.reviewId(), List.of(theirs));
            }
        };
        runs.run();
        var few = reads.measure(() -> attempts.overview(workspace(), developerId));
        runs.run();
        runs.run();
        var many = reads.measure(() -> attempts.overview(workspace(), developerId));

        assertThat(few.value().path("attempts")).hasSize(3);
        assertThat(many.value().path("attempts")).hasSize(9);
        assertThat(many.value().path("coverage").path("attributionComplete").asBoolean())
                .isTrue();
        assertThat(many.cost().statements()).isEqualTo(few.cost().statements());
    }

    @Test
    void shouldReadAScanRunsOwnFileRowsWhateverTheFileListOfItsScan() {
        var scan = scans.scenario();
        var review = scans.scan(scan, ReviewStatus.COMPLETE, Instant.now());
        long ws = scan.owner().workspace().getId();
        long requester = Objects.requireNonNull(scan.owner().account().getId());
        var run = new ReviewAssignmentId(
                review.getId(), scan.owner().practice().getId(), scan.file().getId());
        var job = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,attempt,processing_status,trace_id,input,config_snapshot,
                submitted_at,available_at,started_at,completed_at)
            VALUES(:job,:review,:ws,'COMPLETED',1,'DONE',:trace,CAST(:input AS jsonb),'{}'::jsonb,now(),now(),now(),now())
            """)
                .param("job", job)
                .param("review", review.getId())
                .param("ws", ws)
                .param("trace", job.toString().replace("-", ""))
                .param(
                        "input",
                        json.writeValueAsString(json.createObjectNode()
                                .put("itemId", run.itemId())
                                .set("assignments", json.valueToTree(List.of(run)))))
                .update();
        IntConsumer listFiles = count -> {
            for (int file = 0; file < count; file++)
                scanMember(scan, review, scanFile(scan, "src/Listed" + UUID.randomUUID() + ".java"));
        };
        listFiles.accept(2);
        var few = reads.measure(() -> attempts.overview(ws, requester));
        listFiles.accept(27);
        var many = reads.measure(() -> attempts.overview(ws, requester));

        assertThat(many.value().path("coverage").path("attributionComplete").asBoolean())
                .isTrue();
        assertThat(many.cost()).isEqualTo(few.cost());
    }

    @Test
    void shouldReadWhoARetainedRunWasAboutWhileExecutionStillRefusesIt() {
        var assignment = retainedReview(null);
        var jobId = retainedRun(UUID.randomUUID(), assignment.reviewId(), List.of(assignment));

        var job = jobs.findById(jobId).orElseThrow();
        assertThatThrownBy(() -> execution.load(job))
                .as("the execution owner refuses an assignment without an executable method, as it must")
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("frozen review assignment is unavailable");

        assertThat(assignments.retained(workspace(), assignment.reviewId(), jobId, List.of(assignment)))
                .singleElement()
                .satisfies(retained -> {
                    assertThat(retained.id()).isEqualTo(assignment);
                    assertThat(retained.item().itemId())
                            .isEqualTo(scenario.item().getId());
                    assertThat(retained.developerId()).isEqualTo(developerId);
                });
        var overview = attempts.overview(workspace(), developerId);
        assertThat(overview.path("attempts"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("jobId").asString()).isEqualTo(jobId.toString()));
        assertThat(overview.path("coverage").path("attributionComplete").asBoolean())
                .as("the retained run is attributable, so coverage is complete and the context does not fail")
                .isTrue();
    }

    @Test
    void shouldReadRetainedAttributionOnlyInItsOwnWorkspaceReviewAndForItsOwnDeveloper() {
        var assignment = retainedReview(null);
        var jobId = retainedRun(UUID.randomUUID(), assignment.reviewId(), List.of(assignment));
        var other = data.scenario();

        assertThat(assignments.retained(other.workspace().getId(), assignment.reviewId(), jobId, List.of(assignment)))
                .as("another workspace cannot read this review's attribution")
                .isEmpty();
        assertThat(assignments.retained(
                        workspace(),
                        assignment.reviewId(),
                        jobId,
                        List.of(new ReviewAssignmentId(
                                UUID.randomUUID(), assignment.practiceId(), assignment.itemId()))))
                .as("a key naming another review selects nothing")
                .isEmpty();
        var colleagueId = Objects.requireNonNull(data.member(workspace()).getId());
        assertThat(attempts.overview(workspace(), colleagueId).toString())
                .as("a run about one developer is never shown to another")
                .doesNotContain(jobId.toString());
    }

    @Test
    void shouldReadARunWithoutRecordedAssignmentsOnlyThroughTheReviewsBindingToIt() {
        var boundJob = UUID.randomUUID();
        var bound = retainedReview(boundJob);
        var unbound = retainedReview(null);
        var unboundJob = retainedRun(UUID.randomUUID(), unbound.reviewId(), List.of());

        assertThat(assignments.retained(workspace(), bound.reviewId(), boundJob, List.of()))
                .singleElement()
                .satisfies(retained -> assertThat(retained.developerId()).isEqualTo(developerId));
        assertThat(assignments.retained(workspace(), unbound.reviewId(), unboundJob, List.of()))
                .as("nothing binds the review's rows to this run, so its attribution is unknown")
                .isEmpty();
        assertThat(attempts.overview(workspace(), developerId)
                        .path("coverage")
                        .path("attributionComplete")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void shouldReadExactlyTheRecordedRowsOfTheRunsOwnReviewAndRefuseAnyKeyItCannotRead() {
        var first = retainedReview(null);
        var second = retainedReview(null);
        var jobId = retainedRun(UUID.randomUUID(), first.reviewId(), List.of(first));

        assertThat(assignments.retained(workspace(), first.reviewId(), jobId, List.of(first)))
                .as("one scoped read returns this review's recorded row, not the same item's row of another review")
                .extracting(ReviewAssignmentQuery.Retained::id)
                .containsExactly(first);
        assertThat(assignments.retained(workspace(), first.reviewId(), jobId, List.of(first, second)))
                .as("a key of another review refuses the whole selection")
                .isEmpty();
        assertThat(assignments.retained(
                        workspace(),
                        first.reviewId(),
                        jobId,
                        List.of(
                                first,
                                new ReviewAssignmentId(
                                        first.reviewId(), first.practiceId() + 1_000_000, first.itemId()))))
                .as("a recorded key without its retained row refuses the whole selection")
                .isEmpty();
    }

    @Test
    void shouldAttributeABoundRunOnlyWhenItsBoundRowSaysWhoseWorkItWas() {
        var readableJob = UUID.randomUUID();
        var readable = retainedReview(readableJob);
        var unreadableJob = UUID.randomUUID();
        var unreadable = retainedReview(unreadableJob, json.createObjectNode());

        assertThat(assignments.retained(workspace(), readable.reviewId(), readableJob, List.of()))
                .singleElement()
                .satisfies(retained -> assertThat(retained.developerId()).isEqualTo(developerId));
        assertThat(assignments.retained(workspace(), unreadable.reviewId(), unreadableJob, List.of()))
                .as("a bound row without a developer leaves its run unattributed rather than half read")
                .isEmpty();
        assertThat(assignments.retained(workspace(), unreadable.reviewId(), readableJob, List.of()))
                .as("another review's binding does not attribute this review")
                .isEmpty();
    }

    @Test
    void shouldStateThePartialReviewBesideItsCompletedRunWhenPracticesWereWithheld() {
        var colleagueId = Objects.requireNonNull(data.member(workspace()).getId());
        var reviewId = endedReview(ReviewStatus.PARTIAL, ReviewStopReason.EXECUTION_INCOMPLETE);
        var judged = practice();
        var alsoJudged = practice();
        var incomplete = practice();
        var requested = Stream.of(judged, alsoJudged, incomplete)
                .map(practice -> new ReviewAssignmentId(
                        reviewId, practice.getId(), scenario.item().getId()))
                .toList();
        var jobId = retainedRun(UUID.randomUUID(), reviewId, "COMPLETED", requested);
        member(reviewId, judged, jobId, ReviewItemState.JUDGED, null, developerId);
        member(reviewId, alsoJudged, jobId, ReviewItemState.JUDGED, null, developerId);
        member(reviewId, incomplete, jobId, ReviewItemState.WITHHELD, "EXECUTION_INCOMPLETE", developerId);
        // Withheld before the run started: never handed to it, so only the review's own rows can count it.
        var unsupported = member(
                reviewId, practice(), null, ReviewItemState.WITHHELD, "item_capability_unsupported", developerId);
        // Attributed to a colleague: the developer's counts must leave it out.
        member(reviewId, practice(), null, ReviewItemState.WITHHELD, "source_contract_unsupported", colleagueId);

        assertThat(assignments.retained(workspace(), reviewId, jobId, requested))
                .as("the run's own selection misses the member withheld before it ran")
                .extracting(ReviewAssignmentQuery.Retained::id)
                .containsExactlyInAnyOrderElementsOf(requested)
                .doesNotContain(unsupported);
        assertReadAsRecorded(jobId, "PARTIAL", "EXECUTION_INCOMPLETE", new Counts(4, 2, 0, 2, 0));
    }

    @Test
    void shouldStateNoWithheldMembersBesideACompletedRunWhenItsReviewIsComplete() {
        var earlier = endedReview(ReviewStatus.PARTIAL, ReviewStopReason.DEADLINE);
        member(earlier, practice(), null, ReviewItemState.WITHHELD, "DEADLINE", developerId);
        var reviewId = endedReview(ReviewStatus.COMPLETE, null);
        var judged = practice();
        var alsoJudged = practice();
        var requested = Stream.of(judged, alsoJudged)
                .map(practice -> new ReviewAssignmentId(
                        reviewId, practice.getId(), scenario.item().getId()))
                .toList();
        var jobId = retainedRun(UUID.randomUUID(), reviewId, "COMPLETED", requested);
        member(reviewId, judged, jobId, ReviewItemState.JUDGED, null, developerId);
        member(reviewId, alsoJudged, jobId, ReviewItemState.JUDGED, null, developerId);

        assertReadAsRecorded(jobId, "COMPLETE", null, new Counts(2, 2, 0, 0, 0));
    }

    /** Another file of the scan's repository. */
    private long scanFile(ScanReportTestData.Scenario scan, String path) {
        var file = new Item();
        file.setWorkspaceId(scan.owner().workspace().getId());
        file.setKind(new ItemKind("scm.file"));
        file.setParentId(scan.repository().getId());
        file.setPath(path);
        file.setCreatedAt(Instant.now().minusSeconds(7200));
        return productItems.saveAndFlush(file).getId();
    }

    /** The scan's listed member for one more file, attributed to nobody as a scan's members are. */
    private void scanMember(ScanReportTestData.Scenario scan, Review review, long fileId) {
        items.saveAndFlush(ReviewItem.builder()
                .workspaceId(review.getWorkspaceId())
                .reviewId(review.getId())
                .practiceId(scan.owner().practice().getId())
                .itemId(fileId)
                .itemVersion(ScanReportTestData.version())
                .detail(json.createObjectNode().putNull("developerId"))
                .build());
    }

    @Test
    void shouldReadOnlyTheAskedPairsOfItsOwnWorkspaceWhenScansShareTheirItems() {
        var scan = scans.scenario();
        var foreign = scans.scenario();
        var now = Instant.now();
        var complete = scans.scan(scan, ReviewStatus.COMPLETE, now);
        var running = scans.scan(scan, ReviewStatus.RUNNING, now);
        var outside = scans.scan(foreign, ReviewStatus.COMPLETE, now);
        long fileX = scan.file().getId();
        long fileY = scanFile(scan, "src/Other.java");
        long fileZ = scanFile(scan, "src/Sibling.java");
        for (var review : List.of(complete, running)) {
            scanMember(scan, review, fileY);
            scanMember(scan, review, fileZ);
        }
        long ws = scan.owner().workspace().getId();
        long practiceId = scan.owner().practice().getId();
        long requester = Objects.requireNonNull(scan.owner().account().getId());

        assertThat(items.findByWorkspaceIdAndReviewedItems(
                        ws,
                        new UUID[] {complete.getId(), running.getId(), outside.getId()},
                        new Long[] {fileX, fileY, foreign.file().getId()}))
                .as("only the asked pairs of this workspace: no sibling, no crossed pair, no other workspace's row")
                .extracting(row -> new ReviewAssignmentId(row.getReviewId(), row.getPracticeId(), row.getItemId()))
                .containsExactlyInAnyOrder(
                        new ReviewAssignmentId(complete.getId(), practiceId, fileX),
                        new ReviewAssignmentId(running.getId(), practiceId, fileY));

        var completeX = new ReviewAssignmentQuery.ReviewedItem(complete.getId(), fileX);
        var runningY = new ReviewAssignmentQuery.ReviewedItem(running.getId(), fileY);
        var outsideFile = new ReviewAssignmentQuery.ReviewedItem(
                outside.getId(), foreign.file().getId());
        assertThat(assignments.recorded(ws, requester, List.of(completeX, runningY, outsideFile)))
                .as("each scan's own state for its asked file; a scan's members name no developer, so nothing is"
                        + " counted; another workspace's review is not answered")
                .containsOnly(
                        entry(completeX, new ReviewAssignmentQuery.Recorded(ReviewStatus.COMPLETE, null, null)),
                        entry(runningY, new ReviewAssignmentQuery.Recorded(ReviewStatus.RUNNING, null, null)));
    }
}
