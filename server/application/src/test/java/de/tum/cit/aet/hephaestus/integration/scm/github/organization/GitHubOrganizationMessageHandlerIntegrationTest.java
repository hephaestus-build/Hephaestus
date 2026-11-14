package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubAccessReader;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class GitHubOrganizationMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // The handler reads GitHub's current membership; a plain mock answers for GitHub.
    private final GitHubAccessReader accessReader = mock(GitHubAccessReader.class);

    private GitHubOrganizationMessageHandler handler;

    @Autowired
    private GitHubOrganizationProcessor organizationProcessor;

    @Autowired
    private GitHubUserProcessor userProcessor;

    @Autowired
    private IdentityProviderLookup identityProviders;

    @Autowired
    private OrganizationMembershipListener membershipListener;

    @Autowired
    private ScopeIdResolver scopeIdResolver;

    @Autowired
    private EventPayloadDeserializer deserializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final long MEMBER_ID = 231_938_035L;

    private Organization testOrganization;
    private IdentityProvider githubProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
        handler = new GitHubOrganizationMessageHandler(
                organizationProcessor,
                userProcessor,
                identityProviders,
                membershipRepository,
                organizationRepository,
                userRepository,
                membershipListener,
                scopeIdResolver,
                accessReader,
                deserializer,
                transactionTemplate);
        githubReportsMembership("member");
    }

    /** GitHub's answer for the fixture member; it also proves the read runs with no transaction open. */
    private void githubReportsMembership(@Nullable String role) {
        when(accessReader.isReadable(anyLong())).thenReturn(true);
        when(accessReader.organizationRole(anyLong(), eq("HephaestusTest"), eq(MEMBER_ID)))
                .thenAnswer(read -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return Optional.ofNullable(role);
                });
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
        workspace.resume();
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganizationId(testOrganization.getId());
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

        handler.dispatchEvent(event);

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

        handler.dispatchEvent(removeEvent);

        // Then - handler processes without error
        assertThat(organizationRepository.findById(testOrganization.getId())).isPresent();
    }

    @Test
    void shouldStoreAnAddedMemberUnderTheStoredOrganizationAndPerson() throws Exception {
        GitHubOrganizationEventDTO event = loadPayload("organization.member_added");
        long nativeOrganizationId = required(event.organization().id());
        long organizationId = required(testOrganization.getId());
        assertThat(organizationId).as("stored and GitHub ids must differ here").isNotEqualTo(nativeOrganizationId);

        handler.dispatchEvent(event);

        long nativeUserId =
                required(required(required(event.membership()).user()).id());
        User member = userRepository
                .findByNativeIdAndProviderId(nativeUserId, githubProviderId())
                .orElseThrow();
        assertThat(member.getId()).as("stored and GitHub ids must differ here").isNotEqualTo(nativeUserId);
        assertThat(membershipRepository.findUserIdsByOrganizationId(organizationId))
                .containsExactly(member.getId());
        assertThat(membershipRepository.findUserIdsByOrganizationId(nativeOrganizationId))
                .isEmpty();
    }

    @Test
    void shouldRemoveAMemberStoredUnderTheStoredOrganizationAndPerson() throws Exception {
        GitHubOrganizationEventDTO addEvent = loadPayload("organization.member_added");
        handler.dispatchEvent(addEvent);
        long organizationId = required(testOrganization.getId());
        assertThat(membershipRepository.findUserIdsByOrganizationId(organizationId))
                .hasSize(1);
        githubReportsMembership(null);

        handler.dispatchEvent(loadPayload("organization.member_removed"));

        assertThat(membershipRepository.findUserIdsByOrganizationId(organizationId))
                .isEmpty();
    }

    @Test
    void shouldKeepARemovedMemberRemovedWhenTheirOldAdditionIsDeliveredAgain() throws Exception {
        handler.dispatchEvent(loadPayload("organization.member_added"));
        githubReportsMembership(null);
        handler.dispatchEvent(loadPayload("organization.member_removed"));

        handler.dispatchEvent(loadPayload("organization.member_added"));

        assertThat(membershipRepository.findUserIdsByOrganizationId(required(testOrganization.getId())))
                .isEmpty();
    }

    @Test
    void shouldStoreTheRoleGitHubReportsRatherThanTheRoleInTheDelivery() throws Exception {
        githubReportsMembership("admin");

        handler.dispatchEvent(loadPayload("organization.member_added"));

        long organizationId = required(testOrganization.getId());
        long userId = required(
                membershipRepository.findUserIdsByOrganizationId(organizationId).getFirst());
        assertThat(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .get()
                .extracting(membership -> membership.getRole())
                .isEqualTo(OrganizationMemberRole.ADMIN);
    }

    @Test
    void shouldKeepTheMemberWhenTheirOldRemovalIsDeliveredAgain() throws Exception {
        handler.dispatchEvent(loadPayload("organization.member_added"));

        handler.dispatchEvent(loadPayload("organization.member_removed"));

        assertThat(membershipRepository.findUserIdsByOrganizationId(required(testOrganization.getId())))
                .hasSize(1);
    }

    @Test
    void shouldRemoveTheMemberWhenGitHubCannotBeReadForARemoval() throws Exception {
        handler.dispatchEvent(loadPayload("organization.member_added"));
        when(accessReader.organizationRole(anyLong(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("GitHub unavailable"));

        handler.dispatchEvent(loadPayload("organization.member_removed"));

        assertThat(membershipRepository.findUserIdsByOrganizationId(required(testOrganization.getId())))
                .isEmpty();
    }

    @Test
    void shouldStoreNothingAndFailForARetryWhenGitHubCannotBeReadForAnAddition() throws Exception {
        when(accessReader.isReadable(anyLong())).thenReturn(true);
        when(accessReader.organizationRole(anyLong(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("GitHub unavailable"));
        GitHubOrganizationEventDTO added = loadPayload("organization.member_added");

        assertThatThrownBy(() -> handler.dispatchEvent(added)).isInstanceOf(IllegalStateException.class);

        assertThat(membershipRepository.findUserIdsByOrganizationId(required(testOrganization.getId())))
                .isEmpty();
    }

    @Test
    void shouldApplyARemovalButNoAdditionWhenTheWorkspaceCannotReadGitHub() throws Exception {
        handler.dispatchEvent(loadPayload("organization.member_added"));
        when(accessReader.isReadable(anyLong())).thenReturn(false);
        long organizationId = required(testOrganization.getId());

        handler.dispatchEvent(loadPayload("organization.member_removed"));
        handler.dispatchEvent(loadPayload("organization.member_added"));

        assertThat(membershipRepository.findUserIdsByOrganizationId(organizationId))
                .isEmpty();
    }

    @Test
    void shouldHandleRenamedEvent() throws Exception {
        GitHubOrganizationEventDTO event = loadPayload("organization.renamed");

        handler.dispatchEvent(event);

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
