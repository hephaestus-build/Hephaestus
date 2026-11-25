package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceStartedMessages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Any admin lets Hephaestus start messages in the workspace, or stops it, and the contact policy reads the switch. */
@Tag("integration")
class WorkspaceStartedMessagesControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceStartedMessages startedMessages;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceSettingsService settings;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;
    private Consumer<HttpHeaders> admin;
    private Consumer<HttpHeaders> member;

    @BeforeEach
    void setUp() {
        persistUser("admin");
        User ownerUser = persistUser("started-messages-owner");
        workspace =
                createWorkspace("started-messages", "Started messages", "started-messages", AccountType.ORG, ownerUser);
        admin = signedIn(persistUser("started-messages-admin"), Membership.WorkspaceRole.ADMIN);
        member = signedIn(persistUser("started-messages-member"), Membership.WorkspaceRole.MEMBER);
    }

    @Test
    void shouldLetHephaestusStartMessagesInANewWorkspace() {
        read(admin).jsonPath("$.enabled").isEqualTo(true);
        assertThat(startedMessages.startedMessagesEnabled(workspace.getId())).isTrue();
    }

    @Test
    void shouldLetAnyAdminTurnStartedMessagesOffAndOnAgainWithAnAuditEntryForEach() {
        update(admin, false)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);
        assertThat(stored()).isFalse();
        assertThat(startedMessages.startedMessagesEnabled(workspace.getId())).isFalse();

        update(admin, true)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true);
        assertThat(stored()).isTrue();
        assertThat(auditedValues())
                .containsExactly("{\"startedMessagesEnabled\":false}", "{\"startedMessagesEnabled\":true}");
    }

    @Test
    void shouldRecordOneChangeWhenTwoAdminsTurnTheSwitchOffAtOnce() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The first admin's change holds the workspace row until the second admin's change waits for it.
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            settings.updateStartedMessages(workspace.getId(), false);
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second =
                CompletableFuture.runAsync(() -> settings.updateStartedMessages(workspace.getId(), false));
        await().atMost(Duration.ofSeconds(20))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock')",
                        Boolean.class)));
        release.countDown();
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertThat(stored()).isFalse();
        assertThat(auditedValues()).containsExactly("{\"startedMessagesEnabled\":false}");
    }

    @Test
    void shouldRecordNothingWhenTheSwitchAlreadyHasTheValue() {
        update(admin, true).expectStatus().isOk().expectBody(Void.class);
        assertThat(auditedValues()).isEmpty();
    }

    @Test
    void shouldRefuseAMemberWhoReadsOrChangesTheSwitch() {
        webTestClient
                .get()
                .uri("/workspaces/{slug}/started-messages", workspace.getWorkspaceSlug())
                .headers(member)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        update(member, false).expectStatus().isForbidden().expectBody(Void.class);
        assertThat(stored()).isTrue();
    }

    @Test
    void shouldTreatAnUnknownWorkspaceAsClosedToStartedMessages() {
        assertThat(startedMessages.startedMessagesEnabled(Long.MAX_VALUE)).isFalse();
    }

    private WebTestClient.BodyContentSpec read(Consumer<HttpHeaders> caller) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/started-messages", workspace.getWorkspaceSlug())
                .headers(caller)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private WebTestClient.ResponseSpec update(Consumer<HttpHeaders> caller, boolean enabled) {
        return webTestClient
                .patch()
                .uri("/workspaces/{slug}/started-messages", workspace.getWorkspaceSlug())
                .headers(caller)
                .bodyValue(Map.of("enabled", enabled))
                .exchange();
    }

    private boolean stored() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT started_messages_enabled FROM workspace WHERE id = ?", Boolean.class, workspace.getId()));
    }

    private List<String> auditedValues() {
        return jdbc
                .queryForList(
                        "SELECT new_value FROM config_audit_event WHERE workspace_id = ?"
                                + " AND entity_type = 'WORKSPACE_FEATURES' AND new_value LIKE '%startedMessagesEnabled%'"
                                + " ORDER BY id",
                        String.class, workspace.getId())
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
