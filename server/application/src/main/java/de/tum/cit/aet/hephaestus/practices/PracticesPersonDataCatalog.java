package de.tum.cit.aet.hephaestus.practices;

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
    "delivery_policy_evaluation",
    "feedback_dispatch",
    "reaction",
    "feedback_placement",
    "feedback_observation",
    "feedback_approval",
    "feedback_withdrawal",
    "observation_invalidation",
    "feedback",
    "observation",
    "feedback_approval_actor",
    "feedback_withdrawal_actor",
    "feedback_restoration_actor",
    "observation_invalidation_actor",
    "observation_restoration_actor"
})
public class PracticesPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "delivery_policy_evaluation",
                        "delivery_policy_evaluation",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users)))) OR t.agent_job_id IN (SELECT j.id FROM agent_job j WHERE j.metadata->>'author_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'actor_user_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'about_user_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'pull_request_id' IN (SELECT id::text FROM (SELECT id FROM issue WHERE author_id IN (:users) OR merged_by_id IN (:users) UNION SELECT issue_id FROM issue_comment WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review_comment WHERE author_id IN (:users)) artifacts) OR j.metadata->>'issue_id' IN (SELECT id::text FROM (SELECT id FROM issue WHERE author_id IN (:users) OR merged_by_id IN (:users) UNION SELECT issue_id FROM issue_comment WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review_comment WHERE author_id IN (:users)) artifacts) OR j.id IN (SELECT agent_job_id FROM observation WHERE about_user_id IN (:users)) OR j.id IN (SELECT agent_job_id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users)))",
                        "id,workspace_id,agent_job_id,feedback_id,admitted_revision,evaluated_revision,resolver_version,surface,stage,allowed,decisive_reason,checks,facts,evaluated_at",
                        "id",
                        "",
                        -230),
                new ExternalFeedbackPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_dispatch",
                        "feedback_dispatch",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users)))) OR t.agent_job_id IN (SELECT j.id FROM agent_job j WHERE j.metadata->>'author_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'actor_user_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'about_user_id' IN (SELECT id::text FROM \"user\" WHERE id IN (:users)) OR j.metadata->>'pull_request_id' IN (SELECT id::text FROM (SELECT id FROM issue WHERE author_id IN (:users) OR merged_by_id IN (:users) UNION SELECT issue_id FROM issue_comment WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review_comment WHERE author_id IN (:users)) artifacts) OR j.metadata->>'issue_id' IN (SELECT id::text FROM (SELECT id FROM issue WHERE author_id IN (:users) OR merged_by_id IN (:users) UNION SELECT issue_id FROM issue_comment WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review WHERE author_id IN (:users) UNION SELECT pull_request_id FROM pull_request_review_comment WHERE author_id IN (:users)) artifacts) OR j.id IN (SELECT agent_job_id FROM observation WHERE about_user_id IN (:users)) OR j.id IN (SELECT agent_job_id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users)))",
                        "id,destination_key,workspace_id,agent_job_id,feedback_id,destination,state,body,practice_slugs,package_content,delivered_placements,write_started,delivered_external_ref,lease_owner,lease_expires_at,next_attempt_at,attempt_count,suppression_reason,last_error,projected_at,projection_owner,projection_expires_at,created_at,updated_at,write_started_at,inline_write_started",
                        "id",
                        "",
                        -220),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "reaction",
                        "reaction",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users)))) OR t.reactor_user_id IN (:users)",
                        "id,reactor_user_id,action,explanation,created_at,feedback_id,recurrence_key,usefulness",
                        "id",
                        "",
                        -210),
                new ExternalFeedbackPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_placement",
                        "feedback_placement",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))))",
                        "id,feedback_id,placement_type,anchor_kind,anchor_path,anchor_start_line,anchor_end_line,anchor_side,posted_comment_ref,created_at,chat_message_id",
                        "id",
                        "",
                        -200),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_observation",
                        "feedback_observation",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))))",
                        "feedback_id,observation_id,role,ordinal",
                        "feedback_id,observation_id",
                        "",
                        -190),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_approval",
                        "feedback_approval",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))))",
                        "id,feedback_id,workspace_id,actor_account_id,decision,rejection_reason,rejection_note,content_digest,decided_at",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_withdrawal",
                        "feedback_withdrawal",
                        "t.feedback_id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))))",
                        "id,feedback_id,reason,restoration_reason,restored_at,restored_by_account_id,withdrawn_at,withdrawn_by_account_id,workspace_id",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation_invalidation",
                        "observation_invalidation",
                        "t.observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))",
                        "id,invalidated_at,invalidated_by_account_id,observation_id,provider_copy,provider_copy_retry_at,reason,restoration_reason,restored_at,restored_by_account_id,workspace_id",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback",
                        "feedback",
                        "t.id IN (SELECT id FROM feedback WHERE about_user_id IN (:users) OR recipient_user_id IN (:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN (SELECT id FROM observation WHERE about_user_id IN (:users))))",
                        "id,agent_job_id,workspace_id,artifact_kind,artifact_id,recipient_user_id,about_user_id,channel,position,delivery_state,suppression_reason,body,source,replaces_id,thread_key,created_at,delivered_at,proposed_placements,reviewed_revision,proposed_practice_slugs",
                        "id",
                        "",
                        -160),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation",
                        "observation",
                        "t.about_user_id IN (:users)",
                        "id,occurrence_key,agent_job_id,practice_id,artifact_kind,artifact_id,about_user_id,summary,presence,severity,evidence,evidence_rationale,observed_at,recurrence_key,practice_revision_id,assessment,origin,workspace_id,assessment_status,superseded_at",
                        "id",
                        "",
                        -150),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_approval_actor",
                        "feedback_approval",
                        "t.actor_account_id = :account",
                        "id,feedback_id,workspace_id,actor_account_id,decision,rejection_reason,rejection_note,decided_at",
                        "id",
                        "actor_account_id=NULL,rejection_note=NULL",
                        -185),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_withdrawal_actor",
                        "feedback_withdrawal",
                        "t.withdrawn_by_account_id = :account",
                        "id,feedback_id,workspace_id,withdrawn_by_account_id,withdrawn_at,reason",
                        "id",
                        "withdrawn_by_account_id=NULL,reason='Erased administrator text'",
                        -185),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_restoration_actor",
                        "feedback_withdrawal",
                        "t.restored_by_account_id = :account",
                        "id,feedback_id,workspace_id,restored_by_account_id,restored_at,restoration_reason",
                        "id",
                        "restored_by_account_id=NULL,restoration_reason=NULL",
                        -185),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation_invalidation_actor",
                        "observation_invalidation",
                        "t.invalidated_by_account_id = :account",
                        "id,observation_id,workspace_id,invalidated_by_account_id,invalidated_at,reason",
                        "id",
                        "invalidated_by_account_id=NULL,reason='Erased administrator text'",
                        -185),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation_restoration_actor",
                        "observation_invalidation",
                        "t.restored_by_account_id = :account",
                        "id,observation_id,workspace_id,restored_by_account_id,restored_at,restoration_reason",
                        "id",
                        "restored_by_account_id=NULL,restoration_reason=NULL",
                        -185));
    }
}
