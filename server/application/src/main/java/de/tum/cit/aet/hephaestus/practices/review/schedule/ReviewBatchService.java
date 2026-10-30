package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLister;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.CreateReviewBatchRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewBatchDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.UpdateReviewBatchStatusRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewBatchService {
    private final ReviewBatchRepository batches;
    private final ReviewBatchTargetRepository targets;
    private final ReviewBatchScopeQuery scope;
    private final ReviewBatchCostEstimator costs;
    private final ReviewBatchProperties properties;
    private final ReviewRepository reviews;
    private final ProductReferenceQuery references;
    private final ItemDescriptorRegistry catalog;
    private final List<ItemLister> listers;
    private final Clock clock;
    private final ConfigAuditPort audit;
    private final ReviewService reviewService;

    public ReviewBatch require(long ws, UUID id) {
        return batches.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Review batch", id.toString()));
    }

    @Transactional
    public ReviewBatchDTO create(WorkspaceContext context, CreateReviewBatchRequestDTO request) {
        var descriptor = requireKind(request.itemKind());
        // A scan enumerates its root through a lister. Graph members, such as a pull request's reviews, do not.
        if (listers.stream().anyMatch(lister -> lister.containerKind().equals(descriptor.kind())))
            throw new IllegalArgumentException("Use the scan API to review a repository.");
        validateWindow(request.from(), request.to());
        if (request.budgetUsd() != null)
            throw new IllegalArgumentException("A dated-work batch does not take a scan budget.");
        var selected =
                scope.select(context.id(), request.itemKind(), request.from(), request.to(), properties.maxItems() + 1);
        if (selected.size() > properties.maxItems())
            throw new IllegalArgumentException("The selected batch is too large.");
        var now = clock.instant();
        var batch = ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(context.id())
                .kind(request.itemKind())
                .origin(ReviewBatchOrigin.BACKFILL)
                .fromAt(request.from())
                .toAt(request.to())
                .estimatedItems(selected.size())
                .estimatedCostUsd(costs.estimateTotalUsd(context.id(), request.itemKind(), selected.size()))
                .requestedByAccountId(SecurityUtils.getCurrentAccountId().orElseThrow())
                .createdAt(now)
                .updatedAt(now)
                .build();
        batches.saveAndFlush(batch);
        freezeTargets(batch, selected);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.REVIEW_BATCH, batch.getId(), context.id(), ReviewBatchSnapshot.of(batch)));
        return dto(batch);
    }

    public ItemDescriptor requireKind(String kind) {
        return catalog.descriptorFor(new ItemKind(kind))
                .filter(ItemDescriptor::reviewable)
                .orElseThrow(() -> new IllegalArgumentException("Choose a reviewable item kind."));
    }

    private void validateWindow(@Nullable Instant from, @Nullable Instant to) {
        if (from == null
                || to == null
                || !from.isBefore(to)
                || Duration.between(from, to).compareTo(properties.maxWindow()) > 0)
            throw new IllegalArgumentException("Choose a valid review window within the batch limit.");
    }

    public void freezeTargets(ReviewBatch batch, List<Long> ids) {
        targets.saveAll(ids.stream()
                .map(id -> new ReviewBatchTarget(batch.getId(), id, batch.getWorkspaceId()))
                .toList());
    }

    @Transactional
    public ReviewBatchDTO updateStatus(
            WorkspaceContext context,
            UUID id,
            UpdateReviewBatchStatusRequestDTO request,
            @Nullable EntityTagPrecondition precondition) {
        var batch = require(context.id(), id);
        checkVersion(batch.getVersion(), precondition);
        var before = ReviewBatchSnapshot.of(batch);
        var next = request.status();
        if (next != ReviewBatchStatus.RUNNING
                && next != ReviewBatchStatus.PAUSED
                && next != ReviewBatchStatus.CANCELLED)
            throw new IllegalArgumentException("Choose running, paused or cancelled.");
        if (batch.getStatus() == ReviewBatchStatus.COMPLETED || batch.getStatus() == ReviewBatchStatus.CANCELLED)
            throw new ReviewBatchConflictException("This batch has ended.");
        if (next == ReviewBatchStatus.PAUSED || next == ReviewBatchStatus.CANCELLED) {
            for (var review : reviews.findByWorkspaceIdAndBatchIdOrderByCreatedAtAsc(context.id(), id)) {
                if (review.getKind() == ReviewKind.SCAN && !review.getStatus().isTerminal()) {
                    if (next == ReviewBatchStatus.PAUSED)
                        batch.setCursorItemId(
                                targets.findByWorkspaceIdAndBatchIdOrderByItemIdAsc(context.id(), id).stream()
                                        .map(ReviewBatchTarget::getItemId)
                                        .filter(item -> item < review.getItemId())
                                        .max(Long::compareTo)
                                        .orElse(null));
                    reviewService.cancel(context.id(), review.getId(), "BATCH_" + next.name());
                }
            }
        }
        batch.setStatus(next);
        batch.setPauseReason(next == ReviewBatchStatus.PAUSED ? ReviewBatchPauseReason.PAUSED_BY_ADMIN : null);
        batch.setUpdatedAt(clock.instant());
        if (next == ReviewBatchStatus.RUNNING) {
            if (batch.getStartedAt() == null) batch.setStartedAt(clock.instant());
            batch.setConfirmedByAccountId(SecurityUtils.getCurrentAccountId().orElseThrow());
        }
        if (next == ReviewBatchStatus.CANCELLED) batch.setFinishedAt(clock.instant());
        batches.saveAndFlush(batch);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.REVIEW_BATCH, id, context.id(), before, ReviewBatchSnapshot.of(batch)));
        return dto(batch);
    }

    @Transactional(readOnly = true)
    public Page<ReviewBatchDTO> list(WorkspaceContext context, Pageable page) {
        return batches.findByWorkspaceId(context.id(), page).map(this::dto);
    }

    public ReviewBatchDTO dto(ReviewBatch batch) {
        var ids = Stream.of(batch.getRequestedByAccountId(), batch.getConfirmedByAccountId())
                .filter(Objects::nonNull)
                .toList();
        var people = references.accounts(batch.getWorkspaceId(), ids);
        var requestedBy = batch.getRequestedByAccountId();
        var confirmedBy = batch.getConfirmedByAccountId();
        return new ReviewBatchDTO(
                batch.getId(),
                Long.toString(batch.getVersion()),
                batch.getKind(),
                batch.getOrigin(),
                batch.getScheduleId(),
                batch.getFromAt(),
                batch.getToAt(),
                batch.getStatus(),
                batch.getPauseReason(),
                decimal(batch.getBudgetUsd()),
                batch.getEstimatedItems(),
                decimal(batch.getEstimatedCostUsd()),
                batch.getSubmittedCount(),
                batch.getSkippedCount(),
                batch.getFailedCount(),
                requestedBy == null ? null : people.get(requestedBy),
                confirmedBy == null ? null : people.get(confirmedBy),
                batch.getCreatedAt(),
                batch.getStartedAt(),
                batch.getFinishedAt(),
                EntityTagPrecondition.format(Long.toString(batch.getVersion())),
                reviews.findByWorkspaceIdAndBatchIdOrderByCreatedAtAsc(batch.getWorkspaceId(), batch.getId()).stream()
                        .map(r -> r.getId())
                        .toList(),
                targets.findByWorkspaceIdAndBatchIdOrderByItemIdAsc(batch.getWorkspaceId(), batch.getId()).stream()
                        .map(t -> Long.toString(t.getItemId()))
                        .toList());
    }

    static void checkVersion(long version, @Nullable EntityTagPrecondition precondition) {
        if (precondition == null)
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Send If-Match for this update.");
        if (!precondition.matches(Long.toString(version)))
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "The record changed. Reload it before this update.");
    }

    static @Nullable String decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
