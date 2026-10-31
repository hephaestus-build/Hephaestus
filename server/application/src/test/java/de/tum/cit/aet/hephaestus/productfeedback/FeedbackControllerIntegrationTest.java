package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class FeedbackControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @BeforeEach
    void setUpAdminIdentity() {
        persistUser("admin");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ProductFeedbackRepository feedback;

    @Autowired
    private AccountRepository accounts;

    /** A member account signed in the way production does: a numeric JWT subject wired to a workspace member. */
    private record Member(long accountId, Account.AppRole role, Workspace workspace) {
        Consumer<HttpHeaders> headers() {
            String prefix = role == Account.AppRole.APP_ADMIN ? "mock-jwt-sub-" : "mock-jwt-member-";
            return headers -> headers.setBearerAuth(prefix + accountId);
        }

        String path(String suffix) {
            return "/workspaces/" + workspace.getWorkspaceSlug() + "/product-feedback" + suffix;
        }
    }

    @Test
    void shouldKeepFeedbackContextOptionalAndLetAdministratorsTriageIt() {
        Member member = member("feedback-triage");
        webTestClient
                .post()
                .uri(member.path(""))
                .headers(member.headers())
                .bodyValue(Map.of(
                        "kind", "IDEA",
                        "message", "Let me pin a practice",
                        "pagePath", "/w/feedback-triage/practices",
                        "userAgent", "Mozilla/5.0 (X11; Linux x86_64)"))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(Void.class);
        ProductFeedback stored = feedback.findAll().stream()
                .filter(item -> item.getAccountId().equals(member.accountId()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.getKind()).isEqualTo(ProductFeedback.Kind.IDEA);
        assertThat(stored.getUserAgent()).isEqualTo("Mozilla/5.0 (X11; Linux x86_64)");
        assertThat(stored.getAppVersion()).isNotBlank();
        assertThat(stored.isResolved()).isFalse();

        webTestClient
                .get()
                .uri("/admin/product-feedback?status=OPEN")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[?(@.id == '" + stored.getId() + "')].account.displayName")
                .isEqualTo("Member feedback-triage")
                .jsonPath("$.content[?(@.id == '" + stored.getId() + "')].workspace.slug")
                .isEqualTo("feedback-triage");
        webTestClient
                .patch()
                .uri("/admin/product-feedback/" + stored.getId())
                .headers(member.headers())
                .bodyValue(Map.of("resolved", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.resolvedBy.id")
                .isEqualTo(member.accountId());
        webTestClient
                .get()
                .uri("/admin/product-feedback?status=OPEN")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[?(@.id == '" + stored.getId() + "')]")
                .isEmpty();
        assertThat(feedback.findById(stored.getId()).orElseThrow().getResolvedByAccountId())
                .isEqualTo(member.accountId());
    }

    @Test
    @WithUser
    void shouldDenyAdministrationToRegularUsers() {
        webTestClient
                .get()
                .uri("/admin/product-feedback")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldAllowInboxReadWhenInstanceAdmin() {
        webTestClient
                .get()
                .uri("/admin/product-feedback")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldRequireAuthenticationForInstanceEndpoints() {
        webTestClient
                .get()
                .uri("/admin/product-feedback")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        webTestClient
                .post()
                .uri("/product-feedback")
                .headers(TestAuthUtils.withCsrf(csrf))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    /** One workspace whose admin member is a real account, so the same token can author and answer. */
    private Member member(String slug) {
        User actor = persistUser(slug + "-member");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug, AccountType.ORG, actor);
        ensureMembership(workspace, actor, Membership.WorkspaceRole.ADMIN);
        return member(slug, Account.AppRole.APP_ADMIN, workspace, actor);
    }

    private Member member(String slug, Account.AppRole role, Workspace workspace, User actor) {
        var account = accounts.findById(accountId(actor)).orElseThrow();
        account.setDisplayName("Member " + slug);
        account.setAppRole(role);
        account.setStatus(Account.Status.ACTIVE);
        account = accounts.save(account);
        return new Member(Objects.requireNonNull(account.getId()), role, workspace);
    }
}
