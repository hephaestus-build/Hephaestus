package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The scheduled cleanup reaches every owner's create receipts through the owner function, under the test profile's
 * tenancy enforcement, which throws on any statement that reads a workspace table without its workspace.
 */
class ClientInputRetentionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ClientInputs clientInputs;

    @Test
    void shouldExpireReceiptsOlderThanEightDaysOfEveryOwnerAndKeepTheRest() {
        var first = persistUser("receipt-first-owner");
        var second = persistUser("receipt-second-owner");
        var workspace = createWorkspace("receipt-expiry", "Receipt expiry", "receipt-expiry", AccountType.ORG, first);
        UUID expiredFirst = UUID.randomUUID();
        UUID expiredSecond = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        var owner = PostgreSQLTestContainer.ownerJdbc();
        String seed = """
                INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
                VALUES(?,?,?,sha256('fixture'::bytea),uuidv7(),uuidv7(),now() - make_interval(days => ?))
                """;
        owner.update(seed, workspace.getId(), accountId(first), expiredFirst, 9);
        owner.update(seed, workspace.getId(), accountId(second), expiredSecond, 9);
        owner.update(seed, workspace.getId(), accountId(first), fresh, 7);

        clientInputs.prune();

        assertThat(owner.queryForList(
                        "SELECT idem_key FROM client_input WHERE idem_key IN (?,?,?)",
                        UUID.class,
                        expiredFirst,
                        expiredSecond,
                        fresh))
                .containsExactly(fresh);
    }
}
