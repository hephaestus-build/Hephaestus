package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackCopyReader;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackReadbackObservedEvent;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads the provider outside a transaction, then records its facts under the existing feedback custody. */
@Slf4j
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackProviderReadback {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackTargetResolver targets;
    private final List<FeedbackCopyReader> readers;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final PersonDataWriteFence privacy;
    private final FeedbackSendRepository sends;

    public void capture(long workspaceId, UUID feedbackId, String owner) {
        var row = feedback.findByIdAndWorkspaceId(feedbackId, workspaceId).orElse(null);
        if (row == null) return;
        var children = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId);
        if (children.isEmpty()
                || children.stream()
                        .anyMatch(copy -> copy.getState() != FeedbackCopyState.POSTED
                                || copy.getExternalRef() == null
                                || copy.getWriteMarker() == null)) return;
        var requested = children.stream()
                .map(copy -> new CopyReadRequest(
                        copy.getId(),
                        Objects.requireNonNull(copy.getExternalRef()),
                        copy.getThreadRef(),
                        Objects.requireNonNull(copy.getWriteMarker()),
                        copy.getPath() == null
                                ? null
                                : new FeedbackAnchor(
                                        copy.getPath(), copy.getStartLine(), copy.getEndLine(), copy.getSide())))
                .toList();
        List<CopyReadback> observed = requested.stream()
                .map(copy -> CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE))
                .toList();
        try {
            var target = targets.resolveExisting(row);
            var matching = readers.stream()
                    .filter(reader -> reader.kind() == target.integration().kind())
                    .toList();
            if (matching.size() == 1) {
                var response = matching.getFirst().read(target, requested);
                if (response.size() == requested.size()
                        && response.stream()
                                        .map(CopyReadback::copyId)
                                        .distinct()
                                        .count()
                                == requested.size()
                        && requested.stream()
                                .map(CopyReadRequest::copyId)
                                .toList()
                                .containsAll(response.stream()
                                        .map(CopyReadback::copyId)
                                        .toList())) observed = response;
            }
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Provider readback is unavailable: workspaceId={}, feedbackId={}, cause={}",
                    workspaceId,
                    feedbackId,
                    unavailable.getClass().getSimpleName());
        }
        var snapshots = observed;
        if (observed.stream()
                        .mapToLong(
                                copy -> (copy.body() == null ? 0 : copy.body().length())
                                        + (copy.renderedBody() == null
                                                ? 0
                                                : copy.renderedBody().length()))
                        .sum()
                > 524_288)
            snapshots = requested.stream()
                    .map(copy -> CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE))
                    .toList();
        var retained = snapshots;
        transactions.executeWithoutResult(status -> {
            if (!privacy.holdForAccountWrite(row.getDeveloperId())) return;
            var locked =
                    feedback.lockByIdAndWorkspaceId(feedbackId, workspaceId).orElse(null);
            if (locked == null || locked.getDeveloperId() != row.getDeveloperId()) return;
            var send = sends.lockByFeedbackId(workspaceId, feedbackId);
            if (send.filter(attempt -> attempt.getState() == FeedbackSendState.SENDING
                            && owner.equals(attempt.getLeaseOwner())
                            && attempt.getLeaseExpiresAt() != null
                            && attempt.getLeaseExpiresAt().isAfter(clock.instant()))
                    .isEmpty()) return;
            var state = (ObjectNode) locked.getComposition().deepCopy();
            if (retained.stream()
                    .anyMatch(copy -> copy.resolved() || copy.status() == CopyReadback.Status.HUMAN_EDITED))
                state.put("humanControlled", true);
            state.set("readback", mapper.valueToTree(retained));
            state.put("readAt", clock.instant().toString());
            feedback.updateComposition(workspaceId, feedbackId, state);
            events.publishEvent(new FeedbackReadbackObservedEvent(workspaceId, locked.getReviewId()));
        });
    }
}
