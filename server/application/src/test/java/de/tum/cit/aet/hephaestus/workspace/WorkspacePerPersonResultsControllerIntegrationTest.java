package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Only an owner turns per-person results for admins on; any admin turns them off; every member reads them. */
@Tag("integration")
class WorkspacePerPersonResultsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Consumer<HttpHeaders> owner;
    private Consumer<HttpHeaders> admin;
    private Consumer<HttpHeaders> member;

    @BeforeEach
    void setUp() {
        persistUser("admin");
        User ownerUser = persistUser("per-person-owner");
        workspace = createWorkspace("per-person", "Per person", "per-person", AccountType.ORG, ownerUser);
        owner = signedIn(ownerUser, Membership.WorkspaceRole.OWNER);
        admin = signedIn(persistUser("per-person-admin"), Membership.WorkspaceRole.ADMIN);
        member = signedIn(persistUser("per-person-member"), Membership.WorkspaceRole.MEMBER);
    }

    @Test
    void aNewWorkspaceStartsWithoutPerPersonResults() {
        read(member)
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.canTurnOn")
                .isEqualTo(false);
        read(owner)
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.canTurnOn")
                .isEqualTo(true);
    }

    @Test
    void onlyAnOwnerTurnsThemOnAndAnyAdminTurnsThemOff() {
        update(admin, true).expectStatus().isForbidden();
        assertThat(stored()).isFalse();

        update(owner, true)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true);
        assertThat(stored()).isTrue();
        read(member).jsonPath("$.enabled").isEqualTo(true);

        update(admin, false)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);
        assertThat(stored()).isFalse();

        update(member, false).expectStatus().isForbidden();
        assertThat(auditedValues())
                .containsExactly("{\"perPersonResultsForAdmins\":true}", "{\"perPersonResultsForAdmins\":false}");
    }

    private WebTestClient.BodyContentSpec read(Consumer<HttpHeaders> caller) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/per-person-results", workspace.getWorkspaceSlug())
                .headers(caller)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private WebTestClient.ResponseSpec update(Consumer<HttpHeaders> caller, boolean enabled) {
        return webTestClient
                .patch()
                .uri("/workspaces/{slug}/per-person-results", workspace.getWorkspaceSlug())
                .headers(caller)
                .bodyValue(Map.of("enabled", enabled))
                .exchange();
    }

    private boolean stored() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT per_person_results_for_admins FROM workspace WHERE id = ?", Boolean.class, workspace.getId()));
    }

    private List<String> auditedValues() {
        return jdbc
                .queryForList(
                        "SELECT new_value FROM config_audit_event WHERE workspace_id = ? AND entity_type = 'WORKSPACE_VISIBILITY'"
                                + " ORDER BY id",
                        String.class,
                        workspace.getId())
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    /** A plain account signed in the way production does, with the given role in the workspace. */
    private Consumer<HttpHeaders> signedIn(User user, Membership.WorkspaceRole role) {
        ensureMembership(workspace, user, role);
        var account = accounts.findById(accountId(user)).orElseThrow();
        if (account.getStatus() == Account.Status.UNCLAIMED) account.claim();
        long id = Objects.requireNonNull(accounts.save(account).getId());
        return headers -> headers.setBearerAuth("mock-jwt-member-" + id);
    }
}
