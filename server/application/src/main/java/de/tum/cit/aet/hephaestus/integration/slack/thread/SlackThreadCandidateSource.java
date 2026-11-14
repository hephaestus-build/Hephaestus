package de.tum.cit.aet.hephaestus.integration.slack.thread;

import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadCandidate;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadCandidateSource;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Only {@link #settledCandidates} spans workspaces; all other reads require an explicit workspace predicate. */
@Service
public class SlackThreadCandidateSource implements ThreadCandidateSource {

    private final SlackThreadRepository threadRepository;
    private final SlackMessageRepository messageRepository;

    public SlackThreadCandidateSource(
            SlackThreadRepository threadRepository, SlackMessageRepository messageRepository) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public List<ThreadCandidate> settledCandidates(int minMessageCount) {
        return threadRepository.findSettledCandidateRows(minMessageCount).stream()
                .map(SlackThreadCandidateSource::toCandidate)
                .toList();
    }

    private static ThreadCandidate toCandidate(SlackThreadRepository.SettledCandidateRow row) {
        return new ThreadCandidate(
                row.getWorkspaceId(),
                row.getThreadId(),
                row.getSlackChannelId(),
                row.getSlackChannelName(),
                row.getSlackThreadTs(),
                row.getLastTs(),
                row.getLastReviewedTs(),
                Arrays.stream(row.getParticipantAccountIds()).boxed().toList());
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
