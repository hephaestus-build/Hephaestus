package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.EditProductFeedbackDraftDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.MyProductFeedbackDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackSharingDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ReportRoute;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ReportState;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackSharingDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** What a person does with their own reports and their sharing choice. */
@Service
@ConditionalOnServerRole
class MyReportsService {

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final ProductFeedbackConsentRepository consents;
    private final DestinationSettings destinations;
    private final RoutingService routing;
    private final FileIssueQueue fileIssues;
    private final GitHubLogins logins;
    private final ProductFeedbackIssues issues;
    private final IssueTransfers transfers;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final Clock clock;

    MyReportsService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            ProductFeedbackConsentRepository consents,
            DestinationSettings destinations,
            RoutingService routing,
            FileIssueQueue fileIssues,
            GitHubLogins logins,
            ProductFeedbackIssues issues,
            IssueTransfers transfers,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            Clock clock) {
        this.filings = filings;
        this.feedback = feedback;
        this.consents = consents;
        this.destinations = destinations;
        this.routing = routing;
        this.fileIssues = fileIssues;
        this.logins = logins;
        this.issues = issues;
        this.transfers = transfers;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    /** The newest choice for the current notice version; an older notice's choice does not count. */
    @Nullable
    ProductFeedbackConsent currentConsent(long accountId) {
        int notice = destinations.current().getNoticeVersion();
        return consents.findFirstByAccountIdOrderByCreatedAtDesc(accountId)
                .filter(consent -> consent.getNoticeVersion() == notice)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public ProductFeedbackSharingDTO sharing(long accountId) {
        return sharingOf(accountId);
    }

    private ProductFeedbackSharingDTO sharingOf(long accountId) {
        ProductFeedbackDestination destination = destinations.current();
        ProductFeedbackConsent consent = currentConsent(accountId);
        return new ProductFeedbackSharingDTO(
                destination.configured() && !destination.isPaused(),
                destination.isPaused() ? null : destination.getPublicRepository(),
                consent == null ? null : consent.isAllowPublic(),
                consent != null && consent.isMention(),
                logins.of(accountId).orElse(null),
                consent == null ? null : consent.getCreatedAt());
    }

    @Transactional
    public ProductFeedbackSharingDTO updateSharing(long accountId, UpdateProductFeedbackSharingDTO request) {
        ProductFeedbackDestination destination = destinations.current();
        Instant now = clock.instant();
        consents.save(new ProductFeedbackConsent(
                accountId, request.allowPublic(), request.mention(), destination.getNoticeVersion(), now));
        if (!request.allowPublic()) {
            // Locked: a claim that takes a held row at this moment must not be overwritten by this flush.
            for (ProductFeedbackFiling filing : filings.lockAllByAccount(accountId)) {
                if (!filing.isAllowPublic()) continue;
                if (!isOnGitHub(filing)) keepPrivate(filing, now);
                else if (filing.getRoute() == FilingRoute.PRIVATE) filing.setAllowPublic(false);
            }
        }
        return sharingOf(accountId);
    }

    /**
     * Opens a report's filing at submission. Returns the route it is heading for. Without a destination, or while
     * paused, the report stays saved for the admins.
     *
     * @param share the composer's box for this report; null keeps the remembered choice, and without one it is private
     */
    ProductFeedbackFiling open(ProductFeedback report, @Nullable Boolean share, Instant now) {
        ProductFeedbackDestination destination = destinations.current();
        if (!destination.configured() || destination.isPaused()) return filings.save(savedForAdmins(report, now));
        boolean publicDestination = destination.getPublicRepository() != null;
        ProductFeedbackConsent consent = currentConsent(report.getAccountId());
        boolean allowPublic = publicDestination && (share != null ? share : consent != null && consent.isAllowPublic());
        // Only a tick is a choice, so it alone records one. Silence stays private and records nothing: Recital 32.
        if (allowPublic) consent = ticked(report.getAccountId(), consent, destination, now);
        var filing = new ProductFeedbackFiling(report.getId(), FilingState.DRAFT_QUEUED, allowPublic, now);
        if (consent != null) filing.setConsentId(consent.getId());
        routing.prepare(filing, report, report.getMessage());
        if (allowPublic) {
            filing.setFiledAfter(now.plus(DraftingService.HOLD));
            if (consent != null && consent.isMention())
                filing.setMentionLogin(logins.of(report.getAccountId()).orElse(null));
        }
        ProductFeedbackFiling saved = filings.save(filing);
        fileIssues.draft(saved, report, now);
        return saved;
    }

    /** The consent behind a tick: the current one if it already allows a public issue, otherwise a new event. */
    private ProductFeedbackConsent ticked(
            long accountId,
            @Nullable ProductFeedbackConsent current,
            ProductFeedbackDestination destination,
            Instant now) {
        if (current != null && current.isAllowPublic()) return current;
        return consents.save(new ProductFeedbackConsent(
                accountId, true, current != null && current.isMention(), destination.getNoticeVersion(), now));
    }

    private static ProductFeedbackFiling savedForAdmins(ProductFeedback report, Instant now) {
        var filing = new ProductFeedbackFiling(report.getId(), FilingState.SAVED, false, now);
        filing.setRoute(FilingRoute.ADMINS);
        return filing;
    }

    @Transactional(readOnly = true)
    public List<MyProductFeedbackDTO> mine(long accountId, boolean instanceAdmin) {
        var byId = new HashMap<UUID, ProductFeedbackFiling>();
        filings.findAllByAccount(accountId).forEach(filing -> byId.put(filing.getFeedbackId(), filing));
        return feedback.findAllByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(report -> dto(report, byId.get(report.getId()), instanceAdmin))
                .sorted(Comparator.comparing(MyProductFeedbackDTO::createdAt).reversed())
                .toList();
    }

    @Transactional
    public MyProductFeedbackDTO keepPrivate(long accountId, UUID id) {
        ProductFeedback report = owned(accountId, id);
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow(() -> conflict("This report has no filing."));
        if (isOnGitHub(filing) || filing.getState() == FilingState.FILING)
            throw conflict("This report is already being filed. Use Make private once it is on GitHub.");
        keepPrivate(filing, clock.instant());
        return dto(report, filing);
    }

    private void keepPrivate(ProductFeedbackFiling filing, Instant now) {
        filing.setAllowPublic(false);
        filing.setFiledAfter(null);
        filing.setMentionLogin(null);
        if (filing.getState() == FilingState.HELD) {
            ProductFeedbackDestination destination = destinations.current();
            filing.setRoute(destination.getPrivateRepository() != null ? FilingRoute.PRIVATE : FilingRoute.ADMINS);
            filing.setReason(FilingReason.OPTED_OUT);
            // Routing froze the public repository. Left in place, filing would read it as a changed destination.
            filing.clearRecipient();
            filing.apply(FilingLifecycle.Event.KEPT_PRIVATE);
            filing.setNextAttemptAt(now);
            meters.route(Objects.requireNonNull(filing.getRoute()), FilingReason.OPTED_OUT);
        }
        filing.setUpdatedAt(now);
    }

    /**
     * Edits a held report. The model drafts the edited text again, redaction and the gate run on the new revision, the
     * risk latch stays, and the hold starts over.
     */
    @Transactional
    public MyProductFeedbackDTO edit(long accountId, UUID id, EditProductFeedbackDraftDTO request) {
        ProductFeedback report = owned(accountId, id);
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow(() -> conflict("This report has no filing."));
        if (filing.getState() != FilingState.HELD) throw conflict("This report can no longer be edited.");
        Instant now = clock.instant();
        routing.prepare(
                filing,
                report,
                request.title().strip() + "\n\n" + request.description().strip());
        filing.apply(FilingLifecycle.Event.EDITED);
        filing.setFiledAfter(now.plus(DraftingService.HOLD));
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
        fileIssues.draft(filing, report, now);
        return dto(report, filing);
    }

    /** Lets a private report be promoted with {@code /publish}, or takes that back while it is private. */
    @Transactional
    public MyProductFeedbackDTO allowPublic(long accountId, UUID id, boolean allow) {
        ProductFeedback report = owned(accountId, id);
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow(() -> conflict("This report has no filing."));
        if (filing.getRoute() != FilingRoute.PRIVATE) throw conflict("Only a private report can change this.");
        Instant now = clock.instant();
        if (allow) {
            ProductFeedbackDestination destination = destinations.current();
            if (destination.getPublicRepository() == null)
                throw conflict("This instance has no public repository for this report.");
            // Promotion reads the consent on the filing, and a report sent unticked has none.
            filing.setConsentId(ticked(accountId, currentConsent(accountId), destination, now)
                    .getId());
        }
        filing.setAllowPublic(allow);
        filing.setUpdatedAt(now);
        return dto(report, filing);
    }

    /** Moves the person's own public issue to the private repository. Copies made while it was public can remain. */
    public MyProductFeedbackDTO makePrivate(long accountId, UUID id) {
        var target = Objects.requireNonNull(transactions.execute(status -> {
            owned(accountId, id);
            ProductFeedbackFiling filing =
                    filings.findById(id).orElseThrow(() -> conflict("This report has no filing."));
            if (filing.getState() != FilingState.FILED || filing.getRoute() != FilingRoute.PUBLIC)
                throw conflict("Only a public issue can be made private.");
            String privateRepository = destinations.current().getPrivateRepository();
            if (privateRepository == null) throw conflict("This instance has no private repository to move it to.");
            return new MoveTarget(
                    Objects.requireNonNull(filing.getRepository()),
                    Objects.requireNonNull(filing.getIssueNumber()),
                    Objects.requireNonNull(filing.getIssueNodeId()),
                    Objects.requireNonNull(filing.getIssueUrl()),
                    privateRepository,
                    // A published report's issue carries the promotion marker; its old private issue, the report's.
                    filing.getPredecessorIssueNumber() == null
                            ? id.toString()
                            : IssueRenderer.promotionMarker(id).toString());
        }));
        var from =
                issues.repository(target.from()).orElseThrow(() -> conflict("The public repository is unreachable."));
        var to = issues.repository(target.to())
                .filter(ProductFeedbackIssues.Repository::isPrivate)
                .orElseThrow(() -> conflict("The private repository is unreachable or not private."));
        if (from.id() == to.id()) throw conflict("The public and private repositories are the same.");
        // The move's end takes the filing to the private repository (TransferIssueAdapter.ended).
        Instant now = clock.instant();
        var result = transfers
                .move(new TransferIssueAdapter.Payload(
                        id,
                        accountId,
                        target.marker(),
                        now,
                        to,
                        target.number(),
                        target.nodeId(),
                        target.url(),
                        // GitHub filters by the last update, and the move updates the issue.
                        now.minus(Duration.ofHours(1))))
                .orElseThrow(
                        () -> conflict("Another change of this report is on its way to GitHub. Try again shortly."));
        switch (result) {
            case ActionResult.Done done -> meters.step("transfer", "ok");
            case ActionResult.Failed failed -> throw conflict("GitHub refused to move the issue.");
            default ->
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "GitHub did not confirm the move. Hephaestus checks it again and finishes it.");
        }
        return Objects.requireNonNull(transactions.execute(
                status -> dto(owned(accountId, id), filings.findById(id).orElseThrow())));
    }

    private record MoveTarget(String from, long number, String nodeId, String url, String to, String marker) {}

    private ProductFeedback owned(long accountId, UUID id) {
        return feedback.findById(id)
                .filter(report -> report.getAccountId() == accountId)
                .orElseThrow(() -> new EntityNotFoundException("Product feedback", id.toString()));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static boolean isOnGitHub(ProductFeedbackFiling filing) {
        return filing.getState() == FilingState.FILED;
    }

    MyProductFeedbackDTO dto(ProductFeedback report, @Nullable ProductFeedbackFiling filing) {
        return dto(report, filing, false);
    }

    /** @param instanceAdmin the reader administers the instance, so a private issue's address tells them nothing new */
    MyProductFeedbackDTO dto(ProductFeedback report, @Nullable ProductFeedbackFiling filing, boolean instanceAdmin) {
        String message = report.getMessage().strip();
        if (filing == null)
            return new MyProductFeedbackDTO(
                    report.getId(),
                    report.getKind(),
                    message,
                    Objects.requireNonNull(report.getCreatedAt()),
                    ReportState.SAVED_FOR_ADMINS,
                    ReportRoute.ADMINS,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    false);
        ReportState state = FilingLifecycle.reporterState(filing.getState(), filing.getRoute());
        FilingRoute route = filing.getRoute();
        ReportRoute shown = route == null
                ? (filing.isAllowPublic() ? ReportRoute.PUBLIC : ReportRoute.PRIVATE)
                : ReportRoute.valueOf(route.name());
        Draft draft = routing.read(filing.getDraft());
        boolean isPublicIssue = filing.getState() == FilingState.FILED && route == FilingRoute.PUBLIC;
        boolean showsIssue = isPublicIssue || (instanceAdmin && filing.getState() == FilingState.FILED);
        FilingReason reason = filing.getReason();
        boolean held = filing.getState() == FilingState.HELD;
        boolean filed = filing.getState() == FilingState.FILED;
        return new MyProductFeedbackDTO(
                report.getId(),
                report.getKind(),
                message,
                Objects.requireNonNull(report.getCreatedAt()),
                state,
                shown,
                reason == null || reason == FilingReason.NONE ? null : reason.words(),
                held || state == ReportState.DRAFTING ? filing.getFiledAfter() : null,
                showsIssue ? filing.getIssueUrl() : null,
                showsIssue ? filing.getIssueNumber() : null,
                Boolean.FALSE.equals(filing.getIssueOpen()) ? filing.getIssueStateReason() : null,
                filed ? filing.getIssueTitle() : null,
                filed ? filing.getIssueText() : null,
                filing.getRemoved(),
                draft == null || filing.getDraftedAt() == null ? null : draft.title(),
                draft == null || !held ? null : draft.type() == Draft.Type.BUG ? draft.observed() : draft.problem(),
                filing.isAllowPublic(),
                held
                        || (filing.isAllowPublic()
                                && (filing.getState() == FilingState.DRAFT_QUEUED
                                        || filing.getState() == FilingState.DRAFTING
                                        || filing.getState() == FilingState.DRAFT_WAITING
                                        || filing.getState() == FilingState.ANSWERED)),
                held && draft != null && filing.getDraftedAt() != null,
                isPublicIssue && destinations.current().getPrivateRepository() != null,
                route == FilingRoute.PRIVATE
                        && !filing.isRiskLatch()
                        && filing.getDraftedAt() != null
                        && destinations.current().getPublicRepository() != null);
    }
}
