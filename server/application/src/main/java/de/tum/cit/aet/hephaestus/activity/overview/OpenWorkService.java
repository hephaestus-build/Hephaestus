package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.WorkItemQueryRepository.ReviewRequest;
import de.tum.cit.aet.hephaestus.activity.overview.WorkItemQueryRepository.StandingReview;
import de.tum.cit.aet.hephaestus.activity.overview.WorkItemQueryRepository.TeamReviewRequest;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO.ReviewerState;
import de.tum.cit.aet.hephaestus.activity.overview.dto.TeamRefDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemListDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer.ReviewState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
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
import org.springframework.data.domain.SliceImpl;
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
        User member = scopes.member(workspaceId, login);
        long userId = member.getId();
        Pageable first = PageRequest.ofSize(LIMIT);
        Set<Long> teamIds = scopes.memberTeams(workspaceId, member).stream()
                .map(Team::getId)
                .collect(Collectors.toSet());
        Slice<PullRequest> reviewRequests = workItems.findReviewRequests(workspaceId, userId, first);
        Slice<PullRequest> teamReviewRequests = teamIds.isEmpty()
                ? new SliceImpl<>(List.of())
                : workItems.findTeamReviewRequests(workspaceId, userId, teamIds, first);
        Slice<PullRequest> pullRequests = workItems.findOpenPullRequests(workspaceId, userId, first);
        Map<Long, List<ReviewerDTO>> reviewers = reviewers(Stream.of(reviewRequests, teamReviewRequests, pullRequests)
                .flatMap(slice -> slice.getContent().stream())
                .toList());
        Map<Long, List<TeamRefDTO>> requestedTeams = requestedTeams(teamReviewRequests.getContent(), teamIds);
        return new OpenWorkDTO(
                list(reviewRequests, reviewers, Map.of()),
                list(teamReviewRequests, reviewers, requestedTeams),
                list(pullRequests, reviewers, Map.of()),
                list(workItems.findAssignedIssues(workspaceId, userId, first), Map.of(), Map.of()));
    }

    /** Which of the member's teams each pull request asks, by name. */
    private Map<Long, List<TeamRefDTO>> requestedTeams(List<PullRequest> pullRequests, Set<Long> teamIds) {
        if (pullRequests.isEmpty()) {
            return Map.of();
        }
        return workItems
                .findRequestedTeams(
                        pullRequests.stream().map(PullRequest::getId).toList(), teamIds)
                .stream()
                .collect(Collectors.groupingBy(
                        TeamReviewRequest::getPullRequestId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(
                                        request -> new TeamRefDTO(request.getTeamId(), request.getTeamName()),
                                        Collectors.toList()),
                                teams -> teams.stream()
                                        .sorted(Comparator.comparing(TeamRefDTO::name, ActivityScopeResolver.NAMES))
                                        .toList())));
    }

    /**
     * Each pull request's reviewers besides its author, listed by {@link ReviewerState}, then by name.
     *
     * <ul>
     *   <li>GitHub lists only the reviewers it is waiting for, so a listed reviewer is REQUESTED.
     *   <li>GitLab keeps every reviewer listed and says where each review stands; asking a reviewer again sets
     *       them back to unreviewed, so that state is the reviewer's. A reviewer synced before GitLab's state was
     *       stored has none, and there a standing review is taken over the request.
     *   <li>Anyone else reviewing stands where their latest verdict left them, or their latest comment when they
     *       gave no verdict.
     * </ul>
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
        Map<Long, List<ReviewRequest>> requested = workItems.findRequestedReviewers(ids).stream()
                .collect(Collectors.groupingBy(ReviewRequest::getPullRequestId));
        Map<Long, List<ReviewerDTO>> reviewers = new HashMap<>();
        for (PullRequest pullRequest : pullRequests) {
            boolean gitLab = pullRequest.getProvider().getType() == IdentityProviderType.GITLAB;
            Map<Long, ReviewerDTO> byUser = new HashMap<>(reviewed.getOrDefault(pullRequest.getId(), Map.of()));
            for (ReviewRequest request : requested.getOrDefault(pullRequest.getId(), List.of())) {
                User user = request.getReviewer();
                ReviewState stated = request.getReviewState();
                if (stated != null) {
                    byUser.put(user.getId(), reviewer(user, state(stated)));
                } else if (!gitLab || !byUser.containsKey(user.getId())) {
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

    /** Where GitLab says a reviewer stands; a review still owed is requested, as Heph counts it pending. */
    private static ReviewerState state(ReviewState stated) {
        if (stated.awaitsReview()) {
            return ReviewerState.REQUESTED;
        }
        return switch (stated) {
            case APPROVED -> ReviewerState.APPROVED;
            case REQUESTED_CHANGES -> ReviewerState.CHANGES_REQUESTED;
            default -> ReviewerState.COMMENTED;
        };
    }

    private static ReviewerState state(StandingReview review) {
        return switch (review.getState()) {
            case APPROVED -> ReviewerState.APPROVED;
            case CHANGES_REQUESTED -> ReviewerState.CHANGES_REQUESTED;
            default -> ReviewerState.COMMENTED;
        };
    }

    private static WorkItemListDTO list(
            Slice<? extends Issue> work,
            Map<Long, List<ReviewerDTO>> reviewers,
            Map<Long, List<TeamRefDTO>> requestedTeams) {
        return new WorkItemListDTO(
                work.getContent().stream()
                        .map(item -> {
                            WorkItemDTO dto = WorkItemDTO.from(item);
                            List<ReviewerDTO> listed = reviewers.get(item.getId());
                            List<TeamRefDTO> teams = requestedTeams.get(item.getId());
                            dto = listed == null ? dto : dto.withReviewers(listed);
                            return teams == null ? dto : dto.withRequestedTeams(teams);
                        })
                        .toList(),
                work.hasNext());
    }
}
