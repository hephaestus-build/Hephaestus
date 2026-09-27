package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityItemDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns ledger events into timeline entries, loading each kind of target once per page. An event whose work
 * is gone stays on the timeline without it, so the timeline keeps matching the counts.
 */
@Component
@RequiredArgsConstructor
class ActivityTimelineAssembler {

    private final WorkItemQueryRepository workItems;
    private final PullRequestReviewRepository reviews;
    private final IssueCommentRepository comments;
    private final PullRequestReviewCommentRepository codeComments;

    List<ActivityItemDTO> assemble(List<ActivityEvent> events) {
        Map<Long, Issue> workById = load(
                targets(events, ActivityTargetType.PULL_REQUEST, ActivityTargetType.ISSUE),
                workItems::findAllByIdIn,
                Issue::getId);
        Map<Long, PullRequestReview> reviewById = load(
                targets(events, ActivityTargetType.REVIEW),
                reviews::findAllByIdWithRelations,
                PullRequestReview::getId);
        Map<Long, IssueComment> commentById = load(
                targets(events, ActivityTargetType.ISSUE_COMMENT),
                comments::findAllByIdWithRelations,
                IssueComment::getId);
        Map<Long, PullRequestReviewComment> codeCommentById = load(
                targets(events, ActivityTargetType.REVIEW_COMMENT),
                codeComments::findAllByIdWithRelations,
                PullRequestReviewComment::getId);

        return events.stream()
                .map(event -> {
                    ActivityKind kind = ActivityKind.of(event.getEventType()).orElseThrow();
                    UserInfoDTO actor = Objects.requireNonNull(UserInfoDTO.fromUser(event.getActor()));
                    Long targetId = event.getTargetId();
                    return switch (ActivityTargetType.fromValue(event.getTargetType())) {
                        case REVIEW -> {
                            PullRequestReview review = reviewById.get(targetId);
                            yield item(
                                    event,
                                    kind,
                                    actor,
                                    review == null ? null : review.getPullRequest(),
                                    review == null ? null : review.getHtmlUrl());
                        }
                        case ISSUE_COMMENT -> {
                            IssueComment comment = commentById.get(targetId);
                            yield item(
                                    event,
                                    kind,
                                    actor,
                                    comment == null ? null : comment.getIssue(),
                                    comment == null ? null : comment.getHtmlUrl());
                        }
                        case REVIEW_COMMENT -> {
                            PullRequestReviewComment comment = codeCommentById.get(targetId);
                            yield item(
                                    event,
                                    kind,
                                    actor,
                                    comment == null ? null : comment.getPullRequest(),
                                    comment == null ? null : comment.getHtmlUrl());
                        }
                        default -> item(event, kind, actor, workById.get(targetId), null);
                    };
                })
                .toList();
    }

    private static ActivityItemDTO item(
            ActivityEvent event, ActivityKind kind, UserInfoDTO actor, @Nullable Issue work, @Nullable String htmlUrl) {
        WorkItemDTO available = WorkItemDTO.fromAvailable(work);
        return new ActivityItemDTO(
                event.getId().toString(),
                kind,
                event.getOccurredAt(),
                actor,
                available,
                available == null ? null : htmlUrl);
    }

    private static Set<Long> targets(Collection<ActivityEvent> events, ActivityTargetType... types) {
        Set<String> values =
                Arrays.stream(types).map(ActivityTargetType::getValue).collect(Collectors.toSet());
        return events.stream()
                .filter(event -> values.contains(event.getTargetType()))
                .map(ActivityEvent::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> load(
            Set<Long> ids, Function<Collection<Long>, List<T>> query, Function<T, Long> id) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return query.apply(ids).stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
