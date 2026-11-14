package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewScheduleDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewScheduleInputDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewScheduleService {
    private final ReviewScheduleRepository schedules;
    private final ReviewBatchService batches;
    private final ProductReferenceQuery references;
    private final ConfigAuditPort audit;
    private final Clock clock;
    private final ReviewBatchRepository batchRows;

    public ReviewSchedule require(long ws, UUID id) {
        return schedules
                .findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Review schedule", id.toString()));
    }

    public List<ReviewScheduleDTO> list(WorkspaceContext ws) {
        return schedules.findByWorkspaceIdOrderByKindAsc(ws.id()).stream()
                .map(this::dto)
                .toList();
    }

    @Transactional
    public ReviewScheduleDTO create(WorkspaceContext ws, ReviewScheduleInputDTO input) {
        validate(input);
        if (schedules.existsByWorkspaceIdAndKind(ws.id(), input.itemKind()))
            throw new ReviewScheduleConflictException("A schedule for this kind already exists.");
        var now = clock.instant();
        var schedule = ReviewSchedule.builder()
                .id(UUID.randomUUID())
                .workspaceId(ws.id())
                .kind(input.itemKind())
                .cadence(input.cadence())
                .lookbackDays(input.lookbackDays())
                .budgetUsd(budget(input.budgetUsd()))
                .enabled(input.enabled())
                .nextRunAt(now.plus(input.cadence().interval()))
                .createdByAccountId(SecurityUtils.getCurrentAccountId().orElseThrow())
                .createdAt(now)
                .updatedAt(now)
                .build();
        schedules.saveAndFlush(schedule);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.REVIEW_SCHEDULE, schedule.getId(), ws.id(), ReviewScheduleSnapshot.of(schedule)));
        return dto(schedule);
    }

    @Transactional
    public ReviewScheduleDTO replace(
            WorkspaceContext ws, UUID id, ReviewScheduleInputDTO input, @Nullable EntityTagPrecondition precondition) {
        validate(input);
        var schedule = require(ws.id(), id);
        ReviewBatchService.checkVersion(schedule.getVersion(), precondition);
        if (!schedule.getKind().equals(input.itemKind()))
            throw new InvalidInputException("The schedule's item kind cannot change.");
        var before = ReviewScheduleSnapshot.of(schedule);
        schedule.setCadence(input.cadence());
        schedule.setLookbackDays(input.lookbackDays());
        schedule.setBudgetUsd(budget(input.budgetUsd()));
        schedule.setEnabled(input.enabled());
        schedule.setUpdatedAt(clock.instant());
        schedule.setNextRunAt(clock.instant().plus(input.cadence().interval()));
        schedules.saveAndFlush(schedule);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.REVIEW_SCHEDULE, id, ws.id(), before, ReviewScheduleSnapshot.of(schedule)));
        return dto(schedule);
    }

    @Transactional
    public ReviewScheduleDTO enabled(
            WorkspaceContext ws, UUID id, boolean enabled, @Nullable EntityTagPrecondition precondition) {
        var schedule = require(ws.id(), id);
        ReviewBatchService.checkVersion(schedule.getVersion(), precondition);
        var before = ReviewScheduleSnapshot.of(schedule);
        schedule.setEnabled(enabled);
        schedule.setUpdatedAt(clock.instant());
        if (enabled && schedule.getNextRunAt().isBefore(clock.instant()))
            schedule.setNextRunAt(clock.instant().plus(schedule.getCadence().interval()));
        schedules.saveAndFlush(schedule);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.REVIEW_SCHEDULE, id, ws.id(), before, ReviewScheduleSnapshot.of(schedule)));
        return dto(schedule);
    }

    @Transactional
    public void delete(WorkspaceContext ws, UUID id, @Nullable EntityTagPrecondition precondition) {
        var s = require(ws.id(), id);
        ReviewBatchService.checkVersion(s.getVersion(), precondition);
        if (batchRows.existsByWorkspaceIdAndScheduleId(ws.id(), id))
            throw new ReviewScheduleConflictException(
                    "This schedule has review history. Disable it to stop new reviews.");
        audit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.REVIEW_SCHEDULE, id, ws.id(), ReviewScheduleSnapshot.of(s)));
        schedules.delete(s);
    }

    private void validate(ReviewScheduleInputDTO input) {
        batches.requireKind(input.itemKind());
        if (input.itemKind().equals("scm.repository")) {
            if (input.lookbackDays() != null || input.budgetUsd() == null)
                throw new InvalidInputException("A repository schedule needs a scan budget and no lookback.");
            budget(input.budgetUsd());
        } else if (input.budgetUsd() != null
                || input.lookbackDays() == null
                || input.lookbackDays() < 1
                || input.lookbackDays() > input.cadence().maxLookback())
            throw new InvalidInputException("Choose a lookback within the schedule cadence and no scan budget.");
    }

    private static @Nullable BigDecimal budget(@Nullable String text) {
        if (text == null) return null;
        if (!text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?"))
            throw new InvalidInputException("Enter a decimal budget with at most four decimal places.");
        var value = new BigDecimal(text);
        if (value.scale() > 4
                || value.compareTo(new BigDecimal("0.0001")) < 0
                || value.compareTo(new BigDecimal("100000")) > 0)
            throw new InvalidInputException(
                    "The scan budget must be 0.0001 to 100000 USD with at most four decimal places.");
        return value;
    }

    public ReviewScheduleDTO dto(ReviewSchedule s) {
        return new ReviewScheduleDTO(
                s.getKind(),
                s.getCadence(),
                s.getLookbackDays(),
                s.isEnabled(),
                ReviewBatchService.decimal(s.getBudgetUsd()),
                s.getId(),
                Long.toString(s.getVersion()),
                s.getNextRunAt(),
                s.getLastRunAt(),
                s.getCreatedByAccountId() == null
                        ? null
                        : references
                                .accounts(s.getWorkspaceId(), List.of(s.getCreatedByAccountId()))
                                .get(s.getCreatedByAccountId()),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
