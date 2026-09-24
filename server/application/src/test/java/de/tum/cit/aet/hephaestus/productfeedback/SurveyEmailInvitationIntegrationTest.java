package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.notification.SurveyEmailRequested;
import de.tum.cit.aet.hephaestus.notification.SurveyEndedSummaryRequested;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.notification.preferences.UpdateNotificationPreferencesDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyEditDTO;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class SurveyEmailInvitationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private SurveyEmailInvitationService invitationService;

    @Autowired
    private SurveyEmailInvitationRepository invitations;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyRepository surveys;

    @Autowired
    private SurveyParticipationRepository participations;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private CapturingJavaMailSender mail;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private FailedEventPublications failed;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private WebTestClient web;

    @Autowired
    private FeedbackAccountErasureAdapter erasure;

    @Autowired
    private ApplicationEventPublisher events;

    private record Recipient(long accountId, Workspace workspace) {}

    @BeforeEach
    void releaseSilentMode() {
        setSilentMode(false);
    }

    @AfterEach
    void restoreTransport() {
        mail.failWith(null);
        mail.sent().clear();
        setSilentMode(false);
    }

    @Test
    void shouldRequestEachRecipientOnceAndCountRelayAcceptanceWithoutChangingParticipation() throws Exception {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        assertThat(invitationService.preview(survey.getId()).deliveryConfigured())
                .isTrue();

        assertThat(invitationService.preview(survey.getId()).remaining()).isEqualTo(1);
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), false)
                        .queued())
                .isEqualTo(1);
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), false)
                        .queued())
                .isZero();

        var summary = invitationService.preview(survey.getId());
        assertThat(summary.alreadyRequested()).isEqualTo(1);
        assertThat(summary.accepted()).isEqualTo(1);
        assertThat(summary.remaining()).isZero();
        assertThat(invitations.requestedAccountIds(survey.getId())).containsExactly(recipient.accountId());
        assertThat(participations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .isEmpty();
        assertThat(mail.sent()).hasSize(1);
        var bytes = new java.io.ByteArrayOutputStream();
        mail.sent().getFirst().writeTo(bytes);
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("private-survey-description");

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new SurveyEmailRequested(
                        survey.getId(),
                        recipient.accountId(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        false,
                        0L)));
        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void shouldRollBackRequestRowsAndDurablePublicationsTogether() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(invitationService
                            .invite(survey.getId(), recipient.accountId(), false)
                            .queued())
                    .isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(invitations.requestedAccountIds(survey.getId())).isEmpty();
        assertThat(publications(survey.getId())).isEmpty();
        assertThat(mail.sent()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"paused", "closed", "declined", "answered", "deleted", "unsubscribed", "membership-removed"})
    void shouldRecheckCurrentSurveyEligibilityBeforeRetry(String change) {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        invitationService.invite(survey.getId(), recipient.accountId(), false);
        assertThat(publications(survey.getId())).hasSize(1);
        switch (change) {
            case "paused" -> {
                survey.edit(
                        survey.getTitle(), survey.getDescription(), survey.getStartsAt(), survey.getEndsAt(), false);
                surveys.save(survey);
            }
            case "closed" -> {
                survey.edit(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(1),
                        true);
                surveys.save(survey);
            }
            case "declined" ->
                surveyService.decline(survey.getId(), recipient.workspace().getId(), recipient.accountId());
            case "answered" ->
                surveyService.respond(
                        survey.getId(),
                        recipient.workspace().getId(),
                        recipient.accountId(),
                        new SubmitSurveyDTO(List.of()));
            case "deleted" -> surveyService.delete(survey.getId());
            case "unsubscribed" -> subscribe(recipient.accountId(), false);
            case "membership-removed" ->
                jdbc.update(
                        "DELETE FROM workspace_membership WHERE workspace_id = ?",
                        recipient.workspace().getId());
            default -> throw new IllegalArgumentException(change);
        }
        mail.failWith(null);

        resubmit(survey.getId());

        assertThat(mail.sent()).isEmpty();
        assertThat(publications(survey.getId())).isEmpty();
        assertThat(invitations.countBySurveyIdAndAcceptedAtIsNotNull(survey.getId()))
                .isZero();
    }

    @Test
    void shouldCancelPendingInvitationsOnPauseWithoutRevivingThemOnResume() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        invitationService.invite(survey.getId(), recipient.accountId(), false);
        assertThat(publications(survey.getId())).hasSize(1);

        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(), survey.getDescription(), survey.getStartsAt(), survey.getEndsAt(), false));
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(), survey.getDescription(), survey.getStartsAt(), survey.getEndsAt(), true));
        mail.failWith(null);
        resubmit(survey.getId());

        assertThat(mail.sent()).isEmpty();
        assertThat(invitations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .get()
                .extracting(SurveyEmailInvitation::getCancelledAt)
                .isNotNull();
        assertThat(publications(survey.getId())).isEmpty();
        assertThat(invitationService.preview(survey.getId()).remaining()).isEqualTo(1);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), true)
                        .queued())
                .isEqualTo(1);
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), true)
                        .queued())
                .isZero();
        var renewed = invitations
                .findBySurveyIdAndAccountId(survey.getId(), recipient.accountId())
                .orElseThrow();
        assertThat(renewed.getRequestGeneration()).isEqualTo(1);
        assertThat(renewed.getCancelledAt()).isNull();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var staleRequest = new SurveyEmailInvitation(
                    survey, recipient.accountId(), recipient.accountId(), Instant.now(), renewed.getExpiresAt(), false);
            assertThat(invitations.requeueUnaccepted(staleRequest)).isZero();
        });
        assertThat(invitationService.eligibleInvitation(survey.getId(), recipient.accountId(), false, 0))
                .isEmpty();

        invitationService.markAccepted(survey.getId(), recipient.accountId(), Instant.now(), false, 0);
        assertThat(invitations.countBySurveyIdAndAcceptedAtIsNotNull(survey.getId()))
                .isZero();
        mail.failWith(null);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new SurveyEmailRequested(
                        survey.getId(),
                        recipient.accountId(),
                        renewed.getRequestedAt(),
                        renewed.getExpiresAt(),
                        false,
                        0)));
        assertThat(mail.sent()).isEmpty();
        resubmit(survey.getId());

        assertThat(mail.sent()).hasSize(1);
        assertThat(invitations.countBySurveyIdAndAcceptedAtIsNotNull(survey.getId()))
                .isEqualTo(1);
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), true)
                        .queued())
                .isZero();
    }

    @Test
    void shouldExplicitlyRequeueAnExpiredUnacceptedInvitationWithANewGeneration() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        invitationService.invite(survey.getId(), recipient.accountId(), false);
        assertThat(jdbc.update(
                        "UPDATE product_survey_email_invitation SET expires_at = ? WHERE survey_id = ?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                        survey.getId()))
                .isEqualTo(1);
        assertThat(invitationService.preview(survey.getId()).remaining()).isEqualTo(1);
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), false)
                        .queued())
                .isEqualTo(1);
        var renewed = invitations
                .findBySurveyIdAndAccountId(survey.getId(), recipient.accountId())
                .orElseThrow();
        assertThat(renewed.getRequestGeneration()).isEqualTo(1);
        assertThat(invitationService.eligibleInvitation(survey.getId(), recipient.accountId(), false, 0))
                .isEmpty();
        mail.failWith(null);
        resubmit(survey.getId());
        assertThat(mail.sent()).hasSize(1);
        assertThat(invitationService.preview(survey.getId()).remaining()).isZero();
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), false)
                        .queued())
                .isZero();
    }

    @Test
    void shouldEraseRequesterAttributionWithoutRemovingOtherPeoplesInvitations() {
        Recipient recipient = recipient();
        Recipient requester = recipient();
        Survey survey = survey(recipient, null);
        invitationService.invite(survey.getId(), requester.accountId(), false);

        erasure.eraseAccount(requester.accountId());

        assertThat(invitations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .get()
                .extracting(SurveyEmailInvitation::getRequestedByAccountId)
                .isNull();
    }

    @Test
    void shouldNotInviteAResearchNonparticipantOrAMemberOfAnotherWorkspace() {
        Recipient recipient = recipient();
        Survey research = survey(recipient, "Unconsented study");
        Recipient other = recipient();
        subscribe(other.accountId(), false);
        Survey foreign = survey(other, null);

        assertThat(invitationService.preview(research.getId()).eligible()).isZero();
        assertThat(invitationService
                        .invite(research.getId(), recipient.accountId(), false)
                        .queued())
                .isZero();
        assertThat(invitationService.preview(foreign.getId()).eligible()).isZero();
        assertThat(mail.sent()).isEmpty();
        assertThat(participations.findBySurveyIdAndAccountId(research.getId(), recipient.accountId()))
                .isEmpty();
    }

    @Test
    void shouldEraseRecipientRows() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        invitationService.invite(survey.getId(), recipient.accountId(), false);

        erasure.eraseAccount(recipient.accountId());

        assertThat(invitations.requestedAccountIds(survey.getId())).isEmpty();
    }

    @Test
    @WithUser
    void shouldRequireInstanceAdministrationForPreviewAndSending() {
        UUID surveyId = UUID.randomUUID();
        web.get()
                .uri("/admin/product-feedback/surveys/" + surveyId + "/email-invitations")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        web.post()
                .uri("/admin/product-feedback/surveys/" + surveyId + "/email-invitations")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotCountSilentModeAsRelayAcceptance() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        setSilentMode(true);

        invitationService.invite(survey.getId(), recipient.accountId(), false);

        assertThat(mail.sent()).isEmpty();
        assertThat(invitationService.preview(survey.getId()).accepted()).isZero();
        assertThat(publications(survey.getId())).isEmpty();
    }

    @Test
    void shouldSendOnlyOneExplicitlyRequestedReminderAfterSeventyTwoHours() throws Exception {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        invitationService.invite(survey.getId(), recipient.accountId(), true);
        assertThat(mail.sent()).hasSize(1);
        acceptedHoursAgo(survey.getId(), 71);

        invitationService.scheduleReminders();
        assertThat(mail.sent()).hasSize(1);
        acceptedHoursAgo(survey.getId(), 73);
        invitationService.scheduleReminders();
        invitationService.scheduleReminders();

        assertThat(mail.sent()).hasSize(2);
        var initialBody = (jakarta.mail.Multipart) mail.sent().getFirst().getContent();
        var reminderBody = (jakarta.mail.Multipart) mail.sent().getLast().getContent();
        var initialAlternatives =
                (jakarta.mail.Multipart) initialBody.getBodyPart(0).getContent();
        var reminderAlternatives =
                (jakarta.mail.Multipart) reminderBody.getBodyPart(0).getContent();
        assertThat(initialAlternatives.getCount()).isEqualTo(2);
        assertThat(reminderAlternatives.getCount()).isEqualTo(2);
        for (int part = 0; part < 2; part++) {
            assertThat(initialAlternatives.getBodyPart(part).getContent().toString())
                    .doesNotContain("This is your one reminder.");
            assertThat(reminderAlternatives.getBodyPart(part).getContent().toString())
                    .contains("This is your one reminder.", "no further reminder is scheduled.");
        }
        var invitation = invitations
                .findBySurveyIdAndAccountId(survey.getId(), recipient.accountId())
                .orElseThrow();
        assertThat(invitation.getReminderRequestedAt()).isNotNull();
        assertThat(invitation.getReminderAcceptedAt()).isNotNull();
        assertThat(participations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .isEmpty();
    }

    @Test
    void shouldLeaveRemindersOffUnlessTheAdministratorExplicitlyRequestsOne() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        invitationService.invite(survey.getId(), recipient.accountId(), false);
        acceptedHoursAgo(survey.getId(), 73);

        invitationService.scheduleReminders();

        assertThat(mail.sent()).hasSize(1);
        assertThat(invitations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .get()
                .extracting(SurveyEmailInvitation::getReminderRequestedAt)
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"responded", "declined", "unsubscribed", "paused-then-resumed"})
    void shouldNotRemindPeopleWhoAreNoLongerEligible(String change) {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        invitationService.invite(survey.getId(), recipient.accountId(), true);
        acceptedHoursAgo(survey.getId(), 73);
        switch (change) {
            case "responded" ->
                surveyService.respond(
                        survey.getId(),
                        recipient.workspace().getId(),
                        recipient.accountId(),
                        new SubmitSurveyDTO(List.of()));
            case "declined" ->
                surveyService.decline(survey.getId(), recipient.workspace().getId(), recipient.accountId());
            case "unsubscribed" -> subscribe(recipient.accountId(), false);
            case "paused-then-resumed" -> {
                surveyService.edit(
                        survey.getId(),
                        new SurveyEditDTO(
                                survey.getTitle(),
                                survey.getDescription(),
                                survey.getStartsAt(),
                                survey.getEndsAt(),
                                false));
                surveyService.edit(
                        survey.getId(),
                        new SurveyEditDTO(
                                survey.getTitle(),
                                survey.getDescription(),
                                survey.getStartsAt(),
                                survey.getEndsAt(),
                                true));
            }
            default -> throw new IllegalArgumentException(change);
        }

        invitationService.scheduleReminders();
        invitationService.scheduleReminders();

        assertThat(mail.sent()).hasSize(1);
        assertThat(invitations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .get()
                .extracting(SurveyEmailInvitation::getReminderRequestedAt)
                .isNull();
        assertThat(invitationService
                        .invite(survey.getId(), recipient.accountId(), true)
                        .queued())
                .isZero();
    }

    @Test
    void shouldQueueOneEndedSurveySummaryOnlyForItsOwnOptedInAdministrators() throws Exception {
        Recipient admin = recipient();
        var account = accounts.findById(admin.accountId()).orElseThrow();
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.save(account);
        summarySubscription(admin.accountId(), true);
        Survey survey = survey(admin, null);
        surveyService.respond(
                survey.getId(), admin.workspace().getId(), admin.accountId(), new SubmitSurveyDTO(List.of()));
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(1),
                        true));

        invitationService.scheduleSummaries();
        invitationService.scheduleSummaries();

        assertThat(mail.sent()).hasSize(1);
        assertThat(mail.sent().getFirst().getSubject()).isEqualTo("A Hephaestus survey has ended");
        assertThat(surveys.findById(survey.getId()))
                .get()
                .extracting(Survey::getSummaryQueuedAt)
                .isNotNull();
        assertThat(participations.findBySurveyIdAndAccountId(survey.getId(), admin.accountId()))
                .get()
                .extracting(SurveyParticipation::getStatus)
                .isEqualTo(SurveyParticipation.Status.RESPONDED);
    }

    @Test
    void shouldNotTreatSurveyInvitationSubscriptionAsSummaryConsent() {
        Recipient recipient = recipient();
        var account = accounts.findById(recipient.accountId()).orElseThrow();
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.save(account);
        Survey survey = survey(recipient, null);
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(1),
                        true));

        invitationService.scheduleSummaries();

        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void shouldReplaceAnObsoleteSummaryWhenTheSurveyEndChanges() {
        Recipient admin = recipient();
        var account = accounts.findById(admin.accountId()).orElseThrow();
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.save(account);
        summarySubscription(admin.accountId(), true);
        Survey survey = survey(admin, null);
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(10),
                        true));
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        invitationService.scheduleSummaries();
        assertThat(surveys.findById(survey.getId()))
                .get()
                .extracting(Survey::getSummaryQueuedAt)
                .isNotNull();

        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(1),
                        true));
        assertThat(surveys.findById(survey.getId()))
                .get()
                .extracting(Survey::getSummaryQueuedAt)
                .isNull();
        mail.failWith(null);
        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof SurveyEndedSummaryRequested event
                        && event.surveyId().equals(survey.getId())));
        assertThat(mail.sent()).isEmpty();

        invitationService.scheduleSummaries();
        invitationService.scheduleSummaries();

        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void shouldRejectASummaryClaimForAnObsoleteEndTime() {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        Instant oldEnd = Objects.requireNonNull(
                surveys.findById(survey.getId()).orElseThrow().getEndsAt());
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        oldEnd.plusSeconds(60),
                        true));
        Instant newEnd = Objects.requireNonNull(
                surveys.findById(survey.getId()).orElseThrow().getEndsAt());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(surveys.claimSummary(survey.getId(), oldEnd, Instant.now()))
                    .isZero();
            assertThat(surveys.claimSummary(survey.getId(), newEnd, Instant.now()))
                    .isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotReviveAnInvitationOrReminderFromAnEarlierSubscription(boolean reminder) {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        if (reminder) {
            invitationService.invite(survey.getId(), recipient.accountId(), true);
            acceptedHoursAgo(survey.getId(), 73);
            mail.sent().clear();
        }
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        if (reminder) invitationService.scheduleReminders();
        else invitationService.invite(survey.getId(), recipient.accountId(), false);
        assertThat(publications(survey.getId())).hasSize(1);

        subscribe(recipient.accountId(), false);
        subscribe(recipient.accountId(), true);
        mail.failWith(null);
        resubmit(survey.getId());

        assertThat(mail.sent()).isEmpty();
        assertThat(publications(survey.getId())).isEmpty();
        var request = invitations
                .findBySurveyIdAndAccountId(survey.getId(), recipient.accountId())
                .orElseThrow();
        if (reminder) assertThat(request.getReminderAcceptedAt()).isNull();
        else assertThat(request.getAcceptedAt()).isNull();
        Survey fresh = survey(recipient, null);
        invitationService.invite(fresh.getId(), recipient.accountId(), false);
        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void shouldNotReviveAnEndedSurveySummaryFromAnEarlierSubscription() {
        Recipient admin = recipient();
        var account = accounts.findById(admin.accountId()).orElseThrow();
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.saveAndFlush(account);
        summarySubscription(admin.accountId(), true);
        Survey survey = survey(admin, null);
        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(10),
                        true));
        mail.failWith(new MailSendException("relay down", new ConnectException("refused")));
        invitationService.scheduleSummaries();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                        Integer.class,
                        SurveyEndedSummaryRequested.class.getName(),
                        "%" + survey.getId() + "%"))
                .isEqualTo(1);

        summarySubscription(admin.accountId(), false);
        summarySubscription(admin.accountId(), true);
        mail.failWith(null);
        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof SurveyEndedSummaryRequested event
                        && event.surveyId().equals(survey.getId())));
        assertThat(mail.sent()).isEmpty();

        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getStartsAt(),
                        Instant.now().minusSeconds(1),
                        true));
        invitationService.scheduleSummaries();
        assertThat(mail.sent()).hasSize(1);
        summarySubscription(admin.accountId(), false);
    }

    @Test
    void shouldResetAConcurrentSummaryClaimWhenTheEndChanges() throws Exception {
        Survey survey = survey(recipient(), null);
        Instant end = Objects.requireNonNull(
                surveys.findById(survey.getId()).orElseThrow().getEndsAt());
        Instant newEnd = end.plusSeconds(60);
        raceOnSurveyRow(
                () -> assertThat(surveys.claimSummary(survey.getId(), end, Instant.now()))
                        .isEqualTo(1),
                () -> surveyService.edit(
                        survey.getId(),
                        new SurveyEditDTO(
                                survey.getTitle(), survey.getDescription(), survey.getStartsAt(), newEnd, true)));

        Survey saved = surveys.findById(survey.getId()).orElseThrow();
        assertThat(saved.getEndsAt()).isEqualTo(newEnd);
        assertThat(saved.getSummaryQueuedAt()).isNull();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> assertThat(surveys.claimSummary(survey.getId(), newEnd, Instant.now()))
                        .isEqualTo(1));
    }

    @Test
    void shouldPreserveAConcurrentSummaryClaimWhenOnlyTheTitleChanges() throws Exception {
        Survey survey = survey(recipient(), null);
        Instant end = Objects.requireNonNull(
                surveys.findById(survey.getId()).orElseThrow().getEndsAt());
        raceOnSurveyRow(
                () -> assertThat(surveys.claimSummary(survey.getId(), end, Instant.now()))
                        .isEqualTo(1),
                () -> surveyService.edit(
                        survey.getId(),
                        new SurveyEditDTO("Edited title", survey.getDescription(), survey.getStartsAt(), end, true)));

        Survey saved = surveys.findById(survey.getId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Edited title");
        assertThat(saved.getSummaryQueuedAt()).isNotNull();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> assertThat(surveys.claimSummary(survey.getId(), end, Instant.now()))
                        .isZero());
    }

    @Test
    void shouldNotQueueAnInvitationBehindAConcurrentPauseOrReviveItOnResume() throws Exception {
        Recipient recipient = recipient();
        Survey survey = survey(recipient, null);
        raceOnSurveyRow(
                () -> surveyService.edit(
                        survey.getId(),
                        new SurveyEditDTO(
                                survey.getTitle(),
                                survey.getDescription(),
                                survey.getStartsAt(),
                                survey.getEndsAt(),
                                false)),
                () -> assertThat(invitationService
                                .invite(survey.getId(), recipient.accountId(), true)
                                .queued())
                        .isZero());

        surveyService.edit(
                survey.getId(),
                new SurveyEditDTO(
                        survey.getTitle(), survey.getDescription(), survey.getStartsAt(), survey.getEndsAt(), true));
        assertThat(invitations.findBySurveyIdAndAccountId(survey.getId(), recipient.accountId()))
                .isEmpty();
        assertThat(publications(survey.getId())).isEmpty();
        assertThat(mail.sent()).isEmpty();
    }

    private void raceOnSurveyRow(Runnable holder, Runnable follower) throws Exception {
        var locked = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holding = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                holder.run();
                int holderPid = Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                locked.countDown();
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    // PostgreSQL caches this transaction's statistics snapshot until explicitly cleared.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    return Objects.requireNonNull(jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Integer.class,
                                    holderPid))
                            > 0;
                });
            }));
            var following = pool.submit(() -> {
                if (!locked.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Survey lock was not acquired");
                follower.run();
                return true;
            });
            holding.get(15, TimeUnit.SECONDS);
            following.get(15, TimeUnit.SECONDS);
        }
    }

    private void acceptedHoursAgo(UUID surveyId, long hours) {
        assertThat(jdbc.update(
                        "UPDATE product_survey_email_invitation SET accepted_at = ? WHERE survey_id = ?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(hours * 3600)),
                        surveyId))
                .isEqualTo(1);
    }

    private void summarySubscription(long accountId, boolean enabled) {
        var current = subscriptions.get(accountId);
        subscriptions.update(
                accountId,
                new UpdateNotificationPreferencesDTO(false, true, true, false, enabled),
                EntityTagPrecondition.parse(current.etag()),
                true);
    }

    private Recipient recipient() {
        String slug = "survey-email-" + UUID.randomUUID();
        var actor = persistUser(slug);
        Workspace workspace = createWorkspace(slug, "Survey workspace", slug, AccountType.ORG, actor);
        ensureWorkspaceMembership(workspace, actor, WorkspaceMembership.WorkspaceRole.MEMBER);
        Account account = new Account("Survey recipient");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account = accounts.save(account);
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(ensureGitHubProvider().getId()));
        link.setSubject(actor.getNativeId().toString());
        link.setUsernameAtSignup(actor.getLogin());
        link.setExternalActorId(actor.getId());
        identities.save(link);
        long id = Objects.requireNonNull(account.getId());
        subscribe(id, true);
        return new Recipient(id, workspace);
    }

    private void subscribe(long accountId, boolean enabled) {
        var current = subscriptions.get(accountId);
        subscriptions.update(
                accountId,
                new UpdateNotificationPreferencesDTO(false, enabled, enabled, false, false),
                EntityTagPrecondition.parse(current.etag()),
                false);
    }

    private Survey survey(Recipient recipient, @Nullable String researchOrganization) {
        return surveys.saveAndFlush(new Survey(
                "Survey",
                "private-survey-description",
                researchOrganization,
                mapper.createArrayNode(),
                recipient.workspace().getId(),
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(86400),
                recipient.accountId()));
    }

    private List<UUID> publications(UUID surveyId) {
        return jdbc
                .queryForList(
                        "SELECT id FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                        UUID.class,
                        SurveyEmailRequested.class.getName(),
                        "%" + surveyId + "%")
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private void resubmit(UUID surveyId) {
        failed.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> publication.getEvent() instanceof SurveyEmailRequested event
                        && event.surveyId().equals(surveyId)));
    }

    private void setSilentMode(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged) {
            settings.updateSilentMode(
                    engaged,
                    engaged ? "survey test" : null,
                    "survey-test",
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }
}
