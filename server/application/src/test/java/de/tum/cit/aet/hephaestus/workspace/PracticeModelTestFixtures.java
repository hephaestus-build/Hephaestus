package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeModelTestFixtures {
    private final PracticeVersionService versions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @PersistenceContext
    private @Nullable EntityManager entities;

    public record Work(Practice practice, Item item, Review review, ReviewJob job) {}

    @Transactional
    public Work work(Workspace workspace, String slug, String kind, long sourceId) {
        var manager = Objects.requireNonNull(entities);
        var itemKind = new ItemKind(kind);
        var definition = DefinitionTestFixtures.withLineNotes(
                DefinitionTestFixtures.withReviewOn(
                        DefinitionTestFixtures.definition(itemKind, slug, "Check the retained fixture work.", null),
                        new ReviewOn(List.of(), Map.of())),
                false);
        var practice = versions.create(
                new PracticeVersionService.NewPractice(workspace, slug, itemKind, definition, null, 0, null));
        var item = new Item();
        item.setWorkspaceId(workspace.getId());
        item.setKind(itemKind);
        item.setSourceId(sourceId);
        item.setCreatedAt(Instant.now());
        manager.persist(item);
        manager.flush();
        var now = Instant.now();
        var review = Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .itemId(Objects.requireNonNull(item.getId()))
                .itemVersion("fixture:1")
                .origin(ReviewOrigin.REQUEST)
                .status(ReviewStatus.RUNNING)
                .orderedAt(now)
                .createdAt(now)
                .build();
        manager.persist(review);
        var models = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, null),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot(
                        "review", ModelKind.CHAT, new FrozenModel("fixture", "OPENAI", "fixture", null, Map.of())));
        String digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT review_model_digest(CAST(? AS jsonb))", String.class, mapper.writeValueAsString(models)));
        manager.persist(ReviewPractice.builder()
                .workspaceId(workspace.getId())
                .reviewId(review.getId())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(ReviewPracticeStatus.READY)
                .method(new ReviewMethod(1, digest, models))
                .build());
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(review.getId());
        job.setInput(new ReviewJobInput(
                Objects.requireNonNull(item.getId()),
                List.of(new ReviewAssignmentId(
                        review.getId(), practice.getId(), Objects.requireNonNull(item.getId())))));
        job.setConfigSnapshot(mapper.valueToTree(Map.of("model", "fixture")));
        job.setAvailableAt(now);
        job.setTraceId(job.getId().toString().replace("-", ""));
        manager.persist(job);
        manager.flush();
        return new Work(practice, item, review, job);
    }

    @Transactional(readOnly = true)
    public Work forJob(ReviewJob job) {
        var manager = Objects.requireNonNull(entities);
        var input = Objects.requireNonNull(job.getInput());
        var assignment = input.assignments().getFirst();
        return new Work(
                Objects.requireNonNull(manager.find(Practice.class, assignment.practiceId())),
                Objects.requireNonNull(manager.find(Item.class, assignment.itemId())),
                Objects.requireNonNull(manager.find(Review.class, assignment.reviewId())),
                job);
    }

    @Transactional
    public Work forkReview(Work work) {
        var manager = Objects.requireNonNull(entities);
        var now = Instant.now();
        var review = Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(work.review().getWorkspaceId())
                .itemId(work.review().getItemId())
                .itemVersion(work.review().getItemVersion())
                .origin(ReviewOrigin.REQUEST)
                .status(ReviewStatus.RUNNING)
                .orderedAt(now)
                .createdAt(now)
                .build();
        manager.persist(review);
        var original = Objects.requireNonNull(manager.find(
                ReviewPractice.class,
                new ReviewPractice.Id(work.review().getId(), work.practice().getId())));
        manager.persist(ReviewPractice.builder()
                .workspaceId(review.getWorkspaceId())
                .reviewId(review.getId())
                .practiceId(work.practice().getId())
                .practiceVersionId(original.getPracticeVersionId())
                .status(ReviewPracticeStatus.READY)
                .method(original.getMethod())
                .build());
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(review.getWorkspaceId());
        job.setReviewId(review.getId());
        job.setInput(new ReviewJobInput(
                Objects.requireNonNull(work.item().getId()),
                List.of(new ReviewAssignmentId(
                        review.getId(),
                        work.practice().getId(),
                        Objects.requireNonNull(work.item().getId())))));
        job.setConfigSnapshot(work.job().getConfigSnapshot());
        job.setAvailableAt(now);
        job.setTraceId(job.getId().toString().replace("-", ""));
        manager.persist(job);
        manager.flush();
        return new Work(work.practice(), work.item(), review, job);
    }

    @Transactional
    public Result result(Work work, long developerId, String summary) {
        return result(work, developerId, summary, null);
    }

    @Transactional
    public Result resultWithProblem(Work work, long developerId, String summary, String problemText) {
        return result(work, developerId, summary, problemText);
    }

    private Result result(Work work, long developerId, String summary, @Nullable String problemText) {
        var manager = Objects.requireNonNull(entities);
        long existing = manager.createQuery(
                        "SELECT count(r) FROM Result r WHERE r.reviewId=:review AND"
                                + " r.practiceId=:practice AND r.itemId=:item",
                        Long.class)
                .setParameter("review", work.review().getId())
                .setParameter("practice", work.practice().getId())
                .setParameter("item", work.item().getId())
                .getSingleResult();
        if (existing != 0) work = forkReview(work);
        jdbc.update(
                "INSERT INTO"
                        + " membership(workspace_id,account_id,role,hidden,review_selected,public_activity_seen,joined_at)"
                        + " VALUES (?,?,'MEMBER',false,false,false,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
                work.review().getWorkspaceId(),
                developerId);
        var at = Instant.now();
        var assignment = Objects.requireNonNull(manager.find(
                ReviewPractice.class,
                new ReviewPractice.Id(work.review().getId(), work.practice().getId())));
        var frozen = manager.find(
                ReviewItem.class,
                new ReviewItem.Id(
                        work.review().getId(),
                        work.practice().getId(),
                        Objects.requireNonNull(work.item().getId())));
        if (frozen == null) {
            frozen = ReviewItem.builder()
                    .workspaceId(work.review().getWorkspaceId())
                    .reviewId(work.review().getId())
                    .practiceId(work.practice().getId())
                    .itemId(Objects.requireNonNull(work.item().getId()))
                    .itemVersion(work.review().getItemVersion())
                    .state(ReviewItemState.LISTED)
                    .jobId(work.job().getId())
                    .build();
            manager.persist(frozen);
        }
        manager.flush();
        jdbc.update(
                """
                UPDATE result SET ended_at=?, end_reason=?
                WHERE workspace_id=? AND practice_id=? AND item_id=? AND developer_id=? AND ended_at IS NULL
                """,
                Timestamp.from(at),
                ResultEndReason.REPLACED.name(),
                work.review().getWorkspaceId(),
                work.practice().getId(),
                work.item().getId(),
                developerId);
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(work.review().getWorkspaceId())
                .reviewId(work.review().getId())
                .practiceId(work.practice().getId())
                .practiceVersionId(assignment.getPracticeVersionId())
                .itemId(Objects.requireNonNull(work.item().getId()))
                .itemVersion(Objects.requireNonNull(frozen.getItemVersion()))
                .developerId(developerId)
                .outcome(problemText == null ? ResultOutcome.MET : ResultOutcome.NOT_MET)
                .summary(summary)
                .coverage(mapper.valueToTree(new HostCoverage(true, null, 0, 0, 0, List.of())))
                .evidence(mapper.valueToTree(new ResultEvidence(List.of(), null, null, List.of())))
                .recordedAt(Instant.now())
                .build();
        manager.persist(result);
        manager.flush();
        if (problemText != null) {
            jdbc.update(
                    """
                    INSERT INTO problem(id,workspace_id,result_id,position,severity,summary,explanation,citations,basis)
                    VALUES (?,?,?,0,'MINOR',?,?,?::jsonb,?::jsonb)
                    """,
                    UUID.randomUUID(),
                    result.getWorkspaceId(),
                    result.getId(),
                    problemText,
                    problemText,
                    "[{\"path\":\"context/conversation.json\",\"startLine\":1,\"endLine\":1}]",
                    mapper.writeValueAsString(Map.of(
                            "type",
                            "duty",
                            "criterionQuote",
                            "Cover failures",
                            "event",
                            0,
                            "explanation",
                            problemText)));
        }
        frozen.setState(ReviewItemState.JUDGED);
        frozen.setResultId(result.getId());
        frozen.setAttemptedAt(Instant.now());
        assignment.setStatus(ReviewPracticeStatus.DONE);
        work.job().setStatus(ReviewJobStatus.COMPLETED);
        work.job().setProcessingStatus(ProcessingStatus.DONE);
        work.job().setCompletedAt(Instant.now());
        manager.merge(work.job());
        work.review().setStatus(ReviewStatus.COMPLETE);
        work.review().setAdmittedAt(result.getRecordedAt());
        work.review().setCompletedAt(Instant.now());
        manager.merge(work.review());
        manager.flush();
        return result;
    }

    @Transactional
    public Feedback feedback(Result result, FeedbackDestination destination, FeedbackState state, String body) {
        return feedback(result, destination, state, body, Instant.now());
    }

    @Transactional
    public Feedback feedback(
            Result result, FeedbackDestination destination, FeedbackState state, String body, Instant now) {
        var manager = Objects.requireNonNull(entities);
        var feedback = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .reviewId(result.getReviewId())
                .itemId(result.getItemId())
                .developerId(Objects.requireNonNull(result.getDeveloperId()))
                .destination(destination)
                .state(state)
                .body(body)
                .createdAt(now)
                .deliveredAt(state == FeedbackState.DELIVERED ? now : null)
                .build();
        manager.persist(feedback);
        manager.persist(FeedbackBasis.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .feedbackId(feedback.getId())
                .resultId(result.getId())
                .developerId(Objects.requireNonNull(result.getDeveloperId()))
                .ordinal(0)
                .build());
        manager.flush();
        return feedback;
    }
}
