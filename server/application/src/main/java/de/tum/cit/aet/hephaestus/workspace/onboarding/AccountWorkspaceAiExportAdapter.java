package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceAiExport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AccountWorkspaceAiExportAdapter implements AccountWorkspaceAiExport {
    private final WorkspaceMemberOnboardingRepository members;

    @Override
    @Transactional(readOnly = true)
    public List<Preference> preferences(long accountId) {
        return members.findForAccountExport(accountId).stream()
                .map(member -> new Preference(
                        member.getWorkspace().getWorkspaceSlug(),
                        member.getAiChoice() == null
                                ? null
                                : member.getAiChoice().name(),
                        member.getUpdatedAt(),
                        member.getWelcomedAt(),
                        member.getCompletedAt()))
                .toList();
    }
}
