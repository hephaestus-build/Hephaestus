package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Keep another person's conversation and replies while removing an exactly linked feedback copy. */
final class MentorFeedbackCopyPersonDataStore extends JdbcPersonDataStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<PersonConversationCopySource> sources;
    private final boolean runtime;

    MentorFeedbackCopyPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            List<PersonConversationCopySource> sources,
            boolean runtime) {
        super(
                jdbc,
                mapper,
                runtime ? "chat_thread_feedback_runtime_copy" : "chat_message_feedback_copy",
                runtime ? "chat_thread" : "chat_message",
                "FALSE",
                runtime
                        ? "id,workspace_id,created_at,surface"
                        : "id,thread_id,created_at,role,parts,status,parent_message_id",
                runtime ? "id" : "id,thread_id",
                runtime
                        ? "session_jsonl=''::bytea"
                        : "parts='[{\"type\":\"text\",\"text\":\"This feedback was erased.\"}]'::jsonb,metadata=NULL",
                runtime ? -245 : -240);
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sources = List.copyOf(sources);
        this.runtime = runtime;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        var references = sources.stream()
                .flatMap(source -> source.conversationCopies(person).stream())
                .distinct()
                .toList();
        if (references.isEmpty()) return new PersonDataSelection(List.of());
        var params = new HashMap<>(parameters(person, mapper));
        params.put("copies", mapper.writeValueAsString(references));
        var rows = jdbc.query("""
                SELECT m.id,m.thread_id,t.workspace_id,c."workspaceId"
                FROM jsonb_to_recordset(CAST(:copies AS jsonb)) c("workspaceId" bigint,"messageId" uuid)
                JOIN chat_message m ON m.id=c."messageId"
                JOIN chat_thread t ON t.id=m.thread_id
                WHERE NOT (t.user_id=ANY(:users))
                ORDER BY m.id
                """, params, (rs, row) -> {
            if (rs.getLong(3) != rs.getLong(4)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A conversation copy has a different workspace; correct its exact delivery reference");
            }
            return new PersonDataSelection.RowKey(
                    runtime
                            ? Map.of("id", Objects.requireNonNull(rs.getString(2)))
                            : Map.of(
                                    "id",
                                    Objects.requireNonNull(rs.getString(1)),
                                    "thread_id",
                                    Objects.requireNonNull(rs.getString(2))));
        });
        return new PersonDataSelection(rows.stream().distinct().toList());
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        var rows = super.export(selection);
        return runtime
                ? rows
                : rows.stream()
                        .map(row -> MentorMessagePersonDataStore.conversationRow(mapper, row))
                        .toList();
    }
}
