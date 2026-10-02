package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PrivacyAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    private final tools.jackson.databind.ObjectMapper mapper;

    @Override
    public void eraseAccount(long id) {
        var steps = new PersonDataStoreAdministrationContributor(namedJdbc, mapper);
        var actor = new de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope(
                id, java.util.List.of(), java.util.List.of());
        steps.erase(steps.select(actor));
        jdbc.update(
                "UPDATE person_data_request SET administrator_account_id=NULL,version=version+1 WHERE administrator_account_id=?",
                id);
    }
}
