package de.tum.cit.aet.hephaestus.activity;

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
@PersonDataStores({"activity_event"})
public class ActivityPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(new JdbcPersonDataStore(
                jdbc,
                mapper,
                "activity_event",
                "activity_event",
                "t.actor_id IN (:users)",
                "id,event_key,event_type,occurred_at,actor_id,workspace_id,repository_id,target_type,target_id,ingested_at",
                "id",
                "",
                -50));
    }
}
