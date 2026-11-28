package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackDmProducerPinRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each workspace needs a separate proxy call for REQUIRES_NEW so one rollback cannot stop the sweep.
 * Erase derived content through {@link ConversationFeedbackErasure} before deleting its source thread.
 * Retain whole threads that have any activity after the cutoff.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackRetentionPurger {

    private final SlackMessageRepository slackMessageRepository;
    private final SlackThreadRepository slackThreadRepository;
    private final ConversationFeedbackErasure conversationFeedbackErasure;
    private final SlackDmProducerPinRepository pins;

    /**
     * Prune everything for {@code workspaceId} older than {@code cutoff}, in a fresh transaction that commits (or
     * rolls back) independently of any caller transaction and of the other workspaces in the sweep: erase the
     * derived CONVERSATION feedback/results of the aged threads, delete the aged {@code slack_message} rows,
     * then drop the aged {@code slack_thread} aggregates.
     *
     * @return the number of {@code slack_message} rows deleted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long purgeWorkspaceBefore(long workspaceId, Instant cutoff) {
        // Aged threads: last_ts strictly older than the cutoff, rendered as a Slack ts string for a lexicographic
        // (== numeric, for the fixed <10-digit>.<6-digit> format) comparison.
        String cutoffTs = SlackTs.ofInstant(cutoff);
        pins.deleteOlderThan(workspaceId, cutoffTs);
        List<Long> agedThreadIds = slackThreadRepository.findAgedThreadIds(workspaceId, cutoffTs);

        conversationFeedbackErasure.expireForThreads(workspaceId, agedThreadIds);

        if (agedThreadIds.isEmpty()) {
            return 0;
        }

        // 2) Delete raw messages at the same thread grain as the aggregate selection.
        long messagesDeleted = slackMessageRepository.deleteByWorkspaceIdAndThreadIds(workspaceId, agedThreadIds);

        // 3) Drop the aged thread aggregates (carry participant_account_ids PII).
        slackThreadRepository.deleteByWorkspaceIdAndIdIn(workspaceId, agedThreadIds);
        return messagesDeleted;
    }
}
