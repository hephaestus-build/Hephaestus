package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackFilter;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackItemDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
class FeedbackService {
    static final int ACCOUNT_DAILY_REPORTS = 5;
    private final ProductFeedbackRepository feedback;
    private final FeedbackRefs refs;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final String appVersion;
    private final MyReportsService reports;
    private final DestinationSettings destinations;
    private final ProductFeedbackFilingRepository filings;

    FeedbackService(
            ProductFeedbackRepository feedback,
            FeedbackRefs refs,
            Clock clock,
            ApplicationEventPublisher events,
            @Value("${spring.application.version}") String appVersion,
            MyReportsService reports,
            DestinationSettings destinations,
            ProductFeedbackFilingRepository filings) {
        this.feedback = feedback;
        this.refs = refs;
        this.clock = clock;
        this.events = events;
        this.appVersion = appVersion;
        this.reports = reports;
        this.destinations = destinations;
        this.filings = filings;
    }

    @Transactional
    public FilingDTOs.FeedbackReceiptDTO add(
            FeedbackRequestDTO request, Long accountId, @Nullable Long workspaceId, boolean instanceAdmin) {
        if (!instanceAdmin) {
            feedback.lockSubmission(accountId);
            if (feedback.countByAccountIdAndCreatedAtGreaterThanEqual(
                            accountId, clock.instant().truncatedTo(ChronoUnit.DAYS))
                    >= ACCOUNT_DAILY_REPORTS) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "You can send up to %d reports per day. Try again tomorrow (UTC)."
                                .formatted(ACCOUNT_DAILY_REPORTS));
            }
        }
        try {
            ProductFeedback saved = feedback.saveAndFlush(new ProductFeedback(
                    accountId,
                    workspaceId,
                    request.kind(),
                    request.message(),
                    request.pagePath(),
                    request.userAgent(),
                    appVersion));
            ProductFeedbackFiling filing = reports.open(saved, request.share(), clock.instant());
            if (filing.getState() == FilingState.SAVED) {
                events.publishEvent(new ProductFeedbackSubmittedEvent(
                        saved.getId(),
                        Objects.requireNonNull(saved.getCreatedAt()),
                        switch (saved.getKind()) {
                            case BUG -> ProductFeedbackSubmittedEvent.Kind.BUG;
                            case IDEA -> ProductFeedbackSubmittedEvent.Kind.IDEA;
                            case FEEDBACK -> ProductFeedbackSubmittedEvent.Kind.FEEDBACK;
                        }));
                return new FilingDTOs.FeedbackReceiptDTO(
                        saved.getId(), FilingDTOs.ReportRoute.ADMINS, FilingDTOs.ReportState.SAVED_FOR_ADMINS, null);
            }
            FilingDTOs.ReportRoute route = filing.isAllowPublic()
                    ? FilingDTOs.ReportRoute.PUBLIC
                    : destinationHasPrivate() ? FilingDTOs.ReportRoute.PRIVATE : FilingDTOs.ReportRoute.ADMINS;
            return new FilingDTOs.FeedbackReceiptDTO(
                    saved.getId(), route, FilingDTOs.ReportState.DRAFTING, filing.getFiledAfter());
        } catch (DataIntegrityViolationException exception) {
            if (DataIntegrityViolationConstraints.hasName(exception, "uk_product_feedback_rate_limit")) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "You can send feedback only once per minute.", exception);
            }
            throw exception;
        }
    }

    private boolean destinationHasPrivate() {
        return destinations.current().getPrivateRepository() != null;
    }

    @Transactional(readOnly = true)
    public Page<FeedbackItemDTO> list(FeedbackFilter filter, Pageable pageable) {
        Page<ProductFeedback> page = feedback.findForAdmin(filter.name(), pageable);
        return new PageImpl<>(dtos(page.getContent()), pageable, page.getTotalElements());
    }

    @Transactional
    public FeedbackItemDTO triage(UUID id, boolean resolved, Long accountId) {
        ProductFeedback item =
                feedback.findById(id).orElseThrow(() -> new EntityNotFoundException("Product feedback", id.toString()));
        // Reports that are filed, or on their way, are worked on in GitHub.
        boolean onGitHub = filings.lock(id)
                .map(filing -> !FilingLifecycle.waitsForAdmin(filing.getState()))
                .orElse(false);
        if (onGitHub)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This report goes to GitHub. Work on it there, not in this list.");
        if (resolved) item.resolve(accountId, clock.instant());
        else item.reopen();
        return dtos(List.of(item)).getFirst();
    }

    private List<FeedbackItemDTO> dtos(List<ProductFeedback> list) {
        List<@Nullable Long> accountIds = new ArrayList<>();
        for (ProductFeedback f : list) {
            accountIds.add(f.getAccountId());
            accountIds.add(f.getResolvedByAccountId());
        }
        ProductFeedbackDestination destination = destinations.current();
        Set<UUID> fileable = new HashSet<>();
        Set<UUID> undrafted = new HashSet<>();
        Map<UUID, ProductFeedbackFiling> byId = new HashMap<>();
        for (ProductFeedbackFiling filing :
                filings.findAllById(list.stream().map(ProductFeedback::getId).toList())) {
            byId.put(filing.getFeedbackId(), filing);
            if (filing.canFileAt(destination)) fileable.add(filing.getFeedbackId());
            if (!filing.isFileDisclosed()) undrafted.add(filing.getFeedbackId());
        }
        FeedbackRefs.Resolved resolved = refs.resolve(
                accountIds, list.stream().map(ProductFeedback::getWorkspaceId).toList());
        return list.stream()
                .map(f -> item(f, byId.get(f.getId()), resolved, fileable, undrafted))
                .toList();
    }

    private FeedbackItemDTO item(
            ProductFeedback f,
            @Nullable ProductFeedbackFiling filing,
            FeedbackRefs.Resolved resolved,
            Set<UUID> fileable,
            Set<UUID> undrafted) {
        var shown = reports.dto(f, filing, true);
        return new FeedbackItemDTO(
                f.getId(),
                resolved.account(f.getAccountId()),
                resolved.workspace(f.getWorkspaceId()),
                f.getKind(),
                f.getMessage(),
                f.getPagePath(),
                f.getUserAgent(),
                f.getAppVersion(),
                Objects.requireNonNull(f.getCreatedAt()),
                f.getResolvedAt(),
                resolved.account(f.getResolvedByAccountId()),
                !f.isResolved() && fileable.contains(f.getId()),
                undrafted.contains(f.getId()),
                shown.state(),
                shown.route(),
                shown.reason(),
                shown.issueUrl(),
                shown.issueNumber(),
                shown.issueClosedAs(),
                shown.issueTitle(),
                shown.issueText(),
                filing == null || FilingLifecycle.waitsForAdmin(filing.getState()));
    }
}
