package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubMemberEventDTO;
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
 * Integration tests for GitHubMemberMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 * This handler processes member (collaborator) events for repository access.
 */
class GitHubMemberMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubMemberMessageHandler handler;

    @Autowired
    private RepositoryRepository repositoryRepository;

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
    private Repository testRepository;

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
        Organization org = new Organization();
        org.setNativeId(215361191L);
        org.setProvider(gitProvider);
        org.setLogin("HephaestusTest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        org = organizationRepository.save(org);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(1000663383L);
        testRepository.setProvider(gitProvider);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner("HephaestusTest/TestRepository");
        testRepository.setHtmlUrl("https://github.com/HephaestusTest/TestRepository");
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.member");
    }

    @Test
    void shouldHandleMemberAddedEvent() throws Exception {
        GitHubMemberEventDTO event = loadPayload("member.added");

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("added");
        // User should be created if member contains user info
        if (event.member() != null) {
            assertThat(userRepository.findByNativeIdAndProviderId(
                            required(event.member().id()), gitProviderId()))
                    .isPresent();
        }
    }

    @Test
    void shouldHandleMemberRemovedEvent() throws Exception {
        // Given - first add a member
        GitHubMemberEventDTO addEvent = loadPayload("member.added");
        if (addEvent.member() != null) {
            User member = new User();
            member.setNativeId(required(addEvent.member().id()));
            member.setProvider(gitProvider);
            member.setLogin(addEvent.member().login());
            member.setAvatarUrl(required(addEvent.member().avatarUrl()));
            member.setCreatedAt(Instant.now());
            member.setUpdatedAt(Instant.now());
            userRepository.save(member);
        }

        // Load remove event
        GitHubMemberEventDTO removeEvent = loadPayload("member.removed");

        handler.handleEvent(removeEvent);

        // Then - handler processes without error
        assertThat(removeEvent.action()).isEqualTo("removed");
    }

    private GitHubMemberEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubMemberEventDTO.class);
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
