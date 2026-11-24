package de.tum.cit.aet.hephaestus.agent.run;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conversation runs for a scheduler test, each with the turn and the question or digest that it answers, as a
 * submission queues them. When a transaction commits, the database refuses a conversation run without them, and a claim
 * or an end of the run that its turn does not follow.
 */
public final class ConversationRuns {

    private static final String DIGEST_PAYLOAD =
            "{\"window\":{\"weekStart\":\"2026-10-05\",\"zone\":\"UTC\"},\"feedbackIds\":[],\"changeKeys\":[]}";

    private ConversationRuns() {}

    /** The subject of a new conversation: {@code conversation:<id>}. */
    public static String subject() {
        return "conversation:" + UUID.randomUUID();
    }

    /**
     * Queues a run of the subject's conversation, with its turn and its question or digest, in the caller's transaction.
     * The first run of a subject creates the conversation and its owner.
     */
    public static AgentRunRef queue(
            AgentRuns runs,
            JdbcTemplate jdbc,
            long workspace,
            AgentRunKind kind,
            AgentRunBand band,
            String subject,
            @Nullable Duration startWithin) {
        UUID conversation = UUID.fromString(subject.substring("conversation:".length()));
        List<@Nullable Long> owners =
                jdbc.queryForList("SELECT account_id FROM conversation WHERE id = ?", Long.class, conversation);
        long owner;
        if (owners.isEmpty()) {
            owner = Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO account (display_name) VALUES ('Conversation run owner') RETURNING id", Long.class));
            jdbc.update(
                    "INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)"
                            + " VALUES (?, ?, ?, now(), now())",
                    conversation,
                    workspace,
                    owner);
        } else {
            owner = Objects.requireNonNull(owners.getFirst());
        }
        long seq = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COALESCE(max(seq), 0) + 1 FROM conversation_entry WHERE conversation_id = ?",
                Long.class,
                conversation));
        AgentRunRef run = runs.enqueue(
                workspace, kind, band, subject, startWithin, new AgentRunStimulus(owner, conversation, seq));
        boolean digest = kind == AgentRunKind.DIGEST;
        UUID turn = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id, status,"
                        + " surface, run_id, created_at) VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, now())",
                turn,
                workspace,
                conversation,
                owner,
                turn.toString(),
                digest ? "SYSTEM" : "APP",
                run.id());
        jdbc.update(
                "INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface,"
                        + " text, turn_id, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), now())",
                UUID.randomUUID(),
                workspace,
                conversation,
                owner,
                seq,
                digest ? "DIGEST" : "USER",
                digest ? "SYSTEM" : "APP",
                digest ? "Weekly recap" : "A question",
                turn,
                digest ? DIGEST_PAYLOAD : null);
        return run;
    }

    /** Starts the turn of a claimed run, as the worker does in the claim's transaction, and returns the lease. */
    public static AgentRunLease started(JdbcTemplate jdbc, AgentRunLease lease) {
        jdbc.update(
                "UPDATE conversation_turn SET status = 'RUNNING', attempt = ?, started_at = clock_timestamp()"
                        + " WHERE (run_id, workspace_id) = (?, ?)",
                lease.epoch(),
                lease.runId(),
                lease.workspaceId());
        return lease;
    }

    /** A sandbox's claim that starts the turn of the run that it leases. */
    public static Predicate<AgentRunLease> starts(JdbcTemplate jdbc) {
        return lease -> {
            started(jdbc, lease);
            return true;
        };
    }

    /** Ends the turn of a run that ends completed, in the end's transaction. */
    public static void completed(JdbcTemplate jdbc, AgentRunLease lease) {
        jdbc.update(
                "UPDATE conversation_turn SET status = 'DONE', finished_at = clock_timestamp()"
                        + " WHERE (run_id, workspace_id) = (?, ?)",
                lease.runId(),
                lease.workspaceId());
    }
}
