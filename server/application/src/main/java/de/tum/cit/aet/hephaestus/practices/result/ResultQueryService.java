package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultDetailDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultQueryService {
    private final CurrentDeveloperLookup developer;
    private final ResultRepository results;
    private final ResultViewQuery views;
    private final ResultPresentation presentation;
    private final EvidenceAuthorization authorization;

    public Page<ResultListDTO> listOwnResults(long ws, ResultFeedQuery query, Pageable page) {
        long developer = this.developer.currentDeveloperIdElseThrow();
        var filter = new ResultQueryFilter(
                null,
                query.itemId(),
                query.itemKind(),
                query.practiceSlug(),
                query.groupSlug(),
                developer,
                query.outcome() == null ? null : List.of(query.outcome()),
                query.severities(),
                null,
                null,
                null,
                null,
                null,
                null);
        var rows = results.list(ws, filter, query.sort(), query.displayableOnly(), page);
        var permitted = authorization.permitsAll(ws, rows.getContent(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var projected = presentation.lists(ws, views.views(ws, rows.getContent()), permitted);
        return new PageImpl<>(projected, page, rows.getTotalElements());
    }

    public ResultDetailDTO ownResult(long ws, UUID id) {
        long developer = this.developer.currentDeveloperIdElseThrow();
        var r = results.findByIdAndDeveloperIdAndWorkspaceId(id, developer, ws)
                .orElseThrow(() -> new EntityNotFoundException("Result", id.toString()));
        return presentation.detail(
                views.views(ws, List.of(r)).getFirst(),
                authorization.permits(ws, r, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY));
    }
}
