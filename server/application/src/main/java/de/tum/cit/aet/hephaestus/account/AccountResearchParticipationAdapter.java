package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.ConsentSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationCommand;
import org.springframework.stereotype.Service;

@Service
@WorkspaceAgnostic("User-scoped research-consent write — not workspace-specific")
public class AccountResearchParticipationAdapter implements ResearchParticipationCommand {

    private final AccountPreferencesService accountPreferencesService;

    public AccountResearchParticipationAdapter(AccountPreferencesService accountPreferencesService) {
        this.accountPreferencesService = accountPreferencesService;
    }

    @Override
    public void setForUserId(long userId, boolean participate, ConsentSource source) {
        accountPreferencesService.setForUserId(userId, participate, source);
    }
}
