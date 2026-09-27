package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.WorkItemQueryRepository.RequestedReviewer;
import de.tum.cit.aet.hephaestus.activity.overview.WorkItemQueryRepository.StandingReview;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO.ReviewerState;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemListDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What is open for one member right now. */
@Service
@RequiredArgsConstructor
public class OpenWorkService {

    /** Enough to see what is waiting; the provider's own lists are one link away. */
    static final int LIMIT = 50;

    /** Comments first, then verdicts, each oldest first: the last one is where the reviewer stands. */
    private static final Comparator<StandingReview> STANDING = Comparator.comparing(
                    (StandingReview review) -> review.getState() != PullRequestReview.State.COMMENTED)
            .thenComparing(StandingReview::getSubmittedAt)
            .thenComparing(StandingReview::getId);

    private static final Comparator<ReviewerDTO> LISTED = Comparator.comparing(ReviewerDTO::state)
            .thenComparing(reviewer -> reviewer.user().name(), ActivityScopeResolver.NAMES)
            .thenComparing(reviewer -> reviewer.user().login(), ActivityScopeResolver.NAMES);

    private final WorkItemQueryRepository workItems;
    private final ActivityScopeResolver scopes;

    @Transactional(readOnly = true)
    public OpenWorkDTO openWork(long workspaceId, String login) {
        long userId = scopes.member(workspaceId, login).getId();
        Pageable first = PageRequest.ofSize(LIMIT);
        Slice<PullRequest> reviewRequests = workItems.findReviewRequests(workspaceId, userId, first);
        Slice<PullRequest> pullRequests = workItems.findOpenPullRequests(workspaceId, userId, first);
        Map<Long, List<ReviewerDTO>> reviewers =
                reviewers(Stream.concat(reviewRequests.getContent().stream(), pullRequests.getContent().stream())
                        .toList());
        return new OpenWorkDTO(
                list(reviewRequests, reviewers),
                list(pullRequests, reviewers),
                list(workItems.findAssignedIssues(workspaceId, userId, first), Map.of()));
    }

    /**
     * Each pull request's reviewers besides its author, listed by {@link ReviewerState}, then by name. A reviewer
     * stands where their latest verdict left them, or their latest comment when they gave no verdict. A requested
     * reviewer is REQUESTED on GitHub, which lists only those it is waiting for; GitLab keeps reviewers listed after
     * they reviewed, so there a review stands over the request.
     */
    private Map<Long, List<ReviewerDTO>> reviewers(List<PullRequest> pullRequests) {
        if (pullRequests.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = pullRequests.stream().map(PullRequest::getId).collect(Collectors.toSet());
        Map<Long, Map<Long, ReviewerDTO>> reviewed = new HashMap<>();
        workItems.findStandingReviews(ids).stream()
                .sorted(STANDING)
                .forEach(review -> reviewed.computeIfAbsent(review.getPullRequestId(), id -> new HashMap<>())
                        .put(review.getReviewer().getId(), reviewer(review.getReviewer(), state(review))));
        Map<Long, List<User>> requested = workItems.findRequestedReviewers(ids).stream()
                .collect(Collectors.groupingBy(
                        RequestedReviewer::getPullRequestId,
                        Collectors.mapping(RequestedReviewer::getReviewer, Collectors.toList())));
        Map<Long, List<ReviewerDTO>> reviewers = new HashMap<>();
        for (PullRequest pullRequest : pullRequests) {
            boolean requestStands = pullRequest.getProvider().getType() != IdentityProviderType.GITLAB;
            Map<Long, ReviewerDTO> byUser = new HashMap<>(reviewed.getOrDefault(pullRequest.getId(), Map.of()));
            for (User user : requested.getOrDefault(pullRequest.getId(), List.of())) {
                if (requestStands || !byUser.containsKey(user.getId())) {
                    byUser.put(user.getId(), reviewer(user, ReviewerState.REQUESTED));
                }
            }
            User author = pullRequest.getAuthor();
            if (author != null) {
                byUser.remove(author.getId());
            }
            reviewers.put(
                    pullRequest.getId(), byUser.values().stream().sorted(LISTED).toList());
        }
        return reviewers;
    }

    private static ReviewerDTO reviewer(User user, ReviewerState state) {
        return new ReviewerDTO(Objects.requireNonNull(UserInfoDTO.fromUser(user)), state);
    }

    private static ReviewerState state(StandingReview review) {
        return switch (review.getState()) {
            case APPROVED -> ReviewerState.APPROVED;
            case CHANGES_REQUESTED -> ReviewerState.CHANGES_REQUESTED;
            default -> ReviewerState.COMMENTED;
        };
    }

    private static WorkItemListDTO list(Slice<? extends Issue> work, Map<Long, List<ReviewerDTO>> reviewers) {
        return new WorkItemListDTO(
                work.getContent().stream()
                        .map(item -> {
                            WorkItemDTO dto = WorkItemDTO.from(item);
                            List<ReviewerDTO> listed = reviewers.get(item.getId());
                            return listed == null ? dto : dto.withReviewers(listed);
                        })
                        .toList(),
                work.hasNext());
    }
}
