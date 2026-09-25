package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAiChoiceExport;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkspaceOnboardingLifecycleIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceMemberOnboardingRepository members;

    @Autowired
    private AccountAiChoiceRepository choices;

    @Autowired
    private WorkspaceOnboardingSettingsRepository settings;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountAiChoiceExport exports;

    @Autowired
    private WorkspaceOnboardingLifecycle lifecycle;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldExportOnlyOwnChoiceAndEraseOnlyTheRequestedAccountOrWorkspace() {
        var first =
                createWorkspace("privacy-first", "First", "first", AccountType.ORG, persistUser("privacy-owner-one"));
        var second = createWorkspace(
                "privacy-second", "Second", "second", AccountType.ORG, persistUser("privacy-owner-two"));
        long account = Objects.requireNonNull(
                accounts.save(new Account("Export subject")).getId());
        long other = Objects.requireNonNull(
                accounts.save(new Account("Other member")).getId());
        chose(account, MemberAiChoice.NO_AI);
        chose(other, MemberAiChoice.IN_HOUSE_ONLY);
        seen(first, account);
        seen(second, account);
        seen(first, other);
        seen(second, other);
        var policy = new WorkspaceOnboardingSettings();
        policy.setWorkspace(first);
        settings.save(policy);

        var exported = exports.choice(account);
        assertThat(exported).isNotNull();
        assertThat(exported.aiChoice()).isEqualTo("NO_AI");

        new TransactionTemplate(transactions).executeWithoutResult(status -> lifecycle.eraseAccount(account));
        assertThat(exports.choice(account)).isNull();
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), account))
                .isEmpty();
        var otherExport = exports.choice(other);
        assertThat(otherExport).isNotNull();
        assertThat(otherExport.aiChoice()).isEqualTo("IN_HOUSE_ONLY");
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), other)).isPresent();
        assertThat(settings.findByWorkspaceId(first.getId())).isPresent();

        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> lifecycle.deleteWorkspaceData(first.getId()));
        assertThat(settings.findByWorkspaceId(first.getId())).isEmpty();
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), other)).isEmpty();
        assertThat(members.findByWorkspace_IdAndAccountId(second.getId(), other))
                .isPresent();
        // Purging a workspace never touches a member's account-level answer.
        assertThat(exports.choice(other)).isNotNull();
    }

    private void chose(long accountId, MemberAiChoice choice) {
        var row = new AccountAiChoice();
        row.setAccountId(accountId);
        row.setAiChoice(choice);
        row.setUpdatedAt(Instant.now());
        choices.save(row);
    }

    private void seen(Workspace workspace, long accountId) {
        var member = new WorkspaceMemberOnboarding();
        member.setWorkspace(workspace);
        member.setAccountId(accountId);
        member.setSeenRevision(0);
        member.setUpdatedAt(Instant.now());
        members.save(member);
    }
}
