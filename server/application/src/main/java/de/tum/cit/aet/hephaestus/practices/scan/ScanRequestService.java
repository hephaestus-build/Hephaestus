package de.tum.cit.aet.hephaestus.practices.scan;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.StartScanRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScanRequestService {
    private final ReviewService service;
    private final PracticeRepository practices;
    private final ScanReportService reports;
    private final ScanQueryService query;
    private final JsonMapper json;

    /** Not transactional: the request pins the repository, which fetches its mirror, outside any transaction. */
    public ScanSummaryDTO start(long workspaceId, long actorId, UUID key, StartScanRequestDTO request) {
        long repository = PracticeReviewCoverageService.positiveId(request.repositoryItemId());
        var budget = request.budgetUsd();
        var selected = request.practiceSlugs() == null
                ? List.<Long>of()
                : practices.findByWorkspaceId(workspaceId).stream()
                        .filter(p -> request.practiceSlugs().contains(p.getSlug()))
                        .map(Practice::getId)
                        .sorted()
                        .toList();
        if (request.practiceSlugs() != null
                && selected.size() != request.practiceSlugs().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select practices from this workspace.");
        // A replay carries the digest of its first request, whose ref and version the frozen scan already matched.
        var id = service.request(ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(repository)
                .kind(ReviewKind.SCAN)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(actorId)
                .budgetUsd(budget)
                .requestKey(key)
                .requestDigest(digest(repository, request))
                .practiceIds(selected)
                .reuse(request.reuse() == null || request.reuse())
                .scanRef(request.ref())
                .expectedVersion(request.expectedVersion())
                .build());
        return query.summary(reports.require(workspaceId, id));
    }

    private String digest(long repository, StartScanRequestDTO request) {
        var body = json.createObjectNode();
        body.put("repositoryItemId", Long.toString(repository));
        body.set("ref", json.valueToTree(request.ref()));
        body.set("expectedVersion", json.valueToTree(request.expectedVersion()));
        body.set(
                "practiceSlugs",
                json.valueToTree(
                        request.practiceSlugs() == null
                                ? null
                                : request.practiceSlugs().stream().sorted().toList()));
        body.put("budgetUsd", request.budgetUsd().stripTrailingZeros().toPlainString());
        body.put("reuse", request.reuse() == null || request.reuse());
        return ProblemComparison.sha256(json.writeValueAsString(body));
    }
}
