package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.integration.core.email.SmtpEmailGateway;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class WorkspaceAccessNotificationsIntegrationTest extends BaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private WorkspaceAccessRequestRepository requests;

    @Autowired
    private WorkspaceAccessNotificationRepository outbox;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceAccessNotifications transactionalNotifications;

    private final SmtpEmailGateway email = mock(SmtpEmailGateway.class);
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);

    @Test
    void shouldKeepOneOutboxRowAndOneSendWhenSchedulingAndDeliveringTwice() {
        var request = request("notification-once");
        var service = service();
        service.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        service.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        var notification = onlyNotification(request);
        when(email.send(anyString(), anyString(), anyString())).thenReturn(SmtpEmailGateway.Result.SENT);
        service.deliver(request.getWorkspace().getId(), notification.getId());
        service.deliver(request.getWorkspace().getId(), notification.getId());
        assertThat(notification.getState()).isEqualTo(WorkspaceAccessNotification.State.SENT);
        assertThat(notification.getAttempts()).isEqualTo(1);
        var body = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("admins@example.test"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("https://hephaestus.example.test/w/notification-once/admin/access")
                .doesNotContain("Private application details");
    }

    @Test
    void shouldRecordSuppressionWithoutClaimingDeliveryOrConsumingAnAttempt() {
        var request = request("notification-silent");
        var service = service();
        service.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        var notification = onlyNotification(request);
        when(email.send(anyString(), anyString(), anyString())).thenReturn(SmtpEmailGateway.Result.SUPPRESSED);
        service.deliver(request.getWorkspace().getId(), notification.getId());
        assertThat(notification.getState()).isEqualTo(WorkspaceAccessNotification.State.PENDING);
        assertThat(notification.getReason()).isEqualTo(WorkspaceAccessNotification.Reason.SILENT_MODE);
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getNextAttemptAt()).isAfter(NOW);
    }

    @Test
    void shouldCancelStaleNotificationsBeforeResolvingARecipientOrSending() {
        var request = request("notification-stale");
        var service = service();
        service.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        var notification = onlyNotification(request);
        request.setStatus(WorkspaceAccessRequest.Status.CANCELLED);
        service.deliver(request.getWorkspace().getId(), notification.getId());
        assertThat(notification.getState()).isEqualTo(WorkspaceAccessNotification.State.CANCELLED);
        assertThat(notification.getReason()).isEqualTo(WorkspaceAccessNotification.Reason.REQUEST_REPLACED);
        verifyNoInteractions(email, contacts);
    }

    @Test
    void shouldSendRenewalLinkToVerifiedApplicantWithoutPrivateComments() {
        var request = request("notification-renew");
        request.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(request.getWorkspace());
        membership.setAccountId(request.getAccountId());
        membership.setSource(WorkspaceAccountMembership.Source.REQUEST);
        membership.setAccessRequestId(request.getId());
        membership.setExpiresAt(NOW.plusSeconds(86400));
        memberships.saveAndFlush(membership);
        var service = service();
        service.enqueue(request, WorkspaceAccessNotification.Kind.REMINDER);
        var notification = onlyNotification(request);
        when(contacts.verifiedEmail(request.getAccountId())).thenReturn(Optional.of("applicant@example.test"));
        when(email.send(anyString(), anyString(), anyString())).thenReturn(SmtpEmailGateway.Result.SENT);
        service.deliver(request.getWorkspace().getId(), notification.getId());
        var body = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("applicant@example.test"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("/w/notification-renew/request-access?renew=true")
                .doesNotContain("Private application details", "admins@example.test");
    }

    @Test
    void shouldRollBackTheNotificationWithItsApplicationTransaction() {
        var request = request("notification-rollback");
        transactionalNotifications.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        var notificationId = onlyNotification(request).getId();
        var requestId = request.getId();
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(requests.findById(requestId)).isEmpty();
        assertThat(outbox.findById(notificationId)).isEmpty();
    }

    @Test
    void shouldKeepFailureAndBackoffVisibleWithoutClaimingDelivery() {
        var request = request("notification-failure");
        var service = service();
        service.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        var notification = onlyNotification(request);
        when(email.send(anyString(), anyString(), anyString())).thenReturn(SmtpEmailGateway.Result.FAILED);
        service.deliver(request.getWorkspace().getId(), notification.getId());
        assertThat(notification.getState()).isEqualTo(WorkspaceAccessNotification.State.PENDING);
        assertThat(notification.getReason()).isEqualTo(WorkspaceAccessNotification.Reason.DELIVERY_FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private WorkspaceAccessNotifications service() {
        return new WorkspaceAccessNotifications(
                outbox,
                memberships,
                contacts,
                email,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://hephaestus.example.test");
    }

    private WorkspaceAccessNotification onlyNotification(WorkspaceAccessRequest request) {
        var rows = outbox.findByWorkspace_IdAndRequest_IdOrderById(
                request.getWorkspace().getId(), request.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private WorkspaceAccessRequest request(String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName("Workspace");
        workspace.setAccountLogin(slug);
        workspace.setAccountType(de.tum.cit.aet.hephaestus.workspace.AccountType.ORG);
        workspaces.saveAndFlush(workspace);
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Applicant")).getId());
        var request = new WorkspaceAccessRequest();
        request.setWorkspace(workspace);
        request.setAccountId(accountId);
        request.setSubmittedAt(NOW);
        request.setPolicySnapshot(new WorkspaceAccessPolicySettings(
                "github",
                "Code of conduct",
                "I agree",
                List.of(),
                List.of(),
                1L,
                List.of(),
                90,
                14,
                "admins@example.test",
                null));
        request.setSubmission(new WorkspaceAccessRequest.Submission(
                new WorkspaceAccessDetails(accountId, List.of(), NOW.plusSeconds(86400)),
                List.of(),
                List.of(),
                "Private application details"));
        return requests.saveAndFlush(request);
    }
}
