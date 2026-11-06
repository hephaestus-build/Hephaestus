package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.EMAILED);
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
        queued.setState(FilingState.QUEUED);
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
