package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "feedback_send",
    "feedback_response",
    "feedback_copy",
    "feedback_basis",
    "feedback_decision",
    "feedback",
    "result_mark",
    "review_item",
    "problem",
    "result",
    "result_mark_created_actor",
    "result_mark_removed_actor",
    "feedback_decision_actor",
    "review",
    "review_batch",
    "review_schedule"
})
public class PracticesPersonDataCatalog implements PersonDataCatalog, PersonConversationCopySource {
    // Migrated review IDs preserve producer job IDs after runtime retention removes the job pointer.
    private static final String RESULT_IDS = "SELECT id FROM result WHERE developer_id=:account OR (evidence_version="
            + Result.MIGRATED_EVIDENCE_VERSION
            + " AND review_id=ANY(:derivedJobs))"
            + " OR id IN(SELECT result_id FROM review_item WHERE"
            + " result_id IS NOT NULL AND (job_id=ANY(:derivedJobs) OR EXISTS(SELECT 1 FROM"
            + " jsonb_array_elements_text(COALESCE(detail->'custody'->'sourceJobIds','[]'::jsonb)) source(value)"
            + " WHERE source.value::uuid=ANY(:derivedJobs))))";
    private static final String FEEDBACK_IDS =
            "SELECT id FROM feedback WHERE developer_id=:account OR id IN(SELECT feedback_id FROM feedback_basis WHERE"
                    + " result_id IN(" + RESULT_IDS + "))";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FeedbackCopyCleanupCustody custody;
    private final PersonIdentityResolver identities;
    private final ScanReportService reports;

    @Override
    public List<ConversationCopy> conversationCopies(PersonScope person) {
        var selected = contributors().stream()
                .filter(s -> s.store().equals("feedback_copy"))
                .findFirst()
                .orElseThrow()
                .select(person);
        if (selected.rows().isEmpty()) return List.of();
        return jdbc.query(
                """
                SELECT DISTINCT c.workspace_id,c.mentor_entry_id FROM feedback_copy c
                WHERE c.mentor_entry_id IS NOT NULL AND EXISTS(SELECT 1 FROM jsonb_populate_recordset(NULL::feedback_copy,CAST(:keys AS jsonb)) s WHERE s.id=c.id AND s.workspace_id=c.workspace_id)
                ORDER BY c.workspace_id,c.mentor_entry_id
                """,
                Map.of(
                        "keys",
                        mapper.writeValueAsString(selected.rows().stream()
                                .map(PersonDataSelection.RowKey::columns)
                                .toList())),
                (rs, n) -> new ConversationCopy(rs.getLong(1), rs.getObject(2, UUID.class)));
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.<PersonDataContributor>of(
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "review",
                                "review",
                                "t.requested_by_id=:account",
                                "id,workspace_id,kind,origin,requested_by_id,created_at,ordered_at,completed_at",
                                "id,workspace_id",
                                "requested_by_id=NULL",
                                -180),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "review_batch",
                                "review_batch",
                                "t.requested_by_account_id=:account OR t.confirmed_by_account_id=:account",
                                "id,workspace_id,kind,origin,status,requested_by_account_id,confirmed_by_account_id,created_at,started_at,finished_at",
                                "id,workspace_id",
                                "version=version+1,requested_by_account_id=CASE WHEN requested_by_account_id=:account"
                                        + " THEN NULL ELSE requested_by_account_id END,confirmed_by_account_id=CASE WHEN"
                                        + " confirmed_by_account_id=:account THEN NULL ELSE confirmed_by_account_id END",
                                -180),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "review_schedule",
                                "review_schedule",
                                "t.created_by_account_id=:account",
                                "id,workspace_id,kind,cadence,enabled,created_by_account_id,created_at,last_run_at,next_run_at",
                                "id,workspace_id",
                                "created_by_account_id=NULL",
                                -180),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback_send",
                                "feedback_send",
                                "t.feedback_id IN(" + FEEDBACK_IDS + ")",
                                "feedback_id,workspace_id,action,state,lease_owner,lease_expires_at,next_attempt_at,attempts,last_error,created_at,updated_at",
                                "feedback_id,workspace_id",
                                "",
                                -230),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback_response",
                                "feedback_response",
                                "t.feedback_id IN(" + FEEDBACK_IDS + ")",
                                "id,workspace_id,feedback_id,resolution,usefulness,comment,created_at",
                                "id,workspace_id",
                                "",
                                -220),
                        new ExternalFeedbackPersonDataStore(
                                custody,
                                jdbc,
                                mapper,
                                "feedback_copy",
                                "feedback_copy",
                                "t.feedback_id IN(" + FEEDBACK_IDS + ")",
                                "id,workspace_id,feedback_id,kind,position,body,path,start_line,end_line,side,stop_reason,last_error,state,write_started_at,write_key,write_marker,external_ref,external_url,mentor_turn_id,mentor_entry_id,created_at,posted_at",
                                "id,workspace_id",
                                "",
                                -210),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback_basis",
                                "feedback_basis",
                                "t.feedback_id IN(" + FEEDBACK_IDS + ")",
                                "id,workspace_id,feedback_id,developer_id,result_id,problem_id,ordinal",
                                "id,workspace_id",
                                "",
                                -200),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback_decision",
                                "feedback_decision",
                                "t.feedback_id IN(" + FEEDBACK_IDS + ")",
                                "id,workspace_id,feedback_id,action,actor_account_id,reason,note,content_digest,decided_at",
                                "id,workspace_id",
                                "",
                                -190),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback",
                                "feedback",
                                "t.id IN(" + FEEDBACK_IDS + ")",
                                "id,workspace_id,review_id,item_id,developer_id,destination,state,stop_reason,body,replaces_id,created_at,delivered_at,checks",
                                "id,workspace_id",
                                "",
                                -180),
                        new ScanReportReferencePersonDataStore(
                                reports,
                                jdbc,
                                mapper,
                                "review_item",
                                "review_item",
                                "t.result_id IN(" + RESULT_IDS
                                        + ") OR t.job_id=ANY(:derivedJobs) OR EXISTS(SELECT 1 FROM"
                                        + " jsonb_array_elements_text(COALESCE(t.detail->'custody'->'sourceJobIds','[]'::jsonb))"
                                        + " source(value) WHERE source.value::uuid=ANY(:derivedJobs))",
                                "workspace_id,review_id,practice_id,item_id,item_version,state,result_id,job_id,attempted_at,reason,detail",
                                "workspace_id,review_id,practice_id,item_id",
                                "",
                                -175),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "result_mark",
                                "result_mark",
                                "t.result_id IN(" + RESULT_IDS + ")",
                                "id,workspace_id,result_id,kind,reason,created_by_id,created_at,removed_by_id,removed_at,removal_reason",
                                "id,workspace_id",
                                "",
                                -170),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "problem",
                                "problem",
                                "t.result_id IN(" + RESULT_IDS + ")",
                                "id,workspace_id,result_id,position,key,severity,summary,explanation,citations,basis,next_step",
                                "id,workspace_id",
                                "",
                                -160),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "result",
                                "result",
                                "t.id IN(" + RESULT_IDS + ")",
                                "id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,summary,explanation,severity,evidence,example,coverage,evidence_version,recorded_at,ended_at,end_reason",
                                "id,workspace_id",
                                "",
                                -150),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "result_mark_created_actor",
                                "result_mark",
                                "t.created_by_id=:account",
                                "id,workspace_id,result_id,created_by_id,created_at,reason",
                                "id,workspace_id",
                                "created_by_id=NULL,reason='Erased administrator text'",
                                -180),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "result_mark_removed_actor",
                                "result_mark",
                                "t.removed_by_id=:account",
                                "id,workspace_id,result_id,removed_by_id,removed_at,removal_reason",
                                "id,workspace_id",
                                "removed_by_id=NULL,removal_reason=NULL",
                                -180),
                        new JdbcPersonDataStore(
                                jdbc,
                                mapper,
                                "feedback_decision_actor",
                                "feedback_decision",
                                "t.actor_account_id=:account",
                                "id,workspace_id,feedback_id,action,actor_account_id,reason,note,content_digest,decided_at",
                                "id,workspace_id",
                                "actor_account_id=NULL,note=NULL,reason=CASE WHEN action='APPROVE' THEN NULL ELSE"
                                        + " 'Erased administrator text' END",
                                -180))
                .stream()
                .map(store ->
                        (PersonDataContributor) new CapturedPersonDataContributor(store, identities, jdbc, mapper))
                .toList();
    }
}
