package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredWorkFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredWorkFeedbackItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DeliveredWorkFeedbackService {
    private final ProductItemQuery items;
    private final ProductReferenceQuery references;
    private final DeliveredWorkFeedbackRepository ledger;
    private final CurrentDeveloperLookup developer;
    private final ResultRepository results;
    private final ResultVisibilityPolicy visibility;

    @Transactional(readOnly = true)
    public DeliveredWorkFeedbackDTO get(long ws, String url) {
        var item = items.atUrl(ws, url);
        if (item.isEmpty()) return new DeliveredWorkFeedbackDTO(null, List.of());
        var ref = references
                .items(ws, List.of(item.get().itemId()))
                .get(item.get().itemId());
        var account = developer.currentDeveloperId();
        if (account.isEmpty()) return new DeliveredWorkFeedbackDTO(ref, List.of());
        var feedback = ledger.findDelivered(ws, account.get(), item.get().itemId()).stream()
                .map(f -> {
                    var basis = ledger.basis(ws, f.id());
                    var allowed = visibility.permitsHistory(
                            ws,
                            results.findByWorkspaceIdAndIdIn(ws, basis).stream()
                                    .map(Result::facts)
                                    .toList(),
                            SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
                    return new DeliveredWorkFeedbackItemDTO(
                            f.id(),
                            f.deliveredAt(),
                            allowed.containsAll(basis) ? f.practices() : List.of(),
                            f.copies());
                })
                .toList();
        return new DeliveredWorkFeedbackDTO(ref, feedback);
    }
}
