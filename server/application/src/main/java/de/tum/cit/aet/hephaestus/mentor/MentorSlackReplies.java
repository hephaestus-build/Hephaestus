package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** One completed Slack reply or digest note, keyed by its ledger entry and owned by the outbox. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class MentorSlackReplies {

    /** How long a reply whose post may have reached Slack waits before the history is read. */
    public static final Duration REREAD = Duration.ofMinutes(1);

    /** One send, then reads of the Slack history while it stays unknown. */
    static final int MAX_ATTEMPTS = 3;

    private static final String REPLY = "slack_reply:";
    private static final String DIGEST = "slack_digest:";

    /**
     * What an action posts.
     *
     * @param turnId the turn whose reply it is; {@code null} for a weekly digest's note
     */
    public record Payload(UUID entryId, @Nullable UUID turnId) {}

    private final MentorEntryRepository entries;
    private final AgentActions actions;
    private final ObjectMapper mapper;

    public static String replyKey(UUID questionId) {
        return REPLY + questionId;
    }

    public static String digestKey(UUID digestId) {
        return DIGEST + digestId;
    }

    /** What an attempt of a post carries out. */
    public Payload payload(ActionAttempt attempt) {
        return mapper.treeToValue(attempt.payload(), Payload.class);
    }

    /** How a post stands, as its action shows it; {@code null} without an action. */
    public static MentorEntry.@Nullable SlackDelivery delivery(@Nullable LaneAction post) {
        if (post == null) return null;
        return switch (post.state()) {
            case PROPOSED, ADMITTED -> MentorEntry.SlackDelivery.PENDING;
            case EXECUTING ->
                post.rereading() ? MentorEntry.SlackDelivery.UNCERTAIN : MentorEntry.SlackDelivery.PENDING;
            case DONE -> MentorEntry.SlackDelivery.DELIVERED;
            case UNCERTAIN -> MentorEntry.SlackDelivery.UNCERTAIN;
            case FAILED, CANCELLED -> MentorEntry.SlackDelivery.FAILED;
        };
    }

    /** How the post under {@code key} stands. */
    @Transactional(readOnly = true)
    public MentorEntry.@Nullable SlackDelivery delivery(long workspaceId, String key) {
        return delivery(actions.byKeys(workspaceId, List.of(key)).get(key));
    }

    /** Creates one digest note; the executor can carry it out immediately. */
    @Transactional
    public Optional<UUID> digest(long workspaceId, long accountId, UUID digestId) {
        return actions.enqueueNew(
                request(workspaceId, accountId, digestKey(digestId), new Payload(digestId, null)), Duration.ZERO);
    }

    /** The action of a Slack turn's question, including an action another replica already claimed. */
    @Transactional(readOnly = true)
    public Optional<UUID> forQuestion(long workspaceId, UUID questionId) {
        return Optional.ofNullable(actions.byKeys(workspaceId, List.of(replyKey(questionId)))
                        .get(replyKey(questionId)))
                .map(LaneAction::id);
    }

    /** The entry that a post's key names. */
    public static UUID entryOf(String idempotencyKey) {
        return UUID.fromString(idempotencyKey.substring(idempotencyKey.indexOf(':') + 1));
    }

    /** A Slack turn ended: enqueue its complete reply in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(MentorTurn turn, long accountId, UUID questionId) {
        String key = replyKey(questionId);
        actions.enqueue(request(turn.getWorkspaceId(), accountId, key, new Payload(questionId, turn.getId())));
    }

    /** The durable ledger event runs after redaction commits, without its conversation locks. */
    @ApplicationModuleListener(propagation = Propagation.REQUIRES_NEW)
    public void changed(MentorLedgerEvents.EntryChanged changed) {
        forget(changed.workspaceId(), changed.entryIds());
    }

    /** Also called by action settlement if its source was erased while the action was still live. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetRedacted(long workspaceId, Collection<UUID> ids) {
        forget(workspaceId, ids);
    }

    private void forget(long workspaceId, Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        var kept = entries.findUnredactedIds(workspaceId, ids);
        var keys = ids.stream()
                .filter(id -> !kept.contains(id))
                .flatMap(id -> Stream.of(replyKey(id), digestKey(id)))
                .toList();
        actions.forgetEnded(workspaceId, keys);
    }

    private ActionRequest request(long workspaceId, long accountId, String key, Payload payload) {
        return new ActionRequest(
                ActionKind.SLACK_POST,
                workspaceId,
                key,
                key,
                mapper.valueToTree(payload),
                accountId,
                MAX_ATTEMPTS,
                null,
                null);
    }
}
