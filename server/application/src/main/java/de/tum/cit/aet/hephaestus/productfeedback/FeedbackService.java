package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackFilter;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackItemDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class FeedbackService {
    private final ProductFeedbackRepository feedback;
    private final FeedbackRefs refs;
    private final Clock clock;
    private final String appVersion;

    FeedbackService(
            ProductFeedbackRepository feedback,
            FeedbackRefs refs,
            Clock clock,
            @Value("${spring.application.version}") String appVersion) {
        this.feedback = feedback;
        this.refs = refs;
        this.clock = clock;
        this.appVersion = appVersion;
    }

    @Transactional
    public ProductFeedback add(FeedbackRequestDTO request, Long accountId, @Nullable Long workspaceId) {
        try {
            return feedback.saveAndFlush(new ProductFeedback(
                    accountId,
                    workspaceId,
                    request.kind(),
                    request.message(),
                    request.pagePath(),
                    request.userAgent(),
                    appVersion));
        } catch (DataIntegrityViolationException exception) {
            if (DataIntegrityViolationConstraints.hasName(exception, "uk_product_feedback_rate_limit")) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "feedback is limited to once per minute", exception);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public Page<FeedbackItemDTO> list(FeedbackFilter filter, Pageable pageable) {
        Page<ProductFeedback> page =
                switch (filter) {
                    case OPEN -> feedback.findAllByResolvedAtIsNullOrderByCreatedAtDesc(pageable);
                    case RESOLVED -> feedback.findAllByResolvedAtIsNotNullOrderByResolvedAtDesc(pageable);
                    case ALL -> feedback.findAllByOrderByCreatedAtDesc(pageable);
                };
        return new PageImpl<>(dtos(page.getContent()), pageable, page.getTotalElements());
    }

    @Transactional
    public FeedbackItemDTO triage(UUID id, boolean resolved, Long accountId) {
        ProductFeedback item =
                feedback.findById(id).orElseThrow(() -> new EntityNotFoundException("Product feedback", id.toString()));
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
        FeedbackRefs.Resolved resolved = refs.resolve(
                accountIds, list.stream().map(ProductFeedback::getWorkspaceId).toList());
        return list.stream()
                .map(f -> new FeedbackItemDTO(
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
                        resolved.account(f.getResolvedByAccountId())))
                .toList();
    }
}
