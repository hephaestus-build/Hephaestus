package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.notification.preferences.UpdateNotificationPreferencesDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * An erased report's GitHub issue: the app scrubs what it can, then hands the rest to a maintainer with an email and a
 * Needs action entry that stays until an admin confirms the step. Only the GitHub transport is controlled.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class ProductFeedbackCleanupIntegrationTest extends BaseIntegrationTest {
    private static final ProductFeedbackIssues.Repository PRIVATE_REPOSITORY =
            new ProductFeedbackIssues.Repository(41, "", "acme/feedback-private", 7, true);

    @MockitoBean
    private ProductFeedbackIssues issues;

    @MockitoBean
    private ProductFeedbackIssueUpdates updates;

    @Autowired
    private CleanupService cleanup;

    @Autowired
    private ProductFeedbackCleanupRepository cleanups;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private FeedbackAccountErasureAdapter erasure;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private NotificationSubscriptionService subscriptions;

    @Autowired
    private CapturingJavaMailSender mail;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private FileIssueQueue queue;

    @Autowired
    private MyReportsService myReports;

    @Autowired
    private DestinationService destinationService;

    @Autowired
    private ProductFeedbackDestinationRepository destinationRows;

    @Autowired
    private ProductFeedbackRepository reports;

    private final List<UUID> createdCleanups = new ArrayList<>();
    private Account admin;

    @BeforeEach
    void setUp() {
        mail.failWith(null);
        mail.sent().clear();
        setSilentMode(false);
        admin = account(Account.AppRole.APP_ADMIN);
    }

    @AfterEach
    void tearDown() {
        cleanups.deleteAllById(createdCleanups);
        for (UUID id : createdCleanups)
            jdbc.update("DELETE FROM agent_action WHERE idempotency_key = ?", "cleanup:" + id);
        subscribed(admin, false);
        mail.sent().clear();
    }

    /**
     * Runs every due scrub, then one announcement on the bean behind its lock proxy. The lock's 30-second floor would
     * skip every announcement after the first; the replica test covers the lock itself.
     */
    private void sweep() {
        ActionTestSupport.drain(executor, jdbc);
        AopTestUtils.<CleanupService>getUltimateTargetObject(cleanup).announceDue();
    }

    @Test
    void shouldLeaveTheEditHistoryToAMaintainerWhenAPrivateIssueIsScrubbed() throws Exception {
        ProductFeedbackCleanup record = record(false, 12L, "I_12", Instant.now());

        sweep();

        verify(updates).edit(eq(PRIVATE_REPOSITORY), eq(12L), eq(CleanupService.ERASED), anyString());
        verify(updates).close(PRIVATE_REPOSITORY, 12L, "not_planned");
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        assertThat(stored.getManualSince()).isNotNull();
        assertThat(sentTo(admin)).singleElement().satisfies(message -> {
            assertThat(message.getSubject()).isEqualTo("Erased product feedback needs manual steps on GitHub");
            assertThat(body(message)).contains("/admin/feedback").contains("Needs action");
        });

        // A step that waits for a maintainer is never claimed again.
        sweep();
        verify(updates).close(PRIVATE_REPOSITORY, 12L, "not_planned");

        webTestClient
                .get()
                .uri("/admin/product-feedback/needs-action")
                .headers(as(admin))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].step")
                .isEqualTo("EDIT_HISTORY")
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].issueUrl")
                .isEqualTo("https://github.com/acme/feedback-private/issues/12");
    }

    @Test
    void shouldListEveryWaitingIssueInOneEmailWhenAnErasureHandsSeveralOver() throws Exception {
        ProductFeedbackCleanup first = record(false, 21L, "I_21", Instant.now());
        ProductFeedbackCleanup second = record(false, 22L, "I_22", Instant.now());

        sweep();

        assertThat(cleanups.findAllById(List.of(first.getId(), second.getId())))
                .extracting(ProductFeedbackCleanup::getManualStep)
                .containsOnly(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        MimeMessage message = sentTo(admin).getFirst();
        assertThat(sentTo(admin)).hasSize(1);
        assertThat(message.getSubject()).isEqualTo("Erased product feedback needs manual steps on GitHub");
        assertThat(body(message))
                .contains("https://github.com/acme/feedback-private/issues/21")
                .contains("https://github.com/acme/feedback-private/issues/22");

        // Announced once: the next sweep finds nothing new to tell.
        sweep();
        assertThat(sentTo(admin)).hasSize(1);
        assertThat(cleanups.findAllById(List.of(first.getId(), second.getId())))
                .extracting(ProductFeedbackCleanup::getNotifiedAt)
                .doesNotContainNull();
    }

    @Test
    void shouldKeepThePrivateRepositoryWhenAPublicIssueCanStillMoveThere() {
        ProductFeedbackDestination before =
                destinationRows.findById(ProductFeedbackDestination.SINGLETON).orElse(null);
        @Nullable String previousPrivate = before == null ? null : before.getPrivateRepository();
        ProductFeedbackDestination destination = before == null ? new ProductFeedbackDestination() : before;
        destination.setPrivateRepository(PRIVATE_REPOSITORY.fullName());
        destinationRows.save(destination);
        record(true, 40L, "I_40", Instant.now());
        try {
            assertThatThrownBy(() -> destinationService.update(
                            new UpdateProductFeedbackDestinationsDTO(null, null, null, 50, false, false),
                            Objects.requireNonNull(admin.getId())))
                    .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(refused.getReason()).startsWith("Keep a private repository");
                    });
            assertThat(destinationRows.findById(ProductFeedbackDestination.SINGLETON))
                    .get()
                    .extracting(ProductFeedbackDestination::getPrivateRepository)
                    .isEqualTo(PRIVATE_REPOSITORY.fullName());
        } finally {
            if (before == null) {
                destinationRows.deleteById(ProductFeedbackDestination.SINGLETON);
            } else {
                destination.setPrivateRepository(previousPrivate);
                destinationRows.save(destination);
            }
        }
    }

    @Test
    void shouldKeepThePrivateRepositoryWhenAFiledPublicIssueCanStillBeMadePrivate() {
        long existingPublic = filings.countByStateAndRoute(FilingState.FILED, FilingRoute.PUBLIC);
        Account reporter = account(Account.AppRole.USER);
        UUID report = filedPublicReport(reporter);
        var other = new ProductFeedbackIssues.Repository(42, "", "acme/feedback-private-2", 7, true);
        when(issues.repository(other.fullName())).thenReturn(Optional.of(other));
        ProductFeedbackDestination before =
                destinationRows.findById(ProductFeedbackDestination.SINGLETON).orElse(null);
        @Nullable String previousPrivate = before == null ? null : before.getPrivateRepository();
        ProductFeedbackDestination destination = before == null ? new ProductFeedbackDestination() : before;
        destination.setPrivateRepository(PRIVATE_REPOSITORY.fullName());
        destinationRows.save(destination);
        long adminId = Objects.requireNonNull(admin.getId());
        try {
            assertThatThrownBy(() -> destinationService.update(
                            new UpdateProductFeedbackDestinationsDTO(null, null, null, 50, false, false), adminId))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            refused -> assertThat(refused.getReason())
                                    .contains((existingPublic + 1)
                                            + (existingPublic == 0 ? " public issue" : " public issues")
                                            + " can still be made private"));

            // Replacing it is allowed: Make private moves the issue into whichever private repository is set.
            destinationService.update(
                    new UpdateProductFeedbackDestinationsDTO(null, other.fullName(), null, 50, false, false), adminId);
            assertThat(destinationRows.findById(ProductFeedbackDestination.SINGLETON))
                    .get()
                    .extracting(ProductFeedbackDestination::getPrivateRepository)
                    .isEqualTo(other.fullName());
        } finally {
            ProductFeedbackDestination row = destinationRows
                    .findById(ProductFeedbackDestination.SINGLETON)
                    .orElseThrow();
            if (before == null) {
                destinationRows.delete(row);
            } else {
                row.setPrivateRepository(previousPrivate);
                destinationRows.save(row);
            }
            reports.deleteById(report);
            subscribed(reporter, false);
        }
    }

    @Test
    void shouldHandTheCleanupToAMaintainerWhenItIsUnfinishedAfterThirtyDays() {
        ProductFeedbackCleanup record = record(true, null, null, Instant.now().minus(Duration.ofDays(31)));

        sweep();

        verifyNoInteractions(issues, updates);
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.UNFINISHED);
        assertThat(sentTo(admin)).hasSize(1);
        webTestClient
                .get()
                .uri("/admin/product-feedback/needs-action")
                .headers(as(admin))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].marker")
                .isEqualTo("hephaestus-product-feedback:" + record.getMarker())
                .jsonPath("$.cleanups[?(@.id == '" + record.getId() + "')].issueNumber")
                .isEmpty();
    }

    @Test
    void shouldHandAScrubThatKeepsFailingAfterAWriteToAMaintainerAfterThirtyDays() {
        ProductFeedbackCleanup record = record(false, 12L, "I_12", Instant.now());
        doThrow(new ProductFeedbackIssues.Unknown("read timed out", null))
                .when(updates)
                .edit(eq(PRIVATE_REPOSITORY), eq(12L), anyString(), anyString());
        sweep();
        assertThat(cleanups.findById(record.getId()).orElseThrow().getManualStep())
                .isNull();

        jdbc.update(
                "UPDATE product_feedback_cleanup SET created_at = now() - interval '31 days' WHERE id = ?",
                record.getId());
        ActionTestSupport.due(jdbc, "cleanup:" + record.getId());
        executor.runDue(); // a reread waits as an executing action, which a drain does not count
        sweep();

        assertThat(cleanups.findById(record.getId()).orElseThrow().getManualStep())
                .isEqualTo(ProductFeedbackCleanup.ManualStep.UNFINISHED);
        assertThat(sentTo(admin)).hasSize(1);
    }

    @Test
    void shouldFindAMovedIssueInsteadOfMovingItAgainWhenGitHubsAnswerWasLost() {
        ProductFeedbackDestination before =
                destinationRows.findById(ProductFeedbackDestination.SINGLETON).orElse(null);
        @Nullable String previousPrivate = before == null ? null : before.getPrivateRepository();
        ProductFeedbackDestination destination = before == null ? new ProductFeedbackDestination() : before;
        destination.setPrivateRepository(PRIVATE_REPOSITORY.fullName());
        destinationRows.save(destination);
        try {
            when(issues.repository(PRIVATE_REPOSITORY.fullName())).thenReturn(Optional.of(PRIVATE_REPOSITORY));
            ProductFeedbackCleanup record = record(true, 40L, "I_40", Instant.now());
            when(updates.transfer(any(), eq(PRIVATE_REPOSITORY)))
                    .thenThrow(new ProductFeedbackIssues.Unknown("read timed out", null));
            sweep();

            when(issues.findByMarker(
                            eq(PRIVATE_REPOSITORY), eq(record.getMarker().toString()), any()))
                    .thenReturn(Optional.of(new ProductFeedbackIssues.Issue(
                            77, "I_77", "https://github.com/acme/feedback-private/issues/77")));
            ActionTestSupport.due(jdbc, "cleanup:" + record.getId());
            executor.runDue(); // a reread waits as an executing action, which a drain does not count
            sweep();

            verify(updates, times(1)).transfer(any(), eq(PRIVATE_REPOSITORY));
            verify(updates).edit(eq(PRIVATE_REPOSITORY), eq(77L), eq(CleanupService.ERASED), anyString());
            assertThat(cleanups.findById(record.getId()).orElseThrow().getManualStep())
                    .isEqualTo(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        } finally {
            if (before == null) {
                destinationRows.deleteById(ProductFeedbackDestination.SINGLETON);
            } else {
                destination.setPrivateRepository(previousPrivate);
                destinationRows.save(destination);
            }
        }
    }

    @Test
    void shouldMoveAPublicIssueToThePrivateRepositoryWhenTheReporterMakesItPrivate() {
        Account reporter = account(Account.AppRole.USER);
        UUID report = makePrivateSetUp(reporter);
        when(updates.transfer(any(), eq(PRIVATE_REPOSITORY)))
                .thenReturn(new ProductFeedbackIssues.Issue(
                        88, "I_88", "https://github.com/acme/feedback-private/issues/88"));
        try {
            myReports.makePrivate(Objects.requireNonNull(reporter.getId()), report);

            var moved = filings.findById(report).orElseThrow();
            assertThat(moved.getRoute()).isEqualTo(FilingRoute.PRIVATE);
            assertThat(moved.getRepository()).isEqualTo(PRIVATE_REPOSITORY.fullName());
            assertThat(moved.getIssueNumber()).isEqualTo(88L);
        } finally {
            privateRepository(null);
        }
    }

    @Test
    void shouldFinishAMoveWhoseAnswerWasLostWithoutMovingTheIssueAgain() {
        Account reporter = account(Account.AppRole.USER);
        UUID report = makePrivateSetUp(reporter);
        when(updates.transfer(any(), eq(PRIVATE_REPOSITORY)))
                .thenThrow(new ProductFeedbackIssues.Unknown("read timed out", null));
        try {
            assertThatThrownBy(() -> myReports.makePrivate(Objects.requireNonNull(reporter.getId()), report))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            unclear -> assertThat(unclear.getReason()).contains("did not confirm the move"));
            assertThat(filings.findById(report).orElseThrow().getRoute()).isEqualTo(FilingRoute.PUBLIC);

            when(issues.findByMarker(eq(PRIVATE_REPOSITORY), eq(report.toString()), any()))
                    .thenReturn(Optional.of(new ProductFeedbackIssues.Issue(
                            88, "I_88", "https://github.com/acme/feedback-private/issues/88")));
            ActionTestSupport.due(jdbc, "file_issue:" + report);
            executor.runDue();

            verify(updates, times(1)).transfer(any(), eq(PRIVATE_REPOSITORY));
            var moved = filings.findById(report).orElseThrow();
            assertThat(moved.getRoute()).isEqualTo(FilingRoute.PRIVATE);
            assertThat(moved.getIssueNumber()).isEqualTo(88L);
        } finally {
            privateRepository(null);
        }
    }

    @Test
    void shouldFindAPublishedReportsMovedIssueByItsPromotionMarkerNotItsClosedPrivateOne() {
        Account reporter = account(Account.AppRole.USER);
        UUID report = makePrivateSetUp(reporter);
        // Published from a private issue (#5), which stays closed in the private repository with the report's marker.
        jdbc.update("UPDATE product_feedback_filing SET predecessor_issue_number = 5 WHERE feedback_id = ?", report);
        when(updates.transfer(any(), eq(PRIVATE_REPOSITORY)))
                .thenThrow(new ProductFeedbackIssues.Unknown("read timed out", null));
        try {
            assertThatThrownBy(() -> myReports.makePrivate(Objects.requireNonNull(reporter.getId()), report))
                    .isInstanceOf(ResponseStatusException.class);
            when(issues.findByMarker(eq(PRIVATE_REPOSITORY), eq(report.toString()), any()))
                    .thenReturn(Optional.of(new ProductFeedbackIssues.Issue(
                            5, "I_5", "https://github.com/acme/feedback-private/issues/5")));
            when(issues.findByMarker(
                            eq(PRIVATE_REPOSITORY),
                            eq(IssueRenderer.promotionMarker(report).toString()),
                            any()))
                    .thenReturn(Optional.of(new ProductFeedbackIssues.Issue(
                            88, "I_88", "https://github.com/acme/feedback-private/issues/88")));
            ActionTestSupport.due(jdbc, "file_issue:" + report);
            executor.runDue();

            assertThat(filings.findById(report).orElseThrow().getIssueNumber())
                    .as("the moved public issue, never the closed private one")
                    .isEqualTo(88L);
        } finally {
            privateRepository(null);
        }
    }

    /** A reporter's filed public issue, with a private repository to move it to. */
    private UUID makePrivateSetUp(Account reporter) {
        UUID report = filedPublicReport(reporter);
        jdbc.update(
                "UPDATE product_feedback_filing SET issue_url = 'https://github.com/acme/feedback/issues/77'"
                        + " WHERE feedback_id = ?",
                report);
        when(issues.repository("acme/feedback"))
                .thenReturn(
                        Optional.of(new ProductFeedbackIssues.Repository(40, "R_public", "acme/feedback", 7, false)));
        when(issues.repository(PRIVATE_REPOSITORY.fullName())).thenReturn(Optional.of(PRIVATE_REPOSITORY));
        privateRepository(PRIVATE_REPOSITORY.fullName());
        return report;
    }

    private void privateRepository(@Nullable String name) {
        ProductFeedbackDestination destination = destinationRows
                .findById(ProductFeedbackDestination.SINGLETON)
                .orElseGet(ProductFeedbackDestination::new);
        destination.setPrivateRepository(name);
        destinationRows.save(destination);
    }

    @Test
    void shouldForgetTheCleanupWhenTheCreateNeverReachedGitHub() {
        ProductFeedbackCleanup record = record(false, null, null, Instant.now());
        when(issues.findByMarker(any(), anyString(), any())).thenReturn(Optional.empty());

        sweep();

        assertThat(cleanups.findById(record.getId())).isEmpty();
        verify(updates, never()).edit(any(), anyLong(), anyString(), anyString());
        assertThat(sentTo(admin)).isEmpty();
    }

    @Test
    void shouldHandThePublicHistoryToAMaintainerWhenNoPrivateRepositoryCanTakeTheIssue() {
        ProductFeedbackCleanup record = record(true, 30L, "I_30", Instant.now());

        sweep();

        // Scrubbed at once; its edit history stays public, so a maintainer gets it now, not after 30 days.
        verify(updates).edit(any(), eq(30L), eq(CleanupService.ERASED), anyString());
        verify(updates, never()).transfer(any(), any());
        ProductFeedbackCleanup stored = cleanups.findById(record.getId()).orElseThrow();
        assertThat(stored.getManualStep()).isEqualTo(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        assertThat(sentTo(admin)).hasSize(1);
    }

    @Test
    void shouldDeleteTheLocatorWhenAnAdminConfirmsAWaitingStep() {
        ProductFeedbackCleanup waiting = record(false, 5L, "I_5", Instant.now());
        waiting.setManualStep(ProductFeedbackCleanup.ManualStep.EDIT_HISTORY);
        waiting.setManualSince(Instant.now());
        cleanups.save(waiting);
        ProductFeedbackCleanup automatic = record(false, 6L, "I_6", Instant.now());
        Account member = account(Account.AppRole.USER);

        confirm(waiting.getId(), member).expectStatus().isForbidden().expectBody(Void.class);
        assertThat(cleanups.findById(waiting.getId())).isPresent();

        confirm(automatic.getId(), admin).expectStatus().isNotFound().expectBody(Void.class);
        assertThat(cleanups.findById(automatic.getId())).isPresent();

        confirm(waiting.getId(), admin).expectStatus().isNoContent().expectBody(Void.class);
        assertThat(cleanups.findById(waiting.getId())).isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT acting_account_id FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_CLEANUP_CONFIRMED'"
                                + " AND CAST(details AS text) LIKE ?",
                        Long.class,
                        "%" + waiting.getId() + "%"))
                .containsExactly(admin.getId());
        subscribed(member, false);
    }

    @Test
    void shouldKeepTheIssueLocatorWhenTheReporterIsErased() {
        Account reporter = account(Account.AppRole.USER);
        long reporterId = Objects.requireNonNull(reporter.getId());
        UUID reportId = filedPublicReport(reporter);

        erasure.eraseAccount(reporterId);

        assertThat(filings.findById(reportId)).isEmpty();
        ProductFeedbackCleanup kept = cleanups.findAll().stream()
                .filter(row -> row.getMarker().equals(reportId))
                .findFirst()
                .orElseThrow();
        createdCleanups.add(kept.getId());
        assertThat(kept.getRepository()).isEqualTo("acme/feedback");
        assertThat(kept.getIssueNumber()).isEqualTo(77L);
        assertThat(kept.isPublicIssue()).isTrue();
        assertThat(kept.getManualStep()).isNull();
        subscribed(reporter, false);
    }

    /** A report filed as a public issue in acme/feedback, as GitHub filing leaves it. */
    private UUID filedPublicReport(Account reporter) {
        var receipt = feedbackService.add(
                new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "public-report-content", null, null, null),
                Objects.requireNonNull(reporter.getId()),
                null,
                false);
        ProductFeedbackFiling filing = filings.findById(receipt.id()).orElseThrow();
        ReflectionTestUtils.setField(filing, "state", FilingState.FILED);
        filing.setRoute(FilingRoute.PUBLIC);
        filing.setRepository("acme/feedback");
        filing.setRepositoryId(40L);
        filing.setInstallationId(7L);
        filing.setIssueNumber(77L);
        filing.setIssueNodeId("I_77");
        filing.setFirstAttemptAt(Instant.now().minusSeconds(600));
        filings.save(filing);
        return receipt.id();
    }

    @Test
    void shouldAnswerARetryOfAStoppedReportWithNoContent() {
        var receipt = feedbackService.add(
                new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "stopped-report-content", null, null, null),
                Objects.requireNonNull(account(Account.AppRole.USER).getId()),
                null,
                false);
        ProductFeedbackFiling filing = filings.findById(receipt.id()).orElseThrow();
        ReflectionTestUtils.setField(filing, "state", FilingState.STOPPED);
        filings.save(filing);

        retry(receipt.id(), account(Account.AppRole.USER))
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        retry(UUID.randomUUID(), admin).expectStatus().isNotFound().expectBody(Void.class);
        retry(receipt.id(), admin).expectStatus().isNoContent().expectBody().isEmpty();

        assertThat(filings.findById(receipt.id()).orElseThrow().getState()).isNotEqualTo(FilingState.STOPPED);
    }

    private WebTestClient.ResponseSpec retry(UUID id, Account actor) {
        return webTestClient
                .post()
                .uri("/admin/product-feedback/" + id + "/retry")
                .headers(as(actor))
                .exchange();
    }

    private WebTestClient.ResponseSpec confirm(UUID id, Account actor) {
        return webTestClient
                .post()
                .uri("/admin/product-feedback/cleanups/" + id + "/confirm")
                .headers(as(actor))
                .exchange();
    }

    /** A due record, first in the claim order, that this test removes again. */
    private ProductFeedbackCleanup record(
            boolean publicIssue, @Nullable Long issueNumber, @Nullable String nodeId, Instant createdAt) {
        var record = new ProductFeedbackCleanup(
                PRIVATE_REPOSITORY.fullName(),
                PRIVATE_REPOSITORY.id(),
                PRIVATE_REPOSITORY.installationId(),
                UUID.randomUUID(),
                issueNumber,
                publicIssue,
                createdAt.minusSeconds(60),
                createdAt);
        record.setIssueNodeId(nodeId);
        record = cleanups.save(record);
        queue.cleanup(record.getId(), Duration.ZERO, createdAt.plus(CleanupService.MANUAL_AFTER));
        createdCleanups.add(record.getId());
        return record;
    }

    private Account account(Account.AppRole role) {
        Account account = new Account("Cleanup test");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account.setAppRole(role);
        account = accounts.save(account);
        subscribed(account, true);
        return account;
    }

    private void subscribed(Account account, boolean enabled) {
        long id = Objects.requireNonNull(account.getId());
        var current = subscriptions.get(id);
        subscriptions.update(
                id,
                new UpdateNotificationPreferencesDTO(enabled, false, false, null),
                EntityTagPrecondition.parse(current.etag()),
                true);
    }

    /** Another class in the database may leave Silent Mode on, which withholds every email. */
    private void setSilentMode(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged) {
            settings.updateSilentMode(
                    engaged,
                    engaged ? "cleanup test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    private static Consumer<HttpHeaders> as(Account account) {
        String prefix = account.getAppRole() == Account.AppRole.APP_ADMIN ? "mock-jwt-sub-" : "mock-jwt-member-";
        return headers -> headers.setBearerAuth(prefix + account.getId());
    }

    /** The messages sent to the account once the outbox has carried out every due email. */
    private List<MimeMessage> sentTo(Account account) {
        ActionTestSupport.drain(executor, jdbc);
        return mail.sent().stream()
                .filter(message -> Objects.equals(account.getPrimaryEmail(), recipient(message)))
                .toList();
    }

    private static String recipient(MimeMessage message) {
        try {
            return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress();
        } catch (MessagingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String body(MimeMessage message) throws IOException, MessagingException {
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
