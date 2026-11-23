package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Formula;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@NoRepositoryBean
public interface AssessmentSqlFixture extends Repository<Conversation, UUID> {
    @NativeQuery("SELECT r.summary FROM conversation c, public.\"result\" r WHERE r.workspace_id = :workspaceId")
    List<String> summaries(long workspaceId);

    @Query("SELECT r.summary FROM Result r WHERE r.workspaceId = :workspaceId")
    List<String> summariesByEntity(long workspaceId);

    @Query("SELECT j FROM ReviewJob j WHERE j.workspaceId = :workspaceId")
    List<Object> outputByEntity(long workspaceId);

    @Query("SELECT j AS job FROM ReviewJob j WHERE j.workspaceId = :workspaceId")
    List<Object> aliasedJob(long workspaceId);

    @NativeQuery("SELECT r retained FROM result r WHERE r.workspace_id = :workspaceId")
    List<Object> implicitlyAliasedResult(long workspaceId);

    @NativeQuery("SELECT j::text FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<String> castJob(long workspaceId);

    @NativeQuery("SELECT EXISTS(SELECT 1 FROM result r WHERE r.workspace_id = :workspaceId AND r.outcome = 'NOT_MET')")
    boolean hasNegativeOutcome(long workspaceId);

    @NativeQuery("SELECT to_jsonb(j) FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<String> jsonOutput(long workspaceId);

    @NativeQuery("SELECT j.id, j.* FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object[]> wholeJob(long workspaceId);

    @NativeQuery("SELECT ALL * FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object[]> wholeJobAfterAll(long workspaceId);

    @NativeQuery("SELECT j, c.id FROM review_job j, conversation c WHERE j.workspace_id = :workspaceId")
    List<Object[]> wholeJobInCommaJoin(long workspaceId);

    @NativeQuery("SELECT 'a--b', j.\"output\" FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object[]> outputAfterQuotedComment(long workspaceId);

    @NativeQuery("SELECT j.output FROM ONLY (public.\"review_job\") j WHERE j.workspace_id = :workspaceId")
    List<String> outputFromOnlyRelation(long workspaceId);

    @NativeQuery("""
            SELECT q.o FROM (
                SELECT j.output AS o FROM review_job "j" WHERE j.workspace_id = :workspaceId
            ) q
            """)
    List<String> outputFromDerivedRelation(long workspaceId);

    @NativeQuery("TABLE review_job")
    List<Object[]> jobTable();

    @NativeQuery("""
            SELECT j.output FROM (review_job j JOIN agent_run a ON a.id = j.run_id)
            WHERE j.workspace_id = :workspaceId
            """)
    List<String> groupedJoinOutput(long workspaceId);

    @NativeQuery("SELECT DISTINCT ON (coalesce(j.id, j.id)) * FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object[]> distinctWholeJob(long workspaceId);

    @NativeQuery("SELECT 1 AS position, * FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object[]> mixedWholeJob(long workspaceId);

    @NativeQuery(
            "SELECT count(*) FROM result r WHERE r.workspace_id = :workspaceId AND r.person_account_id = :accountId")
    long countObservations(long workspaceId, long accountId);

    @NativeQuery("SELECT count(*) FROM (SELECT 1 FROM result r WHERE r.person_account_id = :accountId) results")
    long countNestedObservations(long accountId);

    @NativeQuery("SELECT EXISTS(SELECT 1 FROM result r WHERE r.person_account_id = :accountId)")
    boolean observationExists(long accountId);

    @NativeQuery("SELECT r.practice_id FROM result r WHERE r.person_account_id = :accountId")
    List<Long> observationPractice(long accountId);

    @NativeQuery("SELECT j.status, j.exit_code FROM review_job j WHERE j.review_id = :reviewId")
    List<Object[]> jobExitCode(UUID reviewId);

    @NativeQuery("SELECT jsonb_agg(j ORDER BY j.id) FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<String> aggregatedJob(long workspaceId);

    @NativeQuery("SELECT j.error_message FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<String> captureFailureText(long workspaceId);

    @Query("SELECT r.endReason FROM Result r WHERE r.workspaceId = :workspaceId")
    List<Object> endReasons(long workspaceId);

    @NativeQuery("SELECT r.source_kinds FROM result r WHERE r.workspace_id = :workspaceId")
    List<Object> sourceKinds(long workspaceId);

    @Query("SELECT r.stopReason FROM Review r WHERE r.workspaceId = :workspaceId")
    List<Object> recordedStopReason(long workspaceId);

    @NativeQuery("SELECT ARRAY[j] FROM review_job j WHERE j.workspace_id = :workspaceId")
    List<Object> arrayOfJobs(long workspaceId);

    @NativeQuery("SELECT CASE WHEN j.id = :jobId THEN j ELSE NULL END FROM review_job j")
    List<Object> conditionalJob(long jobId);

    @NativeQuery("SELECT b.payload FROM review_composition_basis b WHERE b.workspace_id = :workspaceId")
    List<String> stagedHistory(long workspaceId);

    @NativeQuery("SELECT public.review_job.output FROM public.review_job WHERE workspace_id = :workspaceId")
    List<String> schemaQualifiedOutput(long workspaceId);

    @NativeQuery("SELECT output ->> 'reason' FROM review_job WHERE id = :jobId")
    String outputProperty(long jobId);

    @NativeQuery("SELECT (SELECT j.output ->> 'reason') FROM review_job j WHERE j.id = :jobId")
    String correlatedOutput(long jobId);

    @Query("SELECT r.id FROM Result r WHERE r.workspaceId = :workspaceId AND r.developerId = :accountId")
    List<UUID> resultIds(long workspaceId, long accountId);

    @Query("SELECT m.reason FROM ResultMark m WHERE m.workspaceId = :workspaceId AND m.resultId = :resultId")
    List<String> markReasons(long workspaceId, UUID resultId);

    @NativeQuery("SELECT j.workspace_id AS output FROM review_job j WHERE j.id = :jobId")
    long workspaceAliasedAsOutput(long jobId);

    @NativeQuery("SELECT j.workspace_id AS j FROM review_job j WHERE j.id = :jobId")
    long workspaceAliasedAsQualifier(long jobId);

    @NativeQuery("SELECT 'it''s output, (cached)' AS label, j.workspace_id FROM review_job j WHERE j.id = :jobId")
    List<Object[]> workspaceWithLiteralLabel(long jobId);

    @NativeQuery("SELECT j.workspace_id FROM review_job j, agent_run a WHERE a.id = j.run_id AND j.id = :jobId")
    long workspaceInCommaJoin(long jobId);

    @NativeQuery("SELECT j.workspace_id FROM ONLY (review_job) j WHERE j.id = :jobId")
    long workspaceFromOnlyRelation(long jobId);

    @NativeQuery("SELECT q.w FROM (SELECT j.workspace_id AS w FROM review_job j WHERE j.id = :jobId) q")
    long workspaceFromDerivedRelation(long jobId);

    @NativeQuery("""
            WITH runs AS (SELECT * FROM agent_run)
            SELECT j.workspace_id FROM review_job j JOIN runs a ON a.id = j.run_id WHERE j.id = :jobId
            """)
    long workspaceWithUnrelatedWildcard(long jobId);

    @NativeQuery("""
            WITH metadata AS (SELECT 1 AS output)
            SELECT j.workspace_id + m.output FROM review_job j CROSS JOIN metadata m WHERE j.id = :jobId
            """)
    long workspaceWithUnrelatedOutput(long jobId);

    @NativeQuery("""
            WITH recent_jobs AS (SELECT j.id FROM review_job j WHERE j.workspace_id = :workspaceId),
            metadata AS (SELECT 1 AS output)
            SELECT j.output FROM metadata j WHERE EXISTS (SELECT 1 FROM recent_jobs)
            """)
    List<Long> workspaceWithShadowedAlias(long workspaceId);

    @NativeQuery("SELECT j.workspace_id FROM review_job j WHERE j.id = :output")
    long workspaceWithOutputParameter(long output);

    @NativeQuery("SELECT count(*) FROM review_job WHERE status = 'QUEUED'")
    long countQueuedJobs();

    abstract class HistoryRow {
        @Formula("(SELECT r.summary FROM result r WHERE r.id = result_id)")
        private @Nullable String summary;

        public @Nullable String summary() {
            return summary;
        }
    }

    @NamedNativeQueries(@NamedNativeQuery(name = "assessmentFixture", query = "SELECT j.output FROM review_job j"))
    final class NestedQueries {}

    final class PublicHistoryReader {
        public void read(FeedbackHistoryEntry delivered) {
            delivered.publicHistory();
        }
    }

    final class RuntimeQuery {
        public void read(NamedParameterJdbcOperations jdbc, String relation) {
            jdbc.queryForList("SELECT output FROM %s".formatted(relation), Map.of(), String.class);
        }
    }

    default void outputByJdbc(NamedParameterJdbcOperations jdbc, long workspaceId) {
        jdbc.queryForList(
                "/* attempt metadata */ SELECT j.output AS stored_output FROM review_job j WHERE j.workspace_id = :workspaceId",
                Map.of("workspaceId", workspaceId),
                String.class);
    }

    default void workspaceByJdbc(NamedParameterJdbcOperations jdbc, long jobId) {
        jdbc.queryForList(
                "SELECT j.workspace_id FROM review_job j WHERE j.id = :jobId", Map.of("jobId", jobId), Long.class);
    }

    default void copyRawOutputByJdbc(NamedParameterJdbcOperations jdbc, long workspaceId) {
        jdbc.update(
                "INSERT INTO scratch(copy) SELECT j.output FROM review_job j WHERE j.workspace_id = :workspaceId",
                Map.of("workspaceId", workspaceId));
    }
}
