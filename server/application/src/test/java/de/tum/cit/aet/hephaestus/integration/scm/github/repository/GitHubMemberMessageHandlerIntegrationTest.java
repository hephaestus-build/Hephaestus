package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubAccessReader;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubReaderRemovals;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class GitHubMemberMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // The handler reads GitHub's current collaborator access; a plain mock answers for GitHub.
    private final GitHubAccessReader accessReader = mock(GitHubAccessReader.class);
    private final RepositoryReadersProvider readers = mock(RepositoryReadersProvider.class);

    private GitHubMemberMessageHandler handler;

    @Autowired
    private ProcessingContextFactory contextFactory;

    @Autowired
    private GitHubUserProcessor userProcessor;

    @Autowired
    private RepositoryCollaboratorRepository collaboratorRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private ScopeIdResolver scopeIdResolver;

    @Autowired
    private EventPayloadDeserializer deserializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
        handler = new GitHubMemberMessageHandler(
                contextFactory,
                userProcessor,
                collaboratorRepository,
                scopeIdResolver,
                accessReader,
                new GitHubReaderRemovals(readers, repositoryRepository),
                deserializer,
                transactionTemplate);
        githubReportsCollaborator("write");
    }

    /** GitHub's answer for the fixture member; it also proves the read runs with no transaction open. */
    private void githubReportsCollaborator(@Nullable String role) {
        when(accessReader.isReadable(anyLong())).thenReturn(true);
        when(accessReader.collaboratorRole(anyLong(), eq("HephaestusTest"), eq("TestRepository"), eq(231_938_035L)))
                .thenAnswer(read -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return Optional.ofNullable(role);
                });
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
        testRepository.setNativeId(998279771L);
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
        workspace.resume();
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganizationId(org.getId());
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaceRepository.save(workspace);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNativeId(testRepository.getNativeId());
        monitor.setNameWithOwner(testRepository.getNameWithOwner());
        monitor.setWorkspace(workspace);
        repositoryToMonitorRepository.save(monitor);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.member");
    }

    @Test
    void shouldHandleMemberAddedEvent() throws Exception {
        GitHubMemberEventDTO event = loadPayload("member.added");

        handler.dispatchEvent(event);

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

        // Load remove event; GitHub reports the person without access
        GitHubMemberEventDTO removeEvent = loadPayload("member.removed");
        githubReportsCollaborator(null);

        handler.dispatchEvent(removeEvent);

        // Then - the removal reaches the monitors' readers, though the mirror held no collaborator row for the person
        assertThat(removeEvent.action()).isEqualTo("removed");
        verify(readers)
                .recordReaderRemoved(
                        Objects.requireNonNull(gitProvider.getId()),
                        required(removeEvent.member().getDatabaseId()),
                        List.of(required(removeEvent.repository().id())));
    }

    @Test
    void shouldKeepARemovedCollaboratorRemovedWhenTheirOldAdditionIsDeliveredAgain() throws Exception {
        handler.dispatchEvent(loadPayload("member.added"));
        githubReportsCollaborator(null);
        handler.dispatchEvent(loadPayload("member.removed"));

        handler.dispatchEvent(loadPayload("member.added"));

        assertThat(storedCollaborator()).isEmpty();
    }

    @Test
    void shouldStoreThePermissionGitHubReportsRatherThanTheOneInTheDelivery() throws Exception {
        githubReportsCollaborator("admin");

        handler.dispatchEvent(loadPayload("member.added"));

        assertThat(storedCollaborator())
                .get()
                .extracting(RepositoryCollaborator::getPermission)
                .isEqualTo(RepositoryCollaborator.Permission.ADMIN);
    }

    @Test
    void shouldKeepTheCollaboratorWhenTheirOldRemovalIsDeliveredAgain() throws Exception {
        handler.dispatchEvent(loadPayload("member.added"));

        handler.dispatchEvent(loadPayload("member.removed"));

        assertThat(storedCollaborator()).isPresent();
    }

    @Test
    void shouldStoreAChangedPermissionWhenGitHubReportsTheChange() throws Exception {
        handler.dispatchEvent(loadPayload("member.added"));
        githubReportsCollaborator("maintain");

        handler.dispatchEvent(loadPayload("member.edited"));

        assertThat(storedCollaborator())
                .get()
                .extracting(RepositoryCollaborator::getPermission)
                .isEqualTo(RepositoryCollaborator.Permission.MAINTAIN);
    }

    @Test
    void shouldRemoveTheCollaboratorWhenGitHubCannotBeReadForARemoval() throws Exception {
        handler.dispatchEvent(loadPayload("member.added"));
        when(accessReader.collaboratorRole(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("GitHub unavailable"));

        handler.dispatchEvent(loadPayload("member.removed"));

        assertThat(storedCollaborator()).isEmpty();
    }

    private Optional<RepositoryCollaborator> storedCollaborator() {
        Long userId = userRepository
                .findByNativeIdAndProviderId(231_938_035L, gitProviderId())
                .map(User::getId)
                .orElse(null);
        return userId == null
                ? Optional.empty()
                : collaboratorRepository.findByRepositoryIdAndUserId(testRepository.getId(), userId);
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
