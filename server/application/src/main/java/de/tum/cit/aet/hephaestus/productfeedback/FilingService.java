package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Files routed reports on GitHub, one at a time per instance. The target and the payload are frozen before each
 * write, and a write with an unclear result is reconciled by the marker before anything is written again. A report
 * that is not on GitHub always reaches the admins by email.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class FilingService {
    private static final Logger log = LoggerFactory.getLogger(FilingService.class);
    /** Serializes filing across server replicas, so the pace holds for the whole instance. */
    private static final long PACE_LOCK = 0x5046_4649_4c49_4e47L;

    static final Duration SPACING = Duration.ofSeconds(10);
    static final int HOURLY_LIMIT = 30;
    static final int PUBLIC_PER_ACCOUNT_DAILY = 5;
    static final int MAX_FAILURES = 5;
    static final Duration WRITE_DEADLINE = Duration.ofSeconds(40);

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final ProductFeedbackDestinationRepository destinationRows;
    private final ProductFeedbackIssues issues;
    private final RoutingService routing;
    private final ProductFeedbackConsentRepository consents;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final Clock clock;

    private volatile Instant lastWrite = Instant.EPOCH;

    FilingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            ProductFeedbackDestinationRepository destinationRows,
            ProductFeedbackIssues issues,
            RoutingService routing,
            ProductFeedbackConsentRepository consents,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            Clock clock) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.destinationRows = destinationRows;
        this.issues = issues;
        this.routing = routing;
        this.consents = consents;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    /** What one claim decided to send. Without a payload it only looks for an earlier attempt's issue. */
    private record Write(
            UUID feedbackId,
            int attempt,
            ProductFeedbackIssues.Repository repository,
            @Nullable Payload payload,
            Instant since) {}

    private record Payload(String title, String body, String type) {}

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT20S")
    void fileDue() {
        if (Duration.between(lastWrite, clock.instant()).compareTo(SPACING) < 0) return;
        Write write;
        try {
            write = transactions.execute(status -> claim());
        } catch (RuntimeException failed) {
            log.warn(
                    "Product feedback filing claim failed: error={}",
                    failed.getClass().getSimpleName());
            return;
        }
        if (write != null) send(write);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    void measureWaiting() {
        Instant now = clock.instant();
        meters.oldestWaiting(filings.oldestWaiting()
                .map(filing ->
                        Math.max(0, Duration.between(filing.getCreatedAt(), now).toSeconds()))
                .orElse(0L));
    }

    private @Nullable Write claim() {
        Instant now = clock.instant();
        if (!filings.tryPaceLock(PACE_LOCK)) return null;
        if (filings.countFiledSince(now.minus(Duration.ofHours(1))) >= HOURLY_LIMIT) return null;
        UUID id = filings.claimableFiling(now).orElse(null);
        if (id == null) return null;
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow();
        ProductFeedback report = feedback.findById(id).orElseThrow();
        ProductFeedbackIssues.Repository frozen = frozen(filing);
        if (filing.getState() == FilingState.FILING && frozen != null && filing.getFirstAttemptAt() != null) {
            // An earlier write may have reached GitHub. Find it in its own repository before Pause, consent or a
            // destination change can send the report anywhere else.
            lease(filing, now);
            return new Write(id, filing.getAttempt(), frozen, null, filing.getFirstAttemptAt());
        }
        ProductFeedbackDestination destination = destinations.current();
        if (destination.isPaused() || !destination.configured() || filing.getRoute() == FilingRoute.EMAIL) {
            toEmail(filing, report, filing.getRoute() == FilingRoute.EMAIL ? filing.getReason() : null, now);
            return null;
        }
        if (filing.publicRoute() && !consentCurrent(filing, destination)) {
            filing.setAllowPublic(false);
            reroutePrivate(filing, report, destination, now);
            return null;
        }
        boolean isPublic = filing.publicRoute();
        if (isPublic
                && filings.countPublicFiledByAccountSince(report.getAccountId(), now.truncatedTo(ChronoUnit.DAYS))
                        >= PUBLIC_PER_ACCOUNT_DAILY) {
            filing.setAllowPublic(false);
            reroutePrivate(filing, report, destination, now);
            return null;
        }
        String target = isPublic ? destination.getPublicRepository() : destination.getPrivateRepository();
        if (target == null || (filing.getRepository() != null && !target.equalsIgnoreCase(filing.getRepository()))) {
            toEmail(filing, report, FilingReason.DESTINATION_CHANGED, now);
            return null;
        }
        ProductFeedbackIssues.Repository repository = frozen;
        if (repository == null) {
            repository = issues.repository(target).orElse(null);
            if (repository == null || (!isPublic && !repository.isPrivate())) {
                stop(filing, report, FilingReason.DESTINATION_UNAVAILABLE, now);
                return null;
            }
            filing.setRepository(repository.fullName());
            filing.setRepositoryId(repository.id());
            filing.setRepositoryNodeId(repository.nodeId());
            filing.setInstallationId(repository.installationId());
        }
        Draft draft = Objects.requireNonNull(routing.read(filing.getDraft()));
        var rendered = IssueRenderer.render(
                draft,
                new IssueRenderer.Context(report.getAppVersion(), filing.getPageRoute(), null),
                repository.fullName(),
                isPublic ? null : Objects.requireNonNull(filing.getReason()).words(),
                isPublic ? null : id.toString(),
                isPublic ? filing.getMentionLogin() : null,
                id.toString(),
                routing.version());
        lease(filing, now);
        filing.setPayloadHash(sha256(rendered.title() + "\n" + rendered.body()));
        if (filing.getFirstAttemptAt() == null) filing.setFirstAttemptAt(now.minusSeconds(60));
        String type = draft.type() == Draft.Type.BUG ? "Bug" : "Feature";
        return new Write(
                id,
                filing.getAttempt(),
                repository,
                new Payload(rendered.title(), rendered.body(), type),
                Objects.requireNonNull(filing.getFirstAttemptAt()));
    }

    private static void lease(ProductFeedbackFiling filing, Instant now) {
        filing.setAttempt(filing.getAttempt() + 1);
        filing.setState(FilingState.FILING);
        filing.setLeaseUntil(now.plus(WRITE_DEADLINE.multipliedBy(2)));
        filing.setUpdatedAt(now);
    }

    private void send(Write write) {
        lastWrite = clock.instant();
        try {
            Payload payload = write.payload();
            if (payload == null) {
                var found = issues.findByMarker(
                        write.repository(), write.feedbackId().toString(), write.since());
                if (found.isPresent()) finish(write, found.get(), null);
                else requeue(write);
                return;
            }
            if (write.repository().isPrivate()
                    && !issues.isPrivate(write.repository()).orElse(false)) {
                finish(write, null, FilingReason.DESTINATION_UNAVAILABLE);
                return;
            }
            String type = issues.issueTypes(write.repository()).contains(payload.type()) ? payload.type() : null;
            finish(write, issues.create(write.repository(), payload.title(), payload.body(), type), null);
        } catch (ProductFeedbackIssues.Refused refused) {
            meters.step("file", "refused");
            finish(write, null, FilingReason.GITHUB_REFUSED);
        } catch (RuntimeException unclear) {
            // Unknown, or anything else after the request may have left: the lease expires, and the next claim
            // reconciles by the marker before it writes again.
            meters.step("file", "unknown");
            transactions.executeWithoutResult(status -> filings.lock(write.feedbackId())
                    .filter(filing -> filing.getState() == FilingState.FILING && filing.getAttempt() == write.attempt())
                    .ifPresent(filing -> {
                        filing.setFailures(filing.getFailures() + 1);
                        Instant now = clock.instant();
                        if (filing.getFailures() >= MAX_FAILURES) {
                            stop(
                                    filing,
                                    feedback.findById(filing.getFeedbackId()).orElseThrow(),
                                    FilingReason.DESTINATION_UNAVAILABLE,
                                    now);
                        } else {
                            filing.setLeaseUntil(now.plus(Duration.ofMinutes(filing.getFailures())));
                            filing.setUpdatedAt(now);
                        }
                    }));
        }
    }

    /** No earlier attempt reached GitHub: the report takes the ordinary path again, from its frozen recipient. */
    private void requeue(Write write) {
        transactions.executeWithoutResult(status -> filings.lock(write.feedbackId())
                .filter(filing -> filing.getState() == FilingState.FILING && filing.getAttempt() == write.attempt())
                .ifPresent(filing -> {
                    Instant now = clock.instant();
                    filing.setState(FilingState.QUEUED);
                    filing.setLeaseUntil(null);
                    filing.setNextAttemptAt(now);
                    filing.setUpdatedAt(now);
                }));
    }

    private void finish(Write write, ProductFeedbackIssues.@Nullable Issue issue, @Nullable FilingReason stopped) {
        transactions.executeWithoutResult(status -> {
            ProductFeedbackFiling filing = filings.lock(write.feedbackId())
                    .filter(row -> row.getState() == FilingState.FILING && row.getAttempt() == write.attempt())
                    .orElse(null);
            if (filing == null) return;
            Instant now = clock.instant();
            if (issue == null) {
                stop(
                        filing,
                        feedback.findById(filing.getFeedbackId()).orElseThrow(),
                        stopped == null ? FilingReason.GITHUB_REFUSED : stopped,
                        now);
                return;
            }
            filing.setState(FilingState.FILED);
            filing.setIssueNumber(issue.number());
            filing.setIssueNodeId(issue.nodeId());
            filing.setIssueUrl(issue.url());
            filing.setIssueOpen(true);
            filing.setLeaseUntil(null);
            filing.setUpdatedAt(now);
            meters.step("file", "ok");
            Payload payload = write.payload();
            if (filing.publicRoute() && payload != null) appendToSnapshot(issue.number(), payload.title());
            ProductFeedback report = feedback.findById(filing.getFeedbackId()).orElseThrow();
            events.publishEvent(new ProductFeedbackUpdatedEvent(
                    filing.getFeedbackId(),
                    report.getAccountId(),
                    ProductFeedbackUpdatedEvent.Kind.FILED,
                    filing.publicRoute() ? issue.url() : null,
                    filing.publicRoute(),
                    null,
                    now));
        });
    }

    private void appendToSnapshot(long number, String title) {
        destinationRows.findById(ProductFeedbackDestination.SINGLETON).ifPresent(destination -> {
            var entries = new ArrayList<>(Snapshot.read(destination.getSnapshot()));
            entries.addFirst(new Snapshot.Entry(number, title));
            destination.setSnapshot(Snapshot.write(entries));
        });
    }

    /** The report may still go public: the person allowed it, under the notice version that is current now. */
    private boolean consentCurrent(ProductFeedbackFiling filing, ProductFeedbackDestination destination) {
        if (!filing.isAllowPublic()) return false;
        UUID consent = filing.getConsentId();
        return consent != null
                && consents.findById(consent)
                        .filter(row -> row.getNoticeVersion() == destination.getNoticeVersion())
                        .isPresent();
    }

    private static ProductFeedbackIssues.@Nullable Repository frozen(ProductFeedbackFiling filing) {
        Long id = filing.getRepositoryId();
        Long installation = filing.getInstallationId();
        String name = filing.getRepository();
        String node = filing.getRepositoryNodeId();
        if (id == null || installation == null || name == null || node == null) return null;
        return new ProductFeedbackIssues.Repository(id, node, name, installation, !filing.publicRoute());
    }

    private void reroutePrivate(
            ProductFeedbackFiling filing, ProductFeedback report, ProductFeedbackDestination destination, Instant now) {
        if (destination.getPrivateRepository() == null) {
            toEmail(filing, report, FilingReason.OPTED_OUT, now);
            return;
        }
        filing.setRoute(FilingRoute.PRIVATE);
        filing.setReason(FilingReason.OPTED_OUT);
        filing.setState(FilingState.QUEUED);
        filing.setFiledAfter(null);
        filing.setRepository(null);
        filing.setRepositoryId(null);
        filing.setRepositoryNodeId(null);
        filing.setInstallationId(null);
        filing.setUpdatedAt(now);
        meters.route(FilingRoute.PRIVATE, FilingReason.OPTED_OUT);
    }

    /** The report goes to the admins by email, as it did before product feedback reached GitHub. */
    void toEmail(ProductFeedbackFiling filing, ProductFeedback report, @Nullable FilingReason reason, Instant now) {
        filing.setState(FilingState.EMAILED);
        filing.setRoute(FilingRoute.EMAIL);
        if (reason != null) filing.setReason(reason);
        filing.setLeaseUntil(null);
        filing.setUpdatedAt(now);
        adminEmail(report);
    }

    private void stop(ProductFeedbackFiling filing, ProductFeedback report, FilingReason reason, Instant now) {
        filing.setState(FilingState.STOPPED);
        filing.setReason(reason);
        filing.setLeaseUntil(null);
        filing.setUpdatedAt(now);
        meters.step("file", "stopped");
        log.warn("Product feedback filing stopped: feedbackId={}, reason={}", filing.getFeedbackId(), reason.tag());
        adminEmail(report);
    }

    private void adminEmail(ProductFeedback report) {
        events.publishEvent(new ProductFeedbackSubmittedEvent(
                report.getId(),
                Objects.requireNonNull(report.getCreatedAt()),
                switch (report.getKind()) {
                    case BUG -> ProductFeedbackSubmittedEvent.Kind.BUG;
                    case IDEA -> ProductFeedbackSubmittedEvent.Kind.IDEA;
                    case FEEDBACK -> ProductFeedbackSubmittedEvent.Kind.FEEDBACK;
                }));
    }

    /** Sends a stopped report through the pipeline again, from routing. */
    boolean retry(UUID feedbackId) {
        return Boolean.TRUE.equals(transactions.execute(status -> filings.lock(feedbackId)
                .filter(filing -> filing.getState() == FilingState.STOPPED)
                .map(filing -> {
                    Instant now = clock.instant();
                    filing.setFailures(0);
                    filing.setNextAttemptAt(now);
                    filing.setUpdatedAt(now);
                    if (filing.getFirstAttemptAt() != null && filing.getRepositoryId() != null) {
                        // A write may have reached GitHub: the next claim reconciles by the marker before it writes.
                        filing.setState(FilingState.FILING);
                        filing.setLeaseUntil(now);
                    } else if (filing.getDraft() == null) {
                        filing.setState(FilingState.DRAFT_QUEUED);
                    } else {
                        filing.setRepository(null);
                        filing.setRepositoryId(null);
                        filing.setRepositoryNodeId(null);
                        filing.setInstallationId(null);
                        filing.setState(FilingState.ANSWERED);
                        filing.setAnswer(null);
                    }
                    return true;
                })
                .orElse(false)));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    List<ProductFeedbackFiling> stopped() {
        return filings.findAllByStateOrderByUpdatedAtDesc(FilingState.STOPPED, PageRequest.of(0, 100));
    }
}
