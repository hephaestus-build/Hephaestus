package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceOnboardingControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String MEMBER = "mock-jwt-token-for-test-user";
    private static final String OWNER = "mock-jwt-token-for-admin-user";

    @Autowired
    private WebTestClient client;

    @Autowired
    private MembershipRepository members;

    @Autowired
    private AccountRepository choices;

    @Autowired
    private WorkspaceOnboardingSettingsRepository settings;

    @Autowired
    private MemberAiPreferences preferences;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceModelAssignmentRepository bindings;

    private Workspace workspace(String slug, User member) {
        var workspace = createWorkspace(slug, "Engineering", slug, AccountType.ORG, persistUser("owner-" + slug));
        ensureOwnerMembership(workspace);
        ensureMembership(workspace, member, Membership.WorkspaceRole.MEMBER);
        return workspace;
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
        assertThat(result.aiChoice()).isEqualTo(AiChoice.NO_AI);
        assertThat(result.needsSetup()).isFalse();
        assertThat(choices.findById(accountId(user)))
                .get()
                .extracting(Account::getAiChoice)
                .isEqualTo(AiChoice.NO_AI);
        // Answering is not finishing a workspace's setup; neither workspace gets a setup row.
        assertThat(members.findByWorkspace_IdAndId_AccountId(first.getId(), accountId(user)))
                .get()
                .extracting(Membership::getOnboardingSeenRevision)
                .isNull();
        assertThat(members.findByWorkspace_IdAndId_AccountId(second.getId(), accountId(user)))
                .get()
                .extracting(Membership::getOnboardingSeenRevision)
                .isNull();
        assertThat(preferences.forDeveloper(first.getId(), accountId(user)).permitsAi())
                .isFalse();
        assertThat(preferences.forDeveloper(second.getId(), accountId(user)).permitsAi())
                .isFalse();
        // A developer no account resolves to has not answered; the choice is optional here.
        assertThat(preferences.forDeveloper(first.getId(), null).permitsAi()).isTrue();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(first.getId(), accountId(user)))
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
        assertThat(preferences.forDeveloper(second.getId(), accountId(user)).permitsAi())
                .isTrue();
    }

    @Test
    void shouldAnswerNotFoundToAnAuthenticatedNonMemberReaderOfAPublicWorkspace() {
        persistUser("testuser");
        var workspace = createWorkspace(
                "onboarding-public", "Public", "public", AccountType.ORG, persistUser("unrelated-owner"));
        client.get()
                .uri("/workspaces/{slug}/onboarding/me", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.put()
                .uri("/workspaces/{slug}/onboarding/me/ai-choice", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "NO_AI"))
                .exchange()
                .expectStatus()
                .isNotFound()
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
        ensureMembership(workspace, admin, Membership.WorkspaceRole.ADMIN);
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
        assertThat(preferences.forDeveloper(workspace.getId(), accountId(user)).permitsAi())
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
        assertThat(preferences.forDeveloper(workspace.getId(), accountId(user)).permitsAi())
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
        assertThat(preferences.forDeveloper(workspace.getId(), accountId(user)).permitsAi())
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

        assertThat(members.findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(user)))
                .isPresent();
        assertThat(preferences.forDeveloper(workspace.getId(), accountId(user)).permitsAi())
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
        var member = members.findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(user))
                .orElseThrow();
        assertThat(member.getOnboardingSeenRevision()).isEqualTo(policy.getRevision());
        assertThat(choices.findById(accountId(user)))
                .get()
                .extracting(Account::getAiChoice)
                .isNull();
        assertThat(preferences.forDeveloper(workspace.getId(), accountId(user)).permitsAi())
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

    /** An enabled Hephaestus binding of a fresh model in {@code tier}; an in-house model declares its own operator. */
    private WorkspaceModelAssignment bindConversation(Workspace workspace, DataHandlingTier tier) {
        String slug = workspace.getWorkspaceSlug() + "-" + tier.name().toLowerCase(Locale.ROOT);
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        LlmModel model = LlmCatalogTestFixtures.model(connection, slug, "ask-model");
        if (tier == DataHandlingTier.IN_HOUSE) {
            model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        } else if (tier == DataHandlingTier.CLOUD) {
            model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        }
        WorkspaceModelAssignment binding = LlmCatalogTestFixtures.conversationBinding(workspace, models.save(model));
        binding.setDataHandlingTier(tier);
        return bindings.saveAndFlush(binding);
    }

    @ParameterizedTest
    @EnumSource(DataHandlingTier.class)
    void shouldFollowBindingAndModelAvailabilityInEverySupportedTier(DataHandlingTier tier) {
        var workspace = workspace(
                "onboarding-ask-states-" + tier.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                persistUser("testuser"));
        var binding = bindConversation(workspace, tier);
        var initial = switch (tier) {
            case IN_HOUSE -> choose(workspace.getWorkspaceSlug(), "IN_HOUSE_ONLY");
            case CLOUD -> choose(workspace.getWorkspaceSlug(), "CLOUD");
            case UNDECLARED -> state(workspace.getWorkspaceSlug());
        };
        assertThat(initial.conversationRefusal()).isNull();
        if (tier == DataHandlingTier.UNDECLARED) {
            assertThat(initial.aiChoice()).isNull();
            assertThat(initial.aiOptions()).noneMatch(WorkspaceOnboardingDTO.WorkspaceAiOptionDTO::conversationReady);
        }

        binding.setEnabled(false);
        bindings.saveAndFlush(binding);
        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isEqualTo(AiRefusalReason.UNAVAILABLE);

        binding.setEnabled(true);
        bindings.saveAndFlush(binding);
        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isNull();

        var model = Objects.requireNonNull(binding.getInstanceModel());
        model.setEnabled(false);
        models.saveAndFlush(model);
        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isEqualTo(AiRefusalReason.UNAVAILABLE);

        model.setEnabled(true);
        models.saveAndFlush(model);
        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isNull();

        bindings.delete(binding);
        bindings.flush();
        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isEqualTo(AiRefusalReason.UNAVAILABLE);
    }

    @Test
    void shouldKeepRequiredChoiceAndNoAiRefusalsWithAnAvailableUndeclaredBinding() {
        var workspace = workspace("onboarding-ask-required", persistUser("testuser"));
        bindConversation(workspace, DataHandlingTier.UNDECLARED);
        var policy = new WorkspaceOnboardingSettings();
        policy.setWorkspace(workspace);
        policy.setAiChoiceRequired(true);
        settings.saveAndFlush(policy);

        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal())
                .isEqualTo(AiRefusalReason.CHOICE_REQUIRED);
        assertThat(choose(workspace.getWorkspaceSlug(), "NO_AI").conversationRefusal())
                .isEqualTo(AiRefusalReason.NO_AI);
        assertThat(choose(workspace.getWorkspaceSlug(), "CLOUD").conversationRefusal())
                .isEqualTo(AiRefusalReason.UNAVAILABLE);
    }

    @Test
    void shouldReportThatHephaestusAnswersWhenAModelFitsTheMembersAiChoice() {
        var workspace = workspace("onboarding-ask-ready", persistUser("testuser"));
        bindConversation(workspace, DataHandlingTier.IN_HOUSE);

        assertThat(choose(workspace.getWorkspaceSlug(), "IN_HOUSE_ONLY").conversationRefusal())
                .isNull();
    }

    @Test
    void shouldReportThatHephaestusIsUnavailableWhenTheWorkspaceHasNoHephaestusModel() {
        var workspace = workspace("onboarding-ask-none", persistUser("testuser"));

        assertThat(state(workspace.getWorkspaceSlug()).conversationRefusal()).isEqualTo(AiRefusalReason.UNAVAILABLE);
        assertThat(choose(workspace.getWorkspaceSlug(), "NO_AI").conversationRefusal())
                .isEqualTo(AiRefusalReason.NO_AI);
    }

    @Test
    void shouldReportThatHephaestusIsUnavailableWhenNoModelFitsTheMembersAiChoice() {
        var workspace = workspace("onboarding-ask-mismatch", persistUser("testuser"));
        bindConversation(workspace, DataHandlingTier.UNDECLARED);

        assertThat(choose(workspace.getWorkspaceSlug(), "IN_HOUSE_ONLY").conversationRefusal())
                .isEqualTo(AiRefusalReason.UNAVAILABLE);
    }

    @Test
    void shouldReportThatHephaestusAnswersAMemberWithoutAChoiceFromTheUndeclaredModel() {
        var workspace = workspace("onboarding-ask-undeclared", persistUser("testuser"));
        bindConversation(workspace, DataHandlingTier.UNDECLARED);

        WorkspaceOnboardingDTO onboarding = state(workspace.getWorkspaceSlug());

        assertThat(onboarding.aiChoice()).isNull();
        assertThat(onboarding.aiOptions()).noneMatch(WorkspaceOnboardingDTO.WorkspaceAiOptionDTO::conversationReady);
        assertThat(onboarding.conversationRefusal()).isNull();
    }
}
