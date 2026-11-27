package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationReplies;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names the Slack reply that may hold a claimed copy's words. The rule reads only rows that a redaction keeps: the turn,
 * its conversation's Slack DM, and the {@code slack_ts} of the reply and the question. The post's outbox row is gone once
 * the question is redacted, while the reply stays in Slack.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationSlackReplyLocators implements PersonConversationReplies {
    private final ConversationTurnRepository turns;
    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;

    @Override
    @Transactional(readOnly = true)
    public Map<Claim, String> slackReplies(long workspaceId, Collection<Claim> claims) {
        var replies = new LinkedHashMap<Claim, String>();
        for (var claim : claims) locator(workspaceId, claim).ifPresent(locator -> replies.put(claim, locator));
        return replies;
    }

    private Optional<String> locator(long workspaceId, Claim claim) {
        var found = turns.findByIdAndWorkspaceId(claim.turnId(), workspaceId);
        if (found.isEmpty()) return Optional.empty();
        ConversationTurn turn = found.get();
        // A stopped, failed or lost attempt posts no feedback words to Slack, and a retry's reply holds only its own
        // claims.
        var started = turn.getStartedAt();
        if (turn.getSurface() != ConversationTurn.Surface.SLACK
                || turn.getStatus() != ConversationTurn.Status.DONE
                || started == null
                || claim.claimedAt().isBefore(started)) return Optional.empty();
        Conversation conversation = conversations
                .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                .orElseThrow();
        Conversation main = conversation.isThread()
                ? conversations
                        .findByIdAndWorkspaceId(conversation.rootId(), workspaceId)
                        .orElseThrow()
                : conversation;
        String channel = main.getSlackChannelId();
        if (channel == null) return Optional.of("slack:conversation/" + main.getId());
        String dm = "slack:" + main.getSlackTeamId() + "/" + channel;
        var reply = entries
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, turn.getId(), ConversationEntry.Kind.ANSWER)
                .stream()
                .map(ConversationEntry::getSlackTs)
                .filter(Objects::nonNull)
                .findFirst();
        if (reply.isPresent()) return Optional.of(dm + "/" + reply.get());
        // Slack did not confirm the post. If Slack has the reply, it is Hephaestus's first message after the question,
        // in the question's thread or at the DM's top level, and it carries the question's entry id as metadata.
        ConversationEntry question = entries.findStimulus(workspaceId, turn.getId());
        UUID parent = conversation.getParentEntryId();
        String thread = parent == null
                ? null
                : entries.findByIdAndWorkspaceId(parent, workspaceId)
                        .map(ConversationEntry::getSlackTs)
                        .orElse(null);
        return Optional.of(dm + "?after=" + question.getSlackTs() + (thread == null ? "" : "&thread=" + thread)
                + "&question=" + question.getId());
    }
}
