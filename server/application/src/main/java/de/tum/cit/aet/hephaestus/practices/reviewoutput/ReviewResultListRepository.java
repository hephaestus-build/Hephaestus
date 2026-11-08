package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ReviewResultListRepository extends Repository<Result, UUID> {
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultListRepository$MarkTime(
            m.resultId, m.kind, m.createdAt)
        FROM ResultMark m WHERE m.workspaceId = :workspaceId AND m.resultId IN :ids AND m.removedAt IS NULL
        """)
    List<MarkTime> markTimes(long workspaceId, Collection<UUID> ids);

    record MarkTime(UUID resultId, ResultMarkKind kind, Instant createdAt) {}

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultListRepository$ProblemCount(
            p.resultId, COUNT(p))
        FROM Problem p WHERE p.workspaceId = :workspaceId AND p.resultId IN :ids GROUP BY p.resultId
        """)
    List<ProblemCount> problemCounts(long workspaceId, Collection<UUID> ids);

    record ProblemCount(UUID resultId, long count) {}

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultListRepository$ProblemSummary(
            p.resultId, p.id, p.severity, p.summary)
        FROM Problem p WHERE p.workspaceId = :workspaceId AND p.resultId IN :ids
        ORDER BY p.resultId, p.position
        """)
    List<ProblemSummary> problemSummaries(long workspaceId, Collection<UUID> ids);

    record ProblemSummary(UUID resultId, UUID id, Severity severity, String summary) {}

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultListRepository$FeedbackCount(
            b.resultId, f.state, COUNT(DISTINCT f.id))
        FROM Feedback f JOIN FeedbackBasis b ON b.feedbackId = f.id AND b.workspaceId = f.workspaceId
        WHERE f.workspaceId = :workspaceId AND b.resultId IN :ids GROUP BY b.resultId, f.state
        """)
    List<FeedbackCount> feedbackCounts(long workspaceId, Collection<UUID> ids);

    record FeedbackCount(UUID resultId, FeedbackState state, long count) {}
}
