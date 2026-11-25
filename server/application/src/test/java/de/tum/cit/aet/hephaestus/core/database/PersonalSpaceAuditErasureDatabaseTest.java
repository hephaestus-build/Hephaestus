package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.column;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.execute;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Under the prod context's append-only trigger, the trail of a suspended personal space goes with the space when its
 * owner's erasure ends it (KR-06, #5094). A team's trail, and an active personal space's, stay inside the retention
 * window.
 */
@Tag("database")
class PersonalSpaceAuditErasureDatabaseTest {

    @Test
    void shouldDeleteOnlyTheTrailOfASuspendedPersonalSpace() throws Exception {
        var database = migratedDatabase("prod");
        execute(database, """
                INSERT INTO account(id,display_name) VALUES(995941,'Owner');
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(995942,'w995942','W995942','w995942','ORG','ACTIVE');
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status,kind,personal_account_id)
                VALUES(995943,'me--aaaaaaaaaaaaaaab','Personal space','me--aaaaaaaaaaaaaaab','USER','ACTIVE','PERSONAL',995941);
                INSERT INTO config_audit_event(id,workspace_id,actor_kind,entity_type,entity_id,action,changed_keys)
                VALUES(995944,995942,'SYSTEM','WORKSPACE_ROLE','member','CREATED',ARRAY['role']),
                      (995945,995943,'SYSTEM','WORKSPACE_ROLE','member','CREATED',ARRAY['role']);
                """);

        assertThatThrownBy(() -> execute(database, "DELETE FROM config_audit_event WHERE id = 995945"))
                .as("an active personal space keeps its trail")
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("config_audit_event is append-only");
        execute(database, "UPDATE workspace SET status = 'SUSPENDED' WHERE id IN (995942, 995943)");
        assertThatThrownBy(() -> execute(database, """
                        CREATE TEMP TABLE workspace (id bigint, kind text, status text);
                        INSERT INTO workspace VALUES (995942, 'PERSONAL', 'SUSPENDED');
                        DELETE FROM config_audit_event WHERE id = 995944;
                        """))
                .as("a session's temporary table cannot pass a team off as a suspended personal space")
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("config_audit_event is append-only");
        assertThatThrownBy(() -> execute(database, "DELETE FROM config_audit_event WHERE id = 995944"))
                .as("a team keeps its trail, also when suspended")
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("config_audit_event is append-only");

        execute(database, "DELETE FROM config_audit_event WHERE id = 995945");

        assertThat(column(database, "SELECT id::text FROM config_audit_event WHERE id IN (995944, 995945)"))
                .containsExactly("995944");
    }
}
