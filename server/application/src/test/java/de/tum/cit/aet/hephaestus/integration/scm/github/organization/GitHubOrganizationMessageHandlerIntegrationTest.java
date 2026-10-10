package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubOrganizationMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubOrganizationMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubOrganizationMessageHandler handler;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization testOrganization;
    private IdentityProvider githubProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider (required by the handler)
        githubProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        testOrganization.setHtmlUrl("https://github.com/HephaestusTest");
        testOrganization.setProvider(githubProvider);
        testOrganization = organizationRepository.save(testOrganization);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(testOrganization);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("organization.organization");
    }

    @Test
    void shouldHandleMemberAddedEvent() throws Exception {
        GitHubOrganizationEventDTO event = loadPayload("organization.member_added");

        handler.handleEvent(event);

        // Then - organization should still exist
        assertThat(organizationRepository.findById(testOrganization.getId())).isPresent();
        // User should be created if membership contains user info
        if (event.membership() != null && event.membership().user() != null) {
            assertThat(userRepository.findByNativeIdAndProviderId(
                            required(event.membership().user().id()), githubProviderId()))
                    .isPresent();
        }
    }

    @Test
    void shouldHandleMemberRemovedEvent() throws Exception {
        // Given - first add a member
        GitHubOrganizationEventDTO addEvent = loadPayload("organization.member_added");
        if (addEvent.membership() != null && addEvent.membership().user() != null) {
            User member = new User();
            member.setNativeId(required(addEvent.membership().user().id()));
            member.setProvider(githubProvider);
            member.setLogin(addEvent.membership().user().login());
            member.setAvatarUrl(required(addEvent.membership().user().avatarUrl()));
            member.setCreatedAt(Instant.now());
            member.setUpdatedAt(Instant.now());
            userRepository.save(member);
        }

        // Load remove event
        GitHubOrganizationEventDTO removeEvent = loadPayload("organization.member_removed");

        handler.handleEvent(removeEvent);

        // Then - handler processes without error
        assertThat(organizationRepository.findById(testOrganization.getId())).isPresent();
    }

    @Test
    void shouldHandleRenamedEvent() throws Exception {
        GitHubOrganizationEventDTO event = loadPayload("organization.renamed");

        handler.handleEvent(event);

        // Then - organization should reflect new name
        assertThat(organizationRepository.findById(testOrganization.getId()))
                .isPresent()
                .get()
                .satisfies(org -> {
                    // The handler should update the organization login
                    assertThat(org.getLogin()).isEqualTo(event.organization().login());
                });
    }

    private GitHubOrganizationEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubOrganizationEventDTO.class);
    }

    private Long githubProviderId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
