package de.tum.cit.aet.hephaestus.productfeedback.adapter;

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
@PersonDataStores({
    "product_feedback",
    "product_survey_participation",
    "product_survey_email_invitation",
    "product_survey",
    "product_feedback_resolver",
    "product_survey_invitation_requester"
})
public class ProductfeedbackPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback",
                        "product_feedback",
                        "t.account_id = :account",
                        "id,account_id,workspace_id,kind,message,page_path,created_at,submission_minute,app_version,user_agent,resolved_at,resolved_by_account_id",
                        "id",
                        "",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_survey_participation",
                        "product_survey_participation",
                        "t.account_id = :account",
                        "id,survey_id,account_id,workspace_id,status,answers_json,invited_at,decided_at",
                        "id",
                        "",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_survey_email_invitation",
                        "product_survey_email_invitation",
                        "t.account_id = :account",
                        "id,accepted_at,account_id,cancelled_at,expires_at,reminder_accepted_at,reminder_enabled,reminder_requested_at,requested_at,requested_by_account_id,survey_id,workspace_id,request_generation",
                        "id",
                        "",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_survey",
                        "product_survey",
                        "t.created_by_account_id = :account",
                        "id,title,description,questions_json,workspace_id,starts_at,ends_at,active,created_by_account_id,created_at,research_organization,summary_queued_at",
                        "id",
                        "created_by_account_id=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback_resolver",
                        "product_feedback",
                        "t.resolved_by_account_id = :account",
                        "id,kind,resolved_at,resolved_by_account_id",
                        "id",
                        "resolved_by_account_id=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_survey_invitation_requester",
                        "product_survey_email_invitation",
                        "t.requested_by_account_id = :account",
                        "id,survey_id,workspace_id,requested_by_account_id,requested_at,accepted_at,cancelled_at",
                        "id",
                        "requested_by_account_id=NULL",
                        400));
    }
}
