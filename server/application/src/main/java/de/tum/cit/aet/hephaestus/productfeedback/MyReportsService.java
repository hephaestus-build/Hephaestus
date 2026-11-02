package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.EditProductFeedbackDraftDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.MyProductFeedbackDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackSharingDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ReportRoute;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ReportState;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackSharingDTO;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** What a person does with their own reports and their sharing choice. */
@Service
@ConditionalOnServerRole
class MyReportsService {
    private static final int EXCERPT = 140;

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final ProductFeedbackConsentRepository consents;
    private final DestinationSettings destinations;
    private final RoutingService routing;
    private final GitHubLogins logins;
    private final ProductFeedbackIssues issues;
    private final ProductFeedbackIssueUpdates updates;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final Clock clock;

    MyReportsService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            ProductFeedbackConsentRepository consents,
            DestinationSettings destinations,
            RoutingService routing,
            GitHubLogins logins,
            ProductFeedbackIssues issues,
            ProductFeedbackIssueUpdates updates,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            Clock clock) {
        this.filings = filings;
        this.feedback = feedback;
        this.consents = consents;
        this.destinations = destinations;
        this.routing = routing;
        this.logins = logins;
        this.issues = issues;
        this.updates = updates;
        this.events = events;
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
            for (ProductFeedbackFiling filing : filings.findAllByAccount(accountId)) {
                if (!filing.isAllowPublic()) continue;
                if (!isOnGitHub(filing)) keepPrivate(filing, now);
                else if (filing.getRoute() == FilingRoute.PRIVATE) filing.setAllowPublic(false);
            }
        }
        return sharingOf(accountId);
    }

    /**
     * Opens a report's filing at submission. Returns the route it is heading for. Without a destination, or while
     * paused, the report goes to the admin email as before.
     *
     * @param share the switch on the composer; null keeps the remembered choice
     */
    ProductFeedbackFiling open(ProductFeedback report, @Nullable Boolean share, Instant now) {
        ProductFeedbackDestination destination = destinations.current();
        if (!destination.configured() || destination.isPaused()) return filings.save(emailed(report, now));
        boolean publicDestination = destination.getPublicRepository() != null;
        ProductFeedbackConsent consent = currentConsent(report.getAccountId());
        if (publicDestination && consent == null) {
            if (share == null)
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose whether your reports can become public GitHub issues.");
            consent = consents.save(new ProductFeedbackConsent(
                    report.getAccountId(), share, false, destination.getNoticeVersion(), now));
        }
        boolean allowPublic = publicDestination && (share != null ? share : consent != null && consent.isAllowPublic());
        var filing = new ProductFeedbackFiling(report.getId(), FilingState.DRAFT_QUEUED, allowPublic, now);
        if (consent != null) filing.setConsentId(consent.getId());
        routing.prepare(filing, report, report.getMessage());
        if (allowPublic) {
            filing.setFiledAfter(now.plus(DraftingService.HOLD));
            if (consent != null && consent.isMention())
                filing.setMentionLogin(logins.of(report.getAccountId()).orElse(null));
        }
        return filings.save(filing);
    }

    private static ProductFeedbackFiling emailed(ProductFeedback report, Instant now) {
        var filing = new ProductFeedbackFiling(report.getId(), FilingState.EMAILED, false, now);
        filing.setRoute(FilingRoute.EMAIL);
        return filing;
    }

    @Transactional(readOnly = true)
    public List<MyProductFeedbackDTO> mine(long accountId) {
        var byId = new HashMap<UUID, ProductFeedbackFiling>();
        filings.findAllByAccount(accountId).forEach(filing -> byId.put(filing.getFeedbackId(), filing));
        return feedback.findAllByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(report -> dto(report, byId.get(report.getId())))
                .sorted(Comparator.comparing(MyProductFeedbackDTO::createdAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public MyProductFeedbackDTO get(long accountId, UUID id) {
        ProductFeedback report = owned(accountId, id);
        return dto(report, filings.findById(id).orElse(null));
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
            filing.setRoute(destination.getPrivateRepository() != null ? FilingRoute.PRIVATE : FilingRoute.EMAIL);
            filing.setReason(FilingReason.OPTED_OUT);
            filing.setState(FilingState.QUEUED);
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
        filing.setState(FilingState.DRAFT_QUEUED);
        filing.setFiledAfter(now.plus(DraftingService.HOLD));
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
        return dto(report, filing);
    }

    /** Lets a private report be promoted with {@code /publish}, or takes that back while it is private. */
    @Transactional
    public MyProductFeedbackDTO allowPublic(long accountId, UUID id, boolean allow) {
        ProductFeedback report = owned(accountId, id);
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow(() -> conflict("This report has no filing."));
        if (filing.getRoute() != FilingRoute.PRIVATE) throw conflict("Only a private report can change this.");
        filing.setAllowPublic(allow);
        filing.setUpdatedAt(clock.instant());
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
                    privateRepository);
        }));
        var from =
                issues.repository(target.from()).orElseThrow(() -> conflict("The public repository is unreachable."));
        var to = issues.repository(target.to())
                .filter(ProductFeedbackIssues.Repository::isPrivate)
                .orElseThrow(() -> conflict("The private repository is unreachable or not private."));
        if (from.id() == to.id()) throw conflict("The public and private repositories are the same.");
        var moved =
                updates.transfer(new ProductFeedbackIssues.Issue(target.number(), target.nodeId(), target.url()), to);
        meters.step("transfer", "ok");
        return Objects.requireNonNull(transactions.execute(status -> {
            ProductFeedback report = owned(accountId, id);
            ProductFeedbackFiling filing = filings.lock(id).orElseThrow();
            Instant now = clock.instant();
            filing.setRoute(FilingRoute.PRIVATE);
            filing.setReason(FilingReason.OPTED_OUT);
            filing.setAllowPublic(false);
            filing.setRepository(to.fullName());
            filing.setRepositoryId(to.id());
            filing.setRepositoryNodeId(to.nodeId());
            filing.setInstallationId(to.installationId());
            filing.setIssueNumber(moved.number());
            filing.setIssueNodeId(moved.nodeId());
            filing.setIssueUrl(moved.url());
            filing.setUpdatedAt(now);
            events.publishEvent(new ProductFeedbackUpdatedEvent(
                    id, accountId, ProductFeedbackUpdatedEvent.Kind.MADE_PRIVATE, null, false, null, now));
            return dto(report, filing);
        }));
    }

    private record MoveTarget(String from, long number, String nodeId, String url, String to) {}

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
        String message = report.getMessage().strip();
        String excerpt = message.length() <= EXCERPT ? message : message.substring(0, EXCERPT - 1) + "…";
        if (filing == null)
            return new MyProductFeedbackDTO(
                    report.getId(),
                    report.getKind(),
                    excerpt,
                    Objects.requireNonNull(report.getCreatedAt()),
                    ReportState.SENT_TO_ADMINS,
                    ReportRoute.EMAIL,
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
        ReportState state = switch (filing.getState()) {
            case DRAFT_QUEUED, DRAFTING, ANSWERED -> ReportState.DRAFTING;
            case HELD -> ReportState.HELD;
            case QUEUED, FILING -> ReportState.FILING;
            case FILED -> ReportState.FILED;
            case EMAILED -> ReportState.SENT_TO_ADMINS;
            case STOPPED -> ReportState.STOPPED;
        };
        FilingRoute route = filing.getRoute();
        ReportRoute shown = route == null
                ? (filing.isAllowPublic() ? ReportRoute.PUBLIC : ReportRoute.PRIVATE)
                : ReportRoute.valueOf(route.name());
        Draft draft = routing.read(filing.getDraft());
        boolean isPublicIssue = filing.getState() == FilingState.FILED && route == FilingRoute.PUBLIC;
        FilingReason reason = filing.getReason();
        boolean held = filing.getState() == FilingState.HELD;
        return new MyProductFeedbackDTO(
                report.getId(),
                report.getKind(),
                excerpt,
                Objects.requireNonNull(report.getCreatedAt()),
                state,
                shown,
                reason == null || reason == FilingReason.NONE ? null : reason.words(),
                held || state == ReportState.DRAFTING ? filing.getFiledAfter() : null,
                isPublicIssue ? filing.getIssueUrl() : null,
                isPublicIssue ? filing.getIssueNumber() : null,
                Boolean.FALSE.equals(filing.getIssueOpen()) ? filing.getIssueStateReason() : null,
                filing.getRemoved(),
                draft == null || filing.getDraftedAt() == null ? null : draft.title(),
                draft == null || !held ? null : draft.type() == Draft.Type.BUG ? draft.observed() : draft.problem(),
                filing.isAllowPublic(),
                held
                        || (filing.isAllowPublic()
                                && (filing.getState() == FilingState.DRAFT_QUEUED
                                        || filing.getState() == FilingState.DRAFTING
                                        || filing.getState() == FilingState.ANSWERED)),
                held && draft != null && filing.getDraftedAt() != null,
                isPublicIssue && destinations.current().getPrivateRepository() != null,
                route == FilingRoute.PRIVATE && !filing.isRiskLatch() && filing.getDraftedAt() != null);
    }
}
