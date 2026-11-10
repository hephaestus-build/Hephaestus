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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
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

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private JdbcTemplate jdbc;

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
                .isOk()
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

    @Test
    void shouldStoreAnInstanceReportWithoutAWorkspaceWhenItIsSentOutsideOne() {
        Member member = member("feedback-instance");
        webTestClient
                .post()
                .uri("/product-feedback")
                .headers(member.headers())
                .bodyValue(Map.of("kind", "IDEA", "message", "An idea from the account menu"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        ProductFeedback stored = feedback.findAllByAccountId(member.accountId()).getFirst();
        assertThat(stored.getWorkspaceId()).isNull();
    }

    @Test
    void shouldRefuseASecondReportWhenTheAccountSentOneThisMinute() throws InterruptedException {
        Member member = member("feedback-rate");
        // The limit counts calendar minutes, so both sends must fall into the same one.
        while (LocalTime.now(ZoneOffset.UTC).getSecond() >= 58) Thread.sleep(250);
        send(member, "First").expectStatus().isOk().expectBody(Void.class);
        send(member, "Second")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("You can send feedback only once per minute.");
        assertThat(feedback.findAllByAccountId(member.accountId()))
                .extracting(ProductFeedback::getMessage)
                .containsExactly("First");
    }

    @Test
    void shouldAnswerNotFoundWhenTheSenderOfAWorkspaceReportIsNotAMember() {
        Member owner = member("feedback-owner");
        Member outsider = user("feedback-outsider");
        webTestClient
                .post()
                .uri(owner.path(""))
                .headers(outsider.headers())
                .bodyValue(Map.of("kind", "BUG", "message", "Sent into a foreign workspace"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(feedback.findAllByAccountId(outsider.accountId())).isEmpty();
    }

    @Test
    void shouldHideAReportWhenAnotherAccountAsksForIt() {
        Member author = member("feedback-author");
        Member other = user("feedback-other");
        send(author, "Only mine").expectStatus().isOk().expectBody(Void.class);
        UUID id = feedback.findAllByAccountId(author.accountId()).getFirst().getId();
        webTestClient
                .post()
                .uri("/product-feedback/" + id + "/keep-private")
                .headers(other.headers())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        webTestClient
                .get()
                .uri("/product-feedback/mine")
                .headers(other.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '" + id + "')]")
                .doesNotExist();
        webTestClient
                .get()
                .uri("/product-feedback/mine")
                .headers(author.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '" + id + "')]")
                .exists();
    }

    @Test
    void shouldRefuseTriageWhenTheReportGoesToGitHub() {
        Member member = member("feedback-on-github");
        send(member, "Filed on GitHub").expectStatus().isOk().expectBody(Void.class);
        ProductFeedback report = feedback.findAllByAccountId(member.accountId()).getFirst();
        ProductFeedbackFiling filing = filings.findById(report.getId()).orElseThrow();
        ReflectionTestUtils.setField(filing, "state", FilingState.FILED);
        filings.save(filing);

        webTestClient
                .patch()
                .uri("/admin/product-feedback/" + report.getId())
                .headers(member.headers())
                .bodyValue(Map.of("resolved", true))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);
        assertThat(feedback.findById(report.getId()).orElseThrow().isResolved()).isFalse();
    }

    @Test
    void shouldLimitOrdinaryAccountsAcrossInstanceAndWorkspaceSubmissions() {
        Member member = user("feedback-daily-limit");
        seedDailyReports(member);
        send(member, "A sixth report")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("You can send up to 5 reports per day. Try again tomorrow (UTC).");
        webTestClient
                .post()
                .uri("/product-feedback")
                .headers(member.headers())
                .bodyValue(Map.of("kind", "BUG", "message", "Try without a workspace"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody(Void.class);
        assertThat(feedback.findAllByAccountId(member.accountId())).hasSize(5);
    }

    @Test
    void shouldAllowInstanceAdminsBeyondTheDailyMemberLimit() {
        Member member = member("feedback-daily-admin");
        seedDailyReports(member);
        send(member, "An admin report").expectStatus().isOk().expectBody(Void.class);
        assertThat(feedback.findAllByAccountId(member.accountId())).hasSize(6);
    }

    private void seedDailyReports(Member member) {
        // Distinct calendar minutes today, excluding the current one even across midnight.
        for (int minute = 1; minute <= FeedbackService.ACCOUNT_DAILY_REPORTS; minute++) {
            jdbc.update(
                    """
                    INSERT INTO product_feedback
                        (id, account_id, workspace_id, kind, message, app_version, created_at, submission_minute)
                    VALUES (?, ?, ?, 'BUG', 'An earlier report', 'test', now(),
                        date_trunc('day', now()) + (mod(extract(hour FROM now())::int * 60 +
                            extract(minute FROM now())::int + ?, 1440)) * interval '1 minute')
                    """,
                    UUID.randomUUID(),
                    member.accountId(),
                    minute % 2 == 0 ? member.workspace().getId() : null,
                    minute);
        }
    }

    private WebTestClient.ResponseSpec send(Member member, String message) {
        return webTestClient
                .post()
                .uri(member.path(""))
                .headers(member.headers())
                .bodyValue(Map.of("kind", "IDEA", "message", message))
                .exchange();
    }

    /** One workspace whose admin member is a real account, so the same token can author and answer. */
    private Member member(String slug) {
        User actor = persistUser(slug + "-member");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug, AccountType.ORG, actor);
        ensureMembership(workspace, actor, Membership.WorkspaceRole.ADMIN);
        return member(slug, Account.AppRole.APP_ADMIN, workspace, actor);
    }

    /** A plain account that is a member of its own workspace only, so no instance-admin elevation applies. */
    private Member user(String slug) {
        User actor = persistUser(slug + "-member");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug, AccountType.ORG, actor);
        ensureMembership(workspace, actor, Membership.WorkspaceRole.ADMIN);
        return member(slug, Account.AppRole.USER, workspace, actor);
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
