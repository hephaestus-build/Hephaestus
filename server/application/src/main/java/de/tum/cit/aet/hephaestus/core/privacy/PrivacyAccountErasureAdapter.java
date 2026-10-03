package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
class PrivacyAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;

    @Override
    public void eraseAccount(long id) {
        var steps = new PersonDataStoreAdministrationContributor(namedJdbc, mapper);
        var actor = new PersonScope(id, List.of(), List.of());
        steps.erase(steps.select(actor));
        jdbc.update(
                "UPDATE person_data_request SET administrator_account_id=NULL,version=version+1 WHERE administrator_account_id=?",
                id);
    }
}
