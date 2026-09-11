package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real provisioning and persistence, with the installation-status API controlled at its provider boundary. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GitHubInstallationMessageHandlerIntegrationTest extends BaseIntegrationTest {

    private GitHubInstallationMessageHandler handler;

    @Mock
    private GitHubAppTokenService appTokens;

    @Autowired
    private ProvisioningListener provisioningListener;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private NatsMessageDeserializer deserializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        // A local provider boundary keeps the shared Spring context and all transactional
        // provisioning collaborators real, without requiring an App private key or network.
        handler = new GitHubInstallationMessageHandler(
                provisioningListener,
                organizationService,
                appTokens,
                gitProviderRepository,
                deserializer,
                transactionTemplate);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("installation.installation");
    }

    @Test
    void shouldPersistActiveWorkspaceWhenCreated(CapturedOutput output) throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.created");
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(false);

        handler.handleEvent(event);

        Workspace workspace = persistedWorkspace(event);
        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(workspace.getAccountLogin()).isEqualTo("HephaestusTest");
        assertThat(workspace.getRepositorySelection()).isEqualTo(RepositorySelection.ALL);
        assertThat(output)
                .doesNotContain("Could not verify installation status", "GitHub App credentials not configured");
    }

    @Test
    void shouldPurgeExistingWorkspaceWhenDeleted() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.deleted");
        Workspace workspace = createWorkspaceFor(event);

        handler.handleEvent(event);

        assertThat(workspaceRepository.findById(Objects.requireNonNull(workspace.getId())))
                .hasValueSatisfying(
                        persisted -> assertThat(persisted.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED));
    }

    @Test
    void shouldSuspendExistingWorkspaceWhenProviderConfirmsSuspension() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.suspend");
        createWorkspaceFor(event);
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(true);

        handler.handleEvent(event);

        assertThat(persistedWorkspace(event).getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);
    }

    @Test
    void shouldReactivateSuspendedWorkspaceWhenProviderConfirmsActivation() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.unsuspend");
        Workspace workspace = createWorkspaceFor(event);
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        workspaceRepository.saveAndFlush(workspace);
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(false);

        handler.handleEvent(event);

        assertThat(persistedWorkspace(event).getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    void shouldActivateWorkspaceWhenSuspendEventIsStale() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.suspend");
        Workspace workspace = createWorkspaceFor(event);
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        workspaceRepository.saveAndFlush(workspace);
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(false);

        handler.handleEvent(event);

        assertThat(persistedWorkspace(event).getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    void shouldSuspendWorkspaceWhenUnsuspendEventIsStale() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.unsuspend");
        createWorkspaceFor(event);
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(true);

        handler.handleEvent(event);

        assertThat(persistedWorkspace(event).getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);
    }

    @Test
    void shouldPreserveStatusAndExplainWhenProviderVerificationFails(CapturedOutput output) throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.suspend");
        createWorkspaceFor(event);
        when(appTokens.isInstallationSuspended(installationId(event)))
                .thenThrow(new IllegalStateException("fixture provider unavailable"));

        handler.handleEvent(event);

        assertThat(persistedWorkspace(event).getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(output)
                .contains(
                        "Failed to verify installation status via API, skipping status update: installationId=78181208, error=fixture provider unavailable");
    }

    @Test
    void shouldNotProvisionWorkspaceWhenCreatedInstallationNoLongerExists() throws IOException {
        GitHubInstallationEventDTO event = loadPayload("installation.created");
        when(appTokens.isInstallationSuspended(installationId(event)))
                .thenThrow(new InstallationNotFoundException(installationId(event)));

        handler.handleEvent(event);

        assertThat(workspaceRepository.findByInstallationId(installationId(event)))
                .isEmpty();
    }

    @Test
    void shouldExplainMissingInstallationWithoutChangingExistingWorkspace(CapturedOutput output) throws IOException {
        GitHubInstallationEventDTO existing = loadPayload("installation.created");
        Workspace workspace = createWorkspaceFor(existing);

        handler.handleEvent(new GitHubInstallationEventDTO("created", null, null, null));

        assertThat(persistedWorkspace(existing).getId()).isEqualTo(workspace.getId());
        assertThat(persistedWorkspace(existing).getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(output).contains("Received installation event with missing data: action=created");
    }

    private Workspace createWorkspaceFor(GitHubInstallationEventDTO event) {
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(false);
        handler.handleEvent(
                new GitHubInstallationEventDTO("created", event.installation(), event.repositories(), event.sender()));
        return persistedWorkspace(event);
    }

    private Workspace persistedWorkspace(GitHubInstallationEventDTO event) {
        return workspaceRepository.findByInstallationId(installationId(event)).orElseThrow();
    }

    private static long installationId(GitHubInstallationEventDTO event) {
        return Objects.requireNonNull(event.installation()).id();
    }

    private GitHubInstallationEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubInstallationEventDTO.class);
    }
}
