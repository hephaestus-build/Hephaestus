package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRunRows;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A personal space as the database holds it, for tests: the workspace and its owner's one membership. */
public final class PersonalSpaceFixtures {

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private PersonalSpaceFixtures() {}

    /** Inserts the owner's personal space and returns its id. */
    public static long insert(JdbcTemplate jdbc, long ownerId) {
        String slug = slug();
        Long id = jdbc.queryForObject(
                "INSERT INTO workspace (slug, display_name, account_login, account_type, status, kind,"
                        + " personal_account_id) VALUES (?, 'Personal space', ?, 'USER', 'ACTIVE', 'PERSONAL', ?)"
                        + " RETURNING id",
                Long.class,
                slug,
                slug,
                ownerId);
        long spaceId = Objects.requireNonNull(id);
        jdbc.update(
                "INSERT INTO membership (workspace_id, account_id, role, role_source) VALUES (?, ?, 'MEMBER', 'PERSON')",
                spaceId,
                ownerId);
        return spaceId;
    }

    /** Inserts a conversation in the space with one finished turn, its run and its answer; returns the conversation. */
    public static UUID insertFinishedTurn(JdbcTemplate jdbc, long spaceId, long ownerId) {
        UUID conversation = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)"
                        + " VALUES (?, ?, ?, now(), now())",
                conversation,
                spaceId,
                ownerId);
        jdbc.update(ConversationTurnRunRows.insert(run, spaceId, conversation, "DONE", 1));
        jdbc.update(
                "INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id, status,"
                        + " surface, attempt, run_id, created_at, finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'DONE', 'APP', 1, ?, now(), now())",
                turn,
                spaceId,
                conversation,
                ownerId,
                turn.toString(),
                run);
        jdbc.update(
                "INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, turn_id, kind,"
                        + " surface, text, created_at) VALUES (?, ?, ?, ?, 1, ?, 'ANSWER', 'APP', 'An answer', now())",
                UUID.randomUUID(),
                spaceId,
                conversation,
                ownerId,
                turn);
        return conversation;
    }

    /** A slug that only a personal space can have. */
    static String slug() {
        var slug = new StringBuilder("me--");
        for (int i = 0; i < 16; i++) {
            slug.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return slug.toString();
    }
}
