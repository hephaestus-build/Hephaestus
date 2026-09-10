package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubInstallationMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubInstallationMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubInstallationMessageHandler handler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository workspaces;

    @Autowired
    private de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository accountMemberships;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository users;

    @Autowired
    private de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository accounts;

    @Autowired
    private de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository identities;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // Ensure GitHub IdentityProvider exists - required by GithubLifecycleListener
        gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("installation.installation");
    }

    @Test
    void shouldHandleCreatedEvent() throws Exception {
        GitHubInstallationEventDTO event = loadPayload("installation.created");

        handler.handleEvent(event);

        var installation = java.util.Objects.requireNonNull(event.installation());
        var workspace = workspaces.findByInstallationId(installation.id()).orElseThrow();
        assertThat(workspace.getAccountLogin()).isEqualTo("HephaestusTest");
        assertThat(accountMemberships.findByWorkspace_Id(workspace.getId()))
                .as("a GitHub organization must not be invented as a human owner")
                .isEmpty();
        handler.handleEvent(event);
        assertThat(workspaces
                        .findByInstallationId(installation.id())
                        .orElseThrow()
                        .getId())
                .isEqualTo(workspace.getId());
    }

    @Test
    void shouldNotGrantOwnershipToMatchingLoginFromAnotherProvider() throws Exception {
        var gitlab = gitProviderRepository.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com"));
        var unrelated = de.tum.cit.aet.hephaestus.testconfig.TestUserFactory.ensureUser(
                users, "HephaestusTest", 215361191L, gitlab);
        de.tum.cit.aet.hephaestus.testconfig.TestUserFactory.ensureAccountForUser(accounts, identities, unrelated);
        var event = loadPayload("installation.created");
        handler.handleEvent(event);
        var workspace = workspaces
                .findByInstallationId(
                        java.util.Objects.requireNonNull(event.installation()).id())
                .orElseThrow();
        assertThat(accountMemberships.findByWorkspace_Id(workspace.getId())).isEmpty();
        assertThat(users.findById(unrelated.getId()))
                .get()
                .extracting(user -> user.getProvider().getId())
                .isEqualTo(gitlab.getId());
    }

    @Test
    void shouldHandleDeletedEvent() throws Exception {
        GitHubInstallationEventDTO event = loadPayload("installation.deleted");

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("deleted");
    }

    @Test
    void shouldHandleSuspendedEvent() throws Exception {
        GitHubInstallationEventDTO event = loadPayload("installation.suspend");

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("suspend");
    }

    @Test
    void shouldHandleUnsuspendedEvent() throws Exception {
        GitHubInstallationEventDTO event = loadPayload("installation.unsuspend");

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("unsuspend");
    }

    @Test
    void shouldHandleNullInstallationGracefully() {
        // Given - event with null installation
        GitHubInstallationEventDTO event = new GitHubInstallationEventDTO("created", null, null, null);

        // When - should not throw
        handler.handleEvent(event);
        // Then - handler logs warning but doesn't crash
    }

    @Test
    void shouldHandleUnknownActionGracefully() throws Exception {
        // Given - load a valid event and parse to get structure, then create with unknown action
        GitHubInstallationEventDTO baseEvent = loadPayload("installation.created");
        GitHubInstallationEventDTO event = new GitHubInstallationEventDTO(
                "unknown_action", baseEvent.installation(), baseEvent.repositories(), baseEvent.sender());

        // When - should not throw
        handler.handleEvent(event);
        // Then - handler logs debug message for unhandled action
    }

    private GitHubInstallationEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubInstallationEventDTO.class);
    }
}
