package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"account"})
class CoreAccountPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AccountPurger purger;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account",
                        "account",
                        "t.id=:account",
                        "id,display_name,primary_email,status,created_at",
                        "id",
                        "",
                        1000) {
                    @Override
                    public long erase(PersonDataSelection selection) {
                        for (var row : selection.rows())
                            purger.purge(Long.valueOf(row.columns().get("id")));
                        return selection.rows().size();
                    }
                });
    }
}
