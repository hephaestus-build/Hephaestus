package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationCoreConnectionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The dead-letter API reads the inbox's dead events and queues one again on a replay. */
class ConsumerDeadLetterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ProviderEventStore store;

    @Autowired
    private ConsumerDeadLetterService service;

    @Autowired
    private IntegrationCoreConnectionPersonDataCatalog privacy;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AuthEventRepository authEvents;

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Workspace other;
    private String slug;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var owner = persistUser("admin");
        slug = "deadletters-a-" + unique;
        workspace = createWorkspace(slug, "Events A", "events-a-" + unique, AccountType.ORG, owner);
        other = createWorkspace("deadletters-b-" + unique, "Events B", "events-b-" + unique, AccountType.ORG, owner);
    }

    @Test
    @WithAdminUser
    void shouldListAWorkspacesDeadLettersAndKeepThemOutOfOtherWorkspaces() {
        long id = dead(workspace.getId(), "private-canary");

        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(id);
                    assertThat(row.provider()).isEqualTo("github");
                    assertThat(row.errorClass()).isEqualTo("com.example.Failure");
                });
        assertThat(service.listWorkspace(other.getId(), null, 100).content()).isEmpty();
        client.get()
                .uri("/workspaces/{slug}/integrations/dead-letters", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(id)
                .jsonPath("$.content[0].payload")
                .doesNotExist()
                .jsonPath("$.content[0].subject")
                .doesNotExist();
        client.post()
                .uri("/admin/integration/dead-letters/{id}/replays", id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldReadRecordsForwardOnceWithACursor() {
        long first = dead(workspace.getId(), "a");
        long second = dead(workspace.getId(), "b");
        long third = dead(workspace.getId(), "c");

        var page = service.listWorkspace(workspace.getId(), null, 2);
        assertThat(page.content()).extracting(ConsumerDeadLetterDTO::id).containsExactly(first, second);
        var next = service.listWorkspace(workspace.getId(), page.nextCursor(), 2);
        assertThat(next.content()).extracting(ConsumerDeadLetterDTO::id).containsExactly(third);
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    @WithAdminUser
    void shouldQueueAReplayedEventAgainWithItsPayloadAsItsFirstAttempt() {
        long id = dead(workspace.getId(), "replay");

        client.post()
                .uri("/workspaces/{slug}/integrations/dead-letters/{id}/replays", slug, id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.replayedAt")
                .exists()
                .jsonPath("$.errorClass")
                .isEqualTo("com.example.Failure");

        var row = jdbc.queryForMap("SELECT state, attempt, payload FROM provider_event WHERE id = ?", id);
        assertThat(row.get("state")).isEqualTo("QUEUED");
        assertThat(row.get("attempt")).isEqualTo(0);
        assertThat(new String((byte[]) Objects.requireNonNull(row.get("payload")), StandardCharsets.UTF_8))
                .contains("replay");
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .isEmpty();
        client.post()
                .uri("/workspaces/{slug}/integrations/dead-letters/{id}/replays", slug, id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldKeepInstallationRowsSeparateAndAuditTheirReplay() {
        long id = dead(null, "installation");

        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .extracting(ConsumerDeadLetterDTO::id)
                .doesNotContain(id);
        client.post()
                .uri("/workspaces/{slug}/integrations/dead-letters/{id}/replays", slug, id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.post()
                .uri("/admin/integration/dead-letters/{id}/replays", id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        Long actor = jdbc.queryForObject("SELECT replayed_by FROM provider_event WHERE id = ?", Long.class, id);
        assertThat(authEvents.findAll().stream()
                        .filter(event -> event.getEventType() == AuthEvent.EventType.INSTALLATION_EVENT_REPLAYED
                                && ("{\"deadLetterId\":" + id + "}").equals(event.getDetails()))
                        .toList())
                .singleElement()
                .satisfies(event -> assertThat(event.getAccountId()).isEqualTo(actor));
    }

    @Test
    @WithUser
    void shouldRejectMembersWhoAreNotWorkspaceAdministrators() {
        ensureMembership(workspace, persistUser("testuser"), WorkspaceRole.MEMBER);
        client.get()
                .uri("/admin/integration/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/{slug}/integrations/dead-letters", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldClearTheReplayActorAndKeepTheEventWhenTheActorIsErased() {
        long id = dead(workspace.getId(), "privacy");
        client.post()
                .uri("/workspaces/{slug}/integrations/dead-letters/{id}/replays", slug, id)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        Long actor = Objects.requireNonNull(
                jdbc.queryForObject("SELECT replayed_by FROM provider_event WHERE id = ?", Long.class, id));
        var scope = new PersonScope(actor, List.of(), List.of());
        var replays = store("provider_event_replay");

        var selected = replays.select(scope);
        assertThat(replays.export(selected, scope)).singleElement().satisfies(row -> {
            assertThat(row.path("id").asLong()).isEqualTo(id);
            assertThat(row.has("payload")).isFalse();
        });
        // The actor's replay names no one in its payload, so the content store selects nothing.
        assertThat(store("provider_event").select(scope).rows()).isEmpty();
        transactions.executeWithoutResult(
                status -> assertThat(replays.erase(selected, scope)).isEqualTo(1));
        assertThat(jdbc.queryForMap("SELECT state, replayed_by FROM provider_event WHERE id = ?", id))
                .containsEntry("state", "QUEUED")
                .containsEntry("replayed_by", null);
    }

    @Test
    void shouldEraseOnlyTheEventsThatNameThePersonsUserObject() {
        long provider = Objects.requireNonNull(ensureGitHubProvider().getId());
        long named = dead(workspace.getId(), "x", "{\"sender\":{\"login\":\"erased\",\"id\":4711}}");
        long numberOnly = dead(workspace.getId(), "y", "{\"issue\":{\"number\":4711,\"id\":99}}");
        long otherUser = dead(workspace.getId(), "z", "{\"sender\":{\"login\":\"kept\",\"id\":47110}}");
        var scope = new PersonScope(null, List.of(new PersonIdentity(provider, "4711", null)), List.of());
        var events = store("provider_event");

        var selected = events.select(scope);
        transactions.executeWithoutResult(status -> events.erase(selected, scope));

        assertThat(jdbc.queryForList(
                        "SELECT id FROM provider_event WHERE id IN (?, ?, ?)",
                        Long.class,
                        named,
                        numberOnly,
                        otherUser))
                .containsExactlyInAnyOrder(numberOnly, otherUser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "tag_push.create", "tag_push.delete"})
    void shouldEraseGitLabPushEventsByTheSendersProviderIdentity(String fixture) throws Exception {
        long provider = Objects.requireNonNull(ensureGitLabProvider().getId());
        String payload =
                new ClassPathResource("gitlab/" + fixture + ".json").getContentAsString(StandardCharsets.UTF_8);
        long named = dead(workspace.getId(), fixture, payload, "gitlab");
        long otherUser = dead(workspace.getId(), "other-user", "{\"user_id\":900010}", "gitlab");
        long otherProvider = dead(workspace.getId(), "other-provider", payload);
        long otherSource = dead(workspace.getId(), "other-source", payload, "gitlab");
        jdbc.update(
                "UPDATE provider_event SET headers = jsonb_build_object(?, ?) WHERE id = ?",
                ProviderEvent.SOURCE_SERVER_URL,
                "https://other.gitlab.example.test",
                otherSource);
        var scope = new PersonScope(null, List.of(new PersonIdentity(provider, "90001", null)), List.of());
        var events = store("provider_event");

        var selected = events.select(scope);
        transactions.executeWithoutResult(status -> events.erase(selected, scope));

        assertThat(jdbc.queryForList(
                        "SELECT id FROM provider_event WHERE id IN (?, ?, ?, ?)",
                        Long.class,
                        named,
                        otherUser,
                        otherProvider,
                        otherSource))
                .containsExactlyInAnyOrder(otherUser, otherProvider, otherSource);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"author_id", "assignee_id", "merge_user_id", "updated_by_id", "last_edited_by_id", "assignee_ids"
            })
    void shouldEraseGitLabEventsThatNameThePersonAsAnItemParticipant(String field) {
        long provider = Objects.requireNonNull(ensureGitLabProvider().getId());
        String value = field.equals("assignee_ids") ? "[90001]" : "90001";
        long named = dead(
                workspace.getId(),
                field,
                "{\"user\":{\"id\":2,\"username\":\"other\"},\"object_attributes\":{\"" + field + "\":" + value + "}}",
                "gitlab");
        long entityOnly =
                dead(workspace.getId(), "entity", "{\"object_attributes\":{\"id\":90001,\"iid\":90001}}", "gitlab");
        var scope = new PersonScope(null, List.of(new PersonIdentity(provider, "90001", null)), List.of());
        var events = store("provider_event");

        var selected = events.select(scope);
        transactions.executeWithoutResult(status -> events.erase(selected, scope));

        assertThat(jdbc.queryForList("SELECT id FROM provider_event WHERE id IN (?, ?)", Long.class, named, entityOnly))
                .containsExactly(entityOnly);
    }

    private PersonDataContributor store(String name) {
        return privacy.contributors().stream()
                .filter(candidate -> candidate.store().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** A dead event of {@code workspaceId}, or an installation one, whose payload carries {@code marker}. */
    private long dead(@Nullable Long workspaceId, String marker) {
        return dead(workspaceId, marker, "{\"marker\":\"" + marker + "\"}");
    }

    private long dead(@Nullable Long workspaceId, String marker, String payload) {
        return dead(workspaceId, marker, payload, "github");
    }

    private long dead(@Nullable Long workspaceId, String marker, String payload, String provider) {
        String delivery = UUID.randomUUID().toString() + "-" + marker;
        store.store(List.of(new ProviderEventStore.Arrival(
                workspaceId,
                provider,
                workspaceId == null ? provider + ".?.?.installation" : provider + ".acme.repo.issues",
                delivery,
                Map.of(ProviderEvent.SOURCE_SERVER_URL, "https://" + provider + ".com"),
                payload.getBytes(StandardCharsets.UTF_8))));
        jdbc.update(
                "UPDATE provider_event SET state = 'DEAD', ended_at = now(), error_class = 'com.example.Failure' "
                        + "WHERE delivery_key = ?",
                delivery);
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM provider_event WHERE delivery_key = ?", Long.class, delivery));
    }
}
