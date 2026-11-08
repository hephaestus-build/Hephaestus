package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackQueryRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveredCommentLookupService implements DeliveredIssueCommentLookup, DeliveredPullRequestCommentLookup {
    private static final List<String> CONFIRMED_STATES =
            List.of(FeedbackCopyState.POSTED.name(), FeedbackCopyState.RETRACTED.name());
    private final FeedbackQueryRepository queries;

    @Override
    @WorkspaceAgnostic("Provider-ingest provenance for the same native issue across its monitoring workspaces")
    public List<DeliveredComment> findForIssue(long issueId, ItemKind kind) {
        return queries.issueReceipts(issueId, kind.value(), FeedbackCopyKind.SUMMARY.name(), CONFIRMED_STATES).stream()
                .map(DeliveredCommentLookupService::comment)
                .toList();
    }

    @Override
    @WorkspaceAgnostic("Provider-ingest provenance for a repository's issues across its monitoring workspaces;"
            + " the caller's workspace must monitor the repository")
    public Map<Long, List<DeliveredComment>> findForRepository(long workspaceId, long repositoryId, ItemKind kind) {
        return queries
                .repositoryReceipts(
                        workspaceId, repositoryId, kind.value(), FeedbackCopyKind.SUMMARY.name(), CONFIRMED_STATES)
                .stream()
                .collect(Collectors.groupingBy(
                        FeedbackQueryRepository.IssueReceipt::getIssueId,
                        Collectors.mapping(DeliveredCommentLookupService::comment, Collectors.toList())));
    }

    @Override
    public CommentIds findForPullRequest(long workspaceId, long pullRequestId, ItemKind kind) {
        var regular = new HashSet<Long>();
        var inline = new HashSet<Long>();
        var reviews = new HashSet<Long>();
        for (var receipt : queries.pullRequestReceipts(workspaceId, pullRequestId, kind.value(), CONFIRMED_STATES)) {
            switch (receipt.getKind()) {
                case "general" -> regular.add(receipt.getNativeId());
                case "inline" -> inline.add(receipt.getNativeId());
                case "review" -> reviews.add(receipt.getNativeId());
                default -> throw new IllegalStateException("Unknown delivered comment kind");
            }
        }
        return new CommentIds(Set.copyOf(regular), Set.copyOf(inline), Set.copyOf(reviews));
    }

    private static DeliveredComment comment(FeedbackQueryRepository.IssueReceipt row) {
        return new DeliveredComment(row.getExternalRef(), row.getUrl());
    }
}
