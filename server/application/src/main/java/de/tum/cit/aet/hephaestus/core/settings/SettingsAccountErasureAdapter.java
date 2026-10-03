package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class SettingsAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;

    @Override
    public void eraseAccount(long accountId) {
        jdbc.update(
                "UPDATE instance_settings SET silent_mode_changed_by_account_id = NULL WHERE silent_mode_changed_by_account_id = ?",
                accountId);
    }
}
