package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"membership", "workspace_onboarding_settings"})
public class WorkspacePersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "membership",
                        "membership",
                        "t.account_id=:account",
                        "workspace_id,account_id,role,role_source,hidden,review_selected,onboarding_seen_revision,public_activity_seen,joined_at,left_at",
                        "workspace_id,account_id",
                        "",
                        550),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "workspace_onboarding_settings",
                        "workspace_onboarding_settings",
                        "FALSE",
                        "workspace_id,ai_choice_required,enabled,required_connection_ids,revision",
                        "workspace_id",
                        "",
                        0));
    }
}
