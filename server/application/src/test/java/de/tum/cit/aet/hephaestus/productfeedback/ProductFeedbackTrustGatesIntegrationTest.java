package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting;
import de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * What must hold when a destination or a worker does not behave: a public destination is proven public, and a report
 * never waits for a worker forever. Only the GitHub transport is controlled.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class ProductFeedbackTrustGatesIntegrationTest extends BaseIntegrationTest {
    private static final ProductFeedbackIssues.Repository PUBLIC_REPOSITORY =
            new ProductFeedbackIssues.Repository(51, "R_public", "acme/feedback-public", 7, false);
    private static final ProductFeedbackIssues.Repository PRIVATE_REPOSITORY =
            new ProductFeedbackIssues.Repository(52, "R_private", "acme/feedback-private", 7, true);

    @MockitoBean
    private ProductFeedbackIssues issues;

    // One adapter implements both interfaces, so replacing only one would leave a bean of the wrong type.
    @MockitoBean
    private ProductFeedbackIssueUpdates updates;

    @Autowired
    private DestinationService destinationService;

    @Autowired
    private RoutingService routing;

    @Autowired
    private FilingService filing;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private MyReportsService reports;

    @Autowired
    private FeedbackAccountErasureAdapter feedbackErasure;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ProductFeedbackDrafting drafting;

    @Autowired
    private ProductFeedbackDestinationRepository destinationRows;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private @Nullable String previousPublic;
    private @Nullable String previousPrivate;
    private @Nullable String previousSnapshot;
    private boolean previousPaused;
    private Account admin;

    @BeforeEach
    void setUp() {
        ProductFeedbackDestination destination = destination();
        previousPublic = destination.getPublicRepository();
        previousPrivate = destination.getPrivateRepository();
        previousSnapshot = destination.getSnapshot();
        previousPaused = destination.isPaused();
        destination.setPublicRepository(PUBLIC_REPOSITORY.fullName());
        destination.setPrivateRepository(PRIVATE_REPOSITORY.fullName());
        destination.setPaused(false);
        destinationRows.save(destination);
        when(issues.repository(PUBLIC_REPOSITORY.fullName())).thenReturn(Optional.of(PUBLIC_REPOSITORY));
        when(issues.repository(PRIVATE_REPOSITORY.fullName())).thenReturn(Optional.of(PRIVATE_REPOSITORY));
        Account account = new Account("Trust gate test");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        account.setAppRole(Account.AppRole.APP_ADMIN);
        admin = accounts.save(account);
    }

    @AfterEach
    void tearDown() {
        ProductFeedbackDestination destination = destination();
        destination.setPublicRepository(previousPublic);
        destination.setPrivateRepository(previousPrivate);
        destination.setSnapshot(previousSnapshot);
        destination.setPaused(previousPaused);
        destinationRows.save(destination);
    }

    private ProductFeedbackDestination destination() {
        return destinationRows
                .findById(ProductFeedbackDestination.SINGLETON)
                .orElseGet(ProductFeedbackDestination::new);
    }

    @Test
    void shouldRefuseAPrivateRepositoryWhenItIsNamedAsThePublicDestination() {
        var secret = new ProductFeedbackIssues.Repository(53, "R_secret", "acme/secret", 7, true);
        when(issues.repository(secret.fullName())).thenReturn(Optional.of(secret));

        assertThatThrownBy(() -> destinationService.update(
                        new UpdateProductFeedbackDestinationsDTO(
                                secret.fullName(), PRIVATE_REPOSITORY.fullName(), null, 50, false, false),
                        Objects.requireNonNull(admin.getId())))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(refused.getReason())
                            .isEqualTo("acme/secret is private. The public repository must be public.");
                });
        assertThat(destination().getPublicRepository()).isEqualTo(PUBLIC_REPOSITORY.fullName());
    }

    @Test
    void shouldDropTheIssueTitlesWhenThePublicRepositoryTurnsPrivate() {
        ProductFeedbackDestination destination = destination();
        destination.setSnapshot("7\tA title from when it was public\n");
        destination.setSnapshotAt(Instant.now());
        destinationRows.save(destination);
        when(issues.repository(PUBLIC_REPOSITORY.fullName()))
                .thenReturn(Optional.of(new ProductFeedbackIssues.Repository(
                        PUBLIC_REPOSITORY.id(),
                        PUBLIC_REPOSITORY.nodeId(),
                        PUBLIC_REPOSITORY.fullName(),
                        PUBLIC_REPOSITORY.installationId(),
                        true)));

        AopTestUtils.<DestinationService>getUltimateTargetObject(destinationService)
                .refreshSnapshot();

        assertThat(destination().getSnapshot()).isNull();
        assertThat(destination().getSnapshotAt()).isNull();
        verify(issues, never()).openIssues(any(), anyInt());
    }

    @Test
    void shouldRouteAReportWithoutADraftWhenNoWorkerTookItInTime() {
        UUID id = report();
        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));

        routing.routeAnswered();

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.getState()).isEqualTo(FilingState.QUEUED);
        assertThat(routed.getRoute()).isEqualTo(FilingRoute.PRIVATE);
        assertThat(routed.getReason()).isEqualTo(FilingReason.NO_DRAFT);
        assertThat(routed.getDraft()).isNotNull();
    }

    @Test
    void shouldRouteAReportWithoutADraftWhenItsWorkerStoppedAndNoneReplacedIt() {
        UUID id = report();
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'DRAFTING', lease_until = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.now().minus(DraftingService.EXPIRY.plusMinutes(1))),
                id);

        routing.routeAnswered();

        assertThat(filings.findById(id).orElseThrow().getReason()).isEqualTo(FilingReason.NO_DRAFT);
    }

    @Test
    void shouldGiveAFailedDraftMoreChancesBeforeFilingGoesOn() {
        UUID id = report();
        for (int attempt = 1; attempt < DraftingService.DRAFT_ATTEMPTS; attempt++) {
            jdbc.update(
                    "UPDATE product_feedback_filing SET state = 'DRAFTING', attempt = ? WHERE feedback_id = ?",
                    attempt,
                    id);

            drafting.fail(id, attempt, ProductFeedbackDrafting.Failure.PROVIDER_ERROR, 0, 0);

            assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
        }
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'DRAFTING', attempt = ? WHERE feedback_id = ?",
                DraftingService.DRAFT_ATTEMPTS,
                id);

        drafting.fail(id, DraftingService.DRAFT_ATTEMPTS, ProductFeedbackDrafting.Failure.PROVIDER_ERROR, 0, 0);

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
    }

    @Test
    void shouldNotWaitForADraftWhenNoModelIsThere() {
        UUID id = report();
        jdbc.update("UPDATE product_feedback_filing SET state = 'DRAFTING', attempt = 1 WHERE feedback_id = ?", id);

        drafting.fail(id, 1, ProductFeedbackDrafting.Failure.MODEL_UNAVAILABLE, 0, 0);

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
    }

    @Test
    void shouldDropALateAnswerWhenTheReportAlreadyMovedOn() {
        UUID id = report();
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'DRAFTING', attempt = 1, lease_until = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.now().minus(DraftingService.EXPIRY.plusMinutes(1))),
                id);
        routing.routeAnswered();

        drafting.complete(id, 1, "{\"disposition\":\"ISSUE\"}", 10, 10);

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.getState()).isEqualTo(FilingState.QUEUED);
        assertThat(routed.getAnswer()).isNull();
        assertThat(routed.getReason()).isEqualTo(FilingReason.NO_DRAFT);
    }

    @Test
    void shouldKeepAnExpiredReportForTheAdminsWhileFilingIsPaused() {
        UUID id = report();
        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));
        routing.routeAnswered();
        ProductFeedbackDestination destination = destination();
        destination.setPaused(true);
        destinationRows.save(destination);
        jdbc.update(
                "UPDATE product_feedback_filing SET next_attempt_at = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.EPOCH),
                id);
        FilingService target = AopTestUtils.getUltimateTargetObject(filing);
        ReflectionTestUtils.setField(target, "lastWrite", Instant.EPOCH);

        target.fileDue();

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.SAVED);
        verify(issues, never()).create(any(), anyString(), anyString(), any());
    }

    @Test
    void shouldKeepWaitingForAWorkerWhileTheReportIsYoung() {
        UUID id = report();

        routing.routeAnswered();

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
    }

    @Test
    void shouldLinkARelatedIssueInThePublicRepositoryWhenTheIssueIsPrivate() {
        UUID id = report();
        when(issues.isPrivate(any())).thenReturn(Optional.of(true));
        when(issues.issueTypes(any())).thenReturn(List.of());
        when(issues.create(any(), anyString(), anyString(), isNull()))
                .thenReturn(new ProductFeedbackIssues.Issue(
                        90, "I_90", "https://github.com/acme/feedback-private/issues/90"));
        ProductFeedbackFiling queued = filings.findById(id).orElseThrow();
        ReflectionTestUtils.setField(queued, "state", FilingState.QUEUED);
        queued.setRoute(FilingRoute.PRIVATE);
        queued.setReason(FilingReason.OPTED_OUT);
        queued.setDraft(routing.write(new Draft(
                Draft.Disposition.ISSUE,
                Draft.Type.BUG,
                "The list jumps while it loads",
                "The list jumps.",
                "",
                List.of(),
                "",
                "",
                List.of(),
                "",
                17L,
                false,
                false,
                true,
                Draft.Confidence.HIGH)));
        // The oldest row is claimed first, so rows of earlier tests cannot take this claim.
        queued.setNextAttemptAt(Instant.EPOCH);
        filings.save(queued);
        FilingService target = AopTestUtils.getUltimateTargetObject(filing);
        ReflectionTestUtils.setField(target, "lastWrite", Instant.EPOCH);

        target.fileDue();

        var body = ArgumentCaptor.forClass(String.class);
        verify(issues).create(eq(PRIVATE_REPOSITORY), anyString(), body.capture(), isNull());
        assertThat(body.getValue())
                .contains("https://github.com/acme/feedback-public/issues/17")
                .doesNotContain("acme/feedback-private/issues/17");
    }

    private UUID send(Account reporter, boolean share) {
        return feedbackService
                .add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.FEEDBACK, "The list jumps", null, null, share),
                        Objects.requireNonNull(reporter.getId()),
                        null,
                        false)
                .id();
    }

    /** A report saved for the admins, as it is after a time with no destination. */
    private UUID savedReport(boolean allowPublic) {
        UUID id = report();
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'SAVED', route = 'ADMINS', allow_public = ?,"
                        + " prompt_input = NULL WHERE feedback_id = ?",
                allowPublic,
                id);
        return id;
    }

    @Test
    void shouldStartASavedReportOverAndRecordWhoSentItWhenAnAdminFilesIt() {
        UUID id = savedReport(false);
        long admin = Objects.requireNonNull(this.admin.getId());

        assertThat(filing.fileSaved(id, admin)).isTrue();

        ProductFeedbackFiling started = filings.findById(id).orElseThrow();
        assertThat(started.getState()).isEqualTo(FilingState.DRAFT_QUEUED);
        assertThat(started.getRoute()).isNull();
        assertThat(started.getPromptInput()).isNotBlank();
        assertThat(started.isAllowPublic()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_FILED_BY_ADMIN'"
                                + " AND acting_account_id = ? AND details::text LIKE ?",
                        Long.class,
                        admin,
                        "%" + id + "%"))
                .isEqualTo(1);
    }

    @Test
    void shouldNotMakeAFiledReportPublicWithoutTheReportersChoice() {
        UUID id = savedReport(false);
        filing.fileSaved(id, Objects.requireNonNull(admin.getId()));
        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));

        routing.routeAnswered();

        assertThat(filings.findById(id).orElseThrow().getRoute()).isEqualTo(FilingRoute.PRIVATE);
    }

    @Test
    void shouldRefuseToFileWhenNoRepositoryCanTakeAPrivateReport() {
        UUID id = savedReport(false);
        ProductFeedbackDestination destination = destination();
        destination.setPrivateRepository(null);
        destinationRows.save(destination);
        long admin = Objects.requireNonNull(this.admin.getId());

        assertThat(filing.fileSaved(id, admin)).isFalse();

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.SAVED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_FILED_BY_ADMIN'"
                                + " AND details::text LIKE ?",
                        Long.class,
                        "%" + id + "%"))
                .isZero();
    }

    @Test
    void shouldNeverPublishByAnAdminsAction() {
        UUID id = savedReport(true);

        assertThat(filing.fileSaved(id, Objects.requireNonNull(admin.getId()))).isTrue();
        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));
        routing.routeAnswered();

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.isAllowPublic()).isFalse();
        assertThat(routed.getRoute()).isEqualTo(FilingRoute.PRIVATE);
    }

    @Test
    void shouldKeepTheReportersEditWhenAnAdminFilesTheReport() {
        UUID id = savedReport(true);
        jdbc.update("UPDATE product_feedback_filing SET prompt_input = 'The edited text' WHERE feedback_id = ?", id);

        filing.fileSaved(id, Objects.requireNonNull(admin.getId()));

        assertThat(filings.findById(id).orElseThrow().getPromptInput()).isEqualTo("The edited text");
    }

    @Test
    void shouldFileAPrivateIssueWhenTheReporterKeepsAHeldReportPrivate() {
        Account reporter = newReporter();
        UUID id = send(reporter, true);
        heldForPublic(id);

        reports.keepPrivate(Objects.requireNonNull(reporter.getId()), id);
        when(issues.isPrivate(any())).thenReturn(Optional.of(true));
        when(issues.issueTypes(any())).thenReturn(List.of());
        when(issues.create(any(), anyString(), anyString(), any()))
                .thenReturn(new ProductFeedbackIssues.Issue(
                        91, "I_91", "https://github.com/acme/feedback-private/issues/91"));
        jdbc.update(
                "UPDATE product_feedback_filing SET next_attempt_at = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.EPOCH),
                id);
        FilingService target = AopTestUtils.getUltimateTargetObject(filing);
        ReflectionTestUtils.setField(target, "lastWrite", Instant.EPOCH);
        target.fileDue();

        verify(issues).create(eq(PRIVATE_REPOSITORY), anyString(), anyString(), any());
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.FILED);
    }

    /** A report from before the composer named the model. */
    private UUID olderReport() {
        UUID id = savedReport(false);
        jdbc.update("UPDATE product_feedback_filing SET file_disclosed = false WHERE feedback_id = ?", id);
        return id;
    }

    private long filedAudits(UUID id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_FILED_BY_ADMIN'"
                        + " AND details::text LIKE ?",
                Long.class,
                "%" + id + "%"));
    }

    @Test
    void shouldMoveAnOlderReportToThePrivateRepositoryWithNoModelCall() {
        UUID id = olderReport();

        assertThat(filing.fileSaved(id, Objects.requireNonNull(admin.getId()))).isTrue();

        // ANSWERED never reaches a worker: only DRAFT_QUEUED and DRAFTING rows are claimed for a model.
        ProductFeedbackFiling moved = filings.findById(id).orElseThrow();
        assertThat(moved.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(moved.getPromptInput()).isNull();

        routing.routeAnswered();

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.getRoute()).isEqualTo(FilingRoute.PRIVATE);
        assertThat(routed.getRepository()).isEqualTo(PRIVATE_REPOSITORY.fullName());
        assertThat(routed.getDraftedAt()).isNull();
        assertThat(routed.isAllowPublic()).isFalse();
    }

    @Test
    void shouldFileTheReportersEditWhenNoDraftComes() throws Exception {
        UUID id = olderReport();
        jdbc.update("UPDATE product_feedback_filing SET prompt_input = 'The edited text' WHERE feedback_id = ?", id);
        filing.fileSaved(id, Objects.requireNonNull(admin.getId()));

        routing.routeAnswered();

        assertThat(filings.findById(id).orElseThrow().getDraft()).contains("The edited text");
    }

    @Test
    void shouldNeverHandAReportToAModelWhenItsAuthorWasNotTold() {
        UUID id = report();
        jdbc.update(
                "UPDATE product_feedback_filing SET file_disclosed = false, next_attempt_at = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.EPOCH),
                id);

        drafting.claim("test-worker");

        assertThat(filings.findById(id).orElseThrow().getAttempt()).isZero();
    }

    @Test
    void shouldMoveEverythingOnceWhenAMoveRunsTwice() {
        UUID first = olderReport();
        UUID second = savedReport(false);
        long admin = Objects.requireNonNull(this.admin.getId());

        assertThat(filing.fileAllSaved(admin)).isGreaterThanOrEqualTo(2);
        assertThat(filing.fileAllSaved(admin)).isZero();

        assertThat(filings.findById(first).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(filings.findById(second).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
        assertThat(filedAudits(first)).isEqualTo(1);
    }

    @Test
    void shouldResumeAnInterruptedMoveWithWhatIsLeft() {
        UUID done = savedReport(false);
        UUID left = savedReport(false);
        long admin = Objects.requireNonNull(this.admin.getId());
        filing.fileSaved(done, admin);

        filing.fileAllSaved(admin);

        assertThat(filings.findById(left).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
        assertThat(filedAudits(done)).isEqualTo(1);
    }

    @Test
    void shouldMoveNothingWhilePaused() {
        savedReport(false);
        ProductFeedbackDestination destination = destination();
        destination.setPaused(true);
        destinationRows.save(destination);

        assertThat(filing.fileAllSaved(Objects.requireNonNull(admin.getId()))).isZero();
    }

    @Test
    void shouldMarkANewReportAsDisclosed() {
        assertThat(filings.findById(report()).orElseThrow().isFileDisclosed()).isTrue();
    }

    @Test
    void shouldRefuseToFileAResolvedReport() {
        UUID id = savedReport(false);
        jdbc.update("UPDATE product_feedback SET resolved_at = now() WHERE id = ?", id);

        assertThat(filing.fileSaved(id, Objects.requireNonNull(admin.getId()))).isFalse();
    }

    @Test
    void shouldFileOnceWhenAnAdminCallsTwice() {
        UUID id = savedReport(false);
        long admin = Objects.requireNonNull(this.admin.getId());

        assertThat(filing.fileSaved(id, admin)).isTrue();
        assertThat(filing.fileSaved(id, admin)).isFalse();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_event WHERE event_type = 'PRODUCT_FEEDBACK_FILED_BY_ADMIN'"
                                + " AND details::text LIKE ?",
                        Long.class,
                        "%" + id + "%"))
                .isEqualTo(1);
    }

    @Test
    void shouldRefuseToFileWhileFilingIsPaused() {
        UUID id = savedReport(false);
        ProductFeedbackDestination destination = destination();
        destination.setPaused(true);
        destinationRows.save(destination);

        assertThat(filing.fileSaved(id, Objects.requireNonNull(admin.getId()))).isFalse();
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.SAVED);
    }

    @Test
    void shouldRefuseToFileAReportThatIsNotSavedForTheAdmins() {
        UUID id = report();

        assertThat(filing.fileSaved(id, Objects.requireNonNull(admin.getId()))).isFalse();
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
    }

    @Test
    void shouldOfferFilingOnlyForASavedReportWhileADestinationTakesIt() {
        UUID saved = savedReport(false);
        UUID stopped = savedReport(false);
        jdbc.update("UPDATE product_feedback_filing SET state = 'STOPPED' WHERE feedback_id = ?", stopped);

        var page = feedbackService
                .list(FeedbackDTOs.FeedbackFilter.OPEN, PageRequest.of(0, 500))
                .getContent();

        assertThat(page.stream()
                        .filter(item -> item.id().equals(saved))
                        .findFirst()
                        .orElseThrow()
                        .canFile())
                .isTrue();
        assertThat(page.stream()
                        .filter(item -> item.id().equals(stopped))
                        .findFirst()
                        .orElseThrow()
                        .canFile())
                .isFalse();
    }

    private UUID send(Account reporter, @Nullable Boolean share, @Nullable String page) {
        return feedbackService
                .add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.FEEDBACK, "The list jumps", page, null, share),
                        Objects.requireNonNull(reporter.getId()),
                        null,
                        false)
                .id();
    }

    private long consentsOf(Account reporter) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM product_feedback_consent WHERE account_id = ?", Long.class, reporter.getId()));
    }

    @Test
    void shouldKeepAFirstReportPrivateAndRecordNoChoiceWhenNothingWasTicked() {
        Account reporter = newReporter();

        UUID id = send(reporter, false, null);

        assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
        assertThat(consentsOf(reporter)).isZero();
    }

    @Test
    void shouldTakeAFirstReportWithoutAnyAnswerAsPrivate() {
        Account reporter = newReporter();

        UUID id = send(reporter, null, null);

        assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
        assertThat(consentsOf(reporter)).isZero();
    }

    @Test
    void shouldRecordTheChoiceWhenTheBoxIsTicked() {
        Account reporter = newReporter();

        UUID id = send(reporter, true, null);

        ProductFeedbackFiling filing = filings.findById(id).orElseThrow();
        assertThat(filing.isAllowPublic()).isTrue();
        assertThat(filing.getConsentId()).isNotNull();
        assertThat(consentsOf(reporter)).isEqualTo(1);
    }

    @Test
    void shouldLeaveTheRememberedChoiceAloneWhenOneReportIsUnticked() {
        Account reporter = newReporter();
        send(reporter, true, null);
        jdbc.update(
                "UPDATE product_feedback SET submission_minute = submission_minute - interval '1 hour'"
                        + " WHERE account_id = ?",
                reporter.getId());

        UUID id = send(reporter, false, null);

        assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
        assertThat(consentsOf(reporter)).isEqualTo(1);
        assertThat(reports.sharing(Objects.requireNonNull(reporter.getId())).allowPublic())
                .isTrue();
    }

    @Test
    void shouldNotReverseAWithdrawalWithoutANewTick() {
        Account reporter = newReporter();
        reports.updateSharing(
                Objects.requireNonNull(reporter.getId()), new FilingDTOs.UpdateProductFeedbackSharingDTO(false, false));

        UUID id = send(reporter, null, null);

        assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
        assertThat(consentsOf(reporter)).isEqualTo(1);
    }

    private void filedPrivately(UUID id) {
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'FILED', route = 'PRIVATE', issue_number = 12,"
                        + " issue_url = 'https://github.com/acme/feedback-private/issues/12' WHERE feedback_id = ?",
                id);
    }

    private void withNewNotice(Runnable body) {
        jdbc.update("UPDATE product_feedback_destination SET notice_version = notice_version + 1");
        try {
            body.run();
        } finally {
            jdbc.update("UPDATE product_feedback_destination SET notice_version = notice_version - 1");
        }
    }

    private int consentNotice(UUID id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT c.notice_version FROM product_feedback_consent c"
                        + " JOIN product_feedback_filing f ON f.consent_id = c.id WHERE f.feedback_id = ?",
                Integer.class,
                id));
    }

    @Test
    void shouldRecordTheChoiceWhenAReportSentUntickedIsAllowedLater() {
        Account reporter = newReporter();
        UUID id = send(reporter, false, null);
        filedPrivately(id);

        reports.allowPublic(Objects.requireNonNull(reporter.getId()), id, true);

        ProductFeedbackFiling filing = filings.findById(id).orElseThrow();
        assertThat(filing.isAllowPublic()).isTrue();
        assertThat(filing.getConsentId()).isNotNull();
        assertThat(consentNotice(id)).isEqualTo(destination().getNoticeVersion());
        assertThat(consentsOf(reporter)).isEqualTo(1);
    }

    @Test
    void shouldRecordANewChoiceWhenAReportIsAllowedUnderANewerNotice() {
        Account reporter = newReporter();
        UUID id = send(reporter, true, null);
        filedPrivately(id);

        withNewNotice(() -> {
            reports.allowPublic(Objects.requireNonNull(reporter.getId()), id, true);

            assertThat(consentNotice(id)).isEqualTo(destination().getNoticeVersion());
            assertThat(consentsOf(reporter)).isEqualTo(2);
        });
    }

    @Test
    void shouldReuseTheCurrentChoiceWhenAReportIsAllowedAgain() {
        Account reporter = newReporter();
        UUID id = send(reporter, true, null);
        filedPrivately(id);
        long account = Objects.requireNonNull(reporter.getId());
        UUID consent = filings.findById(id).orElseThrow().getConsentId();

        reports.allowPublic(account, id, false);
        reports.allowPublic(account, id, true);

        assertThat(filings.findById(id).orElseThrow().getConsentId()).isEqualTo(consent);
        assertThat(consentsOf(reporter)).isEqualTo(1);
    }

    @Test
    void shouldRecordANewChoiceWhenAReportIsAllowedAfterAWithdrawal() {
        Account reporter = newReporter();
        UUID id = send(reporter, true, null);
        filedPrivately(id);
        long account = Objects.requireNonNull(reporter.getId());
        UUID consent = filings.findById(id).orElseThrow().getConsentId();
        reports.updateSharing(account, new FilingDTOs.UpdateProductFeedbackSharingDTO(false, false));

        reports.allowPublic(account, id, true);

        assertThat(filings.findById(id).orElseThrow().getConsentId()).isNotEqualTo(consent);
        assertThat(consentsOf(reporter)).isEqualTo(3);
    }

    @Test
    void shouldRefuseToAllowAReportWhenThereIsNoPublicRepository() {
        Account reporter = newReporter();
        UUID id = send(reporter, false, null);
        filedPrivately(id);
        ProductFeedbackDestination destination = destination();
        destination.setPublicRepository(null);
        destinationRows.save(destination);

        assertThatThrownBy(() -> reports.allowPublic(Objects.requireNonNull(reporter.getId()), id, true))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
        assertThat(consentsOf(reporter)).isZero();
    }

    @Test
    void shouldKeepARememberedChoiceWhenAReportArrivesWithoutAnAnswer() {
        Account reporter = newReporter();
        send(reporter, true, null);
        jdbc.update(
                "UPDATE product_feedback SET submission_minute = submission_minute - interval '1 hour'"
                        + " WHERE account_id = ?",
                reporter.getId());

        UUID id = send(reporter, null, null);

        ProductFeedbackFiling filing = filings.findById(id).orElseThrow();
        assertThat(filing.isAllowPublic()).isTrue();
        assertThat(filing.getConsentId()).isNotNull();
        assertThat(consentsOf(reporter)).isEqualTo(1);
    }

    @Test
    void shouldNotCarryARememberedChoiceOverToANewerNotice() {
        Account reporter = newReporter();
        send(reporter, true, null);
        jdbc.update(
                "UPDATE product_feedback SET submission_minute = submission_minute - interval '1 hour'"
                        + " WHERE account_id = ?",
                reporter.getId());

        withNewNotice(() -> {
            UUID id = send(reporter, null, null);

            assertThat(filings.findById(id).orElseThrow().isAllowPublic()).isFalse();
            assertThat(consentsOf(reporter)).isEqualTo(1);
        });
    }

    @Test
    void shouldKeepAReportForTheAdminsEvenWhenTheHourlyGitHubLimitIsFull() {
        for (int i = 0; i < FilingService.HOURLY_LIMIT; i++)
            jdbc.update(
                    "UPDATE product_feedback_filing SET state = 'FILED', first_attempt_at = now() WHERE feedback_id = ?",
                    report());
        UUID id = report();
        jdbc.update("UPDATE product_feedback_filing SET state = 'QUEUED', route = 'ADMINS' WHERE feedback_id = ?", id);

        fileWhatIsDue(id);

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.SAVED);
    }

    @Test
    void shouldKeepTheClosedPrivateIssueWithinReachOfErasureAfterAPromotion() {
        Account reporter = newReporter();
        UUID id = send(reporter, true, null);
        filedPrivately(id);
        jdbc.update(
                "UPDATE product_feedback_filing SET repository = ?, repository_id = ?, installation_id = ?"
                        + " WHERE feedback_id = ?",
                PRIVATE_REPOSITORY.fullName(),
                PRIVATE_REPOSITORY.id(),
                PRIVATE_REPOSITORY.installationId(),
                id);
        ProductFeedbackFiling filing = filings.findById(id).orElseThrow();
        filing.keepPredecessor();
        filing.setRepository(PUBLIC_REPOSITORY.fullName());
        filing.setRepositoryId(PUBLIC_REPOSITORY.id());
        filing.setRoute(FilingRoute.PUBLIC);
        filing.setIssueNumber(44L);
        filings.save(filing);

        feedbackErasure.eraseAccount(Objects.requireNonNull(reporter.getId()));

        assertThat(jdbc.queryForList(
                        "SELECT issue_number FROM product_feedback_cleanup WHERE marker = ? ORDER BY issue_number",
                        Long.class,
                        id))
                .containsExactly(12L, 44L);
    }

    @Test
    void shouldKeepThePageOffEveryPublicRouteWhenNothingWasTicked() {
        UUID id = send(newReporter(), false, "/w/$workspaceSlug/activity");
        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));

        routing.routeAnswered();

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.getRoute()).isEqualTo(FilingRoute.PRIVATE);
        assertThat(routed.getRepository()).isEqualTo(PRIVATE_REPOSITORY.fullName());
    }

    @Test
    void shouldShowAPrivateIssueOnlyToAnInstanceAdmin() {
        Account reporter = newReporter();
        UUID id = send(reporter, false, null);
        filedPrivately(id);
        long account = Objects.requireNonNull(reporter.getId());

        assertThat(reports.mine(account, false).getFirst().issueUrl()).isNull();
        assertThat(reports.mine(account, true).getFirst().issueUrl())
                .isEqualTo("https://github.com/acme/feedback-private/issues/12");
    }

    private void heldForPublic(UUID id) {
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'HELD', route = 'PUBLIC', reason = 'NONE', draft = ?,"
                        + " repository = ?, repository_id = ?, repository_node_id = ?, installation_id = ?"
                        + " WHERE feedback_id = ?",
                routing.write(Fallback.of(ProductFeedback.Kind.BUG, "The list jumps")),
                PUBLIC_REPOSITORY.fullName(),
                PUBLIC_REPOSITORY.id(),
                PUBLIC_REPOSITORY.nodeId(),
                PUBLIC_REPOSITORY.installationId(),
                id);
    }

    private void fileWhatIsDue(UUID id) {
        jdbc.update(
                "UPDATE product_feedback_filing SET next_attempt_at = ?, filed_after = ?, lease_until = ?"
                        + " WHERE feedback_id = ?",
                Timestamp.from(Instant.EPOCH),
                Timestamp.from(Instant.EPOCH),
                Timestamp.from(Instant.EPOCH),
                id);
        FilingService target = AopTestUtils.getUltimateTargetObject(filing);
        ReflectionTestUtils.setField(target, "lastWrite", Instant.EPOCH);
        target.fileDue();
    }

    private double refusedMoves() {
        var counter = meters.find(ProductFeedbackMetrics.STEPS)
                .tags("step", "transition", "outcome", "refused")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private void destinationIs(boolean paused, boolean hasPublic) {
        ProductFeedbackDestination destination = destination();
        destination.setPaused(paused);
        destination.setPublicRepository(hasPublic ? PUBLIC_REPOSITORY.fullName() : null);
        destinationRows.save(destination);
    }

    /** A person's action that does not fit the state is refused: the row must simply stay as it was. */
    private static boolean done(Runnable action) {
        try {
            action.run();
            return true;
        } catch (ResponseStatusException refused) {
            return false;
        }
    }

    /** Real services, a seeded GitHub that refuses and loses answers, and the promises {@code FilingLifecycle} makes. */
    @Test
    void shouldKeepEveryPromiseThroughRandomEventSequences() {
        jdbc.update("DELETE FROM product_feedback");
        var random = new Random(20261107L);
        var written = new ArrayList<ProductFeedbackIssues.Repository>();
        var found = new ArrayList<ProductFeedbackIssues.Issue>();
        when(issues.isPrivate(any()))
                .thenAnswer(call -> Optional.of(
                        call.<ProductFeedbackIssues.Repository>getArgument(0).isPrivate()));
        when(issues.issueTypes(any())).thenReturn(List.of());
        when(issues.create(any(), anyString(), anyString(), any())).thenAnswer(call -> {
            int outcome = random.nextInt(4);
            if (outcome == 0) throw new ProductFeedbackIssues.Refused("refused");
            ProductFeedbackIssues.Repository repository = call.getArgument(0);
            var issue = new ProductFeedbackIssues.Issue(
                    written.size() + 1L,
                    "I_" + written.size(),
                    "https://github.com/" + repository.fullName() + "/issues/1");
            written.add(repository);
            found.add(issue);
            if (outcome == 1) throw new ProductFeedbackIssues.Unknown("answer lost", null);
            return issue;
        });
        when(issues.findByMarker(any(), anyString(), any()))
                .thenAnswer(call -> found.stream().findFirst());
        var statesSeen = EnumSet.noneOf(FilingState.class);
        double refusedBefore = refusedMoves();
        for (int sequence = 0; sequence < 40; sequence++) {
            written.clear();
            found.clear();
            destinationIs(false, true);
            Account reporter = newReporter();
            long account = Objects.requireNonNull(reporter.getId());
            boolean ticked = random.nextBoolean();
            UUID id = send(reporter, ticked, null);
            if (ticked) heldForPublic(id);
            boolean mayBePublic = ticked;
            boolean paused = false;
            boolean hasPublic = true;
            for (int step = 0; step < 14; step++) {
                switch (random.nextInt(8)) {
                    case 0 -> {
                        backdate("next_attempt_at", id, DraftingService.EXPIRY.plusMinutes(1));
                        routing.routeAnswered();
                    }
                    case 1 -> fileWhatIsDue(id);
                    case 2 -> {
                        // Once kept private, a report gets no public issue unless the person allows it again.
                        if (done(() -> reports.keepPrivate(account, id)) && written.isEmpty()) mayBePublic = false;
                    }
                    case 3 -> {
                        boolean allow = random.nextBoolean();
                        try {
                            reports.allowPublic(account, id, allow);
                            mayBePublic |= allow;
                        } catch (ResponseStatusException refused) {
                            assertThat(filings.findById(id)).isPresent();
                        }
                    }
                    case 4 -> {
                        paused = !paused;
                        destinationIs(paused, hasPublic);
                    }
                    case 5 -> {
                        hasPublic = !hasPublic;
                        destinationIs(paused, hasPublic);
                    }
                    case 6 -> filing.retry(id);
                    default -> done(() -> filing.fileSaved(id, Objects.requireNonNull(admin.getId())));
                }
                ProductFeedbackFiling row = filings.findById(id).orElseThrow();
                statesSeen.add(row.getState());
                String at = "sequence " + sequence + ", step " + step + ", state " + row.getState();
                assertThat(written).as("No report has two issues: %s", at).hasSizeLessThanOrEqualTo(1);
                if (!mayBePublic)
                    assertThat(written)
                            .as("No private report is public: %s", at)
                            .allMatch(ProductFeedbackIssues.Repository::isPrivate);
                if (row.getState() == FilingState.FILED) {
                    assertThat(row.getIssueNumber()).as(at).isNotNull();
                    assertThat(written).as(at).hasSize(1);
                }
            }
            feedbackErasure.eraseAccount(account);
            assertThat(filings.findById(id)).as("Erasure wins from any state").isEmpty();
        }
        assertThat(statesSeen)
                .as("The sequences reached every place a report can wait")
                .contains(
                        FilingState.HELD,
                        FilingState.QUEUED,
                        FilingState.FILED,
                        FilingState.SAVED,
                        FilingState.STOPPED);
        verify(issues, atLeastOnce()).findByMarker(any(), anyString(), any());
        assertThat(refusedMoves()).as("No sweeper hit a move the table refuses").isEqualTo(refusedBefore);
    }

    /** A saved report that may go public, waiting for its draft. */
    private UUID report() {
        return feedbackService
                .add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.FEEDBACK, "The list jumps", null, null, true),
                        // A new account for each report: an account can send one report per minute.
                        Objects.requireNonNull(newReporter().getId()),
                        null,
                        false)
                .id();
    }

    private Account newReporter() {
        Account account = new Account("Trust gate reporter");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        return accounts.save(account);
    }

    private void backdate(String column, UUID id, Duration age) {
        jdbc.update(
                "UPDATE product_feedback_filing SET " + column + " = ? WHERE feedback_id = ?",
                Timestamp.from(Instant.now().minus(age)),
                id);
    }
}
