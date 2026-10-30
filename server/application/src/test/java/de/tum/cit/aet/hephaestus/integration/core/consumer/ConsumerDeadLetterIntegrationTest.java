package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.impl.NatsMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ConsumerDeadLetterIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConsumerDeadLetterService service;

    @Autowired
    private WebTestClient client;

    private Workspace workspace;
    private Workspace other;

    @BeforeEach
    void setUp() {
        var owner = persistUser("admin");
        workspace = createWorkspace("deadletters-a", "Events A", "events-a", AccountType.ORG, owner);
        other = createWorkspace("deadletters-b", "Events B", "events-b", AccountType.ORG, owner);
    }

    @Test
    @WithAdminUser
    void shouldDeduplicateCaptureAndKeepWorkspaceRecordsOutOfOtherScopes() {
        Message message = delivery("github.acme.repo.issues", "not-json", "scope-a");
        service.capture(workspace.getId(), message, new PayloadParsingException("private-canary"));
        service.capture(workspace.getId(), message, new PayloadParsingException("private-canary"));
        var rows = service.listWorkspace(workspace.getId(), 0);
        assertThat(rows).hasSize(1);
        assertThat(service.listWorkspace(other.getId(), 0)).isEmpty();
        client.get()
                .uri("/workspaces/deadletters-a/integrations/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(rows.getFirst().id())
                .jsonPath("$[0].payload")
                .doesNotExist()
                .jsonPath("$[0].subject")
                .doesNotExist();
        client.post()
                .uri(
                        "/workspaces/deadletters-b/integrations/dead-letters/{id}/replays",
                        rows.getFirst().id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.post()
                .uri(
                        "/admin/integration/dead-letters/{id}/replays",
                        rows.getFirst().id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldKeepFailedReplayPending() {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "not-json", "scope-b"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldReplayTheStoredEnvelopeOnlyOnceAfterHandlerSucceeds() {
        // An ignored action is a valid event: dispatch succeeds without importing any provider data.
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "scope-c"),
                new PayloadParsingException("older parser"));
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        for (int call = 0; call < 2; call++) {
            client.post()
                    .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.replayedAt")
                    .exists();
        }
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
                .isNotNull();
    }

    @Test
    @WithAdminUser
    void shouldKeepUnassignedInstallationRowsSeparateFromWorkspaceRows() {
        service.capture(
                null,
                delivery("github.installation.123.installation", "not-json", "installation"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listInstallation(0).getFirst();
        assertThat(service.listWorkspace(workspace.getId(), 0)).isEmpty();
        client.get()
                .uri("/admin/integration/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(row.id());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
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
                .uri("/workspaces/deadletters-a/integrations/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private static Message delivery(String subject, String payload, String consumer) {
        var original = new NatsMessage(
                subject,
                "$JS.ACK.github." + consumer + ".1.17.1.1700000000000000000.0",
                new Headers().put("route-canary", "original"),
                payload.getBytes(StandardCharsets.UTF_8));
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn(subject);
        when(message.getReplyTo()).thenReturn(original.getReplyTo());
        when(message.getData()).thenReturn(original.getData());
        when(message.getHeaders()).thenReturn(original.getHeaders());
        when(message.metaData()).thenReturn(new NatsJetStreamMetaData(original));
        return message;
    }
}
