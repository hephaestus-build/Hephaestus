package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeTrustMeters;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkChanged;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ResultMarkService {

    private final ResultRepository results;
    private final ResultMarkRepository marks;
    private final ReviewRepository reviews;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PracticeTrustMeters trust;

    @Transactional(readOnly = true)
    public List<ResultMark> list(long workspaceId, UUID resultId) {
        requireResult(workspaceId, resultId);
        return marks.findByWorkspaceIdAndResultIdOrderByCreatedAtDescIdDesc(workspaceId, resultId);
    }

    @Transactional
    public ResultMark create(long workspaceId, UUID resultId, ResultMarkKind kind, long actorId, String reason) {
        String validated = reason(reason);
        requireActor(actorId);
        lockResult(workspaceId, resultId);
        if (marks.existsByWorkspaceIdAndResultIdAndKindAndRemovedAtIsNull(workspaceId, resultId, kind)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This result already has this mark.");
        }
        ResultMark mark = marks.save(ResultMark.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .resultId(resultId)
                .kind(kind)
                .reason(validated)
                .createdById(actorId)
                .createdAt(clock.instant())
                .build());
        events.publishEvent(
                new ResultMarkChanged(mark.getId(), workspaceId, resultId, kind, false, mark.getCreatedAt()));
        TransactionCallbacks.afterCommit(() -> trust.marked(kind, false));
        return mark;
    }

    @Transactional
    public ResultMark remove(long workspaceId, UUID resultId, UUID markId, long actorId, String reason) {
        String validated = reason(reason);
        requireActor(actorId);
        lockResult(workspaceId, resultId);
        ResultMark mark = marks.findByIdAndResultIdAndWorkspaceId(markId, resultId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ResultMark", markId.toString()));
        if (mark.getRemovedAt() != null) {
            return mark;
        }
        var at = clock.instant();
        mark.remove(actorId, validated, at);
        events.publishEvent(new ResultMarkChanged(markId, workspaceId, resultId, mark.getKind(), true, at));
        var kind = mark.getKind();
        TransactionCallbacks.afterCommit(() -> trust.marked(kind, true));
        return mark;
    }

    private Result requireResult(long workspaceId, UUID resultId) {
        return results.findByIdAndWorkspaceId(resultId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Result", resultId.toString()));
    }

    private Result lockResult(long workspaceId, UUID resultId) {
        Result result = requireResult(workspaceId, resultId);
        if (reviews.lockByIdAndWorkspaceId(result.getReviewId(), workspaceId).isEmpty())
            throw new EntityNotFoundException("Review", result.getReviewId().toString());
        return results.lockByIdAndWorkspaceId(resultId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Result", resultId.toString()));
    }

    private static String reason(String reason) {
        if (reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a reason of 1 to 500 characters.");
        }
        return reason.strip();
    }

    private static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("A mark requires an authenticated account");
        }
    }
}
