package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The Slack posts of the ledger as outbox actions ({@link ActionKind#SLACK_POST}): the reply to a Slack turn, keyed by
 * its question, and a weekly digest's note, keyed by the digest.
 *
 * <p>The server that streams a turn's reply owns it: its {@link #own} creates the reply's action, held back for
 * {@link #HOLD}, and the owner carries it out when the turn ends. When the turn ends, {@link #ended} makes the reply
 * due within {@link #GRACE}, or creates it unclaimed: if no owner carries it out by then, the executor of any server
 * does.
 */
@Component
@RequiredArgsConstructor
public class MentorSlackReplies {

    /** How long the reply of a running turn waits for its owner: the turn's end shortens it to {@link #GRACE}. */
    static final Duration HOLD = Duration.ofDays(1);

    /** How long the reply of an ended turn waits for its owner before any server sends or settles it. */
    static final Duration GRACE = Duration.ofMinutes(2);

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
     * @param taken whether a server owned the reply while its turn ran: Slack may then show part of it already
     */
    public record Payload(UUID entryId, @Nullable UUID turnId, boolean taken) {}

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

    /** Takes the reply of a running turn for this server. Empty when another server took it, or its turn ended. */
    @Transactional
    public Optional<UUID> own(long workspaceId, long accountId, UUID questionId, UUID turnId) {
        return actions.enqueueNew(
                request(workspaceId, accountId, replyKey(questionId), new Payload(questionId, turnId, true)), HOLD);
    }

    /** Creates a weekly digest's note for this server to send now. Empty when it exists already. */
    @Transactional
    public Optional<UUID> digest(long workspaceId, long accountId, UUID digestId) {
        return actions.enqueueNew(
                request(workspaceId, accountId, digestKey(digestId), new Payload(digestId, null, false)), GRACE);
    }

    /**
     * The reply's action when no server owns it and no attempt took it yet: a server that sees the turn end can carry
     * it out at once instead of waiting for {@link #GRACE}.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> unowned(long workspaceId, UUID questionId) {
        String key = replyKey(questionId);
        if (actions.admittedPayload(workspaceId, key)
                .filter(payload -> !mapper.treeToValue(payload, Payload.class).taken())
                .isEmpty()) return Optional.empty();
        return Optional.ofNullable(actions.byKeys(workspaceId, List.of(key)).get(key))
                .map(LaneAction::id);
    }

    /** The entry that a post's key names. */
    public static UUID entryOf(String idempotencyKey) {
        return UUID.fromString(idempotencyKey.substring(idempotencyKey.indexOf(':') + 1));
    }

    /** A Slack turn ended: its reply is due within {@link #GRACE}, unclaimed when no server owned it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(MentorTurn turn, long accountId, UUID questionId) {
        String key = replyKey(questionId);
        if (actions.enqueueNew(
                        request(turn.getWorkspaceId(), accountId, key, new Payload(questionId, turn.getId(), false)),
                        GRACE)
                .isEmpty()) actions.advance(turn.getWorkspaceId(), key, GRACE);
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
                null);
    }
}
