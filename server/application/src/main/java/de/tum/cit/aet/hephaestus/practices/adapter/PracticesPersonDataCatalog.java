package de.tum.cit.aet.hephaestus.practices.adapter;

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
public class PracticesPersonDataCatalog implements PersonDataCatalog, PersonConversationCopySource {
    private static final String REVIEWED_WORK_SCOPE = """
            agent_job_id = ANY(:derivedJobs) OR about_user_id = ANY(:users)
            OR (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts))
            OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations))
            OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents))
            """;
    private static final String OBSERVATION_IDS = "SELECT id FROM observation WHERE (" + REVIEWED_WORK_SCOPE + ")";
    private static final String FEEDBACK_IDS = "SELECT id FROM feedback WHERE (" + REVIEWED_WORK_SCOPE
            + ") OR recipient_user_id = ANY(:users) OR id IN (SELECT feedback_id FROM feedback_observation WHERE observation_id IN ("
            + OBSERVATION_IDS + "))";
    private static final String JOB_IDS =
            """
            SELECT j.id FROM agent_job j WHERE j.id = ANY(:derivedJobs)
            OR j.metadata->>'slack_thread_id' IN (SELECT unnest(CAST(:conversations AS bigint[]))::text)
            OR j.metadata->>'docs_document_id' IN (SELECT unnest(CAST(:documents AS bigint[]))::text)
            OR j.metadata->>'author_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.metadata->>'actor_user_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.metadata->>'about_user_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.metadata->>'pull_request_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text)
            OR j.metadata->>'issue_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text)
            """ + " OR j.id IN (SELECT agent_job_id FROM observation WHERE id IN (" + OBSERVATION_IDS
                    + ")) OR j.id IN (SELECT agent_job_id FROM feedback WHERE id IN (" + FEEDBACK_IDS + "))";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<ConversationCopy> conversationCopies(PersonScope person) {
        var placement = contributors().stream()
                .filter(store -> store.store().equals("feedback_placement"))
                .findFirst()
                .orElseThrow();
        var selected = placement.select(person);
        if (selected.rows().isEmpty()) return List.of();
        return jdbc.query(
                """
                SELECT DISTINCT f.workspace_id, p.chat_message_id
                FROM feedback_placement p JOIN feedback f ON f.id=p.feedback_id
                WHERE p.chat_message_id IS NOT NULL AND EXISTS (
                    SELECT 1 FROM jsonb_populate_recordset(NULL::feedback_placement,CAST(:keys AS jsonb)) selected
                    WHERE selected.id=p.id)
                ORDER BY f.workspace_id,p.chat_message_id
                """,
                java.util.Map.of(
                        "keys",
                        mapper.writeValueAsString(selected.rows().stream()
                                .map(PersonDataSelection.RowKey::columns)
                                .toList())),
                (rs, row) -> new ConversationCopy(rs.getLong(1), java.util.UUID.fromString(rs.getString(2))));
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "delivery_policy_evaluation",
                        "delivery_policy_evaluation",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")" + " OR t.agent_job_id IN (" + JOB_IDS + ")",
                        "id,workspace_id,agent_job_id,feedback_id,admitted_revision,evaluated_revision,resolver_version,surface,stage,allowed,decisive_reason,checks,facts,evaluated_at",
                        "id",
                        "",
                        -230),
                new ExternalFeedbackPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_dispatch",
                        "feedback_dispatch",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")" + " OR t.agent_job_id IN (" + JOB_IDS + ")",
                        "id,destination_key,workspace_id,agent_job_id,feedback_id,destination,state,body,practice_slugs,package_content,delivered_placements,write_started,delivered_external_ref,delivered_external_url,lease_owner,lease_expires_at,next_attempt_at,attempt_count,suppression_reason,last_error,projected_at,projection_owner,projection_expires_at,created_at,updated_at,write_started_at,inline_write_started",
                        "id",
                        "",
                        -220),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "reaction",
                        "reaction",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")",
                        "id,reactor_user_id,action,explanation,created_at,feedback_id,recurrence_key,usefulness",
                        "id",
                        "",
                        -210),
                new ExternalFeedbackPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_placement",
                        "feedback_placement",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")",
                        "id,feedback_id,placement_type,anchor_kind,anchor_path,anchor_start_line,anchor_end_line,anchor_side,posted_comment_ref,posted_comment_url,created_at,chat_message_id",
                        "id",
                        "",
                        -200),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_observation",
                        "feedback_observation",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")",
                        "feedback_id,observation_id,role,ordinal",
                        "feedback_id,observation_id",
                        "",
                        -190),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_approval",
                        "feedback_approval",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")",
                        "id,feedback_id,workspace_id,actor_account_id,decision,rejection_reason,rejection_note,content_digest,decided_at",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback_withdrawal",
                        "feedback_withdrawal",
                        "t.feedback_id IN (" + FEEDBACK_IDS + ")",
                        "id,feedback_id,reason,restoration_reason,restored_at,restored_by_account_id,withdrawn_at,withdrawn_by_account_id,workspace_id",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation_invalidation",
                        "observation_invalidation",
                        "t.observation_id IN (" + OBSERVATION_IDS + ")",
                        "id,invalidated_at,invalidated_by_account_id,observation_id,provider_copy,provider_copy_retry_at,reason,restoration_reason,restored_at,restored_by_account_id,workspace_id",
                        "id",
                        "",
                        -180),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "feedback",
                        "feedback",
                        "t.id IN (" + FEEDBACK_IDS + ")",
                        "id,agent_job_id,workspace_id,artifact_kind,artifact_id,recipient_user_id,about_user_id,channel,position,delivery_state,suppression_reason,body,source,replaces_id,thread_key,created_at,delivered_at,proposed_placements,reviewed_revision,proposed_practice_slugs",
                        "id",
                        "",
                        -160),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "observation",
                        "observation",
                        "t.id IN (" + OBSERVATION_IDS + ")",
                        "id,occurrence_key,agent_job_id,practice_id,artifact_kind,artifact_id,about_user_id,summary,outcome,severity,evidence,evidence_rationale,observed_at,recurrence_key,practice_revision_id,origin,workspace_id,superseded_at",
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
