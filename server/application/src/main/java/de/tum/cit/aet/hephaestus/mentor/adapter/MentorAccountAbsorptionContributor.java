package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the Hephaestus conversations of an absorbed account to the account that claimed it. A developer has one main
 * conversation per workspace, so where both accounts have one, the absorbed main merges into the claiming one: its
 * entries follow the claiming main's entries in their own order, with new positions and the same ids, so threads,
 * turns and feedback copies still find them. Its summaries are erased, because they summarize a conversation that no
 * longer exists alone; the next turn summarizes the merged conversation again. The absorbed main is then deleted.
 *
 * <p>A conversation runs one turn at a time, and each main may run one. So the absorbed main's running turn returns to
 * the queue before the merge, as after a lost lease: its host's later writes are refused, and the turn starts again
 * from the merged conversation.
 */
@Order(30)
@Component
@RequiredArgsConstructor
public class MentorAccountAbsorptionContributor implements AccountAbsorptionContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final MentorRedaction redaction;
    private final MentorTurnService turns;

    private record Clash(long workspaceId, UUID absorbed, UUID kept) {}

    @Override
    public void absorb(long claimedAccountId, long unclaimedAccountId) {
        Map<String, Long> accounts = Map.of("claimed", claimedAccountId, "unclaimed", unclaimedAccountId);
        List<Clash> clashes = jdbc.query(
                """
                SELECT absorbed.workspace_id, absorbed.id, kept.id FROM mentor_conversation absorbed
                JOIN mentor_conversation kept ON kept.workspace_id = absorbed.workspace_id
                    AND kept.account_id = :claimed AND kept.parent_conversation_id IS NULL
                WHERE absorbed.account_id = :unclaimed AND absorbed.parent_conversation_id IS NULL
                ORDER BY absorbed.id
                FOR UPDATE OF absorbed, kept
                """,
                accounts,
                (rs, row) -> new Clash(
                        rs.getLong(1),
                        Objects.requireNonNull(rs.getObject(2, UUID.class)),
                        Objects.requireNonNull(rs.getObject(3, UUID.class))));
        clashes.forEach(this::merge);
        jdbc.update("UPDATE mentor_conversation SET account_id = :claimed WHERE account_id = :unclaimed", accounts);
    }

    private void merge(Clash clash) {
        Map<String, UUID> ids = Map.of("absorbed", clash.absorbed(), "kept", clash.kept());
        List<UUID> summaries = jdbc.query(
                "SELECT id FROM mentor_entry WHERE conversation_id = :absorbed AND kind = 'SUMMARY'"
                        + " AND redacted_at IS NULL",
                ids,
                (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)));
        jdbc.update("""
                UPDATE mentor_entry e SET conversation_id = :kept, seq = top.seq + moved.position
                FROM (SELECT id, row_number() OVER (ORDER BY seq) AS position
                      FROM mentor_entry WHERE conversation_id = :absorbed) moved,
                     (SELECT COALESCE(max(seq), 0) AS seq FROM mentor_entry WHERE conversation_id = :kept) top
                WHERE e.id = moved.id
                """, ids);
        turns.requeueRunning(clash.workspaceId(), clash.absorbed());
        jdbc.update("UPDATE mentor_turn SET conversation_id = :kept WHERE conversation_id = :absorbed", ids);
        jdbc.update(
                "UPDATE mentor_conversation SET parent_conversation_id = :kept WHERE parent_conversation_id = :absorbed",
                ids);
        Map<String, @Nullable Object> absorbed = jdbc.queryForMap("""
                SELECT slack_team_id, slack_user_id, slack_channel_id, last_activity_at, last_read_at
                FROM mentor_conversation WHERE id = :absorbed
                """, ids);
        jdbc.update("DELETE FROM mentor_conversation WHERE id = :absorbed", ids);
        // The Slack identity moves with the merge when the claiming main has none, so the DM keeps its conversation.
        Map<String, @Nullable Object> kept = new HashMap<>(absorbed);
        kept.put("kept", clash.kept());
        jdbc.update("""
                UPDATE mentor_conversation SET
                    slack_user_id = CASE WHEN slack_team_id IS NULL THEN :slack_user_id ELSE slack_user_id END,
                    slack_channel_id = CASE WHEN slack_team_id IS NULL THEN :slack_channel_id ELSE slack_channel_id END,
                    slack_team_id = COALESCE(slack_team_id, :slack_team_id),
                    last_activity_at = GREATEST(last_activity_at, :last_activity_at),
                    last_read_at = GREATEST(last_read_at, :last_read_at)
                WHERE id = :kept
                """, kept);
        redaction.redact(summaries);
    }

    @Override
    public void finish(long claimedAccountId, long unclaimedAccountId) {
        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM mentor_conversation WHERE account_id = :unclaimed",
                Map.of("unclaimed", unclaimedAccountId),
                Long.class);
        if (remaining == null || remaining != 0) {
            throw new IllegalStateException("Hephaestus conversation ownership did not move to the claimed account");
        }
    }
}
