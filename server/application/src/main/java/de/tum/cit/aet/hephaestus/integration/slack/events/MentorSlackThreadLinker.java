package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorSlackThreadService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.MentorSlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.MentorSlackThreadRepository;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the chat thread and Slack mapping in one transaction. This separate bean provides
 * the transactional proxy boundary; the caller performs Slack I/O after the transaction returns.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class MentorSlackThreadLinker {

    private final MentorSlackThreadRepository mentorSlackThreadRepository;
    private final MentorSlackThreadService mentorSlackThreadService;

    public MentorSlackThreadLinker(
            MentorSlackThreadRepository mentorSlackThreadRepository,
            MentorSlackThreadService mentorSlackThreadService) {
        this.mentorSlackThreadRepository = mentorSlackThreadRepository;
        this.mentorSlackThreadService = mentorSlackThreadService;
    }

    @Transactional
    public UUID findOrCreateThread(
            long workspaceId, String teamId, String channelId, String threadTs, String slackUserId, long developerId) {
        return mentorSlackThreadRepository
                .findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(workspaceId, channelId, threadTs)
                .map(MentorSlackThread::getChatThreadId)
                .orElseGet(() -> {
                    UUID chatThreadId = mentorSlackThreadService.ensureSlackThread(workspaceId, null, developerId);
                    MentorSlackThread mapping = new MentorSlackThread();
                    mapping.setId(UUID.randomUUID());
                    mapping.setWorkspaceId(workspaceId);
                    mapping.setChatThreadId(chatThreadId);
                    mapping.setSlackTeamId(teamId);
                    mapping.setSlackChannelId(channelId);
                    mapping.setSlackThreadTs(threadTs);
                    mapping.setSlackUserId(slackUserId);
                    mentorSlackThreadRepository.save(mapping);
                    return chatThreadId;
                });
    }
}
