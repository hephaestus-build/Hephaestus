package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Follows filed issues by polling, so product feedback stays out of the workspace webhook path. It tells the reporter
 * when the issue changes, and it handles a maintainer's {@code /publish} on a private issue.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class FollowService {
    private static final Logger log = LoggerFactory.getLogger(FollowService.class);
    static final Duration PRIVATE_INTERVAL = Duration.ofMinutes(5);
    static final Duration PUBLIC_INTERVAL = Duration.ofMinutes(30);
    private static final Set<String> MAY_PUBLISH = Set.of("write", "admin");
    private static final Duration PROMOTION_LEASE = Duration.ofMinutes(5);

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final ProductFeedbackIssues issues;
    private final ProductFeedbackIssueUpdates updates;
    private final RoutingService routing;
    private final ProductFeedbackConsentRepository consents;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final Clock clock;

    FollowService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            ProductFeedbackIssues issues,
            ProductFeedbackIssueUpdates updates,
            RoutingService routing,
            ProductFeedbackConsentRepository consents,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            Clock clock) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.issues = issues;
        this.updates = updates;
        this.routing = routing;
        this.consents = consents;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT90S")
    @SchedulerLock(name = "product-feedback-follow", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void followDue() {
        Instant now = clock.instant();
        List<ProductFeedbackFiling> due =
                filings.dueForFollowUp(now.minus(PRIVATE_INTERVAL), now.minus(PUBLIC_INTERVAL));
        if (due.isEmpty()) return;
        Map<String, ProductFeedbackIssues.IssueState> states = new HashMap<>();
        try {
            for (var state : updates.states(due.stream()
                    .map(f -> Objects.requireNonNull(f.getIssueNodeId()))
                    .toList())) states.put(state.nodeId(), state);
        } catch (RuntimeException failed) {
            meters.step("follow", "failed");
            log.warn(
                    "Product feedback follow-up failed: error={}",
                    failed.getClass().getSimpleName());
            return;
        }
        for (ProductFeedbackFiling row : due) {
            transactions.executeWithoutResult(status -> filings.lock(row.getFeedbackId())
                    .filter(filing -> filing.getState() == FilingState.FILED
                            && Objects.equals(filing.getIssueNodeId(), row.getIssueNodeId()))
                    .ifPresent(filing -> apply(filing, states.get(filing.getIssueNodeId()))));
        }
        meters.step("follow", "ok");
    }

    /** Carries out persisted {@code /publish} commands. Pause holds them; a failure leaves them for the next tick. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT100S")
    void promoteDue() {
        if (destinations.current().isPaused()) return;
        for (UUID id : filings.withPublishRequest(clock.instant())) promote(id);
    }

    private void apply(ProductFeedbackFiling filing, ProductFeedbackIssues.@Nullable IssueState state) {
        Instant now = clock.instant();
        filing.setFollowedAt(now);
        if (state == null) return;
        ProductFeedback report = feedback.findById(filing.getFeedbackId()).orElseThrow();
        long seen = filing.getLastCommentId() == null ? 0 : filing.getLastCommentId();
        boolean commented = false;
        long newest = seen;
        for (var comment : state.comments()) {
            if (comment.id() <= seen) continue;
            newest = Math.max(newest, comment.id());
            if (comment.byApp()) continue;
            if (!filing.publicRoute()
                    && comment.body().strip().toLowerCase(Locale.ROOT).startsWith("/publish")) {
                // Recorded before anything acts on it, so the cursor and the command commit together.
                if (filing.getPublishRequestedBy() == null) {
                    filing.setPublishRequestedBy(comment.authorLogin());
                    filing.setPublishRequestedAt(now);
                }
            } else if (filing.publicRoute()) {
                commented = true;
            }
        }
        filing.setLastCommentId(newest == 0 ? null : newest);
        String url = Objects.requireNonNull(filing.getIssueUrl());
        if (commented) notify(filing, report, ProductFeedbackUpdatedEvent.Kind.COMMENTED, url, null, now);
        boolean wasOpen = !Boolean.FALSE.equals(filing.getIssueOpen());
        if (wasOpen && !state.open()) {
            String reason = state.stateReason() == null
                    ? "completed"
                    : state.stateReason().toLowerCase(Locale.ROOT);
            var kind = switch (reason) {
                case "not_planned" -> ProductFeedbackUpdatedEvent.Kind.CLOSED_NOT_PLANNED;
                case "duplicate" -> ProductFeedbackUpdatedEvent.Kind.DUPLICATE;
                default -> ProductFeedbackUpdatedEvent.Kind.CLOSED_COMPLETED;
            };
            notify(filing, report, kind, url, null, now);
            filing.setIssueStateReason(reason);
        } else if (!wasOpen && state.open()) {
            notify(filing, report, ProductFeedbackUpdatedEvent.Kind.REOPENED, url, null, now);
            filing.setIssueStateReason(null);
        }
        filing.setIssueOpen(state.open());
        filing.setUpdatedAt(now);
    }

    private void notify(
            ProductFeedbackFiling filing,
            ProductFeedback report,
            ProductFeedbackUpdatedEvent.Kind kind,
            String url,
            @Nullable String detail,
            Instant now) {
        boolean isPublic = filing.publicRoute();
        events.publishEvent(new ProductFeedbackUpdatedEvent(
                filing.getFeedbackId(),
                report.getAccountId(),
                kind,
                isPublic ? url : null,
                isPublic,
                isPublic ? detail : null,
                now));
    }

    /**
     * Publishes a private report as a new public issue. The commenter needs write access on both repositories, read
     * live; the reporter must have allowed public; and the report needs a model draft. The command never overrides
     * consent, security, the risk latch or abuse. A lease on the row keeps it to one replica, and the recipient is
     * frozen before the first write, so a retry after an unclear result looks for the issue there before it writes.
     */
    private void promote(UUID feedbackId) {
        try {
            var plan = transactions.execute(status -> plan(feedbackId));
            if (plan == null) return;
            String marker = IssueRenderer.promotionMarker(feedbackId).toString();
            var publicRepository = plan.publicRepository();
            ProductFeedbackIssues.Issue issue = plan.frozen() && publicRepository != null
                    ? issues.findByMarker(publicRepository, marker, plan.since())
                            .orElse(null)
                    : null;
            if (issue == null) {
                String refusal = plan.refusal();
                if (refusal == null
                        && (!MAY_PUBLISH.contains(
                                        updates.permission(Objects.requireNonNull(publicRepository), plan.login()))
                                || !MAY_PUBLISH.contains(updates.permission(plan.privateRepository(), plan.login())))) {
                    refusal = "Only someone with write access to both repositories can publish this report.";
                }
                if (refusal != null || publicRepository == null) {
                    updates.comment(plan.privateRepository(), plan.privateNumber(), Objects.requireNonNull(refusal));
                    meters.promotion("refused");
                    clearRequest(feedbackId);
                    return;
                }
                if (!plan.frozen()
                        && !Boolean.TRUE.equals(transactions.execute(status -> freeze(feedbackId, publicRepository))))
                    return;
                issue = issues.create(
                        publicRepository,
                        plan.title(),
                        plan.body(),
                        issues.issueTypes(publicRepository).contains(plan.type()) ? plan.type() : null);
            }
            var published = issue;
            var recipient = Objects.requireNonNull(publicRepository);
            updates.comment(
                    plan.privateRepository(),
                    plan.privateNumber(),
                    "Published as " + recipient.fullName() + "#" + published.number() + ".");
            updates.close(plan.privateRepository(), plan.privateNumber(), "completed");
            transactions.executeWithoutResult(status -> filings.lock(feedbackId).ifPresent(filing -> {
                Instant now = clock.instant();
                filing.setRoute(FilingRoute.PUBLIC);
                filing.setReason(FilingReason.NONE);
                filing.setRepository(recipient.fullName());
                filing.setRepositoryId(recipient.id());
                filing.setRepositoryNodeId(recipient.nodeId());
                filing.setInstallationId(recipient.installationId());
                filing.setIssueNumber(published.number());
                filing.setIssueNodeId(published.nodeId());
                filing.setIssueUrl(published.url());
                filing.setIssueOpen(true);
                filing.setIssueStateReason(null);
                filing.setLastCommentId(null);
                clear(filing);
                filing.setFollowedAt(now);
                filing.setUpdatedAt(now);
                ProductFeedback report =
                        feedback.findById(filing.getFeedbackId()).orElseThrow();
                notify(filing, report, ProductFeedbackUpdatedEvent.Kind.PUBLISHED, published.url(), null, now);
            }));
            meters.promotion("published");
        } catch (RuntimeException failed) {
            // The lease expires and the next tick reconciles in the frozen recipient before it writes again.
            meters.promotion("failed");
            log.warn(
                    "Product feedback promotion failed: feedbackId={}, error={}",
                    feedbackId,
                    failed.getClass().getSimpleName());
        }
    }

    /** False when the report is gone, so nothing is written for an erased report. */
    private boolean freeze(UUID feedbackId, ProductFeedbackIssues.Repository recipient) {
        return filings.lock(feedbackId)
                .map(filing -> {
                    filing.setPromotionRepository(recipient.fullName());
                    filing.setPromotionRepositoryId(recipient.id());
                    filing.setPromotionInstallationId(recipient.installationId());
                    filing.setPromotionRepositoryNodeId(recipient.nodeId());
                    return true;
                })
                .orElse(false);
    }

    private void clearRequest(UUID feedbackId) {
        transactions.executeWithoutResult(status -> filings.lock(feedbackId).ifPresent(FollowService::clear));
    }

    private static void clear(ProductFeedbackFiling filing) {
        filing.setPublishRequestedBy(null);
        filing.setPublishRequestedAt(null);
        filing.setPromotionRepository(null);
        filing.setPromotionRepositoryId(null);
        filing.setPromotionInstallationId(null);
        filing.setPromotionRepositoryNodeId(null);
        filing.setLeaseUntil(null);
    }

    private record Plan(
            String login,
            ProductFeedbackIssues.Repository privateRepository,
            long privateNumber,
            ProductFeedbackIssues.@Nullable Repository publicRepository,
            boolean frozen,
            @Nullable String refusal,
            String title,
            String body,
            @Nullable String type,
            Instant since) {}

    /** Claims the command under a lease and decides what to publish where, with no GitHub write. */
    private @Nullable Plan plan(UUID feedbackId) {
        ProductFeedbackFiling filing = filings.lock(feedbackId).orElse(null);
        String login = filing == null ? null : filing.getPublishRequestedBy();
        Instant requested = filing == null ? null : filing.getPublishRequestedAt();
        if (filing == null || login == null || requested == null) return null;
        Instant now = clock.instant();
        Instant lease = filing.getLeaseUntil();
        if (lease != null && lease.isAfter(now)) return null;
        if (filing.getState() != FilingState.FILED || filing.publicRoute()) {
            clear(filing);
            return null;
        }
        filing.setLeaseUntil(now.plus(PROMOTION_LEASE));
        var privateRepository = new ProductFeedbackIssues.Repository(
                Objects.requireNonNull(filing.getRepositoryId()),
                Objects.requireNonNull(filing.getRepositoryNodeId()),
                Objects.requireNonNull(filing.getRepository()),
                Objects.requireNonNull(filing.getInstallationId()),
                true);
        long number = Objects.requireNonNull(filing.getIssueNumber());
        Instant since = requested.minusSeconds(60);
        Draft draft = filing.getDraftedAt() == null ? null : routing.read(filing.getDraft());
        boolean consentCurrent = filing.getConsentId() != null
                && consents.findById(filing.getConsentId())
                        .filter(row ->
                                row.getNoticeVersion() == destinations.current().getNoticeVersion())
                        .isPresent();
        FilingReason refused =
                RoutingGate.refusePromotion(filing.isAllowPublic() && consentCurrent, draft, filing.isRiskLatch());
        var frozenRecipient = frozenRecipient(filing);
        boolean frozen = frozenRecipient != null;
        String current = destinations.current().getPublicRepository();
        var publicRepository = frozen ? frozenRecipient : currentRecipient(current);
        // A frozen recipient is always reconciled; after an absent result, one that is no longer the destination is
        // refused, never written to.
        if (refused == null
                && (publicRepository == null
                        || current == null
                        || !publicRepository.fullName().equalsIgnoreCase(current))) {
            refused = FilingReason.DESTINATION_UNAVAILABLE;
        }
        if (refused != null || draft == null || publicRepository == null)
            return new Plan(
                    login,
                    privateRepository,
                    number,
                    publicRepository,
                    frozen,
                    refusalText(refused),
                    "",
                    "",
                    null,
                    since);
        ProductFeedback report = feedback.findById(filing.getFeedbackId()).orElseThrow();
        var rendered = IssueRenderer.render(
                draft,
                new IssueRenderer.Context(report.getAppVersion(), filing.getPageRoute(), null),
                publicRepository.fullName(),
                null,
                null,
                null,
                IssueRenderer.promotionMarker(feedbackId).toString(),
                routing.version());
        return new Plan(
                login,
                privateRepository,
                number,
                publicRepository,
                frozen,
                null,
                rendered.title(),
                rendered.body(),
                draft.type() == Draft.Type.BUG ? "Bug" : "Feature",
                since);
    }

    private ProductFeedbackIssues.@Nullable Repository currentRecipient(@Nullable String name) {
        return name == null ? null : issues.repository(name).orElse(null);
    }

    /** The repository an earlier attempt froze; reconciliation addresses it by ID, so a rename does not lose it. */
    private static ProductFeedbackIssues.@Nullable Repository frozenRecipient(ProductFeedbackFiling filing) {
        String name = filing.getPromotionRepository();
        Long id = filing.getPromotionRepositoryId();
        Long installation = filing.getPromotionInstallationId();
        String node = filing.getPromotionRepositoryNodeId();
        if (name == null || id == null || installation == null || node == null) return null;
        return new ProductFeedbackIssues.Repository(id, node, name, installation, false);
    }

    private static String refusalText(@Nullable FilingReason reason) {
        return switch (reason == null ? FilingReason.NO_DRAFT : reason) {
            case OPTED_OUT -> "The reporter did not allow public, so this report stays private.";
            case SECURITY -> "Security reports stay private.";
            case REDACTION, ABOUT_PERSON -> "Personal data was found in this report, so it stays private.";
            case ABUSE -> "This report was marked as abuse, so it stays private.";
            case NO_DRAFT ->
                "This report has no model draft to publish. File a new issue by hand if it should be public.";
            default -> "This report cannot be published now: no public repository is reachable.";
        };
    }
}
