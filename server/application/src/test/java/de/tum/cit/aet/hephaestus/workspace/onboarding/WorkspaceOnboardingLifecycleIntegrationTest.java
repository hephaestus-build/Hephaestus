package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceAiExport;
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
    private WorkspaceOnboardingSettingsRepository settings;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountWorkspaceAiExport exports;

    @Autowired
    private WorkspaceOnboardingLifecycle lifecycle;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldExportOnlyOwnChoicesAndEraseOnlyTheRequestedAccountOrWorkspace() {
        var first =
                createWorkspace("privacy-first", "First", "first", AccountType.ORG, persistUser("privacy-owner-one"));
        var second = createWorkspace(
                "privacy-second", "Second", "second", AccountType.ORG, persistUser("privacy-owner-two"));
        long account = Objects.requireNonNull(
                accounts.save(new Account("Export subject")).getId());
        long other = Objects.requireNonNull(
                accounts.save(new Account("Other member")).getId());
        preference(first, account);
        preference(second, account);
        preference(first, other);
        preference(second, other);
        var policy = new WorkspaceOnboardingSettings();
        policy.setWorkspace(first);
        settings.save(policy);

        assertThat(exports.preferences(account))
                .extracting(AccountWorkspaceAiExport.Preference::workspaceSlug)
                .containsExactlyInAnyOrder(first.getWorkspaceSlug(), second.getWorkspaceSlug());
        assertThat(exports.preferences(account))
                .allSatisfy(preference -> assertThat(preference.aiChoice()).isEqualTo("NO_AI"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> lifecycle.eraseAccount(account));
        assertThat(exports.preferences(account)).isEmpty();
        assertThat(exports.preferences(other)).hasSize(2);
        assertThat(settings.findByWorkspaceId(first.getId())).isPresent();

        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> lifecycle.deleteWorkspaceData(first.getId()));
        assertThat(settings.findByWorkspaceId(first.getId())).isEmpty();
        assertThat(members.findByWorkspace_IdAndAccountId(first.getId(), other)).isEmpty();
        assertThat(members.findByWorkspace_IdAndAccountId(second.getId(), other))
                .isPresent();
    }

    private void preference(Workspace workspace, long accountId) {
        var member = new WorkspaceMemberOnboarding();
        member.setWorkspace(workspace);
        member.setAccountId(accountId);
        member.setAiChoice(MemberAiChoice.NO_AI);
        member.setUpdatedAt(Instant.now());
        members.save(member);
    }
}
