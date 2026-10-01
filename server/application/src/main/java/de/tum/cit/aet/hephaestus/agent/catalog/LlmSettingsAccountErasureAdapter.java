package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class LlmSettingsAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;

    @Override
    public void eraseAccount(long accountId) {
        jdbc.update(
                "UPDATE instance_llm_settings SET updated_by_account_id = NULL WHERE updated_by_account_id = ?",
                accountId);
    }
}
