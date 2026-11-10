package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitHubInstallationDecisionAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class GitHubInstallationDecisionAuditAdapter implements GitHubInstallationDecisionAudit {
    private final AuthEventLogger audit;

    public GitHubInstallationDecisionAuditAdapter(AuthEventLogger audit) {
        this.audit = audit;
    }

    @Override
    public boolean adopted(long actingAccountId, long installationId, long workspaceId) {
        return audit.event(AuthEvent.EventType.GITHUB_INSTALLATION_ADOPTED, AuthEvent.Result.SUCCESS)
                .account(actingAccountId)
                .actingAccount(actingAccountId)
                .workspace(workspaceId)
                .details("{\"installationId\":" + installationId + "}")
                .recordInCurrentTransaction();
    }

    @Override
    public boolean declined(long actingAccountId, long installationId) {
        return audit.event(AuthEvent.EventType.GITHUB_INSTALLATION_DECLINED, AuthEvent.Result.SUCCESS)
                .account(actingAccountId)
                .actingAccount(actingAccountId)
                .details("{\"installationId\":" + installationId + "}")
                .recordInCurrentTransaction();
    }
}
