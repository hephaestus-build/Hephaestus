package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A developer's choice reaches a review through the account their provider identity is linked to.
 * A developer with no resolvable account (never signed in, or unlinked since) has not answered, so
 * only the workspace's own {@code aiChoiceRequired} keeps AI off their work.
 */
@Service
@RequiredArgsConstructor
class MemberAiPreferencesAdapter implements MemberAiPreferences {
    private final WorkspaceOnboardingSettingsRepository settings;
    private final AccountAiChoiceRepository choices;
    private final AccountIdentityQuery identities;
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public Decision forDeveloper(long workspaceId, @Nullable Long developerId) {
        boolean required = settings.findByWorkspaceId(workspaceId)
                .map(WorkspaceOnboardingSettings::isAiChoiceRequired)
                .orElse(false);
        if (developerId == null) return new Decision(required, null);
        var choice = users.findById(developerId)
                .flatMap(user -> identities.resolveActiveAccountId(
                        Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null))
                .flatMap(choices::findById)
                .map(AccountAiChoice::getAiChoice);
        // A saved answer remains binding even when an owner turns off the setup page.
        if (choice.isPresent()) return new Decision(true, choice.get());
        return new Decision(required, null);
    }
}
