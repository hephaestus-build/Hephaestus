package de.tum.cit.aet.hephaestus.workspace;

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
@PersonDataStores({
    "workspace_membership",
    "workspace_hidden_former_member",
    "practice_review_person_target",
    "account_ai_choice",
    "workspace_member_onboarding",
    "workspace_onboarding_settings"
})
public class WorkspacePersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "workspace_membership",
                        "workspace_membership",
                        "t.user_id IN (:users)",
                        "created_at,role,user_id,workspace_id,hidden",
                        "user_id,workspace_id",
                        "",
                        550),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "workspace_hidden_former_member",
                        "workspace_hidden_former_member",
                        "t.user_id IN (:users)",
                        "user_id,workspace_id",
                        "user_id,workspace_id",
                        "",
                        550),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "practice_review_person_target",
                        "practice_review_person_target",
                        "t.user_id IN (:users)",
                        "workspace_id,user_id",
                        "workspace_id,user_id",
                        "",
                        550),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account_ai_choice",
                        "account_ai_choice",
                        "t.account_id = :account",
                        "account_id,ai_choice,updated_at",
                        "account_id",
                        "",
                        550),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "workspace_member_onboarding",
                        "workspace_member_onboarding",
                        "t.account_id = :account",
                        "id,account_id,seen_revision,updated_at,workspace_id",
                        "id",
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
