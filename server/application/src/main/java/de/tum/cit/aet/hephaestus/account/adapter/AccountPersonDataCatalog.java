package de.tum.cit.aet.hephaestus.account.adapter;

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
@PersonDataStores({"user_preferences"})
public class AccountPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(new JdbcPersonDataStore(
                jdbc,
                mapper,
                "user_preferences",
                "user_preferences",
                "t.user_id = ANY(:users)",
                "id,user_id,participate_in_research,ai_review_enabled",
                "id",
                "",
                600));
    }
}
