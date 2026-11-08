package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.util.ArrayList;
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
    void shouldKeepWorkspaceListRequestCostBoundedAsMembershipsGrow() {
        User owner = persistUser("admin");
        var ids = new ArrayList<Long>();
        for (int index = 0; index < 2; index++) {
            var workspace = listWorkspace(owner, index);
            ids.add(workspace.getId());
        }
        var small = requests.measure(webTestClient, client -> client.get().uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser()).exchange().expectStatus().isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .value(values -> assertThat(values).extracting(WorkspaceListItemDTO::id).containsExactlyElementsOf(ids)));
        for (int index = 2; index < 8; index++) ids.add(listWorkspace(owner, index).getId());

        var larger = requests.measure(webTestClient, client -> client.get().uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser()).exchange().expectStatus().isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .value(values -> assertThat(values).extracting(WorkspaceListItemDTO::id).containsExactlyElementsOf(ids)));

        assertThat(larger.statements()).as("small=%s, larger=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements()).isLessThanOrEqualTo(7);
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    private Workspace listWorkspace(User owner, int index) {
        String slug = "list-cost-%02d".formatted(index);
        var workspace = createWorkspace(slug, "List cost %02d".formatted(index), slug, AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

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
