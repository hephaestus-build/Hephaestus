package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class WorkspaceOnboardingLifecycle implements AccountErasureContributor, WorkspacePurgeContributor {
    private final WorkspaceOnboardingSettingsRepository settings;
    private final WorkspaceMemberOnboardingRepository members;

    @Override
    public void eraseAccount(long accountId) {
        members.deleteByAccountId(accountId);
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        members.deleteByWorkspaceId(workspaceId);
        settings.deleteByWorkspaceId(workspaceId);
    }
}
