package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PrivacyAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;

    @Override
    public void eraseAccount(long id) {
        jdbc.update(
                "UPDATE person_data_request SET administrator_account_id=NULL WHERE administrator_account_id=?", id);
    }
}
