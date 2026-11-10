package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.activity.ActivityFact;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Additional rows of one person, linked to the integration scenario's real account, workspace and review. */
final class PersonErasureAllStoresFixture {
    static final String CANARY = "person-all-stores-canary";
    private final JdbcTemplate jdbc;
    private final SchemaRowSeeder rows;
    private final long generatedIdBase;
    private final List<SeededRow> seeded = new ArrayList<>();

    record Subject(
            long account,
            long user,
            long provider,
            long workspace,
            UUID review,
            UUID job,
            UUID result,
            UUID feedback) {}

    private record SeededRow(String table, Map<String, Object> keys) {}

    PersonErasureAllStoresFixture(JdbcTemplate jdbc) {
        this(jdbc, 996100L);
    }

    PersonErasureAllStoresFixture(JdbcTemplate jdbc, long generatedIdBase) {
        this.jdbc = jdbc;
        this.generatedIdBase = generatedIdBase;
        rows = new SchemaRowSeeder(jdbc);
    }

    void seed(Subject person) {
        long account = person.account();
        long workspace = person.workspace();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp later = Timestamp.from(Instant.now().plusSeconds(86400));
        row("auth_rate_limit_bucket", "id", "login:acct:" + account + ":1");
        row(
                "identity_restriction",
                "provider_id",
                person.provider(),
                "provider_user_key",
                "42",
                "team_key",
                "",
                "kind",
                "HIDE_PUBLIC");
        row(
                "person_data_request",
                "administrator_account_id",
                account,
                "state",
                "PREVIEW",
                "scope_json",
                "{\"accountId\":" + account + ",\"identities\":[]}",
                "completed_json",
                "{\"prior-store\":{\"count\":1,\"completedAt\":\"2026-01-01T00:00:00Z\",\"administratorAccountId\":"
                        + account + "}}");
        row("passkey_credential", "id", CANARY, "account_id", account, "record_json", CANARY);
        row("passkey_challenge", "account_id", account, "purpose", "REGISTER", "options_json", CANARY);
        row("passkey_recovery_code", "hash", "a".repeat(64), "account_id", account);
        row("account_feature", "account_id", account, "flag", CANARY);
        row("account_public_activity_hidden_by_default", "account_id", account);
        row(
                "consent_decision",
                "id",
                996010L,
                "account_id",
                account,
                "purpose",
                "RESEARCH_PARTICIPATION",
                "mechanism",
                "ACCOUNT_SETTINGS",
                "granted",
                true);
        row(
                "auth_event",
                "id",
                996011L,
                "account_id",
                account,
                "acting_account_id",
                account,
                "viewed_account_id",
                account,
                "viewed_actor_id",
                person.user(),
                "event_type",
                "LOGIN",
                "result",
                "SUCCESS",
                "user_agent",
                CANARY,
                "details",
                CANARY);
        row(
                "config_audit_event",
                "id",
                996012L,
                "workspace_id",
                workspace,
                "actor_kind",
                "USER",
                "actor_account_id",
                account,
                "entity_type",
                "WORKSPACE_ROLE",
                "entity_id",
                Long.toString(account),
                "action",
                "UPDATED",
                "changed_keys",
                new String[] {"role"},
                "old_value",
                CANARY,
                "new_value",
                CANARY);
        singletonAttribution("instance_settings", "silent_mode_changed_by_account_id", account);
        jdbc.update(
                "UPDATE instance_settings SET silent_mode_changed_by_account_id=?,silent_mode_reason=? WHERE id=1",
                account,
                CANARY);
        row(
                "event_publication",
                "event_type",
                "test.PersonEvent",
                "listener_id",
                CANARY,
                "serialized_event",
                "{\"accountId\":" + account + ",\"text\":\"" + CANARY + "\"}");
        row(
                "account_export",
                "account_id",
                account,
                "status",
                "READY",
                "payload",
                CANARY.getBytes(StandardCharsets.UTF_8));
        row("notification_subscription", "account_id", account, "kind", "MENTOR_DIGEST", "enabled", true);
        UUID report = UUID.randomUUID();
        row(
                "product_feedback",
                "id",
                report,
                "account_id",
                account,
                "workspace_id",
                workspace,
                "kind",
                "BUG",
                "message",
                CANARY,
                "page_path",
                CANARY,
                "user_agent",
                CANARY,
                "resolved_by_account_id",
                account);
        row("product_feedback_consent", "account_id", account, "allow_public", false, "mention", false);
        row("product_feedback_filing", "feedback_id", report, "state", "DRAFT_QUEUED");
        singletonAttribution("product_feedback_destination", "updated_by_account_id", account);
        singletonAttribution("instance_llm_settings", "updated_by_account_id", account);
        UUID disclosure = UUID.randomUUID();
        row(
                "research_disclosure",
                "id",
                disclosure,
                "workspace_id",
                workspace,
                "created_by_account_id",
                account,
                "content_sha256",
                "a".repeat(64),
                "purpose",
                "RESEARCH_PARTICIPATION",
                "schema_name",
                "hephaestus.research-dataset",
                "schema_version",
                1,
                "created_at",
                now,
                "window_from",
                Timestamp.from(Instant.now().minusSeconds(86400)),
                "window_to",
                now,
                "cutoff_at",
                now,
                "use_until",
                later);
        row(
                "research_disclosure_subject",
                "disclosure_id",
                disclosure,
                "workspace_id",
                workspace,
                "account_id",
                account,
                "grant_decision_id",
                996010L,
                "participant_key",
                "p_" + "a".repeat(26));
        row(
                "slack_monitored_channel",
                "workspace_id",
                workspace,
                "slack_team_id",
                "T1",
                "slack_channel_id",
                "C1",
                "channel_name",
                "shared-channel");
        row("slack_participant_consent", "workspace_id", workspace, "slack_user_id", "UTARGET");
        row(
                "slack_channel_consent_event",
                "workspace_id",
                workspace,
                "slack_channel_id",
                "C1",
                "to_state",
                "ACTIVE",
                "actor_account_id",
                account,
                "reason",
                CANARY);
        long connection = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM connection WHERE workspace_id=? AND kind='SLACK'", Long.class, workspace));
        row(
                "connection_audit",
                "workspace_id",
                workspace,
                "connection_id",
                connection,
                "actor_account_id",
                account,
                "actor_kind",
                "USER",
                "actor_ref",
                CANARY,
                "detail",
                CANARY);
        row(
                "sync_job",
                "workspace_id",
                workspace,
                "connection_id",
                connection,
                "kind",
                "SLACK",
                "type",
                "RECONCILIATION",
                "trigger_source",
                "MANUAL",
                "triggered_by_account_id",
                account,
                "progress",
                "{\"text\":\"" + CANARY + "\"}",
                "error_summary",
                CANARY);
        row(
                "provider_event",
                "workspace_id",
                workspace,
                "provider",
                "github",
                "subject",
                "github.acme.app.push",
                "scope_key",
                workspace + ":github.acme.app",
                "payload",
                "{}".getBytes(StandardCharsets.UTF_8),
                "state",
                "DEAD",
                "ended_at",
                Timestamp.from(Instant.now()),
                "error_class",
                "Failure",
                "replayed_by",
                account);
        row("activity_event", "workspace_id", workspace, "actor_id", person.user(), "event_key", CANARY);
        row("activity_automation", "workspace_id", workspace, "user_id", person.user());
        seedActivityPublication(workspace, person.user(), CANARY);
        row(
                "review_batch",
                "workspace_id",
                workspace,
                "kind",
                "chat.thread",
                "from_at",
                now,
                "to_at",
                later,
                "requested_by_account_id",
                account,
                "confirmed_by_account_id",
                account);
        row(
                "review_schedule",
                "workspace_id",
                workspace,
                "kind",
                "chat.thread",
                "lookback_days",
                1,
                "next_run_at",
                later,
                "created_by_account_id",
                account);
        jdbc.update(
                "UPDATE review SET requested_by_id=? WHERE id=? AND workspace_id=?",
                account,
                person.review(),
                workspace);
        long item = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT item_id FROM result WHERE id=? AND workspace_id=?", Long.class, person.result(), workspace));
        row(
                "item_event",
                "workspace_id",
                workspace,
                "item_id",
                item,
                "name",
                "chat.thread.completed",
                "found_by",
                "REQUEST",
                "item_version",
                "v1",
                "actor_user_id",
                person.user(),
                "requested_by_id",
                account,
                "evidence",
                "{\"participants\":[{\"userId\":" + person.user() + "}],\"text\":\"" + CANARY + "\"}");
        row(
                "llm_usage_event",
                "workspace_id",
                workspace,
                "job_type",
                "PRACTICE_REVIEW",
                "source_type",
                "REVIEW_JOB",
                "source_id",
                person.job(),
                "review_id",
                person.review(),
                "model",
                "test-model",
                "model_kind",
                "CHAT",
                "funding_source",
                "INSTANCE",
                "pricing_state",
                "UNPRICED",
                "usage_provenance",
                "PROXY");
        row(
                "result_mark",
                "workspace_id",
                workspace,
                "result_id",
                person.result(),
                "kind",
                "INCORRECT",
                "reason",
                CANARY,
                "created_by_id",
                account,
                "created_at",
                now,
                "removed_by_id",
                account,
                "removed_at",
                later,
                "removal_reason",
                CANARY);
        row("feedback_response", "workspace_id", workspace, "feedback_id", person.feedback(), "comment", CANARY);
        UUID problemId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM problem WHERE result_id=? AND workspace_id=?", UUID.class, person.result(), workspace));
        seeded.add(new SeededRow("problem", Map.of("id", problemId)));
        row(
                "feedback_basis",
                "workspace_id",
                workspace,
                "feedback_id",
                person.feedback(),
                "result_id",
                person.result(),
                "problem_id",
                problemId,
                "developer_id",
                account,
                "ordinal",
                1);
        row(
                "feedback_decision",
                "workspace_id",
                workspace,
                "feedback_id",
                person.feedback(),
                "actor_account_id",
                account,
                "action",
                "WITHDRAW",
                "reason",
                CANARY,
                "note",
                CANARY);
    }

    /** A queued activity fact that names {@code actor}; person erasure deletes it. */
    void seedActivityPublication(long workspace, long actor, String sourceKey) {
        row(
                "event_publication",
                "event_type",
                ActivityFact.class.getName(),
                "listener_id",
                "activity-recording-" + actor,
                "serialized_event",
                "{\"workspaceId\":" + workspace + ",\"eventType\":\"PULL_REQUEST_OPENED\",\"occurredAt\":\""
                        + Instant.now() + "\",\"actorId\":" + actor + ",\"targetType\":\"PULL_REQUEST\","
                        + "\"targetId\":1,\"sourceKey\":\"" + sourceKey + "\"}");
    }

    void seedScm(long user, long provider, long base) {
        row(
                "organization",
                "id",
                base,
                "native_id",
                base,
                "provider_id",
                provider,
                "login",
                "shared-organization-" + base);
        row("team", "id", base + 1, "native_id", base + 1, "provider_id", provider, "slug", "shared-team-" + base);
        row(
                "repository",
                "id",
                base + 2,
                "native_id",
                base + 2,
                "provider_id",
                provider,
                "organization_id",
                base,
                "name_with_owner",
                "shared/repository-" + base);
        row("organization_membership", "organization_id", base, "user_id", user, "role", "MEMBER");
        row("team_membership", "team_id", base + 1, "user_id", user, "role", "MEMBER");
        row("team_repository_permission", "team_id", base + 1, "repository_id", base + 2, "permission", "READ");
        row("repository_collaborator", "repository_id", base + 2, "user_id", user, "permission", "READ");
        row(
                "milestone",
                "id",
                base + 3,
                "native_id",
                base + 3,
                "provider_id",
                provider,
                "repository_id",
                base + 2,
                "creator_id",
                user,
                "number",
                1,
                "state",
                "OPEN",
                "title",
                CANARY,
                "description",
                CANARY);
        row(
                "issue",
                "id",
                base + 4,
                "native_id",
                base + 4,
                "provider_id",
                provider,
                "repository_id",
                base + 2,
                "issue_type",
                "PULL_REQUEST",
                "author_id",
                user,
                "merged_by_id",
                user,
                "number",
                1,
                "state",
                "OPEN",
                "title",
                CANARY,
                "body",
                CANARY);
        row("issue_assignee", "issue_id", base + 4, "user_id", user);
        row("pull_request_requested_reviewers", "pull_request_id", base + 4, "user_id", user);
        row(
                "issue_comment",
                "id",
                base + 5,
                "native_id",
                base + 5,
                "provider_id",
                provider,
                "issue_id",
                base + 4,
                "author_id",
                user,
                "body",
                CANARY);
        row(
                "pull_request_review",
                "id",
                base + 6,
                "native_id",
                base + 6,
                "provider_id",
                provider,
                "pull_request_id",
                base + 4,
                "author_id",
                user,
                "state",
                "APPROVED",
                "body",
                CANARY);
        row(
                "pull_request_review_thread",
                "id",
                base + 7,
                "native_id",
                base + 7,
                "provider_id",
                provider,
                "pull_request_id",
                base + 4,
                "resolved_by_id",
                user,
                "state",
                "RESOLVED");
        row(
                "pull_request_review_comment",
                "id",
                base + 8,
                "native_id",
                base + 8,
                "provider_id",
                provider,
                "pull_request_id",
                base + 4,
                "review_id",
                base + 6,
                "thread_id",
                base + 7,
                "author_id",
                user,
                "body",
                CANARY);
        row(
                "git_commit",
                "id",
                base + 9,
                "repository_id",
                base + 2,
                "author_id",
                user,
                "committer_id",
                user,
                "sha",
                Long.toHexString(base).repeat(8).substring(0, 40),
                "author_email",
                CANARY,
                "committer_email",
                CANARY,
                "message",
                CANARY,
                "message_body",
                CANARY,
                "signature_signer_login",
                CANARY);
        row(
                "commit_contributor",
                "commit_id",
                base + 9,
                "user_id",
                user,
                "role",
                "CO_AUTHOR",
                "name",
                CANARY,
                "email",
                CANARY,
                "ordinal",
                0);
        row(
                "commit_identity_change",
                "target",
                "git_commit.author_id",
                "row_id",
                base + 9,
                "old_user_id",
                user,
                "new_user_id",
                user,
                "outcome",
                "resolved",
                "changed_at",
                Timestamp.from(Instant.now()));
        row("commit_file_change", "commit_id", base + 9, "filename", CANARY, "change_type", "MODIFIED");
    }

    void seedOutline(long workspace, long connection, String subject) {
        seedOutline(workspace, connection, subject, 999000L);
    }

    void seedOutline(long workspace, long connection, String subject, long collectionId) {
        row(
                "outline_collection",
                "id",
                collectionId,
                "workspace_id",
                workspace,
                "connection_id",
                connection,
                "collection_id",
                UUID.randomUUID().toString(),
                "state",
                "ENABLED",
                "sync_status",
                "COMPLETE");
        row(
                "outline_document",
                "workspace_id",
                workspace,
                "connection_id",
                connection,
                "document_id",
                UUID.randomUUID().toString(),
                "collection_id",
                UUID.randomUUID().toString(),
                "created_by_subject",
                subject,
                "created_by_name",
                CANARY,
                "updated_by_subject",
                subject,
                "updated_by_name",
                CANARY,
                "collaborator_subjects",
                "[\"" + subject + "\"]",
                "title",
                CANARY,
                "slug",
                CANARY,
                "body_markdown",
                CANARY,
                "content_hash",
                CANARY);
    }

    private void singletonAttribution(String table, String column, long account) {
        if (jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=1", account) == 0) {
            row(table, "id", 1L, column, account);
        } else {
            seeded.add(new SeededRow(table, Map.of("id", 1L)));
        }
    }

    private void row(String table, Object... fields) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            if (!(fields[index] instanceof String name))
                throw new IllegalArgumentException("A column name is required");
            values.put(name, fields[index + 1]);
        }
        var columns = jdbc.queryForList(
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=?",
                table);
        if (columns.stream().anyMatch(column -> "id".equals(column.get("column_name"))) && !values.containsKey("id")) {
            String type = columns.stream()
                    .filter(column -> "id".equals(column.get("column_name")))
                    .map(column -> String.valueOf(column.get("data_type")))
                    .findFirst()
                    .orElseThrow();
            values.put("id", type.equals("uuid") ? UUID.randomUUID() : generatedIdBase + seeded.size());
        }
        List<String> keyNames = strings(
                "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey) WHERE i.indrelid=CAST(? AS regclass) AND i.indisprimary ORDER BY a.attnum",
                table);
        for (String key : keyNames) {
            if (!values.containsKey(key)) {
                String type = columns.stream()
                        .filter(column -> key.equals(column.get("column_name")))
                        .map(column -> String.valueOf(column.get("data_type")))
                        .findFirst()
                        .orElseThrow();
                values.put(key, type.startsWith("timestamp") ? Timestamp.from(Instant.now()) : UUID.randomUUID());
            }
        }
        rows.insert(table, values);
        Map<String, Object> keys = new HashMap<>();
        for (String key : keyNames) keys.put(key, Objects.requireNonNull(values.get(key)));
        seeded.add(new SeededRow(table, Map.copyOf(keys)));
    }

    private List<String> strings(String sql, Object... arguments) {
        return jdbc.query(sql, (result, index) -> Objects.requireNonNull(result.getString(1)), arguments);
    }

    List<List<String>> snapshot() {
        return seeded.stream().map(this::read).toList();
    }

    private List<String> read(SeededRow row) {
        List<Object> arguments = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        row.keys().forEach((key, value) -> {
            predicates.add("t.\"" + key + "\"=?");
            arguments.add(value);
        });
        return strings(
                "SELECT to_jsonb(t)::text FROM \"" + row.table() + "\" t WHERE " + String.join(" AND ", predicates),
                arguments.toArray());
    }

    void assertNoPersonalContentOrReferences(Subject person, ObjectMapper mapper) {
        for (var row : seeded) {
            var remaining = read(row);
            for (String json : remaining) {
                assertThat(json)
                        .as("personal content in seeded %s", row.table())
                        .doesNotContain(CANARY);
                var data = mapper.readTree(json);
                if (row.table().equals("outline_document")) {
                    for (String field : List.of(
                            "title",
                            "slug",
                            "body_markdown",
                            "content_hash",
                            "created_by_subject",
                            "created_by_name",
                            "updated_by_subject",
                            "updated_by_name")) {
                        assertThat(data.path(field).isNull())
                                .as("erased Outline field %s", field)
                                .isTrue();
                    }
                    assertThat(data.path("collaborator_subjects")).isEmpty();
                }
                if (row.table().equals("person_data_request")) {
                    assertThat(data.path("scope_json").isNull()).isTrue();
                    assertThat(data.path("selections_json").isNull()).isTrue();
                    var receipts = mapper.readTree(data.path("completed_json").asString());
                    receipts.properties()
                            .forEach(step -> assertThat(step.getValue()
                                            .path("administratorAccountId")
                                            .asLong(-1))
                                    .isNotEqualTo(person.account()));
                }
                var references = strings(
                        "SELECT a.attname FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=ANY(c.conkey) WHERE c.contype='f' AND c.conrelid=CAST(? AS regclass) AND c.confrelid IN ('account'::regclass,'\"user\"'::regclass)",
                        row.table());
                for (String reference : references) {
                    assertThat(data.path(reference).isNull())
                            .as("personal reference %s.%s", row.table(), reference)
                            .isTrue();
                }
            }
        }
    }
}
