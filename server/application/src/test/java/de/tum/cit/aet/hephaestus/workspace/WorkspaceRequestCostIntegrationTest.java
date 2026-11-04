package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The statements that every workspace request costs before its handler runs. Three of them come from the mock JWT
 * decoder of the test context; in production, the decoder makes one admission read instead.
 */
class WorkspaceRequestCostIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SqlRequestTrace requests;

    @Autowired
    private QueryPlans plans;

    @Test
    @WithAdminUser
    void shouldResolveTheWorkspaceInSevenStatementsWhenTheHandlerReadsNothing() {
        User owner = persistUser("admin");
        Workspace workspace = createWorkspace("request-cost", "Request Cost", "request-cost", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        var request = requests.measure(
                webTestClient,
                client -> client.get()
                        .uri("/workspaces/{workspaceSlug}/scoped-test/echo", workspace.getWorkspaceSlug())
                        .headers(TestAuthUtils.withCurrentUser())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Void.class));

        assertThat(request.statements()).isLessThanOrEqualTo(7);
        assertThat(plans.fullScans(request.sql())).isEmpty();
    }

    @Test
    void shouldFindTheFullScanWhenAQueryLeavesOutThePartialIndexPredicate() {
        String withoutPredicate =
                "SELECT account_id FROM identity WHERE provider_id = ? AND provider_user_key = ? AND team_key = ''";

        assertThat(plans.fullScans(List.of(withoutPredicate)))
                .extracting(QueryPlans.FullScan::table)
                .containsExactly("identity");
        assertThat(plans.fullScans(List.of(withoutPredicate + " AND disabled_at IS NULL")))
                .isEmpty();
    }
}
