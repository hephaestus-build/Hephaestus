package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReplySink;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import java.time.Duration;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** {@link ScmCommentReplySink} for GitLab: a note in the request comment's discussion on the merge request. */
@Component
@OutboundEgressGateway
@ConditionalOnBean(GitLabGraphQlClientProvider.class)
@Slf4j
public class GitLabCommentReplySink implements ScmCommentReplySink {
    private static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern ADDRESS =
            Pattern.compile("(gid://gitlab/MergeRequest/\\d+) (gid://gitlab/Discussion/[0-9a-f]+)");

    private final GitLabGraphQlClientProvider gitLab;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackFormatter formatter;

    public GitLabCommentReplySink(
            GitLabGraphQlClientProvider gitLab, OutboundEgressGuard egressGuard, ScmFeedbackFormatter formatter) {
        this.gitLab = gitLab;
        this.egressGuard = egressGuard;
        this.formatter = formatter;
    }

    /** The reply address of a note in a merge request's discussion, or null without both. */
    public static @Nullable String address(@Nullable Long mergeRequestId, @Nullable String discussionId) {
        if (mergeRequestId == null || discussionId == null || discussionId.isBlank()) return null;
        String address = "gid://gitlab/MergeRequest/" + mergeRequestId + " gid://gitlab/Discussion/" + discussionId;
        return ADDRESS.matcher(address).matches() ? address : null;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public void reply(long workspaceId, long itemId, String replyAddress, String text) {
        var parts = ADDRESS.matcher(replyAddress);
        if (!parts.matches()) {
            log.warn("No GitLab thread to answer a request comment in: workspaceId={}, itemId={}", workspaceId, itemId);
            return;
        }
        try {
            String body = formatter.requestAnswer(workspaceId, itemId, text);
            egressGuard.requireDeliveryAllowed("gitlab.answer-request-comment");
            var response = gitLab.forScope(workspaceId)
                    .documentName("CreateMergeRequestDiscussionReply")
                    .variable("noteableId", parts.group(1))
                    .variable("discussionId", parts.group(2))
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (response == null
                    || !response.isValid()
                    || !response.getErrors().isEmpty()
                    || response.field("createNote.note.id").getValue() == null)
                log.warn(
                        "GitLab did not post the answer to a request comment: workspaceId={}, itemId={}",
                        workspaceId,
                        itemId);
        } catch (OutboundEgressSuppressedException suppressed) {
            log.debug("Silent Mode suppressed the answer to a request comment: workspaceId={}", workspaceId);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not answer a request comment: workspaceId={}, itemId={}, error={}",
                    workspaceId,
                    itemId,
                    failure.getClass().getSimpleName());
        }
    }
}
