package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.OpenQuestion;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestComponent
@RequiredArgsConstructor
public class ReviewIntegrationData {
    private final WorkspaceRepository workspaces;
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final ItemRepository items;
    private final PracticeVersionService versions;
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository reviewItems;
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ResultMarkRepository marks;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProductItemQuery productItems;
    private final ClasspathSourceCatalogRegistry sourceCatalog;

    public record Scenario(Workspace workspace, Account account, Practice practice, Item item) {}

    @Transactional
    public Scenario scenario() {
        return scenario(DefinitionTestFixtures.definition());
    }

    @Transactional
    public Scenario scenario(PracticeDefinition definition) {
        return scenario(new ItemKind("scm.pull_request"), definition);
    }

    @Transactional
    public Scenario scenario(ItemKind kind, PracticeDefinition definition) {
        var workspace = WorkspaceTestFixtures.activeWorkspace("s5-" + UUID.randomUUID());
        workspace.getFeatures().setPracticesEnabled(true);
        workspaces.saveAndFlush(workspace);
        var account = accounts.saveAndFlush(new Account("S5 developer"));
        var membership = new Membership();
        membership.setId(new Membership.Id(workspace.getId(), account.getId()));
        membership.setWorkspace(workspace);
        membership.setRole(Membership.WorkspaceRole.OWNER);
        memberships.saveAndFlush(membership);
        var item = new Item();
        item.setWorkspaceId(workspace.getId());
        item.setKind(kind);
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        items.saveAndFlush(item);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "s5-practice-" + UUID.randomUUID().toString().substring(0, 8),
                item.getKind(),
                definition,
                null,
                0,
                account.getId()));
        return new Scenario(workspace, account, practice, item);
    }

    /** The same developer and practice in the same workspace, on another piece of work. */
    @Transactional
    public Scenario onOtherWork(Scenario s) {
        var item = new Item();
        item.setWorkspaceId(s.workspace().getId());
        item.setKind(s.item().getKind());
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        items.saveAndFlush(item);
        return new Scenario(s.workspace(), s.account(), s.practice(), item);
    }

    @Transactional
    public Account member(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var account = accounts.saveAndFlush(new Account("Another developer"));
        var membership = new Membership();
        membership.setId(new Membership.Id(workspaceId, account.getId()));
        membership.setWorkspace(workspace);
        membership.setRole(Membership.WorkspaceRole.MEMBER);
        memberships.saveAndFlush(membership);
        return account;
    }

    public JsonNode capturedSources(Scenario scenario) {
        var item = productItems.require(
                scenario.workspace().getId(), scenario.item().getId());
        var kinds = sourceCatalog.current().sources().stream()
                .filter(contract -> contract.appliesTo(item.kind().value()))
                .map(SourceContract::kind)
                .collect(Collectors.toSet());
        var sources = productItems.fingerprints(item, kinds, null);
        var facts = json.createObjectNode();
        var fingerprints = facts.putObject("sourceFingerprints");
        sources.forEach((kind, fingerprint) -> fingerprints.put(kind.value(), fingerprint));
        return facts;
    }

    @Transactional
    public Result result(Scenario s, ResultOutcome outcome, Instant at) {
        return result(s, outcome, at, at);
    }

    @Transactional
    public Result result(Scenario s, ResultOutcome outcome, Instant orderedAt, Instant recordedAt) {
        return result(s, outcome, orderedAt, recordedAt, "version-" + UUID.randomUUID());
    }

    @Transactional
    public Result result(Scenario s, ResultOutcome outcome, Instant orderedAt, Instant recordedAt, String itemVersion) {
        return result(
                s, outcome, orderedAt, recordedAt, itemVersion, new HostCoverage(true, List.of(), 1, 0, 0, List.of()));
    }

    @Transactional
    public Result result(
            Scenario s,
            ResultOutcome outcome,
            Instant orderedAt,
            Instant recordedAt,
            String itemVersion,
            HostCoverage coverage) {
        var review = Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(s.workspace().getId())
                .itemId(s.item().getId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(s.account().getId())
                .status(ReviewStatus.COMPLETE)
                .itemVersion(itemVersion)
                .orderedAt(orderedAt)
                .createdAt(orderedAt)
                .admittedAt(recordedAt)
                .completedAt(recordedAt)
                .build();
        reviews.saveAndFlush(review);
        assignments.saveAndFlush(ReviewPractice.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(s.practice().getId())
                .practiceVersionId(s.practice().getCurrentVersion().getId())
                .status(ReviewPracticeStatus.DONE)
                .itemsListed(1)
                .listingComplete(true)
                .captureDecision(capturedSources(s))
                .method(method())
                .build());
        var baseEvidence = json.convertValue(json.readTree("""
            {"citations":[{"sourceKind":"scm.pull-request.diff","sourcePath":"inputs/sources/scm/diff.patch","path":"src/Main.java","side":"NEW","commit":"commit-1","startLine":1,"endLine":1,"quote":"changed();","verification":{"status":"VERIFIED","itemSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","quoteSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}],"openQuestions":[]}
            """), ResultEvidence.class);
        var evidence = json.valueToTree(new ResultEvidence(
                baseEvidence.citations(),
                null,
                outcome == ResultOutcome.DOES_NOT_APPLY
                        ? new InapplicabilityWarrant(
                                List.of(baseEvidence.citations().getFirst().sourceKind()),
                                "This practice requires a matching change.",
                                "The captured work has no matching change.")
                        : null,
                outcome == ResultOutcome.COULD_NOT_DETERMINE
                        ? List.of(new OpenQuestion(
                                "Does the changed branch cover this case?",
                                "A focused execution would settle it.",
                                null))
                        : List.of()));
        var member = reviewItems.saveAndFlush(ReviewItem.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(s.practice().getId())
                .itemId(s.item().getId())
                .itemVersion(review.getItemVersion())
                .state(ReviewItemState.LISTED)
                .detail(json.createObjectNode().put("developerId", s.account().getId()))
                .build());
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(s.practice().getId())
                .practiceVersionId(s.practice().getCurrentVersion().getId())
                .itemId(s.item().getId())
                .itemVersion(review.getItemVersion())
                .developerId(s.account().getId())
                .outcome(outcome)
                .summary("Recorded result")
                .explanation("The captured change supports this result.")
                .severity(outcome == ResultOutcome.NOT_MET ? Severity.MAJOR : null)
                .evidence(evidence)
                .coverage(json.valueToTree(coverage))
                .recordedAt(recordedAt)
                .build();
        results.saveAndFlush(result);
        if (outcome == ResultOutcome.NOT_MET)
            problems.save(Problem.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(s.workspace().getId())
                    .resultId(result.getId())
                    .position(0)
                    .key("problem-1")
                    .severity(Severity.MAJOR)
                    .summary("A recorded problem")
                    .explanation("The captured change introduces the problem.")
                    .citations(evidence.get("citations"))
                    .basis(json.valueToTree(new IntroducedBasis(0, "The change introduces this problem.")))
                    .nextStep("Adjust the changed branch.")
                    .build());
        member.setState(ReviewItemState.JUDGED);
        member.setResultId(result.getId());
        member.setAttemptedAt(recordedAt);
        reviewItems.saveAndFlush(member);
        return result;
    }

    /**
     * A review of the scenario's work that is queued or running and has recorded no result: selection froze
     * {@code subject} on its listed item, and {@code requester} ordered it, or nobody when activity started it.
     */
    @Transactional
    public Review activeReview(
            Scenario s, long subject, ReviewStatus status, Instant orderedAt, @Nullable Long requester) {
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(s.workspace().getId())
                .itemId(s.item().getId())
                .kind(ReviewKind.ITEM)
                .origin(requester == null ? ReviewOrigin.EVENT : ReviewOrigin.REQUEST)
                .requestedById(requester)
                .status(status)
                .itemVersion("version-" + UUID.randomUUID())
                .orderedAt(orderedAt)
                .createdAt(orderedAt)
                .build());
        assignments.saveAndFlush(ReviewPractice.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(s.practice().getId())
                .practiceVersionId(s.practice().getCurrentVersion().getId())
                .status(ReviewPracticeStatus.QUEUED)
                .itemsListed(1)
                .listingComplete(true)
                .captureDecision(json.createObjectNode())
                .method(method())
                .build());
        reviewItems.saveAndFlush(ReviewItem.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(s.practice().getId())
                .itemId(s.item().getId())
                .itemVersion(review.getItemVersion())
                .state(ReviewItemState.LISTED)
                .detail(json.createObjectNode().put("developerId", subject))
                .build());
        return review;
    }

    /**
     * A started review of {@code practices} practices, queued again while its job waits for its next attempt, after
     * earlier attempts staged the first {@code ended} of them.
     */
    @Transactional
    public Review continuingReview(Scenario s, long subject, int ended, int practices) {
        var review = activeReview(s, subject, ReviewStatus.QUEUED, Instant.now().minusSeconds(600), null);
        jdbc.sql("UPDATE review SET checks=checks||jsonb_build_object('startedAt',now()::text) WHERE id=:id")
                .param("id", review.getId())
                .update();
        for (int i = 1; i < practices; i++) {
            var practice = versions.create(new PracticeVersionService.NewPractice(
                    s.workspace(),
                    "s5-practice-" + UUID.randomUUID().toString().substring(0, 8),
                    s.item().getKind(),
                    DefinitionTestFixtures.definition(),
                    null,
                    0,
                    s.account().getId()));
            assignments.saveAndFlush(ReviewPractice.builder()
                    .workspaceId(s.workspace().getId())
                    .reviewId(review.getId())
                    .practiceId(practice.getId())
                    .practiceVersionId(practice.getCurrentVersion().getId())
                    .status(ReviewPracticeStatus.QUEUED)
                    .itemsListed(1)
                    .listingComplete(true)
                    .captureDecision(json.createObjectNode())
                    .method(method())
                    .build());
            reviewItems.saveAndFlush(ReviewItem.builder()
                    .workspaceId(s.workspace().getId())
                    .reviewId(review.getId())
                    .practiceId(practice.getId())
                    .itemId(s.item().getId())
                    .itemVersion(review.getItemVersion())
                    .state(ReviewItemState.LISTED)
                    .detail(json.createObjectNode().put("developerId", subject))
                    .build());
        }
        var job = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,run_id,processing_status,trace_id,input,config_snapshot,submitted_at)
            VALUES(:id,:review,:ws,'QUEUED',:run,'NONE',:trace,CAST(:input AS jsonb),'{}'::jsonb,now())
            """)
                .param("id", job)
                .param("run", RuntimeJobTestData.insertRun(jdbc, s.workspace().getId(), review.getId(), "QUEUED", 3))
                .param("review", review.getId())
                .param("ws", s.workspace().getId())
                .param("trace", job.toString().replace("-", ""))
                .param(
                        "input",
                        json.writeValueAsString(Map.of("itemId", s.item().getId(), "assignments", List.of())))
                .update();
        // The first attempt bound every practice to the job; the attempts since staged the first `ended` of them.
        jdbc.sql("UPDATE review_item SET job_id=:job WHERE workspace_id=:ws AND review_id=:review")
                .param("job", job)
                .param("ws", s.workspace().getId())
                .param("review", review.getId())
                .update();
        jdbc.sql("""
            UPDATE review_item SET state='STAGED' WHERE workspace_id=:ws AND review_id=:review AND practice_id IN (
              SELECT practice_id FROM review_item WHERE workspace_id=:ws AND review_id=:review
              ORDER BY practice_id LIMIT :ended)
            """)
                .param("ws", s.workspace().getId())
                .param("review", review.getId())
                .param("ended", ended)
                .update();
        return review;
    }

    /** A practice that the review withheld when it was created, before any attempt, as readiness does. */
    @Transactional
    public void withheldAtCreation(Scenario s, Review review) {
        var practice = versions.create(new PracticeVersionService.NewPractice(
                s.workspace(),
                "s5-practice-" + UUID.randomUUID().toString().substring(0, 8),
                s.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                s.account().getId()));
        assignments.saveAndFlush(ReviewPractice.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(ReviewPracticeStatus.QUEUED)
                .itemsListed(1)
                .listingComplete(true)
                .captureDecision(json.createObjectNode())
                .method(method())
                .build());
        reviewItems.saveAndFlush(ReviewItem.builder()
                .workspaceId(s.workspace().getId())
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .itemId(s.item().getId())
                .itemVersion(review.getItemVersion())
                .state(ReviewItemState.WITHHELD)
                .reason("OCCASION_EVIDENCE_UNAVAILABLE")
                .detail(json.createObjectNode())
                .build());
    }

    private ReviewMethod method() {
        var slots = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, null),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of())));
        String digest = jdbc.sql("SELECT review_model_digest(CAST(:models AS jsonb))")
                .param("models", json.writeValueAsString(slots))
                .query(String.class)
                .single();
        return new ReviewMethod(1, digest, slots);
    }

    @Transactional
    public ResultMark mark(Result result, ResultMarkKind kind, long account) {
        return marks.saveAndFlush(ResultMark.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .resultId(result.getId())
                .kind(kind)
                .reason("Recorded review decision")
                .createdById(account)
                .createdAt(result.getRecordedAt())
                .build());
    }

    @Transactional
    public UUID job(Result result) {
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,run_id,processing_status,trace_id,input,config_snapshot,submitted_at,started_at,completed_at)
            VALUES(:id,:review,:ws,'COMPLETED',:run,'DONE',:trace,CAST(:input AS jsonb),'{}'::jsonb,:at,:at,:at)
            """)
                .param("id", id)
                .param(
                        "run",
                        RuntimeJobTestData.insertRun(
                                jdbc, result.getWorkspaceId(), result.getReviewId(), "COMPLETED", 1))
                .param("review", result.getReviewId())
                .param("ws", result.getWorkspaceId())
                .param("trace", id.toString().replace("-", ""))
                .param("input", jobInput(result))
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        return id;
    }

    @Transactional
    public void reviewStatus(Result result, ReviewStatus status) {
        var review = reviews.findByIdAndWorkspaceId(result.getReviewId(), result.getWorkspaceId())
                .orElseThrow();
        review.setStatus(status);
        review.setCompletedAt(status.isTerminal() ? result.getRecordedAt() : null);
        review.setStopReason(
                switch (status) {
                    case QUEUED, RUNNING, COMPLETE -> null;
                    case PARTIAL -> ReviewStopReason.EXECUTION_INCOMPLETE;
                    case FAILED -> ReviewStopReason.EXECUTION_FAILED;
                    case CANCELLED -> ReviewStopReason.CANCELLED;
                });
        reviews.saveAndFlush(review);
        if (!status.isTerminal()) activeJob(result, status.name(), null);
    }

    /** An unfinished job of the result's review: QUEUED (waiting, or held by {@code hold}) or RUNNING. */
    @Transactional
    public UUID activeJob(Result result, String status, @Nullable AgentRunHold hold) {
        var id = UUID.randomUUID();
        UUID run = RuntimeJobTestData.insertRun(jdbc, result.getWorkspaceId(), result.getReviewId(), status, 0);
        if (hold != null)
            jdbc.sql("UPDATE agent_run SET hold_reason=:hold WHERE id=:run AND state='QUEUED'")
                    .param("hold", hold.name())
                    .param("run", run)
                    .update();
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,run_id,processing_status,trace_id,input,config_snapshot,submitted_at,started_at)
            VALUES(:id,:review,:ws,:status,:run,'NONE',:trace,CAST(:input AS jsonb),'{}'::jsonb,:at,:started)
            """)
                .param("id", id)
                .param("run", run)
                .param("review", result.getReviewId())
                .param("ws", result.getWorkspaceId())
                .param("status", status)
                .param("trace", id.toString().replace("-", ""))
                .param("input", jobInput(result))
                .param("at", Timestamp.from(result.getRecordedAt()))
                .param(
                        "started",
                        "RUNNING".equals(status) ? Timestamp.from(result.getRecordedAt()) : null,
                        Types.TIMESTAMP)
                .update();
        return id;
    }

    /** The job input the worker reads: the review's item and its one assignment. */
    private String jobInput(Result result) {
        return json.writeValueAsString(Map.of(
                "itemId",
                reviews.findByIdAndWorkspaceId(result.getReviewId(), result.getWorkspaceId())
                        .orElseThrow()
                        .getItemId(),
                "assignments",
                List.of(new ReviewAssignmentId(result.getReviewId(), result.getPracticeId(), result.getItemId()))));
    }

    @Transactional
    public void end(Result result, Instant at) {
        var saved = results.findByIdAndWorkspaceId(result.getId(), result.getWorkspaceId())
                .orElseThrow();
        saved.end(ResultEndReason.REPLACED, at);
        results.saveAndFlush(saved);
    }

    public static String memberToken(Scenario scenario) {
        return "mock-jwt-member-" + scenario.account().getId();
    }
}
