package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
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
@PersonDataStores({"membership", "workspace_onboarding_settings", "workspace", "resource_reader"})
// The membership store selects team memberships; the personal space store deletes the space and its owner's membership.
@ErasesColumns({"membership.account_id", "workspace.personal_account_id"})
public class WorkspacePersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonalSpaceErasureAdapter personalSpaces;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "membership",
                        "membership",
                        // A personal space is the owner's alone: PersonalSpacePersonDataStore exports it and disposes
                        // of it whole.
                        "t.account_id=:account AND NOT EXISTS (SELECT 1 FROM workspace w"
                                + " WHERE w.id=t.workspace_id AND w.kind='PERSONAL')",
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
                        0),
                // A reader is a provider identity of the person. Erasure deletes it before the provider profile is
                // anonymized; that narrows who a monitor's proof names, and the next listing proves the rest again.
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "resource_reader",
                        "resource_reader",
                        "t.reader_native_id IN (SELECT u.native_id FROM \"user\" u WHERE u.id = ANY(:users))",
                        "id,workspace_id,repository_to_monitor_id,reader_native_id",
                        "id",
                        "",
                        100),
                new PersonalSpacePersonDataStore(jdbc, mapper, personalSpaces));
    }
}
