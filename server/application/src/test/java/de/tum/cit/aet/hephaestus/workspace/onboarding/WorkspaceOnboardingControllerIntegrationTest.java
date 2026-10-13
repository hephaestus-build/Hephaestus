package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.MentorRefusalReason;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceOnboardingControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String MEMBER = "mock-jwt-token-for-test-user";
    private static final String OWNER = "mock-jwt-token-for-admin-user";

    @Autowired
    private WebTestClient client;

    @Autowired
    private WorkspaceMemberOnboardingRepository members;

    @Autowired
    private AccountAiChoiceRepository choices;

    @Autowired
    private WorkspaceOnboardingSettingsRepository settings;

    @Autowired
    private AccountIdentityQuery identities;

    @Autowired
    private MemberAiPreferences preferences;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    private Workspace workspace(String slug, User member) {
        var workspace = createWorkspace(slug, "Engineering", slug, AccountType.ORG, persistUser("owner-" + slug));
        ensureOwnerMembership(workspace);
        ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
        return workspace;
    }

    private long accountId(User user) {
        return identities
                .resolveActiveAccountId(
                        Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null)
                .orElseThrow();
    }

    private WorkspaceOnboardingDTO choose(String slug, String value) {
        return Objects.requireNonNull(client.put()
                .uri("/workspaces/{slug}/onboarding/me/ai-choice", slug)
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", value))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceOnboardingDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    void shouldSaveTheChoiceOnceForEveryWorkspaceOfTheAuthenticatedMember() {
        var user = persistUser("testuser");
        var first = workspace("onboarding-one", user);
        var second = workspace("onboarding-two", user);
        var result = choose(first.getWorkspaceSlug(), "NO_AI");
        assertThat(result.aiChoice()).isEqualTo(MemberAiChoice.NO_AI);
        assertThat(result.needsSetup()).isFalse();
        assertThat(choices.findById(accountId(user)))
                .get()
                .extracting(AccountAiChoice::getAiChoice)
                .isEqualTo(MemberAiChoice.NO_AI);
        // Answering is not finishing a workspace's setup; neither workspace gets a setup row.
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), accountId(user)))
                .isEmpty();
        assertThat(members.findByWorkspace_IdAndAccountId(second.getId(), accountId(user)))
                .isEmpty();
        assertThat(preferences.forDeveloper(first.getId(), user.getId()).permitsAi())
                .isFalse();
        assertThat(preferences.forDeveloper(second.getId(), user.getId()).permitsAi())
                .isFalse();
        // A developer no account resolves to has not answered; the choice is optional here.
        assertThat(preferences.forDeveloper(first.getId(), null).permitsAi()).isTrue();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(first.getId(), user.getId()))
                .isPresent();

        // The account endpoint reads and writes the same answer without a workspace.
        client.get()
                .uri("/user/ai-choice")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.choice")
                .isEqualTo("NO_AI")
                .jsonPath("$.updatedAt")
                .exists();
        client.put()
                .uri("/user/ai-choice")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "IN_HOUSE_ONLY"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.choice")
                .isEqualTo("IN_HOUSE_ONLY");
        client.get()
                .uri("/workspaces/{slug}/onboarding/me", second.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.aiChoice")
                .isEqualTo("IN_HOUSE_ONLY");
        assertThat(preferences.forDeveloper(second.getId(), user.getId()).permitsAi())
                .isTrue();
    }

    @Test
    void shouldRequireMembershipEvenForAnAuthenticatedReaderOfAPublicWorkspace() {
        persistUser("testuser");
        var workspace = createWorkspace(
                "onboarding-public", "Public", "public", AccountType.ORG, persistUser("unrelated-owner"));
        client.get()
                .uri("/workspaces/{slug}/onboarding/me", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.put()
                .uri("/workspaces/{slug}/onboarding/me/ai-choice", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "NO_AI"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldReservePolicyChangesForWorkspaceOwners() {
        var workspace = workspace("onboarding-owner-only", persistUser("testuser"));
        client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(true, false, 0, List.of()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(settings.findByWorkspaceId(workspace.getId())).isEmpty();
    }

    @Test
    void shouldLetAWorkspaceAdminReadButNotChangeTheSettings() {
        var admin = persistUser("testuser");
        var workspace = createWorkspace(
                "onboarding-admin-reads",
                "Engineering",
                "onboarding-admin-reads",
                AccountType.ORG,
                persistUser("owner-onboarding-admin-reads"));
        ensureOwnerMembership(workspace);
        ensureWorkspaceMembership(workspace, admin, WorkspaceMembership.WorkspaceRole.ADMIN);
        client.get()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);
        client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(true, false, 0, List.of()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldEnforceExplicitChoicesAfterSetupIsEnabledAndAfterItIsHidden() {
        var user = persistUser("testuser");
        var workspace = workspace("onboarding-policy", user);
        var policy = Objects.requireNonNull(client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(true, false, 0, List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceOnboardingSettingsDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(policy.aiChoiceRequired()).isTrue();
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
        client.get()
                .uri("/workspaces/{slug}/onboarding/me", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.needsSetup")
                .isEqualTo(true)
                .jsonPath("$.aiChoiceRequired")
                .isEqualTo(true);
        client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(false, false, policy.revision(), List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
        client.get()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.aiChoiceRequired")
                .isEqualTo(true);
    }

    @Test
    void shouldPreserveLegacyAiPermissionsWhenDismissingWithoutAnEnabledSetupPage() {
        var user = persistUser("testuser");
        var workspace = workspace("onboarding-legacy-dismiss", user);
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isTrue();
        assertThat(preferences.forDeveloper(workspace.getId(), null).permitsAi())
                .isTrue();

        client.put()
                .uri("/workspaces/{slug}/onboarding/me/dismissal", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.aiChoiceRequired")
                .isEqualTo(false)
                .jsonPath("$.needsSetup")
                .isEqualTo(false);

        assertThat(members.findByWorkspace_IdAndAccountId(workspace.getId(), accountId(user)))
                .isEmpty();
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isTrue();
        assertThat(preferences.forDeveloper(workspace.getId(), null).permitsAi())
                .isTrue();
    }

    @Test
    void shouldNotCompleteSetupWithoutSavingAChoice() {
        var user = persistUser("testuser");
        var workspace = workspace("onboarding-dismiss", user);
        var policy = new WorkspaceOnboardingSettings();
        policy.setWorkspace(workspace);
        policy.setEnabled(true);
        policy.setAiChoiceRequired(true);
        settings.saveAndFlush(policy);
        client.put()
                .uri("/workspaces/{slug}/onboarding/me/dismissal", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.needsSetup")
                .isEqualTo(false)
                .jsonPath("$.aiChoice")
                .doesNotExist();
        var member = members.findByWorkspace_IdAndAccountId(workspace.getId(), accountId(user))
                .orElseThrow();
        assertThat(member.getSeenRevision()).isEqualTo(policy.getRevision());
        assertThat(choices.findById(accountId(user))).isEmpty();
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
    }

    private WorkspaceOnboardingDTO state(String slug) {
        return Objects.requireNonNull(client.get()
                .uri("/workspaces/{slug}/onboarding/me", slug)
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceOnboardingDTO.class)
                .returnResult()
                .getResponseBody());
    }

    /** An enabled Heph binding of a fresh model in {@code tier}; an in-house model declares its own operator. */
    private void bindHeph(Workspace workspace, DataHandlingTier tier) {
        String slug = workspace.getWorkspaceSlug() + "-" + tier.name().toLowerCase(Locale.ROOT);
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        LlmModel model = LlmCatalogTestFixtures.model(connection, slug, "heph-model");
        if (tier == DataHandlingTier.IN_HOUSE) {
            model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        }
        WorkspaceAgentBinding binding = LlmCatalogTestFixtures.mentorBinding(workspace, models.save(model));
        binding.setDataHandlingTier(tier);
        bindings.save(binding);
    }

    @Test
    void shouldReportThatHephAnswersWhenAModelFitsTheMembersAiChoice() {
        var workspace = workspace("onboarding-heph-ready", persistUser("testuser"));
        bindHeph(workspace, DataHandlingTier.IN_HOUSE);

        assertThat(choose(workspace.getWorkspaceSlug(), "IN_HOUSE_ONLY").mentorRefusal())
                .isNull();
    }

    @Test
    void shouldReportThatHephIsUnavailableWhenTheWorkspaceHasNoHephModel() {
        var workspace = workspace("onboarding-heph-none", persistUser("testuser"));

        assertThat(state(workspace.getWorkspaceSlug()).mentorRefusal()).isEqualTo(MentorRefusalReason.UNAVAILABLE);
        assertThat(choose(workspace.getWorkspaceSlug(), "NO_AI").mentorRefusal())
                .isEqualTo(MentorRefusalReason.NO_AI);
    }

    @Test
    void shouldReportThatHephIsUnavailableWhenNoModelFitsTheMembersAiChoice() {
        var workspace = workspace("onboarding-heph-mismatch", persistUser("testuser"));
        bindHeph(workspace, DataHandlingTier.UNDECLARED);

        assertThat(choose(workspace.getWorkspaceSlug(), "IN_HOUSE_ONLY").mentorRefusal())
                .isEqualTo(MentorRefusalReason.UNAVAILABLE);
    }

    @Test
    void shouldReportThatHephAnswersAMemberWithoutAChoiceFromTheUndeclaredModel() {
        var workspace = workspace("onboarding-heph-undeclared", persistUser("testuser"));
        bindHeph(workspace, DataHandlingTier.UNDECLARED);

        WorkspaceOnboardingDTO onboarding = state(workspace.getWorkspaceSlug());

        assertThat(onboarding.aiChoice()).isNull();
        assertThat(onboarding.aiOptions()).noneMatch(WorkspaceOnboardingDTO.WorkspaceAiOptionDTO::mentorReady);
        assertThat(onboarding.mentorRefusal()).isNull();
    }
}
