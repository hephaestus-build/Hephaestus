package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceAdminAssurance;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeDrafts;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.review.dto.StartTrialRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialCitationDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialOutcomeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialProblemDTO;
import de.tum.cit.aet.hephaestus.practices.review.report.TrialReport;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts and reads trials: a workspace admin runs one practice on one item through capture, the runner and admission,
 * and reads what it found, while no observation records it (ADR 0118 decisions 8 to 10).
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeTrialService {
    private final ProductItemQuery items;
    private final PracticeDrafts drafts;
    private final PracticeVersionRepository versions;
    private final ReviewService reviews;
    private final ReviewRepository reviewRows;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository memberships;
    private final WorkspaceAdminAssurance adminAssurance;
    private final ItemDescriptorRegistry descriptors;
    private final MemberAiPreferences preferences;
    private final JsonMapper json;

    /**
     * Requests the trial. It runs with no transaction, as a review request does: the pin and source reads fetch from
     * the provider. The caller is a workspace admin; a trial reads someone else's work, so it needs the admin
     * passkey proof that such a request needs.
     */
    public UUID start(long workspaceId, StartTrialRequestDTO request, long accountId) {
        adminAssurance.require(true);
        // A draft practice exists to be tried, so the trial reads drafts too.
        var practice = drafts.find(workspaceId, request.practiceSlug())
                .orElseThrow(() -> new EntityNotFoundException("Practice", request.practiceSlug()));
        var item = item(workspaceId, request);
        // ADR 0118 decision 8: in 1.0 a trial reads GitHub and GitLab work only. A kind without roles, such as a
        // repository, has nobody to review, so it is refused before its pin fetches anything.
        if (!item.kind().value().startsWith("scm.")
                || descriptors
                        .descriptorFor(item.kind())
                        .map(d -> d.roles().isEmpty())
                        .orElse(true))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "A trial can read a GitHub or GitLab pull request, merge request or issue.");
        return reviews.request(ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(item.itemId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.TRIAL)
                .requestedById(accountId)
                .practiceIds(List.of(practice.getId()))
                .practiceVersionId(
                        request.practiceVersionId() == null ? null : Long.parseLong(request.practiceVersionId()))
                .build());
    }

    private ItemRef item(long workspaceId, StartTrialRequestDTO request) {
        var url = request.itemUrl();
        if (url != null)
            return items.atUrl(workspaceId, url)
                    .orElseThrow(() -> new ReviewRequestRejected(
                            ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                            "No pull request, merge request or issue of this workspace has this address."));
        return items.require(workspaceId, Long.parseLong(Objects.requireNonNull(request.itemId())));
    }

    @Transactional(readOnly = true)
    public TrialDTO get(long workspaceId, UUID trialId) {
        var review = reviewRows
                .findByIdAndWorkspaceId(trialId, workspaceId)
                .filter(found -> found.getOrigin() == ReviewOrigin.TRIAL)
                .orElseThrow(() -> new EntityNotFoundException("Trial", trialId.toString()));
        var assignment = assignments
                .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, trialId)
                .getFirst();
        var version = versions.findByIdAndWorkspaceId(
                        Objects.requireNonNull(assignment.getPracticeVersionId()), workspaceId)
                .orElseThrow();
        // Person erasure can remove the membership; the trial then reads as one whose report was cleared.
        var member =
                memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, trialId).stream()
                        .findFirst()
                        .orElse(null);
        var report = review.getReport() == null ? null : json.treeToValue(review.getReport(), TrialReport.class);
        var candidate = report == null || report.candidates().isEmpty()
                ? null
                : report.candidates().getFirst();
        boolean outcomeRemoved = report == null
                && review.getStatus().isTerminal()
                && (member == null || member.getState() == ReviewItemState.TRIED);
        return new TrialDTO(
                review.getId(),
                review.getStatus(),
                review.getStopReason(),
                version.getPractice().getSlug(),
                String.valueOf(version.getId()),
                version.getNumber(),
                version.getPractice().getState(),
                Objects.equals(
                        version.getId(),
                        version.getPractice().getCurrentVersion().getId()),
                String.valueOf(review.getItemId()),
                review.getItemVersion(),
                review.getCreatedAt(),
                review.getCompletedAt(),
                member == null ? null : member.getReason(),
                candidate == null ? null : outcome(candidate),
                outcomeRemoved);
    }

    /**
     * Whether every person whose work the trial read chose Cloud. Only then may an admin's MCP client, which sends what
     * it reads to the admin's own model, read what the trial found (ADR 0110, update of 2026-11-16). Work that the
     * review attributes to nobody has no one who chose, so it does not qualify.
     */
    @Transactional(readOnly = true)
    public boolean readsOnlyCloudWork(long workspaceId, UUID trialId) {
        var members = memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, trialId);
        return !members.isEmpty()
                && members.stream().allMatch(member -> {
                    var developer =
                            member.getDetail() == null ? null : member.getDetail().get("developerId");
                    return developer != null
                            && developer.isIntegralNumber()
                            && preferences
                                            .forDeveloper(workspaceId, developer.longValue())
                                            .choice()
                                    == AiChoice.CLOUD;
                });
    }

    private static TrialOutcomeDTO outcome(ResultCandidate candidate) {
        return new TrialOutcomeDTO(
                candidate.outcome(),
                candidate.summary(),
                candidate.explanation(),
                candidate.problems().stream()
                        .map(problem -> new TrialProblemDTO(
                                problem.severity(),
                                problem.summary(),
                                problem.explanation(),
                                citations(problem.citations()),
                                problem.nextStep()))
                        .toList(),
                citations(candidate.evidence().citations()));
    }

    private static List<TrialCitationDTO> citations(List<EvidenceCitation> citations) {
        return citations.stream()
                .map(citation -> new TrialCitationDTO(
                        citation.sourceKind().value(),
                        citation.path(),
                        citation.startLine(),
                        citation.endLine(),
                        citation.quote()))
                .toList();
    }
}
