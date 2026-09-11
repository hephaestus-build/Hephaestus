package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class FeedbackControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SurveyRepository surveys;

    @Autowired
    private SurveyParticipationRepository participations;

    @Autowired
    private ProductFeedbackRepository feedback;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository identityLinks;

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
    void shouldCarryAnInvitationThroughToAResponseTheAdministratorCanRead() {
        Member member = member("survey-flow");
        SurveyDTO survey = publish(
                member,
                member.workspace().getId(),
                List.of(
                        new QuestionDTO(
                                "useful", "How useful?", QuestionType.RATING, List.of(), true, "Not at all", "Very"),
                        new QuestionDTO("why", "Why?", QuestionType.TEXT, List.of(), false, null, null)));

        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(survey.id().toString())
                .jsonPath("$[0].seen")
                .isEqualTo(false);
        for (int tab = 0; tab < 2; tab++) {
            webTestClient
                    .put()
                    .uri(member.path("/surveys/" + survey.id() + "/invitation"))
                    .headers(member.headers())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);
        }
        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].seen")
                .isEqualTo(true);

        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of(
                        "answers",
                        List.of(
                                Map.of("questionId", "useful", "rating", 4),
                                Map.of("questionId", "why", "text", "Fast"))))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        SurveyParticipation stored = participations
                .findBySurveyIdAndAccountId(survey.id(), member.accountId())
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(Status.RESPONDED);
        assertThat(stored.getWorkspaceId()).isEqualTo(member.workspace().getId());
        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isEmpty();

        webTestClient
                .get()
                .uri("/admin/product-feedback/surveys/" + survey.id() + "/summary")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.participation.invited")
                .isEqualTo(1)
                .jsonPath("$.participation.responded")
                .isEqualTo(1)
                .jsonPath("$.questions[0].average")
                .isEqualTo(4.0)
                .jsonPath("$.questions[0].counts[3].count")
                .isEqualTo(1);
        webTestClient
                .get()
                .uri("/admin/product-feedback/surveys/" + survey.id() + "/responses")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].account.displayName")
                .isEqualTo("Member survey-flow")
                .jsonPath("$.content[0].workspace.slug")
                .isEqualTo(member.workspace().getWorkspaceSlug())
                .jsonPath("$.content[0].answers[1].text")
                .isEqualTo("Fast");
        String csv = webTestClient
                .get()
                .uri("/admin/product-feedback/surveys/" + survey.id() + "/responses/export")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.parseMediaType("text/csv"))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(csv)
                .contains("\"How useful?\",\"Why?\"")
                .contains("\"RESPONDED\",\"Member survey-flow\"")
                .contains("\"4\",\"Fast\"");
    }

    @Test
    void shouldUndoOnlyADeclineAndNeverEraseAnAnswer() {
        Member member = member("survey-undo");
        SurveyDTO declined = publish(member, member.workspace().getId(), textQuestion());
        SurveyDTO answered = publish(member, member.workspace().getId(), textQuestion());
        webTestClient
                .post()
                .uri(member.path("/surveys/" + answered.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of("answers", List.of(Map.of("questionId", "q", "text", "Keep my answer"))))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        webTestClient
                .put()
                .uri(member.path("/surveys/" + declined.id() + "/decline"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(participations.findBySurveyIdAndAccountId(declined.id(), member.accountId()))
                .get()
                .extracting(SurveyParticipation::getStatus)
                .isEqualTo(Status.DECLINED);

        webTestClient
                .delete()
                .uri(member.path("/surveys/" + declined.id() + "/decline"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        webTestClient
                .delete()
                .uri(member.path("/surveys/" + answered.id() + "/decline"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(participations.findBySurveyIdAndAccountId(declined.id(), member.accountId()))
                .get()
                .extracting(SurveyParticipation::getStatus)
                .isEqualTo(Status.INVITED);
        SurveyParticipation response = participations
                .findBySurveyIdAndAccountId(answered.id(), member.accountId())
                .orElseThrow();
        assertThat(response.getStatus()).isEqualTo(Status.RESPONDED);
        assertThat(Objects.requireNonNull(response.getAnswers())
                        .path(0)
                        .path("text")
                        .asString())
                .isEqualTo("Keep my answer");
    }

    @Test
    void shouldRefuseAResponseAfterADeclineWithAProblemDetail() {
        Member member = member("survey-conflict");
        SurveyDTO survey = publish(member, member.workspace().getId(), textQuestion());
        webTestClient
                .put()
                .uri(member.path("/surveys/" + survey.id() + "/decline"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of("answers", List.of(Map.of("questionId", "q", "text", "Too late"))))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(409);

        SurveyParticipation stored = participations
                .findBySurveyIdAndAccountId(survey.id(), member.accountId())
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(Status.DECLINED);
        assertThat(stored.getAnswers()).isNull();
    }

    @Test
    void shouldExportTextAnswersThatCarryQuotesCommasAndLineBreaks() {
        Member member = member("survey-csv");
        SurveyDTO survey = publish(member, member.workspace().getId(), textQuestion());
        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of(
                        "answers", List.of(Map.of("questionId", "q", "text", "He said \"ship it\", then\nleft"))))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        String csv = webTestClient
                .get()
                .uri("/admin/product-feedback/surveys/" + survey.id() + "/responses/export")
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(csv).contains("\"Member survey-csv\",\"\",\"survey-csv\",\"He said \"\"ship it\"\", then\nleft\"\n");
    }

    @Test
    void shouldLetAPlainMemberListAndAnswerButNotAdminister() {
        Member admin = member("survey-member-admin");
        Member member = plainMember("survey-member", admin.workspace());
        SurveyDTO survey = publish(admin, null, textQuestion());

        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '" + survey.id() + "')].seen")
                .isEqualTo(false);
        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of("answers", List.of(Map.of("questionId", "q", "text", "As a member"))))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(participations.findBySurveyIdAndAccountId(survey.id(), member.accountId()))
                .get()
                .extracting(SurveyParticipation::getStatus)
                .isEqualTo(Status.RESPONDED);

        for (String uri : List.of(
                "/admin/product-feedback",
                "/admin/product-feedback/surveys",
                "/admin/product-feedback/surveys/" + survey.id() + "/responses/export")) {
            webTestClient
                    .get()
                    .uri(uri)
                    .headers(member.headers())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
        webTestClient
                .delete()
                .uri("/admin/product-feedback/surveys/" + survey.id())
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(surveys.findById(survey.id())).isPresent();
    }

    @Test
    void shouldHideASurveyFromAnotherWorkspaceAndWhilePaused() {
        Member member = member("survey-scope");
        Workspace other =
                createWorkspace("other-scope", "Other", "other-scope", AccountType.ORG, persistUser("o-scope"));
        SurveyDTO survey = publish(member, other.getId(), textQuestion());

        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isEmpty();
        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of("answers", List.of()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        SurveyDTO everyone = publish(member, null, textQuestion());
        var originalQuestions = surveys.findById(everyone.id()).orElseThrow().getQuestions();
        webTestClient
                .put()
                .uri("/admin/product-feedback/surveys/" + everyone.id())
                .headers(member.headers())
                .bodyValue(Map.of(
                        "title",
                        "Renamed",
                        "description",
                        everyone.description(),
                        "startsAt",
                        everyone.startsAt().toString(),
                        "active",
                        false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Renamed")
                .jsonPath("$.active")
                .isEqualTo(false);
        Survey persisted = surveys.findById(everyone.id()).orElseThrow();
        assertThat(persisted.isActive()).isFalse();
        assertThat(persisted.getQuestions()).isEqualTo(originalQuestions);
        webTestClient
                .get()
                .uri(member.path("/surveys"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isEmpty();
        assertThat(participations.findBySurveyIdAndAccountId(everyone.id(), member.accountId()))
                .isEmpty();
    }

    @Test
    void shouldDeleteASurveyTogetherWithItsParticipation() {
        Member member = member("survey-delete");
        SurveyDTO survey = publish(member, member.workspace().getId(), textQuestion());
        webTestClient
                .put()
                .uri(member.path("/surveys/" + survey.id() + "/decline"))
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .delete()
                .uri("/admin/product-feedback/surveys/" + survey.id())
                .headers(member.headers())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(surveys.findById(survey.id())).isEmpty();
        assertThat(participations.findBySurveyIdAndAccountId(survey.id(), member.accountId()))
                .isEmpty();
    }

    @Test
    void shouldRejectMalformedAnswersWithoutStoringAParticipation() {
        Member member = member("survey-validation");
        SurveyDTO survey = publish(member, member.workspace().getId(), textQuestion());
        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"answers\":[null]}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri(member.path("/surveys/" + survey.id() + "/responses"))
                .headers(member.headers())
                .bodyValue(Map.of("answers", List.of(Map.of("questionId", "q", "rating", 3))))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(participations.findBySurveyIdAndAccountId(survey.id(), member.accountId()))
                .isEmpty();
    }

    @Test
    void shouldKeepFeedbackContextOptionalAndLetAdministratorsTriageIt() {
        Member member = member("feedback-triage");
        webTestClient
                .post()
                .uri(member.path(""))
                .headers(member.headers())
                .bodyValue(Map.of(
                        "kind", "BUG",
                        "message", "The list jumps",
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
        for (String uri : List.of("/admin/product-feedback", "/admin/product-feedback/surveys")) {
            webTestClient
                    .get()
                    .uri(uri)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
        webTestClient
                .put()
                .uri("/admin/product-feedback/surveys/" + UUID.randomUUID())
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of(
                        "title",
                        "t",
                        "description",
                        "d",
                        "startsAt",
                        Instant.now().toString(),
                        "active",
                        false))
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

    private static List<QuestionDTO> textQuestion() {
        return List.of(new QuestionDTO("q", "What should improve?", QuestionType.TEXT, List.of(), false, null, null));
    }

    private SurveyDTO publish(Member admin, @Nullable Long workspaceId, List<QuestionDTO> questions) {
        var request = new CreateSurveyDTO(
                "Survey " + UUID.randomUUID(),
                "Purpose",
                questions,
                workspaceId,
                Instant.now().minusSeconds(10),
                null);
        SurveyDTO result = webTestClient
                .post()
                .uri("/admin/product-feedback/surveys")
                .headers(admin.headers())
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(SurveyDTO.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(result, "publishing must return the survey");
    }

    /** One workspace whose admin member is a real account, so the same token can author and answer. */
    private Member member(String slug) {
        User actor = persistUser(slug + "-member");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug, AccountType.ORG, actor);
        ensureWorkspaceMembership(workspace, actor, WorkspaceMembership.WorkspaceRole.ADMIN);
        return member(slug, Account.AppRole.APP_ADMIN, workspace, actor);
    }

    private Member plainMember(String slug, Workspace workspace) {
        User actor = persistUser(slug + "-member");
        ensureWorkspaceMembership(workspace, actor, WorkspaceMembership.WorkspaceRole.MEMBER);
        return member(slug, Account.AppRole.USER, workspace, actor);
    }

    private Member member(String slug, Account.AppRole role, Workspace workspace, User actor) {
        Account account = new Account("Member " + slug);
        account.setAppRole(role);
        account.setStatus(Account.Status.ACTIVE);
        account = accounts.save(account);
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(ensureGitHubProvider().getId()));
        link.setSubject(String.valueOf(actor.getNativeId()));
        link.setUsernameAtSignup(actor.getLogin());
        link.setExternalActorId(actor.getId());
        identityLinks.save(link);
        return new Member(Objects.requireNonNull(account.getId()), role, workspace);
    }
}
