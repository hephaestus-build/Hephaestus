package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.dto.SpaceDTO;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A personal space is created the first time its owner needs it, once, and it ends with the account (KR-06 layer 2).
 */
class PersonalSpaceProvisioningIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PersonalSpaces personalSpaces;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PersonDataService personDataService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WorkspaceSlugService slugs;

    private long ownerId;

    @BeforeEach
    void owner() {
        ownerId = Objects.requireNonNull(persistAccount("Personal owner").getId());
    }

    /** Both calls wait on the account row that a third transaction holds, so they overlap once it lets go. */
    @Test
    void shouldCreateOneSpaceAndOneReservationWhenTwoRequestsOverlap() throws Exception {
        long reservationsBefore = reservations();
        var executor = Executors.newFixedThreadPool(3);
        try {
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holder = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM account WHERE id = ? FOR UPDATE", Long.class, ownerId);
                        locked.countDown();
                        await(release);
                    }),
                    executor);
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            var first = CompletableFuture.supplyAsync(
                    () -> personalSpaces.ensure(ownerId).getId(), executor);
            var second = CompletableFuture.supplyAsync(
                    () -> personalSpaces.ensure(ownerId).getId(), executor);
            awaitLockWaiters(2);
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM workspace WHERE personal_account_id = ?", Long.class, ownerId))
                .isEqualTo(1L);
        assertThat(reservations()).isEqualTo(reservationsBefore + 1);
        assertThat(jdbc.queryForList(
                        "SELECT m.account_id, m.role FROM membership m JOIN workspace w ON w.id = m.workspace_id"
                                + " WHERE w.personal_account_id = ?",
                        ownerId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("account_id")).isEqualTo(ownerId);
                    assertThat(row.get("role")).isEqualTo("MEMBER");
                });
    }

    @Test
    void shouldListThePersonalSpaceFirstThenTheTeamsByNameAcrossPages() {
        joinTeam("team-zeta", "Zeta");
        joinTeam("team-alpha", "Alpha");

        OffsetPageDTO<SpaceDTO> first = listSpaces(0, 2);
        OffsetPageDTO<SpaceDTO> second = listSpaces(1, 2);

        assertThat(first.totalElements()).isEqualTo(3L);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).hasSize(2);
        assertThat(first.content().get(0).kind()).isEqualTo(WorkspaceKind.PERSONAL);
        assertThat(first.content().get(0).workspaceSlug()).matches("me--[a-z2-7]{16}");
        assertThat(first.content().get(1).workspaceSlug()).isEqualTo("team-alpha");
        assertThat(second.content()).extracting(SpaceDTO::workspaceSlug).containsExactly("team-zeta");
        assertThat(listSpaces(0, 2).content().get(0))
                .as("the address stays")
                .isEqualTo(first.content().get(0));
        webTestClient
                .get()
                .uri("/user/spaces?page={page}&size={size}", 10_000_001, 100)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + ownerId))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldMakeTheOwnersEndedMembershipActiveAgain() {
        long spaceId = personalSpaces.ensure(ownerId).getId();
        jdbc.update(
                "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?", spaceId, ownerId);

        assertThat(personalSpaces.ensure(ownerId).getId()).isEqualTo(spaceId);

        assertThat(jdbc.queryForObject(
                        "SELECT left_at IS NULL FROM membership WHERE workspace_id = ? AND account_id = ?",
                        Boolean.class,
                        spaceId,
                        ownerId))
                .isTrue();
    }

    @Test
    void shouldCreateNoSpaceForAnAccountThatIsBeingDeleted() {
        jdbc.update("UPDATE account SET status = 'DELETING', deleted_at = now() WHERE id = ?", ownerId);

        assertThatThrownBy(() -> personalSpaces.ensure(ownerId)).isInstanceOf(EntityNotFoundException.class);
        assertThat(workspaces.findByPersonalAccountId(ownerId)).isEmpty();
        // An erasure that ends the space between ensure() and the page leaves no list without the space either.
        assertThatThrownBy(() -> personalSpaces.spaces(ownerId, 0, 1)).isInstanceOf(EntityNotFoundException.class);
    }

    /** The erasure ends a provisioned space, and its address stays reserved, so provisioning never draws it again. */
    @Test
    void shouldEndTheSpaceAndKeepItsAddressReservedWhenTheAccountIsErased() {
        var space = personalSpaces.ensure(ownerId);

        personDataService.eraseForAccount(ownerId);

        assertThat(workspaces.findById(space.getId())).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM membership WHERE workspace_id = ?", Long.class, space.getId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM workspace_slug_reservation WHERE slug = ?",
                        Long.class,
                        space.getWorkspaceSlug()))
                .isZero();
        assertThat(slugs.isReserved(space.getWorkspaceSlug())).isTrue();
    }

    /**
     * An instance admin's preview counts the team membership, the personal space, and the sandbox cleanup of both: the
     * personal space's sandboxes hold the person's own copies.
     */
    @Test
    void shouldCountTheTeamMembershipAndTheCleanupOfBothSpacesInAnAdminPreview() {
        personalSpaces.ensure(ownerId);
        long teamId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("team-of-the-subject"))
                .getId();
        jdbc.update("INSERT INTO membership (workspace_id, account_id, role) VALUES (?, ?, 'MEMBER')", teamId, ownerId);
        long adminId =
                Objects.requireNonNull(persistInstanceAdmin("Instance admin").getId());

        var counts = mapper.readTree(
                personDataService.preview(adminId, ownerId, List.of()).request().getCountsJson());

        assertThat(counts.get("membership").asLong()).isEqualTo(1L);
        assertThat(counts.get("workspace").asLong()).isEqualTo(1L);
        assertThat(counts.get("conversation_entry_tool_result_copy").asLong()).isEqualTo(2L);
    }

    private long reservations() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM workspace_slug_reservation WHERE slug LIKE 'me--%'", Long.class));
    }

    private void awaitLockWaiters(int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                + " AND query LIKE 'SELECT status FROM account%'",
                        Long.class))
                < waiters) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The provisioning calls never waited on the account lock");
            }
            Thread.sleep(50); // NOPMD - polls the database for the waiting calls
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test never released the account lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void joinTeam(String slug, String name) {
        var team = WorkspaceTestFixtures.activeWorkspace(slug);
        team.setDisplayName(name);
        long teamId = workspaces.save(team).getId();
        jdbc.update("INSERT INTO membership (workspace_id, account_id, role) VALUES (?, ?, 'MEMBER')", teamId, ownerId);
    }

    private OffsetPageDTO<SpaceDTO> listSpaces(int page, int size) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri("/user/spaces?page={page}&size={size}", page, size)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + ownerId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<OffsetPageDTO<SpaceDTO>>() {})
                .returnResult()
                .getResponseBody());
    }
}
