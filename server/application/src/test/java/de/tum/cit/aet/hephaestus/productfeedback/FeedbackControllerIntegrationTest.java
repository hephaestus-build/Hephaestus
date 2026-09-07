package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class FeedbackControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SurveyRepository surveys;

    @Autowired
    private SurveySubmissionRepository submissions;

    @Autowired
    private AccountRepository accounts;

    @Test
    @WithAdminUser
    void shouldPersistPauseAndResumeWithoutChangingTheQuestions() {
        var created = publishSurvey(null);
        var originalQuestions = surveys.findById(created.id()).orElseThrow().getQuestions();
        for (boolean active : List.of(false, true)) {
            webTestClient
                    .patch()
                    .uri("/admin/product-feedback/surveys/" + created.id() + "/status")
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("active", active))
                    .exchange()
                    .expectStatus()
                    .isOk();
            var persisted = surveys.findById(created.id()).orElseThrow();
            assertThat(persisted.isActive()).isEqualTo(active);
            assertThat(persisted.getQuestions()).isEqualTo(originalQuestions);
        }
    }

    @Test
    @WithUser
    void shouldDenySurveyLifecycleChangesByNonAdministrators() {
        webTestClient
                .patch()
                .uri("/admin/product-feedback/surveys/" + UUID.randomUUID() + "/status")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("active", false))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @WithAdminUser
    void shouldRejectNullAnswersWithoutStoringASubmission() {
        var workspace = createWorkspace(
                "validation-team",
                "Validation team",
                "validation-team",
                AccountType.ORG,
                persistUser("validation-owner"));
        ensureAdminMembership(workspace);
        var survey = publishSurvey(workspace.getId());
        webTestClient
                .post()
                .uri("/workspaces/validation-team/product-feedback/surveys/" + survey.id() + "/responses")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"answers\":{\"q\":null}}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertThat(submissions.findAll())
                .noneMatch(submission -> submission.getSurveyId().equals(survey.id()));
    }

    @Test
    @WithAdminUser
    void shouldUndoOnlyADismissalAndNeverEraseAnAnswer() {
        var owner = persistUser("survey-owner");
        var workspace = createWorkspace("survey-team", "Survey team", "survey-team", AccountType.ORG, owner);
        var otherWorkspace = createWorkspace("other-team", "Other team", "other-team", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        ensureAdminMembership(otherWorkspace);
        var declined = publishSurvey(workspace.getId());
        var answered = publishSurvey(workspace.getId());
        Long accountId = surveys.findById(declined.id()).orElseThrow().getCreatedByAccountId();
        if (accountId == null) throw new AssertionError("Published survey must have an author");
        var otherAccountId = accounts.save(new Account("other-respondent")).getId();
        if (otherAccountId == null) throw new AssertionError("Persisted account must have an id");
        var dismissal = submissions.saveAndFlush(new SurveySubmission(
                declined.id(), accountId, workspace.getId(), SurveySubmission.Disposition.DISMISSED, null));
        var otherDismissal = submissions.saveAndFlush(new SurveySubmission(
                declined.id(), otherAccountId, workspace.getId(), SurveySubmission.Disposition.DISMISSED, null));
        webTestClient
                .post()
                .uri("/workspaces/survey-team/product-feedback/surveys/" + answered.id() + "/responses")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("answers", Map.of("q", "Keep my answer")))
                .exchange()
                .expectStatus()
                .isNoContent();
        webTestClient
                .delete()
                .uri("/workspaces/other-team/product-feedback/surveys/" + declined.id() + "/dismissal")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(submissions.findById(dismissal.getId())).isPresent();
        for (var survey : List.of(declined, answered)) {
            webTestClient
                    .delete()
                    .uri("/workspaces/survey-team/product-feedback/surveys/" + survey.id() + "/dismissal")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        }
        assertThat(submissions.findById(dismissal.getId())).isEmpty();
        assertThat(submissions.findById(otherDismissal.getId())).isPresent();
        assertThat(submissions.findAll())
                .filteredOn(submission -> submission.getSurveyId().equals(answered.id()))
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.getAccountId()).isEqualTo(accountId);
                    assertThat(response.getDisposition()).isEqualTo(SurveySubmission.Disposition.RESPONDED);
                    var answers = response.getAnswers();
                    if (answers == null) throw new AssertionError("Submitted answers must be retained");
                    assertThat(answers.path("q").asString()).isEqualTo("Keep my answer");
                });
    }

    private FeedbackDTOs.SurveyDTO publishSurvey(@Nullable Long workspaceId) {
        var request = new FeedbackDTOs.CreateSurveyDTO(
                "Survey " + UUID.randomUUID(),
                "Purpose",
                List.of(new FeedbackDTOs.QuestionDTO(
                        "q", "What should improve?", FeedbackDTOs.QuestionType.TEXT, List.of(), false)),
                workspaceId,
                Instant.now().minusSeconds(10),
                null);
        var result = webTestClient
                .post()
                .uri("/admin/product-feedback/surveys")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(FeedbackDTOs.SurveyDTO.class)
                .returnResult()
                .getResponseBody();
        if (result == null) throw new AssertionError("Publishing must return the survey");
        return result;
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
                .isOk();
    }

    @Test
    @WithUser
    void shouldDenyInboxReadWhenRegularUser() {
        webTestClient
                .get()
                .uri("/admin/product-feedback")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void shouldRequireAuthenticationForInstanceEndpoints() {
        webTestClient
                .get()
                .uri("/admin/product-feedback")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        webTestClient
                .post()
                .uri("/product-feedback")
                .headers(TestAuthUtils.withCsrf(csrf))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
