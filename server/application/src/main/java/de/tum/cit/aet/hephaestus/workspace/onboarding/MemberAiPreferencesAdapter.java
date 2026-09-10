package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class MemberAiPreferencesAdapter implements MemberAiPreferences {
    private final WorkspaceOnboardingSettingsRepository settings;
    private final WorkspaceMemberOnboardingRepository members;
    private final AccountIdentityQuery identities;
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public Decision forDeveloper(long workspaceId, @Nullable Long developerId) {
        boolean required = settings.findByWorkspaceId(workspaceId)
                .map(WorkspaceOnboardingSettings::isAiChoiceRequired)
                .orElse(false);
        if (developerId == null) return unresolved(workspaceId, required);
        var account = users.findById(developerId)
                .flatMap(user -> identities.resolveActiveAccountId(
                        java.util.Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null));
        if (account.isEmpty()) return unresolved(workspaceId, required);
        var preference = members.findByWorkspace_IdAndAccountId(workspaceId, account.get());
        // A saved refusal remains binding even when an administrator turns off the welcome flow.
        if (preference.isPresent()) return new Decision(true, preference.get().getAiChoice());
        return new Decision(required, null);
    }

    private Decision unresolved(long workspaceId, boolean required) {
        // Unlinking an identity must not turn its saved location or refusal into the legacy default.
        return new Decision(required || members.existsByWorkspace_Id(workspaceId), null);
    }
}
