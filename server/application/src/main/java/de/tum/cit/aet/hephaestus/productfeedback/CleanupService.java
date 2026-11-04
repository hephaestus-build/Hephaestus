package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scrubs the issues of erased reports. A public issue first moves to the private repository when one can take it; then
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
            Instant since) {}

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
    void cleanDue() {
        Job job = transactions.execute(status -> {
            Instant now = clock.instant();
            ProductFeedbackCleanup row =
                    cleanups.claimable(now).flatMap(cleanups::findById).orElse(null);
            if (row == null) return null;
            if (!row.getCreatedAt().plus(MANUAL_AFTER).isAfter(now)) {
                needsMaintainer(row, ProductFeedbackCleanup.ManualStep.UNFINISHED, now);
                return null;
            }
            // Pushes the next attempt out first, so a crash mid-way retries later instead of at once.
            row.setAttempts(row.getAttempts() + 1);
            row.setNextAttemptAt(now.plus(Duration.ofMinutes(Math.min(60, 5L * row.getAttempts()))));
            return new Job(
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
                    row.getSince());
        });
        if (job == null) return;
        try {
            Long number = job.number();
            String nodeId = job.nodeId();
            if (number == null || nodeId == null) {
                var found = findIssue(job.repository(), number, job);
                if (found == null && number == null) {
                    // A complete read found no issue: the create never reached GitHub, so nothing is left to scrub.
                    done(job.id());
                    return;
                }
                if (found != null) {
                    // Kept before the scrub removes the marker, so a later pass can still move the issue.
                    number = found.number();
                    nodeId = found.nodeId();
                    remember(job.id(), job.repository(), number, nodeId, job.publicIssue());
                }
            }
            long issueNumber = Objects.requireNonNull(number);
            var repository = job.repository();
            boolean moved = !job.publicIssue();
            if (job.publicIssue() && nodeId != null) {
                String privateName = destinations.current().getPrivateRepository();
                var target = privateName == null
                        ? null
                        : issues.repository(privateName)
                                .filter(ProductFeedbackIssues.Repository::isPrivate)
                                .orElse(null);
                if (target != null) {
                    var transferred =
                            updates.transfer(new ProductFeedbackIssues.Issue(issueNumber, nodeId, ""), target);
                    remember(job.id(), target, transferred.number(), transferred.nodeId(), false);
                    repository = target;
                    issueNumber = transferred.number();
                    moved = true;
                }
            }
            updates.edit(repository, issueNumber, ERASED, ERASED + ".");
            updates.close(repository, issueNumber, "not_planned");
            if (!moved) {
                // No private repository took it: the scrubbed issue stays public, and so does its edit history.
                log.warn("Product feedback cleanup scrubbed a public issue it could not move: cleanupId={}", job.id());
            }
            // The text is gone, but the edit history still shows it; only a maintainer can delete that.
            handOver(job.id());
            meters.step("cleanup", "ok");
        } catch (RuntimeException failed) {
            meters.step("cleanup", "retried");
            log.warn(
                    "Product feedback cleanup failed: cleanupId={}, error={}",
                    job.id(),
                    failed.getClass().getSimpleName());
        }
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
        events.publishEvent(new ProductFeedbackCleanupNeedsActionEvent(row.getId(), now));
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
