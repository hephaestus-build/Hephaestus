package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.column;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.execute;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeHistoryDatabaseTest {
    private static final String RESULT = "00000000-0000-0000-0000-000000992141";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRetainPartialFailureAndUncertaintyWithoutRepeatingConfirmedCopies(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(database, """
            SELECT right(f.id::text,6) || '|' || f.state || '|' ||
                (f.delivered_at='2026-01-01T00:01:30Z'::timestamptz) || '|' ||
                coalesce(f.stop_reason,'none') || '|' || s.action || '|' || s.state
            FROM feedback f JOIN feedback_send s ON s.feedback_id=f.id
            WHERE f.id IN('00000000-0000-0000-0000-000000992314',
                '00000000-0000-0000-0000-000000992317','00000000-0000-0000-0000-000000992318')
            ORDER BY f.id
            """))
                .containsExactly(
                        "992314|FAILED|true|none|POST|DONE",
                        "992317|UNCONFIRMED|true|DELIVERY_UNCONFIRMED|POST|UNCERTAIN",
                        "992318|FAILED|true|none|POST|DONE");
        assertThat(column(database, """
            SELECT right(feedback_id::text,6) || '|' || kind || '|' || state || '|' ||
                coalesce(external_ref,'none') || '|' || (posted_at IS NOT NULL)
            FROM feedback_copy WHERE feedback_id IN('00000000-0000-0000-0000-000000992317',
                '00000000-0000-0000-0000-000000992318') ORDER BY feedback_id,position
            """))
                .containsExactly(
                        "992317|SUMMARY|POSTED|provider:partial-uncertain|true",
                        "992317|LINE_NOTE|WRITE_STARTED|none|false",
                        "992318|SUMMARY|POSTED|provider:partial-failed|true",
                        "992318|LINE_NOTE|FAILED|none|false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveEachReleasedOutcomeAndNegativeSeverity(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(database, """
            SELECT r.id::text || '|' || r.outcome || '|' || coalesce(r.severity,'NONE') || '|'
                || coalesce(p.severity,'NONE') || '|' || r.practice_version_id || '|' || (r.ended_at IS NULL)
            FROM result r LEFT JOIN problem p ON p.result_id=r.id AND p.workspace_id=r.workspace_id
            WHERE r.id BETWEEN '00000000-0000-0000-0000-000000992521'::uuid
                AND '00000000-0000-0000-0000-000000992526'::uuid ORDER BY r.id
            """))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992521|MET|NONE|NONE|992511|true",
                        "00000000-0000-0000-0000-000000992522|DOES_NOT_APPLY|NONE|NONE|992512|true",
                        "00000000-0000-0000-0000-000000992523|COULD_NOT_DETERMINE|NONE|NONE|992513|true",
                        "00000000-0000-0000-0000-000000992524|NOT_MET|CRITICAL|CRITICAL|992514|true",
                        "00000000-0000-0000-0000-000000992525|NOT_MET|MAJOR|MAJOR|992515|true",
                        "00000000-0000-0000-0000-000000992526|NOT_MET|MINOR|MINOR|992516|true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveUnclaimedAccountsAndTheirMemberships(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT a.status || '|' || count(m.workspace_id) FROM identity i JOIN account a ON"
                                + " a.id=i.account_id JOIN membership m ON m.account_id=a.id WHERE"
                                + " i.provider_id=992102 AND i.provider_user_key='104' GROUP BY a.status"))
                .containsExactly("UNCLAIMED|2");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM identity WHERE provider_id=992102 AND provider_user_key='105'"))
                .containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldStopRuntimeJobsAndPreserveTheirReviewFacts(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT status || '|' || processing_status || '|' || cancellation_reason || '|' ||"
                                + " (lease_id IS NULL) FROM review_job WHERE"
                                + " id='00000000-0000-0000-0000-000000992112'"))
                .containsExactly("CANCELLED|DONE|CONTRACT_CHANGED|true");
        assertThat(column(
                        database,
                        "SELECT status || '|' || processing_status FROM review_job WHERE"
                                + " id='00000000-0000-0000-0000-000000992113'"))
                .containsExactly("COMPLETED|FAILED");
        // An admission is taken as one pair, from the completion or from metadata; a lone digest is kept as a fact.
        assertThat(column(database, """
            SELECT right(j.id::text, 6) || '|' || coalesce(j.admission_attempt::text, '-') || '|'
                || coalesce(left(j.admission_digest, 3), '-') || '|'
                || coalesce((r.checks->'MIGRATION'->'admission')::text, '-')
            FROM review_job j JOIN review r ON r.id=j.id
            WHERE j.id IN ('00000000-0000-0000-0000-000000992111','00000000-0000-0000-0000-000000992113',
                           '00000000-0000-0000-0000-000000992602')
            ORDER BY j.id
            """))
                .containsExactly(
                        "992111|0|aaa|-",
                        "992113|-|-|{\"observationAdmissionDigest\": \"" + "d".repeat(64) + "\"}",
                        "992602|1|eee|-");
        // A send that released projection retired keeps its approved text, posted reference and approval history.
        assertThat(column(database, """
            SELECT f.state || '|' || f.body || '|' || c.state || '|' || (c.body IS NULL) || '|' || c.external_ref
                || '|' || s.state || '|' || (h.facts->'dispatch'->>'payloadCleared')
                || '|' || (h.facts->>'approvalDigest' = repeat('f', 64))
                || '|' || (SELECT count(*) FROM feedback_decision d WHERE d.feedback_id=f.id AND d.action='APPROVE')
            FROM feedback f JOIN feedback_copy c ON c.feedback_id=f.id AND c.kind='SUMMARY'
            JOIN feedback_send s ON s.feedback_id=f.id JOIN review r ON r.id=f.review_id
            CROSS JOIN LATERAL (SELECT r.checks->'MIGRATION'->'feedback'->(f.id::text)->'MIGRATION' AS facts) h
            WHERE f.id='00000000-0000-0000-0000-000000992319'
            """))
                .containsExactly("DELIVERED|Explain this change.|POSTED|true|provider:retired|DONE|true|true|1");
        // Two comments at one anchor keep their own ids and references; each receipt joins its comment by reference.
        assertThat(column(database, """
            SELECT right(c.id::text, 6) || '|' || c.kind || '|' || c.external_ref || '|' || c.write_key || '|' || c.state
            FROM feedback_copy c WHERE c.feedback_id='00000000-0000-0000-0000-000000992320' ORDER BY c.position
            """))
                .containsExactly(
                        "992340|FILE_NOTE|provider:file-a|approved:00000000-0000-0000-0000-000000992320:0|POSTED",
                        "992341|FILE_NOTE|provider:file-b|approved:00000000-0000-0000-0000-000000992320:1|POSTED");
        assertThat(column(database, """
            SELECT c.body FROM feedback_copy c WHERE c.feedback_id='00000000-0000-0000-0000-000000992320' ORDER BY c.position
            """)).containsExactly("Check the timeout.", "Check the retry limit.");
        // Two accepted notes on the same lines but opposite sides stay two copies.
        assertThat(column(database, """
            SELECT c.side || '|' || c.body || '|' || c.state FROM feedback_copy c JOIN feedback f ON f.id=c.feedback_id
            WHERE f.review_id='00000000-0000-0000-0000-000000992621' AND c.kind='LINE_NOTE' ORDER BY c.position
            """))
                .containsExactly("OLD|Explain the removed line.|WITHHELD", "NEW|Explain the added line.|WITHHELD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveItemAndEventIdentity(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT i.kind || '|' || i.source_id || '|' || (i.removed_at IS NOT NULL) || '|' ||"
                                + " p.kind || '|' || p.source_id || '|' || (i.workspace_id=p.workspace_id) FROM"
                                + " review r JOIN item i ON i.id=r.item_id JOIN item p ON p.id=i.parent_id WHERE"
                                + " r.id='00000000-0000-0000-0000-000000992112'"))
                .containsExactly("scm.pull_request_review|992119|true|scm.pull_request|992109|true");
        assertThat(column(
                        database,
                        "SELECT e.name || '|' || e.actor_user_id || '|' || (e.item_id=r.item_id) FROM"
                                + " item_event e JOIN review r ON r.id='00000000-0000-0000-0000-000000992112'"
                                + " WHERE e.id='00000000-0000-0000-0000-000000992181'"))
                .containsExactly("scm.pull_request_review.submitted|992104|true");
        assertThat(column(database, """
            SELECT e.state || '|' || (e.retry_at <= now()) || '|' || e.item_version || '|'
                || (e.last_attempted_at = '2026-01-02T00:01:00Z'::timestamptz) || '|'
                || (e.item_id = i.id) || '|' || e.actor_user_id
            FROM item_event e JOIN item i ON i.workspace_id=e.workspace_id
                AND i.kind='scm.pull_request' AND i.source_id=992109
            WHERE e.id='00000000-0000-0000-0000-000000992184'
            """)).containsExactly("WAITING|true|pending-head|true|true|992103");
        assertThat(column(database, """
            SELECT e.id || '|' || e.name || '|' || e.item_version || '|' || i.kind || '|' || i.source_id || '|'
                || (e.occurred_at = CASE WHEN e.found_by='WEBHOOK' THEN '2025-12-30T00:00:00Z'
                                         ELSE '2025-12-31T00:00:00Z' END::timestamptz) || '|'
                || e.found_by || '|' || e.state || '|' || e.reason || '|'
                || (e.decided_at = '2025-12-30T00:00:05Z'::timestamptz) || '|'
                || (e.decided_at > '2025-12-31T00:00:05Z'::timestamptz) || '|'
                || coalesce((e.last_attempted_at = '2025-12-30T00:00:04Z'::timestamptz)::text, 'none') || '|'
                || (e.retry_at IS NULL) || '|' || (e.actor_user_id IS NULL) || '|' || (e.requested_by_id IS NULL)
            FROM item_event e JOIN item i ON i.id=e.item_id AND i.workspace_id=e.workspace_id
            WHERE e.id IN ('00000000-0000-0000-0000-000000992185','00000000-0000-0000-0000-000000992186')
            ORDER BY e.id
            """))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992185|scm.pull_request.reviewed"
                                + "|sha~1111111111111111111111111111111111111111|scm.pull_request|992109|true"
                                + "|WEBHOOK|WITHHELD|GATE_WITHHELD|true|false|true|true|true|true",
                        "00000000-0000-0000-0000-000000992186|scm.pull_request.reviewed"
                                + "|sha~2222222222222222222222222222222222222222|scm.pull_request|992109|true"
                                + "|SYNC|WITHHELD|LEGACY_REVIEW_UNBOUND|false|true|none|true|true|true");
        // A head-keyed review signal names no review: no review starts from it and nothing can claim it.
        assertThat(column(database, """
            SELECT (SELECT count(*) FROM review
                     WHERE event_id IN ('00000000-0000-0000-0000-000000992185','00000000-0000-0000-0000-000000992186'))
                || '|' || (SELECT count(*) FROM item_event
                     WHERE id IN ('00000000-0000-0000-0000-000000992185','00000000-0000-0000-0000-000000992186')
                       AND state IN ('RECORDED','WAITING') AND (retry_at IS NULL OR retry_at <= now()))
            """)).containsExactly("0|0");
        // A promoted GitHub issue stub keeps its own removed issue item beside the live pull request item.
        assertThat(column(database, """
            SELECT i.kind || '|' || (i.removed_at IS NOT NULL) || '|' || p.kind || '|' || p.source_id
            FROM item i JOIN item p ON p.id=i.parent_id
            WHERE i.workspace_id=992101 AND i.source_id=992109 AND i.kind IN ('scm.issue','scm.pull_request')
            ORDER BY i.kind
            """))
                .containsExactly(
                        "scm.issue|true|scm.repository|992108", "scm.pull_request|false|scm.repository|992108");
        assertThat(column(database, """
            SELECT e.id || '|' || e.name || '|' || e.item_version || '|' || i.kind || '|' || e.state || '|' || e.reason
                || '|' || (e.decided_at = e.occurred_at + interval '5 seconds') || '|' || (e.retry_at IS NULL)
            FROM item_event e JOIN item i ON i.id=e.item_id AND i.workspace_id=e.workspace_id
            WHERE e.id IN ('00000000-0000-0000-0000-000000992187','00000000-0000-0000-0000-000000992188')
            ORDER BY e.id
            """))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992187|scm.issue.opened|dig~stub-opened|scm.issue|WITHHELD"
                                + "|GATE_WITHHELD|true|true",
                        "00000000-0000-0000-0000-000000992188|scm.issue.opened|dig~stub-edited|scm.issue|EXPIRED"
                                + "|ITEM_GONE|true|true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreservePinnedDefinitionsAndTheirNativeIndexes(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM practice_version WHERE id=992131 AND"
                                + " definition->'reads'->>'contract'='1.3.0'"))
                .containsExactly("1");
        assertThat(column(
                        database,
                        "SELECT v.definition->'reads'->>'contract' FROM practice p JOIN practice_version v ON"
                                + " v.id=p.current_version_id WHERE p.id=992121"))
                .containsExactly("3.0.0");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE"
                                + " c.relname='uk_result_current' AND i.indisvalid AND i.indnullsnotdistinct"))
                .containsExactly("1");
        assertThat(
                        column(
                                database,
                                "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND"
                                        + " table_name IN"
                                        + " ('observation','practice_result','practice_observation','agent_job','artifact_signal','identity_link','workspace_membership')"))
                .containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldEnforceTenantAndEvidenceConstraints(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThatThrownBy(() ->
                        execute(database, "DELETE FROM membership WHERE workspace_id=992101 AND account_id=992106"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23001"));
        assertThatThrownBy(() -> execute(
                        database,
                        "INSERT INTO"
                                + " result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,summary,evidence_version,recorded_at)"
                                + " SELECT"
                                + " gen_random_uuid(),workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,summary,0,recorded_at"
                                + " FROM result WHERE id='"
                                + RESULT + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("evidence version 1");
        assertThatThrownBy(() -> execute(
                        database,
                        "INSERT INTO"
                                + " problem(id,workspace_id,result_id,position,severity,summary,explanation,citations)"
                                + " VALUES(gen_random_uuid(),992201,'"
                                + RESULT + "',1,'MINOR','Other','Other','[]')"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23503"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepAcceptedResultsCurrentWhenAnAcceptanceIsRemoved(String context) throws Exception {
        var database = migratedDatabase(context);
        execute(
                database,
                "INSERT INTO result_mark(id,workspace_id,result_id,kind,reason) VALUES(gen_random_uuid(),992101,'"
                        + RESULT + "','ACCEPTED','Accept this risk')");
        assertThat(column(database, "SELECT (ended_at IS NULL)::text FROM result WHERE id='" + RESULT + "'"))
                .containsExactly("true");
        execute(
                database,
                "UPDATE result_mark SET removed_at=now(),removal_reason='Reconsidered' WHERE result_id='" + RESULT
                        + "' AND kind='ACCEPTED'");
        assertThat(column(database, "SELECT (ended_at IS NULL)::text FROM result WHERE id='" + RESULT + "'"))
                .containsExactly("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldWithholdSuppressedFeedbackWithoutCreatingAnUnattemptedCopy(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT state || '|' || stop_reason FROM feedback WHERE"
                                + " id='00000000-0000-0000-0000-000000992316'"))
                .containsExactly("WITHHELD|OUTSIDE_CURRENT_SCOPE");
        assertThat(column(
                        database,
                        "SELECT id::text FROM feedback_copy WHERE feedback_id='00000000-0000-0000-0000-000000992316'"))
                .isEmpty();
        assertThat(column(database, "SELECT state FROM feedback_copy WHERE id='00000000-0000-0000-0000-000000992332'"))
                .containsExactly("WRITE_STARTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreservePracticeStateFiltersAndWorkspaceReviewScope(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT review_scope_repositories || '|' || review_scope_people FROM workspace WHERE"
                                + " id=992101"))
                .containsExactly("ALL|ALL");
        assertThat(column(
                        database,
                        "SELECT definition->'reviewOn'->'state'->'state' FROM practice_version WHERE id=992131"))
                .containsExactly("[\"OPEN\"]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveImmutableConsentFacts(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT account_id || '|' || purpose || '|' || granted || '|' || mechanism || '|' ||"
                                + " notice_version FROM consent_decision WHERE id=992251"))
                .containsExactly("992106|PRIVACY_NOTICE_ACKNOWLEDGEMENT|true|ACCOUNT_SETTINGS|2026-08-30");
        assertThatThrownBy(() -> execute(database, "UPDATE consent_decision SET granted=false WHERE id=992251"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("55000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRejectInvalidReadbackWithoutChangingPostedCopies(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThatThrownBy(() -> execute(
                        database,
                        "UPDATE feedback_copy SET readback='NOT_A_READBACK' WHERE"
                                + " id='00000000-0000-0000-0000-000000992171'"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        assertThat(column(
                        database,
                        "SELECT state || '|' || external_ref FROM feedback_copy WHERE"
                                + " id='00000000-0000-0000-0000-000000992171'"))
                .containsExactly("POSTED|provider:123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveCompositionApprovalAndResponseHistory(String context) throws Exception {
        var database = migratedDatabase(context);
        assertThat(column(
                        database,
                        "SELECT admission_attempt || '|' || admission_digest || '|' || basis_digest FROM"
                                + " review_composition_basis WHERE job_id='00000000-0000-0000-0000-000000992111'"))
                .containsExactly("0|" + "a".repeat(64) + "|" + "b".repeat(64));
        assertThat(column(
                        database,
                        "SELECT actor_account_id::text FROM feedback_decision WHERE"
                                + " id='00000000-0000-0000-0000-000000992261'"))
                .containsExactly("992106");
        assertThat(column(
                        database,
                        "SELECT f.developer_id::text || '|' || r.resolution || '|' || r.usefulness FROM"
                                + " feedback_response r JOIN feedback f ON f.id=r.feedback_id AND"
                                + " f.workspace_id=r.workspace_id WHERE r.id='00000000-0000-0000-0000-000000992263'"))
                .containsExactly("992106|ADDRESSED|HELPFUL");
        assertThat(column(database, """
            SELECT id::text || '|' || coalesce(resolution,'NONE') || '|' || coalesce(usefulness,'NONE')
                || '|' || coalesce(comment,'NONE') || '|' || to_char(created_at AT TIME ZONE 'UTC','HH24:MI:SS')
            FROM feedback_response
            WHERE id IN ('00000000-0000-0000-0000-000000992281',
                '00000000-0000-0000-0000-000000992282','00000000-0000-0000-0000-000000992283')
                AND workspace_id=992101 AND feedback_id='00000000-0000-0000-0000-000000992161'
            ORDER BY created_at
            """))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992281|DISAGREE|UNHELPFUL|The cited revision already explains why.|00:04:00",
                        "00000000-0000-0000-0000-000000992282|NOT_APPLICABLE|NONE|This change has no retry behavior.|00:05:00",
                        "00000000-0000-0000-0000-000000992283|NONE|HELPFUL|NONE|00:06:00");
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "prod,CATALOG_PRACTICE,992101",
                "dev,CATALOG_PRACTICE,992101",
                "prod,WORKSPACE_ROLE,NULL",
                "dev,WORKSPACE_ROLE,NULL",
                "prod,UNKNOWN,992101",
                "dev,UNKNOWN,992101"
            },
            nullValues = "NULL")
    void shouldRejectAuditEntriesOutsideTheirDeclaredScope(
            String context, String entityType, @Nullable Long workspaceId) throws Exception {
        var database = migratedDatabase(context);
        String scope = workspaceId == null ? "NULL" : workspaceId.toString();
        assertThatThrownBy(() -> execute(database, """
            INSERT INTO config_audit_event(id,workspace_id,actor_kind,entity_type,entity_id,action,
                changed_keys,old_value,new_value,occurred_at)
            VALUES(995908,%s,'SYSTEM','%s','scope-check','CREATED',ARRAY['name'],'{}','{}',now())
            """.formatted(scope, entityType)))
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("23514");
    }
}
