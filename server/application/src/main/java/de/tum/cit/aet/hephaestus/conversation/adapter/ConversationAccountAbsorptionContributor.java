package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 * from the merged conversation. The absorbed main's queued runs move to the claiming main's subject, so they wait
 * behind its turns.
 *
 * <p>The rows move between two owners, so the owner function {@code person_row_sweep} moves them with its rule
 * {@code ACCOUNT_ABSORB}. It refuses a clash whose absorbed main still runs a turn or keeps a summary, so a clash that
 * this class did not prepare cannot move. Finding the clashes reads both accounts' conversations; once row-level
 * security covers the conversation tables, that read needs each owner's scope.
 */
@Order(30)
@Component
@RequiredArgsConstructor
public class ConversationAccountAbsorptionContributor implements AccountAbsorptionContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ConversationRedaction redaction;
    private final ConversationTurnService turns;
    private final TurnRuns turnRuns;

    private record Clash(long workspaceId, UUID absorbed, UUID kept) {}

    @Override
    public void absorb(long claimedAccountId, long unclaimedAccountId) {
        Map<String, Long> accounts = Map.of("claimed", claimedAccountId, "unclaimed", unclaimedAccountId);
        List<Clash> clashes = jdbc.query(
                """
                SELECT absorbed.workspace_id, absorbed.id, kept.id FROM conversation absorbed
                JOIN conversation kept ON kept.workspace_id = absorbed.workspace_id
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
        List<UUID> summaries = new ArrayList<>();
        for (Clash clash : clashes) {
            summaries.addAll(jdbc.query(
                    "SELECT id FROM conversation_entry WHERE conversation_id = :absorbed AND kind = 'SUMMARY'"
                            + " AND redacted_at IS NULL",
                    Map.of("absorbed", clash.absorbed()),
                    (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class))));
            turns.requeueRunning(clash.workspaceId(), clash.absorbed());
            turnRuns.moveQueued(clash.workspaceId(), clash.absorbed(), clash.kept());
        }
        // Summaries are never shown, so erasing them before the move changes no open conversation.
        redaction.redact(summaries);
        jdbc.queryForObject(
                "SELECT public.person_row_sweep('ACCOUNT_ABSORB', :unclaimed, :claimed, CAST(NULL AS timestamptz))",
                accounts,
                Integer.class);
    }

    @Override
    public void finish(long claimedAccountId, long unclaimedAccountId) {
        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE account_id = :unclaimed",
                Map.of("unclaimed", unclaimedAccountId),
                Long.class);
        if (remaining == null || remaining != 0) {
            throw new IllegalStateException("Hephaestus conversation ownership did not move to the claimed account");
        }
    }
}
