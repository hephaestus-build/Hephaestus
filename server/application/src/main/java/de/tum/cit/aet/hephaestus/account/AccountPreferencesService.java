package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("Feedback choices apply to every workspace of one account")
@RequiredArgsConstructor
public class AccountPreferencesService {
    private final AccountDirectory accounts;

    @Transactional(readOnly = true)
    public UserSettingsDTO getUserSettings(long accountId) {
        return settings(accountId);
    }

    @Transactional
    public UserSettingsDTO updateUserSettings(long accountId, UpdateUserSettingsDTO settings) {
        accounts.setFeedbackDeliveryEnabled(accountId, settings.practiceFeedbackDeliveryEnabled());
        if (settings.timeZone() != null) {
            accounts.setTimeZone(accountId, settings.timeZone());
        }
        return settings(accountId);
    }

    private UserSettingsDTO settings(long accountId) {
        return new UserSettingsDTO(
                accounts.find(accountId).orElseThrow().feedbackDeliveryEnabled(),
                accounts.timeZone(accountId).map(ZoneId::getId).orElse(null));
    }
}
