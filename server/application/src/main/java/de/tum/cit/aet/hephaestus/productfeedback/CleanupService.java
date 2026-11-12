package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.auth.spi.ProductFeedbackAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackCleanupActionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackCleanupNeedsActionEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scrubs the issues of erased reports, as outbox actions ({@link CleanupIssueAdapter}). A public issue first moves to the private repository when one can take it; then
 * its title and body are replaced and it is closed. The app cannot delete an issue or its edit history, so each scrubbed
 * issue, and each record that has not finished after {@link #MANUAL_AFTER}, becomes a manual step: the admins get an
 * email, Needs action shows the locator, and the record stays until an admin confirms the step.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class CleanupService {
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    static final String ERASED = "Erased at the reporter's request";
    static final Duration MANUAL_AFTER = Duration.ofDays(30);

    private final ProductFeedbackCleanupRepository cleanups;
    private final DestinationSettings destinations;
    private final ProductFeedbackIssues issues;
    private final ProductFeedbackIssueUpdates updates;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final ApplicationEventPublisher events;
    private final ProductFeedbackAudit audit;
    private final Clock clock;

    CleanupService(
            ProductFeedbackCleanupRepository cleanups,
            DestinationSettings destinations,
            ProductFeedbackIssues issues,
            ProductFeedbackIssueUpdates updates,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            ApplicationEventPublisher events,
            ProductFeedbackAudit audit,
            Clock clock) {
        this.cleanups = cleanups;
        this.destinations = destinations;
        this.issues = issues;
        this.updates = updates;
        this.transactions = transactions;
        this.meters = meters;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    private record Job(
            UUID id,
            ProductFeedbackIssues.Repository repository,
            UUID marker,
            @Nullable Long number,
            @Nullable String nodeId,
            boolean publicIssue,
            Instant since,
            Instant createdAt) {}

    /**
     * Announces every step that now waits for a maintainer in one email. One erasure's records end together, so they
     * arrive in one list. The scrubs themselves are outbox actions ({@link CleanupIssueAdapter}).
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
    @SchedulerLock(name = "product-feedback-cleanup", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    void announceDue() {
        transactions.executeWithoutResult(status -> {
            List<UUID> waiting = cleanups.unannounced();
            if (waiting.isEmpty()) return;
            Instant now = clock.instant();
            cleanups.markAnnounced(waiting, now);
            events.publishEvent(new ProductFeedbackCleanupNeedsActionEvent(waiting, now));
        });
    }

    /** One attempt of a record's scrub. A record that is gone, or already waits for a maintainer, needs nothing. */
    ActionResult clean(ActionAttempt attempt, UUID recordId) {
        Job job = transactions.execute(status -> cleanups.findById(recordId)
                .filter(row -> row.getManualSince() == null)
                .map(row -> new Job(
                        row.getId(),
                        new ProductFeedbackIssues.Repository(
                                row.getRepositoryId(),
                                "",
                                row.getRepository(),
                                row.getInstallationId(),
                                !row.isPublicIssue()),
                        row.getMarker(),
                        row.getIssueNumber(),
                        row.getIssueNodeId(),
                        row.isPublicIssue(),
                        row.getSince(),
                        row.getCreatedAt()))
                .orElse(null));
        if (job == null) return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        // An attempt after a failed write keeps rereading, and expiry ends only an action that waits: the deadline
        // holds here too, so the record goes to a maintainer in time.
        if (!job.createdAt().plus(MANUAL_AFTER).isAfter(clock.instant()))
            return new ActionResult.Cancelled(ActionStopReason.EXPIRED);
        boolean fenced = false;
        try {
            Long number = job.number();
            String nodeId = job.nodeId();
            if (number == null || nodeId == null) {
                var found = findIssue(job.repository(), number, job);
                if (found == null && number == null) {
                    // A complete read found no issue: the create never reached GitHub, so nothing is left to scrub.
                    done(job.id());
                    return new ActionResult.Done(null);
                }
                if (found != null) {
                    // Kept before the scrub removes the marker, so a later attempt can still move the issue.
                    number = found.number();
                    nodeId = found.nodeId();
                    remember(job.id(), job.repository(), number, nodeId, job.publicIssue());
                }
            }
            long issueNumber = Objects.requireNonNull(number);
            var repository = job.repository();
            boolean moved = !job.publicIssue();
            ProductFeedbackIssues.Repository target = null;
            if (job.publicIssue() && nodeId != null) {
                String privateName = destinations.current().getPrivateRepository();
                target = privateName == null
                        ? null
                        : issues.repository(privateName)
                                .filter(ProductFeedbackIssues.Repository::isPrivate)
                                .orElse(null);
            }
            if (target != null && attempt.writeStarted()) {
                // An earlier attempt may have moved the issue: its marker is still in it, since no edit ran yet.
                var transferred = findIssue(target, null, job);
                if (transferred != null) {
                    remember(job.id(), target, transferred.number(), transferred.nodeId(), false);
                    repository = target;
                    issueNumber = transferred.number();
                    target = null;
                    moved = true;
                }
            }
            if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
            fenced = true;
            if (target != null) {
                var transferred = updates.transfer(
                        new ProductFeedbackIssues.Issue(issueNumber, Objects.requireNonNull(nodeId), ""), target);
                remember(job.id(), target, transferred.number(), transferred.nodeId(), false);
                repository = target;
                issueNumber = transferred.number();
                moved = true;
            }
            // Both are safe to repeat: an attempt after an unclear answer writes the same text and closes again.
            updates.edit(repository, issueNumber, ERASED, ERASED + ".");
            updates.close(repository, issueNumber, "not_planned");
            if (!moved) {
                // No private repository took it: the scrubbed issue stays public, and so does its edit history.
                log.warn("Product feedback cleanup scrubbed a public issue it could not move: cleanupId={}", job.id());
            }
            // The text is gone, but the edit history still shows it; only a maintainer can delete that.
            handOver(job.id());
            meters.step("cleanup", "ok");
            return new ActionResult.Done(null);
        } catch (ProductFeedbackIssues.Refused refused) {
            // GitHub refuses the step for good: retrying cannot finish it, so a maintainer takes over now.
            meters.step("cleanup", "refused");
            return new ActionResult.Failed(ActionStopReason.REJECTED);
        } catch (RuntimeException failed) {
            meters.step("cleanup", "retried");
            log.warn(
                    "Product feedback cleanup failed: cleanupId={}, error={}",
                    job.id(),
                    failed.getClass().getSimpleName());
            return fenced || attempt.writeStarted()
                    ? new ActionResult.Reread(ActionExecutor.backoff(attempt.attempt()))
                    : new ActionResult.Retry(ActionExecutor.backoff(attempt.attempt()));
        }
    }

    /** A scrub that ends unfinished, after {@link #MANUAL_AFTER} or its attempts, goes to a maintainer. */
    void ended(UUID recordId, ActionState state) {
        if (state == ActionState.DONE) return;
        transactions.executeWithoutResult(status -> cleanups.findById(recordId)
                .filter(row -> row.getManualSince() == null)
                .ifPresent(row -> needsMaintainer(row, ProductFeedbackCleanup.ManualStep.UNFINISHED, clock.instant())));
    }

    private ProductFeedbackIssues.@Nullable Issue findIssue(
            ProductFeedbackIssues.Repository repository, @Nullable Long number, Job job) {
        // A promoted issue carries the promotion marker, not the report's own.
        for (UUID marker : List.of(job.marker(), IssueRenderer.promotionMarker(job.marker()))) {
            var found = issues.findByMarker(repository, marker.toString(), job.since())
                    .filter(issue -> number == null || issue.number() == number);
            if (found.isPresent()) return found.get();
        }
        return null;
    }

    private void remember(
            UUID id, ProductFeedbackIssues.Repository repository, long number, String nodeId, boolean publicIssue) {
        transactions.executeWithoutResult(status -> cleanups.findById(id).ifPresent(row -> {
            row.setRepository(repository.fullName());
            row.setRepositoryId(repository.id());
            row.setInstallationId(repository.installationId());
            row.setIssueNumber(number);
            row.setIssueNodeId(nodeId);
            row.setPublicIssue(publicIssue);
        }));
    }

    private void done(UUID id) {
        transactions.executeWithoutResult(status -> cleanups.deleteById(id));
    }

    private void handOver(UUID id) {
        transactions.executeWithoutResult(status -> cleanups.findById(id)
                .ifPresent(
                        row -> needsMaintainer(row, ProductFeedbackCleanup.ManualStep.EDIT_HISTORY, clock.instant())));
    }

    private void needsMaintainer(ProductFeedbackCleanup row, ProductFeedbackCleanup.ManualStep step, Instant now) {
        row.setManualStep(step);
        row.setManualSince(now);
        meters.step("cleanup", "manual");
        log.info("Product feedback cleanup needs a maintainer: cleanupId={}, step={}", row.getId(), step);
    }

    /** The steps that wait for a maintainer, with what they need to find the issue. */
    @Transactional(readOnly = true)
    public List<ProductFeedbackCleanupActionDTO> needsAction() {
        return cleanups.findTop100ByManualSinceNotNullOrderByManualSinceDesc().stream()
                .map(row -> new ProductFeedbackCleanupActionDTO(
                        row.getId(),
                        Objects.requireNonNull(row.getManualStep()),
                        row.getRepository(),
                        row.getIssueNumber(),
                        row.getIssueNumber() == null
                                ? null
                                : "https://github.com/" + row.getRepository() + "/issues/" + row.getIssueNumber(),
                        IssueRenderer.markerText(row.getMarker()),
                        row.getCreatedAt(),
                        Objects.requireNonNull(row.getManualSince())))
                .toList();
    }

    /** An admin finished the manual step on GitHub; the locator goes with it, and the audit log keeps who and when. */
    @Transactional
    public boolean confirm(UUID id, long accountId) {
        return cleanups.findByIdAndManualSinceNotNull(id)
                .map(row -> {
                    cleanups.delete(row);
                    if (!audit.recordCleanupConfirmed(
                            accountId, id, String.valueOf(row.getManualStep()), row.getRepository()))
                        throw new IllegalStateException("The confirmation could not be audited");
                    return true;
                })
                .orElse(false);
    }
}
