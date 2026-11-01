package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.column;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.execute;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.migratedDatabase;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.releasedDatabase;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.seedBundledPractice;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.upgrade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class PracticeV1CutoverMigrationTest {
    private static final String RESULT = "00000000-0000-0000-0000-000000992141";
    private static final String FEEDBACK = "00000000-0000-0000-0000-000000992161";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveLinkedGoingWellWithoutReplacingCustomizedDefinitions(String context) throws Exception {
        final JsonNode entry;
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-cutover/released-catalog.json"))) {
            var entries = Objects.requireNonNull(JsonMapper.builder().build().readTree(input));
            entry = StreamSupport.stream(entries.spliterator(), false)
                    .filter(candidate ->
                            candidate.path("slug").asString().equals("keeps-functions-small-and-single-purpose"))
                    .findFirst()
                    .orElseThrow();
        }
        var database = releasedDatabase(context);
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            seedBundledPractice(connection, 1500000, 1510000, entry, null);
            seedBundledPractice(connection, 1500001, 1510001, entry, "Keep this exact workspace criterion.");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE practice SET slug='renamed-workspace-practice',name='Workspace name',"
                        + "why_it_matters='Workspace purpose',what_good_looks_like='Workspace example',"
                        + "visual='{\"svg\":\"<svg/>\",\"alt\":\"Workspace visual\"}'::jsonb,"
                        + "guide='{\"markdown\":\"Workspace guide\",\"figures\":{}}'::jsonb WHERE id=1500001");
            }
            seedBundledPractice(connection, 1500002, 1510002, entry, "Unlinked custom criterion.");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE practice SET slug='keeps-functions-small-and-single-purpose',"
                        + "source_curated_slug=NULL,source_curated_fingerprint=NULL WHERE id=1500002");
            }
            seedBundledPractice(connection, 1500003, 1510003, entry, "Another custom criterion.");
            try (var statement = connection.prepareStatement(
                    "UPDATE practice SET adopted_base_source='EXACT_ADOPTION',adopted_base="
                            + "jsonb_set(?::jsonb,'{holdsAs}',to_jsonb('Supplied positive phrase'::text)),slug='supplied-phrase' WHERE id=1500003")) {
                statement.setString(1, entry.path("oldDefinition").toString());
                statement.executeUpdate();
            }
            // A comparison base can carry JSON null, while authored columns have no positive-phrase field.
            seedBundledPractice(connection, 1500004, 1510004, entry, "JSON-null base criterion.");
            try (var statement =
                    connection.prepareStatement("UPDATE practice SET adopted_base_source='EXACT_ADOPTION',adopted_base="
                            + "jsonb_set(?::jsonb,'{holdsAs}','null'::jsonb) WHERE id=1500004")) {
                statement.setString(1, entry.path("oldDefinition").toString());
                statement.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                        signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,
                        precondition,precompute_script,why_it_matters,what_good_looks_like,visual,guide,created_at)
                    SELECT current_revision_id+100,id,2,slug,name,applies_to,subject,signals,evidence_requirements,
                        review_when,criteria,automated_review_policy,delivery_behavior,precondition,precompute_script,
                        why_it_matters,what_good_looks_like,visual,guide,'2026-01-02T00:00:00Z'
                    FROM practice WHERE id IN(1500001,1500002,1500003)
                    """);
                statement.executeUpdate("UPDATE practice SET current_revision_id=current_revision_id+100"
                        + " WHERE id IN(1500001,1500002,1500003)");
            }
            connection.commit();
        }
        upgrade(database, context);
        String phrase = entry.path("goingWell").asString();
        assertThat(column(database, """
            SELECT p.catalog_slug || '|' || (v.definition->>'name') || '|' || (v.definition->>'criteria')
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=1500001
            """))
                .containsExactly(
                        "keeps-functions-small-and-single-purpose|Workspace name|Keep this exact workspace criterion.");
        assertThat(column(database, """
            SELECT (v.definition#>>'{guidance,goingWell}') || '|' || (v.definition#>>'{guidance,whyItMatters}')
                || '|' || (v.definition#>>'{guidance,whatGoodLooksLike}') || '|' || (v.definition#>>'{guidance,visual,svg}')
                || '|' || (v.definition#>>'{guidance,visual,alt}') || '|' || (v.definition#>>'{guidance,guide,markdown}')
                || '|' || (v.definition#>>'{reads,contract}') || '|' || (v.definition->'script'='null'::jsonb)
                || '|' || (v.definition->'scan'='null'::jsonb)
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=1500001
            """))
                .containsExactly(
                        phrase
                                + "|Workspace purpose|Workspace example|<svg/>|Workspace visual|Workspace guide|3.0.0|true|true");
        assertThat(column(database, """
            SELECT (p.catalog_slug IS NULL)::text || '|' || (v.definition#>'{guidance,goingWell}'='null'::jsonb)
                || '|' || (v.definition->>'criteria')
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=1500002
            """)).containsExactly("true|true|Unlinked custom criterion.");
        assertThat(column(database, """
            SELECT (v.definition#>>'{guidance,goingWell}') || '|' || (v.definition#>>'{scan,itemKind}')
            FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=1500000
            """)).containsExactly(phrase + "|scm.file");
        assertThat(column(database, """
            SELECT catalog_base#>>'{definition,guidance,goingWell}' FROM practice WHERE id IN(1500003,1500004) ORDER BY id
            """)).containsExactly("Supplied positive phrase", phrase);
        assertThat(column(database, """
            SELECT definition#>'{guidance,goingWell}' FROM practice_version WHERE id=1510001
            """)).containsExactly("null");
    }

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
    void shouldPreserveReleasedProofAndStopOldExecutionWithoutRepeatingDelivery(String context) throws Exception {
        var database = releasedDatabase(context);
        upgrade(database, context);
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-cutover/assert-migrated.sql"))) {
            execute(database, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(column(
                        database,
                        "SELECT outcome || '|' || severity || '|' || evidence_version || '|' || (ended_at IS NULL)"
                                + " FROM result WHERE id='"
                                + RESULT + "'"))
                .containsExactly("NOT_MET|TRIVIAL|0|true");
        assertThat(column(
                        database,
                        "SELECT (p.id<>r.id)::text || '|' || jsonb_exists(p.citations->0,'sourcePath') || '|' ||"
                                + " (p.basis IS NULL) FROM problem p JOIN result r ON r.id=p.result_id WHERE r.id='"
                                + RESULT + "'"))
                .containsExactly("true|true|true");
        assertThat(column(database, """
            SELECT (citations->0->>'commit') || '|' || jsonb_exists(citations->0,'revision') || '|'
                || (citations->0->>'quote')
            FROM problem WHERE result_id='00000000-0000-0000-0000-000000992141'
            """)).containsExactly("a".repeat(40) + "|false|Add retry");
        assertThat(column(database, """
            SELECT jsonb_exists(evidence,'citations')::text FROM result
            WHERE id='00000000-0000-0000-0000-000000992141'
            """)).containsExactly("false");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM result r LEFT JOIN item i ON"
                                + " (i.id,i.workspace_id)=(r.item_id,r.workspace_id) WHERE r.id='"
                                + RESULT + "' AND i.id IS NULL"))
                .containsExactly("0");
        assertThat(column(database, "SELECT state || '|' || body FROM feedback WHERE id='" + FEEDBACK + "'"))
                .containsExactly("DELIVERED|Say why.");
        assertThat(column(database, "SELECT checks::text FROM feedback WHERE id='" + FEEDBACK + "'"))
                .containsExactly("{}");
        assertThat(column(
                        database,
                        "SELECT checks->'MIGRATION'->'feedback'->'" + FEEDBACK
                                + "'->'MIGRATION'->>'deliveryState' FROM review WHERE"
                                + " id='00000000-0000-0000-0000-000000992111'"))
                .containsExactly("DELIVERED");
        assertThat(column(
                        database,
                        "SELECT id::text || '|' || external_ref || '|' || state FROM feedback_copy WHERE feedback_id='"
                                + FEEDBACK + "'"))
                .containsExactly("00000000-0000-0000-0000-000000992171|provider:123|POSTED");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM feedback_send WHERE feedback_id='" + FEEDBACK
                                + "' AND state='PENDING'"))
                .containsExactly("0");
        assertThat(column(
                        database,
                        "SELECT f.state || '|' || (f.delivered_at IS NULL) || '|' || c.state || '|' || c.body"
                                + " FROM feedback f JOIN feedback_copy c ON c.feedback_id=f.id WHERE"
                                + " f.id='00000000-0000-0000-0000-000000992193'"))
                .containsExactly("UNCONFIRMED|true|WITHHELD|Words shown for this result.");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM feedback_send WHERE"
                                + " feedback_id='00000000-0000-0000-0000-000000992193'"))
                .containsExactly("0");
        // The Heph ledger replaces the chat history (ADR 0057): the copy keeps its words without the message.
        assertThat(column(
                        database,
                        "SELECT (to_regclass('chat_message') IS NULL)::text || '|' || (mentor_entry_id IS NULL) ||"
                                + " '|' || (mentor_turn_id IS NULL) FROM feedback_copy WHERE"
                                + " feedback_id='00000000-0000-0000-0000-000000992193'"))
                .containsExactly("true|true|true");
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
    void shouldReplayWithoutChangingMigratedProductFacts(String context) throws Exception {
        var database = migratedDatabase(context);
        var beforeReplay = column(
                database,
                "SELECT (SELECT count(*) FROM result WHERE workspace_id=992101)::text || '|' || (SELECT count(*) FROM"
                        + " feedback_copy WHERE workspace_id=992101) || '|' || (SELECT count(*) FROM feedback_send WHERE"
                        + " workspace_id=992101)");
        upgrade(database, context);
        assertThat(column(
                        database,
                        "SELECT (SELECT count(*) FROM result WHERE workspace_id=992101)::text || '|' ||"
                                + " (SELECT count(*) FROM feedback_copy WHERE workspace_id=992101) || '|' ||"
                                + " (SELECT count(*) FROM feedback_send WHERE workspace_id=992101)"))
                .isEqualTo(beforeReplay);
        assertThat(column(database, "SELECT locked::text FROM databasechangeloglock WHERE id=1"))
                .containsExactly("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldHaltAndRollBackWhenFeedbackPeopleDisagree(String context) throws Exception {
        var database = releasedDatabase(context);
        execute(database, "UPDATE feedback SET about_user_id=992104 WHERE id='" + FEEDBACK + "'");
        assertThatThrownBy(() -> upgrade(database, context)).hasMessageContaining("feedback person is ambiguous");
        assertThat(column(database, "SELECT count(*)::text FROM observation WHERE id='" + RESULT + "'"))
                .containsExactly("1");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND"
                                + " table_name='result'"))
                .containsExactly("0");
        assertThat(column(database, "SELECT locked::text FROM databasechangeloglock WHERE id=1"))
                .containsExactly("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepPostedSendsFinishedAndConsentFactsImmutableWhenReleasedDataIsMigrated(String context)
            throws Exception {
        var database = releasedDatabase(context);
        upgrade(database, context);
        assertThat(column(
                        database,
                        "SELECT feedback_id::text || '|' || action || '|' || state || '|' || attempts FROM"
                                + " feedback_send WHERE feedback_id IN"
                                + " ('00000000-0000-0000-0000-000000992161','00000000-0000-0000-0000-000000992231','00000000-0000-0000-0000-000000992232')"
                                + " ORDER BY feedback_id"))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992161|POST|DONE|2",
                        "00000000-0000-0000-0000-000000992231|POST|DONE|3",
                        "00000000-0000-0000-0000-000000992232|POST|DONE|4");
        assertThat(column(
                        database,
                        "SELECT feedback_id::text || '|' || action || '|' || state FROM feedback_send"
                                + " WHERE feedback_id BETWEEN '00000000-0000-0000-0000-000000992311'::uuid"
                                + " AND '00000000-0000-0000-0000-000000992316'::uuid ORDER BY feedback_id"))
                .containsExactly(
                        "00000000-0000-0000-0000-000000992311|POST|PENDING",
                        "00000000-0000-0000-0000-000000992312|POST|UNCERTAIN",
                        "00000000-0000-0000-0000-000000992313|POST|UNCERTAIN",
                        "00000000-0000-0000-0000-000000992314|POST|DONE",
                        "00000000-0000-0000-0000-000000992315|RETRACT|PENDING",
                        "00000000-0000-0000-0000-000000992316|POST|DONE");
        assertThat(column(
                        database,
                        "SELECT id::text || '|' || state || '|' || external_ref || '|' ||"
                                + " (posted_at='2026-01-01T00:01:30Z'::timestamptz) FROM feedback_copy"
                                + " WHERE id='00000000-0000-0000-0000-000000992334'"))
                .containsExactly("00000000-0000-0000-0000-000000992334|RETRACTED|provider:updated|true");
        assertThat(column(database, """
            SELECT kind || '|' || state || '|' || (write_started_at IS NULL) || '|' || (external_ref IS NULL)
                || '|' || (posted_at IS NULL)
            FROM feedback_copy WHERE feedback_id='00000000-0000-0000-0000-000000992314' AND kind='LINE_NOTE'
            """)).containsExactly("LINE_NOTE|FAILED|true|true|true");
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
    @ValueSource(strings = {"prod", "dev"})
    void shouldRenameOnlyPersistedReviewAuditKeysAndRestoreTheAppendOnlyGuard(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        String facts = "SELECT (to_jsonb(e)-'entity_type')::text FROM config_audit_event e"
                + " WHERE id BETWEEN 995901 AND 995907 ORDER BY id";
        String guard = "SELECT pg_get_triggerdef(oid) || '|' || tgenabled::text FROM pg_trigger"
                + " WHERE tgrelid='config_audit_event'::regclass AND tgname='trg_config_audit_event_block_mutation'";
        var before = PracticeMigrationFixture.column(database, facts);
        var protection = PracticeMigrationFixture.column(database, guard);
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(database, facts)).isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(database, guard)).isEqualTo(protection);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT id || '|' || entity_type FROM config_audit_event WHERE id BETWEEN 995901 AND 995907 ORDER BY id
            """))
                .containsExactly(
                        "995901|REVIEW_BATCH",
                        "995902|REVIEW_SCHEDULE",
                        "995903|PRACTICE_REVIEW_SETTINGS",
                        "995904|CATALOG_PRACTICE",
                        "995905|CATALOG_GROUP",
                        "995906|CATALOG_GROUP",
                        "995907|PRACTICE_GROUP");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT convalidated::text FROM pg_constraint WHERE conrelid='config_audit_event'::regclass
                AND conname IN ('ck_config_audit_event_entity_type','ck_config_audit_event_scope') ORDER BY conname
            """)).containsExactly("true", "true");
        if (context.equals("prod")) {
            assertThat(protection).hasSize(1);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database, "UPDATE config_audit_event SET entity_type='AGENT_BINDING' WHERE id=995901"))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepEveryLegacySiblingAndWithholdAmbiguousAssignments(String context) throws Exception {
        var database = releasedDatabase(context);
        PracticeMigrationFixture.seedDuplicateHistory(database);
        upgrade(database, context);
        assertThat(column(database, """
            SELECT right(id::text,6)||'|'||outcome||'|'||summary||'|'||explanation||'|'||evidence_version
            FROM result WHERE right(id::text,6) IN ('996141','996142','996143') ORDER BY id
            """))
                .containsExactly(
                        "996141|NOT_MET|Retained summary 996141|Retained explanation 996141|0",
                        "996142|MET|Retained summary 996142|Retained explanation 996142|0",
                        "996143|NOT_MET|Retained summary 996143|Retained explanation 996143|0");
        assertThat(column(database, """
            SELECT count(*)::text FROM result WHERE right(id::text,6) IN ('996141','996142','996143')
                AND recorded_at='2026-01-06T00:00:30Z'
            """)).containsExactly("3");
        assertThat(column(database, """
            SELECT end_reason||'|'||ended_at::date FROM result WHERE id='00000000-0000-0000-0000-000000996143'
            """)).containsExactly("WORK_CHANGED|2026-01-07");
        assertThat(column(database, """
            SELECT right(r.id::text,6)||'|'||p.summary||'|'||p.explanation||'|'||(p.citations->0->>'quote')
            FROM result r JOIN problem p ON p.result_id=r.id WHERE right(r.id::text,6) IN ('996141','996143')
            ORDER BY r.id
            """))
                .containsExactly(
                        "996141|Retained summary 996141|Retained explanation 996141|Quote 996141",
                        "996143|Retained summary 996143|Retained explanation 996143|Quote 996143");
        assertThat(column(database, """
            SELECT evidence->'citations'->0->>'quote' FROM result
            WHERE id='00000000-0000-0000-0000-000000996142'
            """)).containsExactly("Quote 996142");
        assertThat(column(database, """
            SELECT state||'|'||(result_id IS NULL)||'|'||reason||'|'||right(job_id::text,6)||'|'||detail::text
            FROM review_item WHERE review_id='00000000-0000-0000-0000-000000996111'
            """))
                .containsExactly("WITHHELD|true|LEGACY_AMBIGUOUS_RESULTS|996111|{\"legacyResults\": 3}");
        assertThat(column(database, """
            SELECT status||'|'||reason FROM review_practice
            WHERE review_id='00000000-0000-0000-0000-000000996111'
            """)).containsExactly("WITHHELD|LEGACY_AMBIGUOUS_RESULTS");
        assertThat(column(database, """
            SELECT right(id::text,6) FROM result WHERE practice_id=992121 AND developer_id=992106
                AND item_id=(SELECT item_id FROM review WHERE id='00000000-0000-0000-0000-000000996111')
                AND ended_at IS NULL
            """)).containsExactly("996144");
        assertThat(column(database, """
            SELECT right(f.id::text,6)||'|'||f.body||'|'||right(b.result_id::text,6)||'|'||
                coalesce(p.summary,'none')||'|'||right(c.id::text,6)||'|'||c.external_ref
            FROM feedback f JOIN feedback_basis b ON b.feedback_id=f.id
            LEFT JOIN problem p ON p.id=b.problem_id JOIN feedback_copy c ON c.feedback_id=f.id
            WHERE right(f.id::text,6) IN ('996161','996162','996163') ORDER BY f.id
            """))
                .containsExactly(
                        "996161|Retained advice 996161|996141|Retained summary 996141|996171|provider:996161",
                        "996162|Retained advice 996162|996142|none|996172|provider:996162",
                        "996163|Retained advice 996163|996143|Retained summary 996143|996173|provider:996163");
        assertThat(column(database, """
            SELECT right(review_id::text,6)||'|'||state||'|'||coalesce(right(result_id::text,6),reason)
            FROM review_item WHERE right(review_id::text,6) IN ('996114','996115') ORDER BY review_id
            """))
                .containsExactly("996114|WITHHELD|LEGACY_REUSE_UNBOUND", "996115|REUSED|996145");
        assertThat(column(database, """
            SELECT status||'|'||stop_reason FROM review WHERE id='00000000-0000-0000-0000-000000996114'
            """)).containsExactly("PARTIAL|RESULT_WITHHELD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRankEqualTimeLegacySiblingsWithoutChoosingAnAssignmentWinner(String context) throws Exception {
        var database = releasedDatabase(context);
        PracticeMigrationFixture.seedDuplicateHistory(database);
        execute(database, """
            DELETE FROM observation WHERE id='00000000-0000-0000-0000-000000996144';
            DELETE FROM agent_job WHERE id='00000000-0000-0000-0000-000000996112';
            """);
        upgrade(database, context);
        assertThat(column(database, """
            SELECT right(id::text,6) FROM result WHERE practice_id=992121 AND developer_id=992106 AND ended_at IS NULL
            """)).containsExactly("996142");
        assertThat(column(database, """
            SELECT state||'|'||(result_id IS NULL) FROM review_item
            WHERE review_id='00000000-0000-0000-0000-000000996111'
            """)).containsExactly("WITHHELD|true");
    }
}
