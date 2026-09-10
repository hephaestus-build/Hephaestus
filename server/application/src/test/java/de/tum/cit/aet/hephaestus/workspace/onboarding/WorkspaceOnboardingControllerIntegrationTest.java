package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
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
    private WorkspaceOnboardingSettingsRepository settings;

    @Autowired
    private AccountIdentityQuery identities;

    @Autowired
    private MemberAiPreferences preferences;

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
                .bodyValue(java.util.Map.of("choice", value))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceOnboardingDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    void shouldSaveNoAiOnlyForTheAuthenticatedMemberAndWorkspace() {
        var user = persistUser("testuser");
        var first = workspace("onboarding-one", user);
        var second = workspace("onboarding-two", user);
        var result = choose(first.getWorkspaceSlug(), "NO_AI");
        assertThat(result.aiChoice()).isEqualTo(MemberAiChoice.NO_AI);
        assertThat(result.completed()).isFalse();
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), accountId(user)))
                .get()
                .extracting(WorkspaceMemberOnboarding::getAiChoice)
                .isEqualTo(MemberAiChoice.NO_AI);
        assertThat(members.findByWorkspace_IdAndAccountId(second.getId(), accountId(user)))
                .isEmpty();
        assertThat(preferences.forDeveloper(first.getId(), user.getId()).permitsAi())
                .isFalse();
        assertThat(preferences.forDeveloper(first.getId(), null).permitsAi()).isFalse();
        assertThat(preferences.forDeveloper(second.getId(), null).permitsAi()).isTrue();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(first.getId(), user.getId()))
                .isPresent();
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
                .bodyValue(java.util.Map.of("choice", "NO_AI"))
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
                .bodyValue(new WorkspaceOnboardingSettingsDTO(true, 0, "Welcome", List.of()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(settings.findByWorkspaceId(workspace.getId())).isEmpty();
    }

    @Test
    void shouldEnforceExplicitChoicesAfterWelcomeIsEnabledAndAfterItIsHidden() {
        var user = persistUser("testuser");
        var workspace = workspace("onboarding-policy", user);
        var policy = Objects.requireNonNull(client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(true, 0, "Welcome", List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceOnboardingSettingsDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
        client.get()
                .uri("/workspaces/{slug}/onboarding/me", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.needsWelcome")
                .isEqualTo(true)
                .jsonPath("$.aiChoiceRequired")
                .isEqualTo(true);
        client.put()
                .uri("/workspaces/{slug}/onboarding/settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkspaceOnboardingSettingsDTO(false, policy.revision(), "", List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
    }

    @Test
    void shouldPreserveLegacyAiPermissionsWhenDismissingPreferencesWithoutAnEnabledWelcome() {
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
                .jsonPath("$.completed")
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
                .jsonPath("$.completed")
                .isEqualTo(false);
        var member = members.findByWorkspace_IdAndAccountId(workspace.getId(), accountId(user))
                .orElseThrow();
        assertThat(member.getAiChoice()).isNull();
        assertThat(member.getWelcomedAt()).isNotNull();
        assertThat(member.getCompletedAt()).isNull();
        assertThat(preferences.forDeveloper(workspace.getId(), user.getId()).permitsAi())
                .isFalse();
        client.put()
                .uri("/workspaces/{slug}/onboarding/me/completion", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("revision", 0))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }
}
