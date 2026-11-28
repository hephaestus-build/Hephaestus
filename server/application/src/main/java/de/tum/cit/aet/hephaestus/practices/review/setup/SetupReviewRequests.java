package de.tum.cit.aet.hephaestus.practices.review.setup;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRequestRejected;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRequestTransactions;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.review.setup.ReviewSetupRepository.SetupForRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedItem;
import de.tum.cit.aet.hephaestus.practices.spi.SetupReviewRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts a review from a review setup (ADR 0167 decision 1). The setup gives the practices, the budget and the
 * audience, and the review request of every review records it, with its checks of the key, the branch and the commit.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SetupReviewRequests {
    private static final String REPOSITORY = "scm.repository";

    private final ReviewSetupRepository setups;
    private final ReviewRequestTransactions transactions;
    private final ReviewService reviews;
    private final JsonMapper json;

    private record Setup(SetupForRequest setup, List<Long> practiceIds) {}

    /**
     * Not transactional: the review request pins the repository, which fetches its mirror, outside any transaction.
     *
     * @return the new review, or the review that the request key already started
     */
    public UUID request(SetupReviewRequest request) {
        var read = transactions
                .read(() -> setups.findForRequest(request.workspaceId(), request.setupId())
                        .filter(setup -> setup.enabled() && setup.removedAt() == null)
                        .map(setup ->
                                new Setup(setup, setups.findPracticeIds(request.workspaceId(), request.setupId()))))
                // Missing, of another workspace, removed or turned off: the caller cannot tell them apart.
                .orElseThrow(() -> new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.SETUP_UNAVAILABLE, "This review setup does not exist."));
        if (read.practiceIds().isEmpty()) throw unsupported("This review setup selects no practice.");
        return switch (request.item()) {
            case ReviewedItem.RepositoryCommit item -> reviews.request(repositoryCommit(request, read, item));
            // The submitted version's arm comes with the team's request writer.
            case ReviewedItem.SubmittedVersion _ ->
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                        "A review setup cannot review a submitted version yet.");
        };
    }

    private ReviewRequest repositoryCommit(SetupReviewRequest request, Setup read, ReviewedItem.RepositoryCommit item) {
        var setup = read.setup();
        if (!setup.itemKind().equals(REPOSITORY))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "This review setup does not review a repository.");
        switch (setup.audience()) {
            case OPERATIONAL -> {}
            // Until the team audience check reads the team's members and its repositories.
            case TEAM -> throw unsupported("A team review of a repository is not available yet.");
            case PERSONAL -> throw unsupported("A review of a repository is for the workspace admins, not one person.");
        }
        var budget = setup.budgetUsd();
        if (budget == null) throw unsupported("This review setup has no budget, which a review of a repository needs.");
        // A request with no commit pins the head; with a commit, the planner refuses one that is not the head.
        return ReviewRequest.builder()
                .workspaceId(request.workspaceId())
                .itemId(item.repositoryItemId())
                .kind(ReviewKind.SCAN)
                .origin(request.origin())
                .requestedById(request.requestedBy())
                .budgetUsd(budget)
                .requestKey(request.requestKey())
                .requestDigest(digest(request.setupId(), item))
                .practiceIds(read.practiceIds())
                .reuse(true)
                .scanRef(item.ref())
                .expectedVersion(item.commit())
                .setupId(request.setupId())
                .audience(setup.audience())
                .build();
    }

    /** What the request key stands for: another input with the same key is refused. */
    private String digest(UUID setupId, ReviewedItem.RepositoryCommit item) {
        var body = json.createObjectNode();
        body.put("setupId", setupId.toString());
        body.put("repositoryItemId", Long.toString(item.repositoryItemId()));
        body.put("ref", item.ref());
        body.put("commit", item.commit());
        return ProblemComparison.sha256(json.writeValueAsString(body));
    }

    private static ReviewRequestRejected unsupported(String message) {
        return new ReviewRequestRejected(ReviewRequestRejected.Reason.SETUP_UNSUPPORTED, message);
    }
}
