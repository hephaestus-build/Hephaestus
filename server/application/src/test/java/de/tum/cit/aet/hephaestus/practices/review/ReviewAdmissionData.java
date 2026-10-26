package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.OpenQuestion;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@TestComponent
@RequiredArgsConstructor
public class ReviewAdmissionData {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository practices;
    private final ReviewItemRepository items;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final PracticeVersionService versions;

    public record Pending(
            ReviewIntegrationData.Scenario scenario,
            Review review,
            UUID jobId,
            @Nullable Long developerId) {}

    @Transactional
    public Pending pending(ReviewIntegrationData.Scenario scenario, Instant orderedAt, @Nullable Long developerId) {
        return pending(scenario, orderedAt, developerId, "item-version-" + UUID.randomUUID());
    }

    @Transactional
    public Pending pending(
            ReviewIntegrationData.Scenario scenario,
            Instant orderedAt,
            @Nullable Long developerId,
            String itemVersion) {
        var now = Instant.now();
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(scenario.workspace().getId())
                .itemId(scenario.item().getId())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(scenario.account().getId())
                .status(ReviewStatus.RUNNING)
                .itemVersion(itemVersion)
                .createdAt(now)
                .orderedAt(orderedAt)
                .build());
        var slots = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, null),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of())));
        var digest = jdbc.sql("SELECT review_model_digest(CAST(:models AS jsonb))")
                .param("models", json.writeValueAsString(slots))
                .query(String.class)
                .single();
        practices.saveAndFlush(ReviewPractice.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(review.getId())
                .practiceId(scenario.practice().getId())
                .practiceVersionId(scenario.practice().getCurrentVersion().getId())
                .status(ReviewPracticeStatus.READY)
                .itemsListed(1)
                .listingComplete(true)
                .method(new ReviewMethod(1, digest, slots))
                .build());
        var job = UUID.randomUUID();
        var input = Map.of(
                "itemId",
                scenario.item().getId(),
                "assignments",
                List.of(new ReviewAssignmentId(
                        review.getId(),
                        scenario.practice().getId(),
                        scenario.item().getId())));
        jdbc.sql(
                        "INSERT INTO"
                                + " review_job(id,review_id,workspace_id,status,attempt,processing_status,trace_id,input,config_snapshot,submitted_at,available_at,started_at)"
                                + " VALUES(:id,:review,:ws,'RUNNING',0,'PENDING',:trace,CAST(:input AS jsonb),'{}'::jsonb,:at,:at,:at)")
                .param("id", job)
                .param("review", review.getId())
                .param("ws", review.getWorkspaceId())
                .param("trace", job.toString().replace("-", ""))
                .param("at", Timestamp.from(now))
                .param("input", json.writeValueAsString(input))
                .update();
        var detail = json.createObjectNode();
        if (developerId == null) detail.putNull("developerId");
        else detail.put("developerId", developerId);
        items.saveAndFlush(ReviewItem.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(review.getId())
                .practiceId(scenario.practice().getId())
                .itemId(scenario.item().getId())
                .itemVersion(review.getItemVersion())
                .jobId(job)
                .detail(detail)
                .build());
        return new Pending(scenario, review, job, developerId);
    }

    /** Adds another practice to the pending review, withheld for {@code reason} without running. */
    @Transactional
    public void withheld(Pending pending, String reason) {
        var scenario = pending.scenario();
        var practice = versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "s5-withheld-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                scenario.account().getId()));
        var pinned = practices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.practice().getId())
                .orElseThrow();
        practices.saveAndFlush(ReviewPractice.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(pending.review().getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(ReviewPracticeStatus.WITHHELD)
                .reason(reason)
                .itemsListed(1)
                .listingComplete(true)
                .method(pinned.getMethod())
                .build());
        items.saveAndFlush(ReviewItem.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(pending.review().getId())
                .practiceId(practice.getId())
                .itemId(scenario.item().getId())
                .itemVersion(pending.review().getItemVersion())
                .state(ReviewItemState.WITHHELD)
                .reason(reason)
                .detail(json.createObjectNode())
                .build());
    }

    /** Adds {@code practice} at its current version to the pending review and to its job, still listed. */
    @Transactional
    public void assigned(Pending pending, Practice practice) {
        var scenario = pending.scenario();
        var pinned = practices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.practice().getId())
                .orElseThrow();
        practices.saveAndFlush(ReviewPractice.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(pending.review().getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(ReviewPracticeStatus.READY)
                .itemsListed(1)
                .listingComplete(true)
                .method(pinned.getMethod())
                .build());
        jdbc.sql(
                        "UPDATE review_job SET input=jsonb_set(input,'{assignments}',input->'assignments' || CAST(:id AS jsonb))"
                                + " WHERE workspace_id=:ws AND id=:job")
                .param(
                        "id",
                        json.writeValueAsString(List.of(new ReviewAssignmentId(
                                pending.review().getId(),
                                practice.getId(),
                                scenario.item().getId()))))
                .param("ws", pending.review().getWorkspaceId())
                .param("job", pending.jobId())
                .update();
        var detail = json.createObjectNode();
        if (pending.developerId() == null) detail.putNull("developerId");
        else detail.put("developerId", pending.developerId());
        items.saveAndFlush(ReviewItem.builder()
                .workspaceId(scenario.workspace().getId())
                .reviewId(pending.review().getId())
                .practiceId(practice.getId())
                .itemId(scenario.item().getId())
                .itemVersion(pending.review().getItemVersion())
                .jobId(pending.jobId())
                .detail(detail)
                .build());
    }

    @Transactional
    public void running(Pending pending, int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("An attempt must be nonnegative.");
        jdbc.sql("UPDATE review_job SET status='RUNNING',attempt=:attempt WHERE workspace_id=:ws AND id=:id")
                .param("attempt", attempt)
                .param("ws", pending.review().getWorkspaceId())
                .param("id", pending.jobId())
                .update();
    }

    @Transactional
    public void removeRuntime(Pending pending) {
        jdbc.sql("UPDATE review_item SET job_id=NULL WHERE workspace_id=:ws AND job_id=:job")
                .param("ws", pending.review().getWorkspaceId())
                .param("job", pending.jobId())
                .update();
        jdbc.sql("DELETE FROM review_job WHERE workspace_id=:ws AND id=:job")
                .param("ws", pending.review().getWorkspaceId())
                .param("job", pending.jobId())
                .update();
    }

    public ResultCandidate candidate(Pending pending, ResultOutcome outcome) {
        long versionId = Objects.requireNonNull(
                pending.scenario().practice().getCurrentVersion().getId());
        var source = new SourceKind("scm.pull-request.diff");
        var digest = ProblemComparison.sha256("changed();");
        var citation = new EvidenceCitation(
                source,
                "inputs/sources/scm/diff.patch",
                "src/Main.java",
                CitationSide.NEW,
                "commit-1",
                1,
                1,
                "changed();",
                new CitationProof(CitationVerificationStatus.VERIFIED, digest, digest));
        var problem = new ProblemCandidate(
                0,
                "problem-1",
                Severity.MAJOR,
                "Check the changed branch",
                "The captured change introduces the problem.",
                List.of(citation),
                new IntroducedBasis(0, "The problem is introduced here."),
                "Correct the changed branch.");
        return new ResultCandidate(
                pending.scenario().practice().getId(),
                versionId,
                pending.scenario().item().getId(),
                Objects.requireNonNull(pending.review().getItemVersion()),
                pending.developerId(),
                outcome,
                "Recorded result",
                "The captured work supports this result.",
                new ResultEvidence(
                        List.of(citation),
                        null,
                        outcome == ResultOutcome.DOES_NOT_APPLY
                                ? new InapplicabilityWarrant(
                                        List.of(source),
                                        "This practice requires a matching change.",
                                        "The captured work has no matching change.")
                                : null,
                        outcome == ResultOutcome.COULD_NOT_DETERMINE
                                ? List.of(new OpenQuestion(
                                        "Does the changed branch cover this case?",
                                        "A focused execution would settle it.",
                                        null))
                                : List.of()),
                null,
                new HostCoverage(
                        true,
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(new ReadReceipt(
                                source,
                                "inputs/sources/scm/diff.patch",
                                "src/Main.java",
                                pending.review().getItemVersion(),
                                ReadReceiptStatus.COMPLETE,
                                digest,
                                1,
                                1))),
                outcome == ResultOutcome.NOT_MET ? List.of(problem) : List.of());
    }
}
