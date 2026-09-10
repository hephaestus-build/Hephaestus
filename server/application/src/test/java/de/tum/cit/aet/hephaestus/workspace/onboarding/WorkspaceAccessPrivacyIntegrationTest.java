package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAccessRetentionParticipant;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class WorkspaceAccessPrivacyIntegrationTest extends BaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private WorkspaceAccessErasure erasure;

    @Autowired
    private WorkspaceAccessExportAdapter export;

    @Autowired
    private WorkspaceAccessRequestRepository requests;

    @Autowired
    private WorkspaceAccessNotificationRepository notifications;

    @Autowired
    private WorkspaceAccessPolicyRepository policies;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void accountErasureRemovesSubmissionsAndMailButPreservesOtherApplicantsAndWorkspacePolicy() {
        var workspace = workspace("erase-access");
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Applicant")).getId());
        var otherId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Other applicant")).getId());
        var own = request(workspace, accountId);
        var renewal = request(workspace, accountId);
        renewal.setSupersedesRequestId(own.getId());
        requests.saveAndFlush(renewal);
        var retained = request(workspace, otherId);
        retained.setDecidedByAccountId(accountId);
        requests.saveAndFlush(retained);
        membership(workspace, accountId, own.getId());
        notification(own);
        notification(retained);
        var policy = new WorkspaceAccessPolicy();
        policy.setWorkspace(workspace);
        policy.setSettings(settings());
        policies.saveAndFlush(policy);

        erasure.eraseAccount(accountId);
        entityManager.flush();
        entityManager.clear();

        assertThat(requests.findById(own.getId())).isEmpty();
        assertThat(requests.findById(renewal.getId())).isEmpty();
        assertThat(requests.findById(retained.getId()))
                .get()
                .satisfies(r -> assertThat(r.getDecidedByAccountId()).isNull());
        assertThat(notifications.findByWorkspace_IdAndRequest_IdOrderById(workspace.getId(), own.getId()))
                .isEmpty();
        assertThat(notifications.findByWorkspace_IdAndRequest_IdOrderById(workspace.getId(), retained.getId()))
                .hasSize(1);
        assertThat(policies.findByWorkspace_Id(workspace.getId())).isPresent();
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .get()
                .satisfies(m -> assertThat(m.getAccessRequestId()).isNull());
    }

    @Test
    void exportDisclosesOnlyOwnSubmittedDataAndAcknowledgedNotices() {
        var workspace = workspace("export-access");
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Applicant")).getId());
        var otherId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Other applicant")).getId());
        request(workspace, accountId);
        request(workspace, otherId);

        assertThat(export.submissionsForAccount(accountId)).singleElement().satisfies(submitted -> {
            assertThat(submitted.workspaceSlug()).isEqualTo(workspace.getWorkspaceSlug());
            assertThat(submitted.comments()).isEqualTo("My application");
            assertThat(submitted.requestedExpiry()).isEqualTo(NOW.plusSeconds(86400));
            assertThat(submitted.acknowledgedIntroduction()).isEqualTo("Code of conduct");
            assertThat(submitted.acknowledgedNotices())
                    .singleElement()
                    .satisfies(notice -> assertThat(notice.key()).isEqualTo("ai"));
        });
    }

    @Test
    void workspacePurgeLeavesAnotherWorkspacesSubmissionsIntact() {
        var first = workspace("purge-access");
        var second = workspace("keep-access");
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Applicant")).getId());
        var own = request(first, accountId);
        var retained = request(second, accountId);
        membership(first, accountId, own.getId());
        notification(own);
        notification(retained);
        erasure.deleteWorkspaceData(first.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(requests.findById(own.getId())).isEmpty();
        assertThat(requests.findById(retained.getId())).isPresent();
        assertThat(notifications.findByWorkspace_IdAndRequest_IdOrderById(second.getId(), retained.getId()))
                .hasSize(1);
    }

    @Test
    void retentionErasesOnlyCompletedExpiredChainsAndKeepsSharedAccounts() {
        var workspace = workspace("retain-access");
        var otherWorkspace = workspace("retain-other");
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Expired applicant")).getId());
        var expired = request(workspace, accountId);
        expired.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        expired.setApprovedDetails(expired.getSubmission().details());
        requests.saveAndFlush(expired);
        membership(workspace, accountId, expired.getId());
        notification(expired);
        var elsewhere = request(otherWorkspace, accountId);
        var renewal = request(workspace, accountId);
        renewal.setSupersedesRequestId(expired.getId());
        requests.saveAndFlush(renewal);
        var later = Clock.fixed(NOW.plusSeconds(86400L * 100), ZoneOffset.UTC);
        var retention =
                new WorkspaceAccessRetention(workspaces, memberships, requests, notifications, List.of(), later);

        retention.eraseDue(workspace.getId());
        assertThat(requests.findById(expired.getId())).isPresent();
        renewal.setStatus(WorkspaceAccessRequest.Status.CANCELLED);
        renewal.setDecidedAt(NOW);
        requests.saveAndFlush(renewal);
        var guarded = new WorkspaceAccessRetention(
                workspaces,
                memberships,
                requests,
                notifications,
                List.of(mock(WorkspaceAccessRetentionParticipant.class)),
                later);
        guarded.eraseDue(workspace.getId());
        assertThat(requests.findById(expired.getId())).isPresent();

        retention.eraseDue(workspace.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(requests.findById(expired.getId())).isEmpty();
        assertThat(requests.findById(renewal.getId())).isEmpty();
        assertThat(requests.findById(elsewhere.getId())).isPresent();
        assertThat(accounts.findById(accountId)).isPresent();
        assertThat(notifications.findByWorkspace_IdAndRequest_IdOrderById(workspace.getId(), expired.getId()))
                .isEmpty();
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .get()
                .satisfies(m -> assertThat(m.getAccessRequestId()).isNull());
    }

    private Workspace workspace(String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(de.tum.cit.aet.hephaestus.workspace.AccountType.ORG);
        return workspaces.saveAndFlush(workspace);
    }

    private WorkspaceAccessRequest request(Workspace workspace, Long accountId) {
        var request = new WorkspaceAccessRequest();
        request.setWorkspace(workspace);
        request.setAccountId(accountId);
        request.setSubmittedAt(NOW);
        request.setPolicySnapshot(settings());
        request.setSubmission(new WorkspaceAccessRequest.Submission(
                new WorkspaceAccessDetails(accountId, List.of(1L), NOW.plusSeconds(86400)),
                List.of(),
                List.of("ai"),
                "My application"));
        return requests.saveAndFlush(request);
    }

    private void membership(Workspace workspace, Long accountId, Long requestId) {
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        membership.setSource(WorkspaceAccountMembership.Source.REQUEST);
        membership.setAccessRequestId(requestId);
        membership.setExpiresAt(NOW.plusSeconds(86400));
        memberships.saveAndFlush(membership);
    }

    private void notification(WorkspaceAccessRequest request) {
        var notification = new WorkspaceAccessNotification();
        notification.setWorkspace(request.getWorkspace());
        notification.setRequest(request);
        notification.setKind(WorkspaceAccessNotification.Kind.SUBMITTED);
        notification.setNextAttemptAt(NOW);
        notifications.saveAndFlush(notification);
    }

    private static WorkspaceAccessPolicySettings settings() {
        return new WorkspaceAccessPolicySettings(
                "github",
                "Code of conduct",
                "I agree",
                List.of(),
                List.of(
                        new WorkspaceAccessPolicySettings.PolicyNoticeDTO("ai", "AI notice", "Notice text"),
                        new WorkspaceAccessPolicySettings.PolicyNoticeDTO("other", "Other notice", "Not acknowledged")),
                1L,
                List.of(1L),
                90,
                14,
                "private-admin@example.test",
                30);
    }
}
