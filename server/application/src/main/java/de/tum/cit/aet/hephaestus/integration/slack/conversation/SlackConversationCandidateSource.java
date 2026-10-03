package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationCandidateSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Slack-owned implementation of the agent {@link ConversationCandidateSource} SPI, over Slack's own
 * {@code slack_thread}/{@code slack_message} tables. The agent {@code ConversationThreadTriggerScheduler} consumes
 * it through the SPI to enqueue {@code CONVERSATION_REVIEW} jobs and never reaches into the Slack schema.
 *
 * <p>{@link #settledCandidates} is an inherently cross-workspace sweep (each candidate carries its own
 * {@code workspace_id}, allowlisted in {@code SlackIntegrationArchitectureTest}); every other method carries an
 * explicit {@code workspace_id} predicate through its repository call.
 */
@Service
public class SlackConversationCandidateSource implements ConversationCandidateSource {

    private final SlackThreadRepository threadRepository;
    private final SlackMessageRepository messageRepository;

    public SlackConversationCandidateSource(
            SlackThreadRepository threadRepository, SlackMessageRepository messageRepository) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public List<ConversationThreadCandidate> settledCandidates(int minMessageCount) {
        return threadRepository.findSettledCandidateRows(minMessageCount).stream()
                .map(SlackConversationCandidateSource::toCandidate)
                .toList();
    }

    private static ConversationThreadCandidate toCandidate(SlackThreadRepository.SettledCandidateRow row) {
        return new ConversationThreadCandidate(
                row.getWorkspaceId(),
                row.getThreadId(),
                row.getSlackChannelId(),
                row.getSlackChannelName(),
                row.getSlackThreadTs(),
                row.getLastTs(),
                row.getLastReviewedTs(),
                Arrays.stream(row.getParticipantMemberIds()).boxed().toList());
    }

    @Override
    public Optional<ConversationThreadCandidate> candidateById(long workspaceId, long threadId) {
        return threadRepository
                .findConsentedCandidateRow(workspaceId, threadId)
                .map(SlackConversationCandidateSource::toCandidate);
    }

    @Override
    public long liveTurnCount(long workspaceId, String channelId, String threadTs) {
        return messageRepository.countLiveTurns(workspaceId, channelId, threadTs);
    }

    @Override
    public long liveTurnCountSince(long workspaceId, String channelId, String threadTs, @Nullable String watermark) {
        // Slack ts strings sort lexicographically; '' is below any real ts, so a null watermark counts everything.
        String floor = watermark == null ? "" : watermark;
        return messageRepository.countLiveTurnsSince(workspaceId, channelId, threadTs, floor);
    }

    @Override
    public void markReviewed(long workspaceId, long threadId, String lastTs) {
        threadRepository.advanceReviewWatermark(workspaceId, threadId, lastTs);
    }
}
