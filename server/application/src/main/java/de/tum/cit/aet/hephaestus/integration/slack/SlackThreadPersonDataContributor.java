package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Remove only the subject's participation from a shared conversation. */
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
                "SELECT t.id, to_jsonb(ARRAY(SELECT member_id FROM unnest(t.participant_member_ids) member_id WHERE member_id IN (:users)))::text FROM slack_thread t WHERE EXISTS(SELECT 1 FROM unnest(t.participant_member_ids) member_id WHERE member_id IN (:users)) ORDER BY t.id",
                JdbcPersonDataStore.parameters(person, mapper),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of(
                        "id", Long.toString(rs.getLong(1)), "participants", Objects.requireNonNull(rs.getString(2))))));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        List<JsonNode> rows = new ArrayList<>();
        for (var key : selection.rows())
            rows.addAll(jdbc.query(
                    "SELECT jsonb_build_object('id',id,'workspaceId',workspace_id,'slackChannelId',slack_channel_id,'slackThreadTs',slack_thread_ts,'participantMemberIds',CAST(:participants AS jsonb))::text FROM slack_thread WHERE id=:id",
                    Map.of(
                            "id",
                            Long.valueOf(key.columns().get("id")),
                            "participants",
                            key.columns().get("participants")),
                    (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1)))));
        return List.copyOf(rows);
    }

    @Override
    public long erase(PersonDataSelection selection) {
        long rows = 0;
        for (var key : selection.rows()) {
            int affected = jdbc.update(
                    "UPDATE slack_thread SET participant_member_ids=ARRAY(SELECT member_id FROM unnest(participant_member_ids) member_id WHERE member_id NOT IN (SELECT value::bigint FROM jsonb_array_elements_text(CAST(:participants AS jsonb)))) WHERE id=:id",
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
