package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository.StoredComment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The evidence identity of an issue update, one rule for every owner that keys, admits, coalesces, supersedes or
 * captures it.
 *
 * <p>An open issue is identified by its own fields, as before. A closed issue's discussion is part of its identity
 * too: a comment can record what was done and what moved elsewhere, so a comment written, edited or removed after
 * the close is new evidence. Comments carrying Hephaestus's own marker are never evidence, so a posted piece of
 * feedback does not occasion a review of itself.
 */
@Component
public class IssueEvidenceRevision {

    private final IssueCommentRepository comments;

    public IssueEvidenceRevision(IssueCommentRepository comments) {
        this.comments = comments;
    }

    /** The comments an issue review reads, as stored: non-empty, not Hephaestus's own, oldest first. */
    public List<StoredComment> reviewedComments(long issueId) {
        return comments.findStoredHumanByIssueId(issueId, WorkspaceScmProjection.HEPHAESTUS_MARKER);
    }

    public SignalRevision of(ScmEventPayload.IssueData issue) {
        if (issue.isPullRequest() || issue.state() != Issue.State.CLOSED) {
            return ScmSignals.issueUpdatedRevision(issue);
        }
        List<@Nullable String> discussion = new ArrayList<>();
        for (StoredComment comment : reviewedComments(issue.id())) {
            discussion.add(String.valueOf(comment.getNativeId()));
            discussion.add(comment.getAuthorLogin());
            discussion.add(text(comment.getCreatedAt()));
            discussion.add(text(comment.getUpdatedAt()));
            discussion.add(comment.getBody());
        }
        return ScmSignals.issueUpdatedRevision(issue, discussion);
    }

    public SignalKey updatedKey(long workspaceId, ScmEventPayload.IssueData issue) {
        return new SignalKey(workspaceId, issue.id(), ScmSignals.ISSUE_UPDATED, of(issue));
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
