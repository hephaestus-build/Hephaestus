package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.column;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.execute;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A config audit row names the MCP client grant that made the change (ADR 0110 decision 16). Under the prod context's
 * append-only trigger, retention or erasure of the grant clears that reference and keeps the row, and nothing else may
 * change it.
 */
@Tag("database")
class McpGrantAuditTrailDatabaseTest {

    @Test
    void shouldKeepTheAuditRowAndClearItsClientWhenTheGrantIsDeleted() throws Exception {
        var database = migratedDatabase("prod");
        UUID grant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(995931,'w995931','W995931','w995931','ORG','ACTIVE');
                INSERT INTO account(id,display_name) VALUES(995932,'Admin');
                INSERT INTO mcp_client_grant(id,workspace_id,account_id,client_id,client_name,redirect_uri,resource,
                    scopes,code_challenge,status,expires_at,created_at)
                VALUES('%1$s',995931,995932,'https://client.example/c.json','Client','http://127.0.0.1/callback',
                    'https://hephaestus.example/api/workspaces/w995931/mcp','practices:read practices:write',
                    'challenge','ACTIVE',now()+interval '1 hour',now()),
                      ('%2$s',995931,995932,'https://client.example/c.json','Client','http://127.0.0.1/callback',
                    'https://hephaestus.example/api/workspaces/w995931/mcp','practices:read practices:write',
                    'challenge','ACTIVE',now()+interval '1 hour',now());
                INSERT INTO config_audit_event(id,workspace_id,actor_kind,actor_account_id,entity_type,entity_id,action,
                    changed_keys,old_value,new_value,occurred_at,mcp_client_grant_id)
                VALUES(995933,995931,'USER',995932,'PRACTICE_REVIEW_SETTINGS','995931','UPDATED',ARRAY['deliveryStatus'],
                    '{}','{}',now(),'%1$s');
                """.formatted(grant, other));

        assertThatThrownBy(() -> execute(database, """
                UPDATE config_audit_event SET mcp_client_grant_id = '%s' WHERE id = 995933
                """.formatted(other)))
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("config_audit_event is append-only");

        execute(database, "DELETE FROM mcp_client_grant WHERE id = '%s'".formatted(grant));

        assertThat(column(
                        database,
                        "SELECT coalesce(mcp_client_grant_id::text, 'none') FROM config_audit_event WHERE id = 995933"))
                .containsExactly("none");
    }
}
