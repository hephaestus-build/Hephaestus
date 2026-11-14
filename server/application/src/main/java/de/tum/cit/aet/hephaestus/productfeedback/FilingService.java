package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.auth.spi.ProductFeedbackAudit;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Routes reports to GitHub, one at a time per instance. Routing freezes the target and the payload, then hands the
 * report to the outbox as one {@code file_issue} action ({@link FileIssueAdapter}), which writes it and reads it back
 * by its marker. The report records the action's end in the same transaction. A report that is not on GitHub stays
 * saved for the admins.
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
    /** Attempts of one hand-over before the report stops and waits in Needs action. */
    static final int MAX_ATTEMPTS = 5;

    static final int MOVE_BATCH = 500;

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final ProductFeedbackIssues issues;
    private final RoutingService routing;
    private final ProductFeedbackConsentRepository consents;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final ProductFeedbackAudit audit;
    private final FileIssueQueue fileIssues;
    private final Clock clock;

    private volatile Instant lastWrite = Instant.EPOCH;

    FilingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            ProductFeedbackIssues issues,
            RoutingService routing,
            ProductFeedbackConsentRepository consents,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            ProductFeedbackAudit audit,
            FileIssueQueue fileIssues,
            Clock clock) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.issues = issues;
        this.routing = routing;
        this.consents = consents;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.audit = audit;
        this.fileIssues = fileIssues;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT20S")
    void fileDue() {
        if (Duration.between(lastWrite, clock.instant()).compareTo(SPACING) < 0) return;
        try {
            Lookup intent = transactions.execute(status -> claim());
            if (intent == null) return;
            var frozen = intent.frozen();
            var repository =
                    frozen != null ? frozen : issues.repository(intent.target()).orElse(null);
            if (Boolean.TRUE.equals(transactions.execute(status -> complete(intent, repository))))
                lastWrite = clock.instant();
        } catch (RuntimeException failed) {
            meters.failed(failed);
            log.warn(
                    "Product feedback filing claim failed: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    void measureWaiting() {
        Instant now = clock.instant();
        meters.oldestWaiting(filings.oldestWaiting()
                .map(filing ->
                        Math.max(0, Duration.between(filing.getCreatedAt(), now).toSeconds()))
                .orElse(0L));
    }

    private record Lookup(
            UUID id,
            int generation,
            FilingState state,
            String target,
            Instant destinationUpdatedAt,
            ProductFeedbackIssues.@Nullable Repository frozen) {}

    /** Claims a repository lookup; the hand-over checks its generation after the lookup returns. */
    private @Nullable Lookup claim() {
        Instant now = clock.instant();
        if (!filings.tryPaceLock(PACE_LOCK)) return null;
        // The limit is about GitHub: a report bound for the admins must not wait behind it, or they cannot see it.
        boolean atLimit = filings.countFiledSince(now.minus(Duration.ofHours(1))) >= HOURLY_LIMIT;
        UUID id = (atLimit ? filings.claimableForAdmins(now) : filings.claimableFiling(now)).orElse(null);
        if (id == null) return null;
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow();
        ProductFeedback report = feedback.findById(id).orElseThrow();
        ProductFeedbackDestination destination = destinations.current();
        String target = target(filing, report, destination, now);
        if (target == null) return null;
        filing.setAttempt(filing.getAttempt() + 1);
        filing.setNextAttemptAt(now.plus(ProductFeedbackFiling.LOOKUP_LEASE));
        filing.setUpdatedAt(now);
        return new Lookup(
                id, filing.getAttempt(), filing.getState(), target, destination.getUpdatedAt(), frozen(filing));
    }

    private static boolean owns(ProductFeedbackFiling filing, Lookup intent) {
        return filing.getAttempt() == intent.generation() && filing.getState() == intent.state();
    }

    private boolean complete(Lookup intent, ProductFeedbackIssues.@Nullable Repository repository) {
        if (!filings.tryPaceLock(PACE_LOCK)) return false;
        var filing = filings.lock(intent.id()).filter(row -> owns(row, intent)).orElse(null);
        if (filing == null) return false;
        Instant now = clock.instant();
        filing.setNextAttemptAt(now);
        var report = feedback.findById(intent.id()).orElse(null);
        if (report == null) return false;
        var destination = destinations.current();
        String target = target(filing, report, destination, now);
        if (target == null
                || !target.equalsIgnoreCase(intent.target())
                || !destination.getUpdatedAt().equals(intent.destinationUpdatedAt())
                || filings.countFiledSince(now.minus(Duration.ofHours(1))) >= HOURLY_LIMIT) return false;
        if (repository == null || (!filing.publicRoute() && !repository.isPrivate())) {
            stop(filing, report, FilingReason.DESTINATION_UNAVAILABLE, now);
            return false;
        }
        filing.setRepository(repository.fullName());
        filing.setRepositoryId(repository.id());
        filing.setRepositoryNodeId(repository.nodeId());
        filing.setInstallationId(repository.installationId());
        Draft draft = Objects.requireNonNull(routing.read(filing.getDraft()));
        var rendered = IssueRenderer.render(
                draft,
                new IssueRenderer.Context(
                        report.getAppVersion(), filing.getPageRoute(), null, filing.getDraftedAt() != null),
                destination.getPublicRepository(),
                filing.publicRoute()
                        ? null
                        : Objects.requireNonNull(filing.getReason()).words(),
                filing.publicRoute() ? null : intent.id().toString(),
                filing.publicRoute() ? filing.getMentionLogin() : null,
                intent.id().toString(),
                routing.version());
        filing.setIssueTitle(rendered.title());
        filing.setIssueText(rendered.shown());
        filing.setPayloadHash(sha256(rendered.title() + "\n" + rendered.body()));
        if (filing.getFirstAttemptAt() == null) filing.setFirstAttemptAt(now.minusSeconds(60));
        filing.apply(FilingLifecycle.Event.FILING_CLAIMED);
        handOver(
                filing,
                report,
                repository,
                new FileIssueAdapter.IssueText(
                        rendered.title(), rendered.body(), draft.type() == Draft.Type.BUG ? "Bug" : "Feature"),
                now);
        return true;
    }

    private @Nullable String target(
            ProductFeedbackFiling filing, ProductFeedback report, ProductFeedbackDestination destination, Instant now) {
        if (destination.isPaused() || !destination.configured() || filing.getRoute() == FilingRoute.ADMINS) {
            keepForAdmins(filing, report, filing.getRoute() == FilingRoute.ADMINS ? filing.getReason() : null, now);
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
            keepForAdmins(filing, report, FilingReason.DESTINATION_CHANGED, now);
            return null;
        }
        return target;
    }

    /**
     * Hands the report to the outbox as the next generation of its {@code file_issue} action. Without {@code issue}
     * the action only looks for an earlier generation's issue by its marker.
     */
    private void handOver(
            ProductFeedbackFiling filing,
            ProductFeedback report,
            ProductFeedbackIssues.Repository repository,
            FileIssueAdapter.@Nullable IssueText issue,
            Instant now) {
        filing.setUpdatedAt(now);
        fileIssues.enqueue(
                new FileIssueAdapter.Payload(
                        filing.getFeedbackId(),
                        filing.getAttempt(),
                        repository,
                        Objects.requireNonNull(filing.getFirstAttemptAt()),
                        issue),
                report.getAccountId());
    }

    /** Whether the report still waits for this generation of its {@code file_issue} action. */
    boolean current(UUID feedbackId, int generation) {
        return filings.findById(feedbackId)
                .filter(row -> row.getState() == FilingState.FILING && row.getAttempt() == generation)
                .isPresent();
    }

    /**
     * Records how the report's {@code file_issue} action ended, in the transaction that ended it. An end of an
     * older generation, or of a report that is no longer filing, changes nothing.
     */
    void ended(UUID feedbackId, int generation, ActionEnded ended, FileIssueAdapter.@Nullable Ref issue) {
        ProductFeedbackFiling filing = filings.lock(feedbackId)
                .filter(row -> row.getState() == FilingState.FILING && row.getAttempt() == generation)
                .orElse(null);
        if (filing == null) return;
        Instant now = clock.instant();
        ProductFeedback report = feedback.findById(feedbackId).orElseThrow();
        if (ended.state() == ActionState.DONE && issue != null) {
            filed(filing, report, issue, now);
        } else if (ended.reason() == ActionStopReason.ABSENT) {
            // No earlier generation reached GitHub: the report takes the ordinary path again, from routing.
            filing.apply(FilingLifecycle.Event.FILING_REQUEUED);
            filing.setNextAttemptAt(now);
            filing.setUpdatedAt(now);
        } else {
            meters.step("file", ended.reason().name().toLowerCase(Locale.ROOT));
            stop(
                    filing,
                    report,
                    ended.reason() == ActionStopReason.REJECTED
                            ? FilingReason.GITHUB_REFUSED
                            : FilingReason.DESTINATION_UNAVAILABLE,
                    now);
        }
    }

    private void filed(ProductFeedbackFiling filing, ProductFeedback report, FileIssueAdapter.Ref issue, Instant now) {
        filing.apply(FilingLifecycle.Event.FILED);
        filing.setIssueNumber(issue.number());
        filing.setIssueNodeId(issue.nodeId());
        filing.setIssueUrl(issue.url());
        filing.setIssueOpen(true);
        filing.setUpdatedAt(now);
        meters.step("file", "ok");
        Draft draft = routing.read(filing.getDraft());
        if (filing.publicRoute() && draft != null)
            destinations.recordPublicIssue(issue.number(), IssueRenderer.title(draft.title()));
        events.publishEvent(new ProductFeedbackUpdatedEvent(
                filing.getFeedbackId(),
                report.getAccountId(),
                ProductFeedbackUpdatedEvent.Kind.FILED,
                filing.publicRoute() ? issue.url() : null,
                filing.publicRoute(),
                null,
                now));
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
            keepForAdmins(filing, report, FilingReason.OPTED_OUT, now);
            return;
        }
        filing.setRoute(FilingRoute.PRIVATE);
        filing.setReason(FilingReason.OPTED_OUT);
        filing.apply(FilingLifecycle.Event.REROUTED_PRIVATE);
        filing.setFiledAfter(null);
        filing.setRepository(null);
        filing.setRepositoryId(null);
        filing.setRepositoryNodeId(null);
        filing.setInstallationId(null);
        filing.setUpdatedAt(now);
        meters.route(FilingRoute.PRIVATE, FilingReason.OPTED_OUT);
    }

    /** The report stays saved for the admins, and the admin email announces it if the instance can send one. */
    void keepForAdmins(
            ProductFeedbackFiling filing, ProductFeedback report, @Nullable FilingReason reason, Instant now) {
        filing.apply(FilingLifecycle.Event.SAVED);
        filing.setRoute(FilingRoute.ADMINS);
        if (reason != null) filing.setReason(reason);
        filing.setUpdatedAt(now);
        notifyAdmins(report);
    }

    private void stop(ProductFeedbackFiling filing, ProductFeedback report, FilingReason reason, Instant now) {
        filing.apply(FilingLifecycle.Event.STOPPED);
        filing.setReason(reason);
        filing.setUpdatedAt(now);
        meters.step("file", "stopped");
        log.warn("Product feedback filing stopped: feedbackId={}, reason={}", filing.getFeedbackId(), reason.tag());
        notifyAdmins(report);
    }

    private void notifyAdmins(ProductFeedback report) {
        events.publishEvent(new ProductFeedbackSubmittedEvent(
                report.getId(),
                Objects.requireNonNull(report.getCreatedAt()),
                switch (report.getKind()) {
                    case BUG -> ProductFeedbackSubmittedEvent.Kind.BUG;
                    case IDEA -> ProductFeedbackSubmittedEvent.Kind.IDEA;
                    case FEEDBACK -> ProductFeedbackSubmittedEvent.Kind.FEEDBACK;
                }));
    }

    /**
     * Sends a report that is saved for the admins to GitHub, as a private issue. An admin's action never publishes: a
     * public issue needs the reporter's own choice. A report whose author was told about the model starts over from
     * drafting, from their latest text. An older one skips the model: it routes without a draft, so its issue is the
     * author's redacted text. Each call changes one row under its lock, so a repeated or interrupted move changes
     * nothing twice, and the marker keeps a write from making a second issue.
     *
     * @return false when this report is resolved, not saved for the admins, or no repository can take it now
     */
    boolean fileSaved(UUID feedbackId, long accountId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            ProductFeedbackDestination destination = destinations.current();
            ProductFeedbackFiling filing = filings.lock(feedbackId)
                    .filter(row -> row.canFileAt(destination))
                    .orElse(null);
            if (filing == null) return false;
            ProductFeedback report = feedback.findById(feedbackId).orElseThrow();
            if (report.isResolved()) return false;
            if (!audit.recordFiledByAdmin(accountId, feedbackId))
                throw new IllegalStateException("The filing could not be audited");
            meters.step("file_saved", "ok");
            Instant now = clock.instant();
            boolean drafted = filing.isFileDisclosed();
            // A held report the reporter edited keeps its edit here; a report saved at submission has none yet.
            if (drafted && filing.getPromptInput() == null) routing.prepare(filing, report, report.getMessage());
            filing.setAllowPublic(false);
            filing.setMentionLogin(null);
            filing.clearRecipient();
            filing.setDraft(null);
            filing.setRoute(null);
            filing.setReason(null);
            filing.setFiledAfter(null);
            // The wait that the stuck alert measures starts here, not when the report was saved.
            filing.setCreatedAt(now);
            filing.setAnswer(null);
            filing.apply(drafted ? FilingLifecycle.Event.MOVED_DRAFTED : FilingLifecycle.Event.MOVED_UNDRAFTED);
            filing.setNextAttemptAt(now);
            filing.setUpdatedAt(now);
            if (drafted) fileIssues.draft(filing, report, now);
            return true;
        }));
    }

    /**
     * Moves what waits for the admins, up to one batch. A crash leaves the rest waiting, and a second run finds only that
     * rest. Filing keeps its own pace, so a large batch files over time and never floods the repository.
     *
     * @return how many reports started moving
     */
    int fileAllSaved(long accountId) {
        int moved = 0;
        for (UUID id : filings.savedForAdmins(MOVE_BATCH)) {
            if (fileSaved(id, accountId)) moved++;
        }
        return moved;
    }

    /** Sends a stopped report through the pipeline again, from routing. */
    boolean retry(UUID feedbackId) {
        return Boolean.TRUE.equals(transactions.execute(status -> filings.lock(feedbackId)
                .filter(filing -> filing.getState() == FilingState.STOPPED)
                .map(filing -> {
                    Instant now = clock.instant();
                    filing.setNextAttemptAt(now);
                    filing.setUpdatedAt(now);
                    ProductFeedbackIssues.Repository frozen = frozen(filing);
                    if (filing.getFirstAttemptAt() != null && frozen != null) {
                        // A write may have reached GitHub: look for it by its marker before anything writes again.
                        filing.apply(FilingLifecycle.Event.RETRIED_RECONCILE);
                        filing.setAttempt(filing.getAttempt() + 1);
                        handOver(filing, feedback.findById(feedbackId).orElseThrow(), frozen, null, now);
                    } else if (filing.getDraft() == null) {
                        filing.apply(FilingLifecycle.Event.RETRIED_DRAFT);
                        fileIssues.draft(filing, feedback.findById(feedbackId).orElseThrow(), now);
                    } else {
                        filing.setRepository(null);
                        filing.setRepositoryId(null);
                        filing.setRepositoryNodeId(null);
                        filing.setInstallationId(null);
                        filing.apply(FilingLifecycle.Event.RETRIED_ROUTE);
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

    /** The newest stopped reports with what an admin needs to retry them, in two reads. */
    List<FilingDTOs.ProductFeedbackNeedsActionDTO> needsAction() {
        List<ProductFeedbackFiling> stopped =
                filings.findAllByStateOrderByUpdatedAtDesc(FilingState.STOPPED, PageRequest.of(0, 100));
        Map<UUID, ProductFeedback> reports = new HashMap<>();
        feedback.findAllById(stopped.stream()
                        .map(ProductFeedbackFiling::getFeedbackId)
                        .toList())
                .forEach(report -> reports.put(report.getId(), report));
        List<FilingDTOs.ProductFeedbackNeedsActionDTO> items = new ArrayList<>();
        for (ProductFeedbackFiling filing : stopped) {
            ProductFeedback report = reports.get(filing.getFeedbackId());
            if (report == null) continue;
            items.add(new FilingDTOs.ProductFeedbackNeedsActionDTO(
                    report.getId(),
                    report.getKind(),
                    Objects.requireNonNull(report.getCreatedAt()),
                    filing.getUpdatedAt(),
                    filing.getReason() == null ? "unknown" : filing.getReason().words(),
                    filing.getRoute() == null
                            ? FilingDTOs.ReportRoute.PRIVATE
                            : FilingDTOs.ReportRoute.valueOf(filing.getRoute().name()),
                    filing.getRepository()));
        }
        return items;
    }
}
