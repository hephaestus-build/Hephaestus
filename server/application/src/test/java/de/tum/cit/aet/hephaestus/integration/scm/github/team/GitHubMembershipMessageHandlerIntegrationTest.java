package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubMembershipEventDTO;
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
 * Integration tests for GitHubMembershipMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubMembershipMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubMembershipMessageHandler handler;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private IdentityProvider gitProvider;
    private Organization testOrganization;
    private Team testTeam = new Team();

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create git provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setProvider(gitProvider);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
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

    private void createTestTeam(Long teamId, String name) {
        testTeam = new Team();
        testTeam.setNativeId(teamId);
        testTeam.setProvider(gitProvider);
        testTeam.setName(name);
        testTeam.setSlug(name);
        testTeam.setHtmlUrl("https://github.com/orgs/HephaestusTest/teams/" + name);
        testTeam.setOrganization(testOrganization.getLogin());
        testTeam = teamRepository.save(testTeam);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("organization.membership");
    }

    @Test
    void shouldHandleMemberAddedEvent() throws Exception {
        GitHubMembershipEventDTO event = loadPayload("membership.added");
        createTestTeam(required(event.team().id()), event.team().name());

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("added");
    }

    @Test
    void shouldHandleMemberRemovedEvent() throws Exception {
        GitHubMembershipEventDTO event = loadPayload("membership.removed");
        createTestTeam(required(event.team().id()), event.team().name());

        // Add a user to remove
        if (event.member() != null) {
            User member = new User();
            member.setNativeId(required(event.member().id()));
            member.setProvider(gitProvider);
            member.setLogin(event.member().login());
            member.setAvatarUrl(required(event.member().avatarUrl()));
            member.setCreatedAt(Instant.now());
            member.setUpdatedAt(Instant.now());
            userRepository.save(member);
        }

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("removed");
    }

    private GitHubMembershipEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubMembershipEventDTO.class);
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
