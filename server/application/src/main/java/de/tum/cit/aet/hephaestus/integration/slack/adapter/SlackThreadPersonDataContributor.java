package de.tum.cit.aet.hephaestus.integration.slack.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Remove only the subject's participation from a shared conversation. */
@ErasesColumns("slack_thread.participant_account_ids")
final class SlackThreadPersonDataContributor implements PersonDataContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    SlackThreadPersonDataContributor(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String store() {
        return "slack_thread";
    }

    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        return new PersonDataSelection(jdbc.query(
                "SELECT t.id, to_jsonb(ARRAY(SELECT account_id FROM unnest(t.participant_account_ids) account_id WHERE account_id = :account))::text FROM slack_thread t WHERE t.id = ANY(:conversations) OR EXISTS(SELECT 1 FROM unnest(t.participant_account_ids) account_id WHERE account_id = :account) ORDER BY t.id",
                JdbcPersonDataStore.parameters(person, mapper),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of(
                        "id", Long.toString(rs.getLong(1)), "participants", Objects.requireNonNull(rs.getString(2))))));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        List<JsonNode> rows = new ArrayList<>();
        for (var key : selection.rows())
            rows.addAll(jdbc.query(
                    "SELECT jsonb_build_object('id',id,'workspaceId',workspace_id,'slackChannelId',slack_channel_id,'slackThreadTs',slack_thread_ts,'participantAccountIds',CAST(:participants AS jsonb))::text FROM slack_thread WHERE id=:id",
                    Map.of(
                            "id",
                            Long.valueOf(key.columns().get("id")),
                            "participants",
                            key.columns().get("participants")),
                    (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1)))));
        return List.copyOf(rows);
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        long rows = 0;
        for (var key : selection.rows()) {
            int affected = jdbc.update(
                    "UPDATE slack_thread t SET message_count=(SELECT count(*) FROM slack_message m WHERE m.workspace_id=t.workspace_id AND m.slack_channel_id=t.slack_channel_id AND COALESCE(m.slack_thread_ts,m.slack_ts)=t.slack_thread_ts AND m.deleted_at IS NULL),first_ts=(SELECT min(m.slack_ts) FROM slack_message m WHERE m.workspace_id=t.workspace_id AND m.slack_channel_id=t.slack_channel_id AND COALESCE(m.slack_thread_ts,m.slack_ts)=t.slack_thread_ts AND m.deleted_at IS NULL),last_ts=(SELECT max(m.slack_ts) FROM slack_message m WHERE m.workspace_id=t.workspace_id AND m.slack_channel_id=t.slack_channel_id AND COALESCE(m.slack_thread_ts,m.slack_ts)=t.slack_thread_ts AND m.deleted_at IS NULL),last_reviewed_ts=NULL,participant_account_ids=ARRAY(SELECT account_id FROM unnest(participant_account_ids) account_id WHERE account_id NOT IN (SELECT value::bigint FROM jsonb_array_elements_text(CAST(:participants AS jsonb)))) WHERE id=:id",
                    Map.of(
                            "id",
                            Long.valueOf(key.columns().get("id")),
                            "participants",
                            key.columns().get("participants")));
            rows += affected;
        }
        return rows;
    }
}
