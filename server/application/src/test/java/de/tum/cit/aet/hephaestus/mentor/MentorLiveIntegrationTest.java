package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.mentor.web.MentorLiveHub;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.Yaml;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live stream of a developer's conversation: a snapshot on every connect, then the committed changes of their
 * own conversation and no one else's, carried between processes by PostgreSQL notifications.
 */
class MentorLiveIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String MEMBER = "mock-jwt-token-for-mentor-user";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String SNAPSHOT = "snapshot";
    private static final String ENTRY = "entry";
    private static final String TURN = "turn";
    private static final String READ = "read";

    @Autowired
    private WebTestClient client;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorLedgerNotifications notifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MentorLiveHub hub;

    private User developer;
    private User colleague;
    private Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        developer = persistUser("mentor");
        colleague = persistUser("heph-live-colleague");
        workspace = createWorkspace("heph-live", "Heph live", "heph-live", AccountType.ORG, persistUser("live-owner"));
        ensureWorkspaceMembership(workspace, developer, WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, colleague, WorkspaceRole.MEMBER);
    }

    /** One open stream; the test cancels it. */
    private record Stream(List<ServerSentEvent<String>> events, AtomicBoolean ended, Disposable subscription) {

        boolean has(String name, String content) {
            return events.stream()
                    .anyMatch(event -> name.equals(event.event())
                            && event.data() != null
                            && event.data().contains(content));
        }
    }

    private Stream open() {
        return open(MEMBER, null);
    }

    private Stream open(@Nullable String lastEventId) {
        return open(MEMBER, lastEventId);
    }

    /** A stream opened with a bearer token, as a native client opens it, optionally naming the last event it saw. */
    private Stream open(String token, @Nullable String lastEventId) {
        List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        AtomicBoolean ended = new AtomicBoolean();
        Disposable subscription = client.get()
                .uri("/workspaces/heph-live/mentor/events")
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    if (lastEventId != null) {
                        headers.set("Last-Event-ID", lastEventId);
                    }
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody()
                .filter(event -> event.event() != null || event.comment() != null)
                .subscribe(events::add, failure -> ended.set(true), () -> ended.set(true));
        return new Stream(events, ended, subscription);
    }

    private void awaitListening() {
        MentorLedgerProbe.on(notifications).awaitListening(transactions, turnRepository, WAIT);
    }

    private MentorTurnService.Submission submit(User author, String text) {
        MentorConversation main =
                conversations.findOrCreateMain(workspace.getId(), author.getId(), Set.of(author.getId()));
        return turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    @Test
    void shouldSendASnapshotAndThenTheDevelopersOwnChangesWhenTheLedgerChanges() {
        Stream stream = open();
        try {
            await().atMost(WAIT).until(() -> stream.has(SNAPSHOT, "\"activeTurns\":[]"));
            awaitListening();

            MentorTurnService.Submission foreign = submit(colleague, "A colleague's question");
            MentorTurnService.Submission own = submit(developer, "My question");
            turns.stop(workspace.getId(), own.turn().getId());

            await().atMost(WAIT)
                    .until(() -> stream.has(ENTRY, own.question().getId().toString())
                            && stream.has(TURN, "\"status\":\"STOPPED\""));
            assertThat(stream.has(ENTRY, "\"text\":\"My question\"")).isTrue();
            assertThat(stream.has(TURN, own.turn().getId().toString())).isTrue();
            assertThat(stream.events())
                    .noneMatch(event -> event.data() != null
                            && (event.data().contains(foreign.turn().getId().toString())
                                    || event.data()
                                            .contains(foreign.question().getId().toString())));
        } finally {
            stream.subscription().dispose();
        }
    }

    @Test
    void shouldSendTheSnapshotFirstThenTurnsEntriesAndPingsInTheShapeTheApiDocuments() {
        Stream stream = open();
        try {
            await().atMost(WAIT).until(() -> !stream.events().isEmpty());
            awaitListening();
            MentorTurnService.Submission own = submit(developer, "Is the contract kept?");
            await().atMost(WAIT)
                    .until(() -> stream.has(TURN, own.turn().getId().toString())
                            && stream.has(ENTRY, own.question().getId().toString()));
            ReflectionTestUtils.invokeMethod(hub, "heartbeat");
            await().atMost(WAIT)
                    .until(() -> stream.events().stream().anyMatch(event -> "ping".equals(event.comment())));

            assertThat(stream.events().getFirst().event()).isEqualTo(SNAPSHOT);
            Map<String, String> schemas = Map.of(
                    SNAPSHOT, "MentorConversation", TURN, "MentorTurn", ENTRY, "MentorEntry", READ, "MentorRead");
            for (ServerSentEvent<String> event : stream.events()) {
                if (event.event() == null) {
                    continue;
                }
                assertThat(schemas).containsKey(event.event());
                assertMatchesSchema(event.data(), Objects.requireNonNull(schemas.get(event.event())));
            }
        } finally {
            stream.subscription().dispose();
        }
    }

    /** The OpenAPI schemas the generated clients are built from. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws Exception {
        Map<String, Object> spec =
                new Yaml().load(Files.readString(Path.of("..", "openapi.yaml"), StandardCharsets.UTF_8));
        Map<String, Object> components = (Map<String, Object>) Objects.requireNonNull(spec.get("components"));
        Map<String, Object> schemas = (Map<String, Object>) Objects.requireNonNull(components.get("schemas"));
        return (Map<String, Object>) Objects.requireNonNull(schemas.get(name), name);
    }

    /** Every property is one the schema declares, and every property it requires is there. */
    @SuppressWarnings("unchecked")
    private void assertMatchesSchema(@Nullable String data, String name) {
        assertThat(data).as(name).isNotNull();
        try {
            Map<String, Object> schema = schema(name);
            Set<String> declared = ((Map<String, Object>) Objects.requireNonNull(schema.get("properties"))).keySet();
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            Set<String> sent = new HashSet<>(JsonMapper.shared().readTree(data).propertyNames());
            assertThat(declared).as("%s declares every property sent", name).containsAll(sent);
            assertThat(sent).as("%s sends every required property", name).containsAll(required);
        } catch (Exception e) {
            throw new AssertionError("Could not check " + name + " against openapi.yaml", e);
        }
    }

    @Test
    void shouldCloseTheStreamWithoutFurtherContentWhenTheDeveloperLeavesTheWorkspace() {
        Stream stream = open();
        try {
            await().atMost(WAIT).until(() -> stream.has(SNAPSHOT, "\"activeTurns\":[]"));
            awaitListening();
            MentorTurnService.Submission own = submit(developer, "Before I leave");
            await().atMost(WAIT)
                    .until(() -> stream.has(ENTRY, own.question().getId().toString()));

            jdbc.update(
                    "DELETE FROM workspace_membership WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(),
                    developer.getId());
            turns.stop(workspace.getId(), own.turn().getId());

            await().atMost(WAIT).untilTrue(stream.ended());
            assertThat(stream.has(TURN, "\"status\":\"STOPPED\"")).isFalse();
        } finally {
            stream.subscription().dispose();
        }
    }

    @Test
    void shouldSendTheCurrentStateAgainWhenTheClientReconnects() {
        MentorTurnService.Submission own = submit(developer, "Still waiting?");
        Stream first = open();
        try {
            await().atMost(WAIT)
                    .until(() -> first.has(SNAPSHOT, own.turn().getId().toString()));
        } finally {
            first.subscription().dispose();
        }

        // There is no resume by event id: a reconnect, whatever it names, starts from a fresh snapshot.
        Stream second = open("snapshot-1");
        try {
            await().atMost(WAIT)
                    .until(() -> second.has(SNAPSHOT, own.turn().getId().toString()));
            assertThat(second.events().getFirst().event()).isEqualTo(SNAPSHOT);
            assertThat(second.events().getFirst().data())
                    .contains("\"id\":\"" + own.turn().getConversationId() + "\"");
        } finally {
            second.subscription().dispose();
        }
    }

    @Test
    void shouldGiveAStreamOpenedOnAnotherDeviceTheReadMarker() {
        submit(developer, "Read on my phone");
        markRead(MEMBER, "2026-10-12T14:41:00Z");

        Stream other = open();
        try {
            await().atMost(WAIT).until(() -> other.has(SNAPSHOT, "\"lastReadAt\":\"2026-10-12T14:41:00Z\""));
        } finally {
            other.subscription().dispose();
        }
    }

    @Test
    void shouldTellTheDevelopersOtherOpenStreamWhenTheyReadOnOneDeviceAndTellNoColleague() {
        submit(developer, "Read me on two devices");
        User reader = persistUser("testuser");
        ensureWorkspaceMembership(workspace, reader, WorkspaceRole.MEMBER);
        submit(reader, "A colleague's own question");
        Stream laptop = open();
        Stream phone = open();
        Stream colleague = open("mock-jwt-token-for-test-user", null);
        try {
            await().atMost(WAIT)
                    .until(() -> !laptop.events().isEmpty()
                            && !phone.events().isEmpty()
                            && !colleague.events().isEmpty());
            awaitListening();

            markRead(MEMBER, "2026-10-12T14:41:00Z");

            await().atMost(WAIT).until(() -> phone.has(READ, "\"lastReadAt\":\"2026-10-12T14:41:00Z\""));
            await().atMost(WAIT).until(() -> laptop.has(READ, "2026-10-12T14:41:00Z"));
            assertMatchesSchema(
                    phone.events().stream()
                            .filter(event -> READ.equals(event.event()))
                            .findFirst()
                            .orElseThrow()
                            .data(),
                    "MentorRead");

            // An earlier time from another device moves nothing and sends nothing.
            markRead(MEMBER, "2026-10-12T10:00:00Z");
            // A later change of the developer's own ledger proves the streams caught up with the earlier read.
            MentorTurnService.Submission own = submit(developer, "After the read");
            await().atMost(WAIT)
                    .until(() -> phone.has(ENTRY, own.question().getId().toString()));
            assertThat(phone.events().stream().filter(event -> READ.equals(event.event())))
                    .hasSize(1);
            assertThat(colleague.events()).noneMatch(event -> READ.equals(event.event()));
        } finally {
            laptop.subscription().dispose();
            phone.subscription().dispose();
            colleague.subscription().dispose();
        }
    }

    private void markRead(String token, String at) {
        client.put()
                .uri("/workspaces/heph-live/mentor/conversation/read")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("at", at))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAnotherStreamWhenTheDeveloperHoldsTenOpen() {
        List<Stream> streams = new ArrayList<>();
        try {
            for (int opened = 0; opened < MentorLiveHub.MAX_STREAMS_PER_DEVELOPER; opened++) {
                streams.add(open());
            }

            client.get()
                    .uri("/workspaces/heph-live/mentor/events")
                    .headers(headers -> headers.setBearerAuth(MEMBER))
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectBody(Void.class);
        } finally {
            streams.forEach(stream -> stream.subscription().dispose());
        }
    }
}
