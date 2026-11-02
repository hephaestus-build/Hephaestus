package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scrubs the issues of erased reports. A public issue first moves to the private repository; then its title and body
 * are replaced and it is closed. The app cannot delete an issue or its edit history, so a record that keeps failing
 * stays for a maintainer to finish by hand.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class CleanupService {
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    static final String ERASED = "Erased at the reporter's request";

    private final ProductFeedbackCleanupRepository cleanups;
    private final DestinationSettings destinations;
    private final ProductFeedbackIssues issues;
    private final ProductFeedbackIssueUpdates updates;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final Clock clock;

    CleanupService(
            ProductFeedbackCleanupRepository cleanups,
            DestinationSettings destinations,
            ProductFeedbackIssues issues,
            ProductFeedbackIssueUpdates updates,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            Clock clock) {
        this.cleanups = cleanups;
        this.destinations = destinations;
        this.issues = issues;
        this.updates = updates;
        this.transactions = transactions;
        this.meters = meters;
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
        Job job = transactions.execute(status -> cleanups.claimable(clock.instant())
                .flatMap(cleanups::findById)
                .map(row -> {
                    // Pushes the next attempt out first, so a crash mid-way retries later instead of at once.
                    row.setAttempts(row.getAttempts() + 1);
                    row.setNextAttemptAt(
                            clock.instant().plus(Duration.ofMinutes(Math.min(60, 5L * row.getAttempts()))));
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
                })
                .orElse(null));
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
                // Its edit history stays public until the issue moves; the record waits for a private repository.
                meters.step("cleanup", "retried");
                log.warn("Product feedback cleanup waits for a private repository: cleanupId={}", job.id());
                return;
            }
            done(job.id());
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
}
