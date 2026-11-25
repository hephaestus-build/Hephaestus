package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A request id is unique per owner. A unique key that holds no owner answers with a duplicate for another person's
 * row, so a reused id would tell a caller that someone else's request exists.
 */
class ConversationRequestKeyTest extends BaseIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldTreatAnotherPersonsRequestIdAsMissingNotAsADuplicate() {
        long workspace = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO workspace(slug,display_name,account_login,account_type,status)
            VALUES('request-key','request-key','request-key','ORG','ACTIVE') RETURNING id
            """, Long.class));
        long first = account();
        long second = account();
        String requestId = "slack:T1:D9:100.1";
        request(workspace, second, requestId);

        assertThat(turnsWith(workspace, first, requestId))
                .as("the other person's request is not visible to the owner lookup")
                .isZero();
        request(workspace, first, requestId);

        assertThat(turnsWith(workspace, first, requestId)).isOne();
        assertThat(turnsWith(workspace, second, requestId)).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_entry WHERE workspace_id = ? AND request_id = ?",
                        Integer.class,
                        workspace,
                        requestId))
                .isEqualTo(2);
    }

    /** One turn and its question, written the way a submission writes them, with no lookup first. */
    private void request(long workspace, long account, String requestId) {
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)
            VALUES(?,?,?,now(),now())
            """, conversation, workspace, account);
        jdbc.update("""
            INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,available_at,
                stop_reason,created_at,ended_at)
            VALUES(?,?,'TURN','INTERACTIVE',?,'ENDED',0,0,now(),'COMPLETED',now(),now())
            """, run, workspace, "conversation:" + conversation);
        jdbc.update("""
            INSERT INTO conversation_turn(id,workspace_id,conversation_id,account_id,request_id,status,surface,run_id,
                created_at)
            VALUES(?,?,?,?,?,'DONE','SLACK',?,now())
            """, turn, workspace, conversation, account, requestId, run);
        jdbc.update("""
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,turn_id,
                request_id,created_at)
            VALUES(?,?,?,?,1,'USER','SLACK','A question.',?,?,now())
            """, UUID.randomUUID(), workspace, conversation, account, turn, requestId);
    }

    private int turnsWith(long workspace, long account, String requestId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM conversation_turn WHERE workspace_id = ? AND account_id = ? AND request_id = ?",
                Integer.class,
                workspace,
                account,
                requestId));
    }

    private long account() {
        return Objects.requireNonNull(
                accounts.save(new Account("Request owner")).getId());
    }
}
