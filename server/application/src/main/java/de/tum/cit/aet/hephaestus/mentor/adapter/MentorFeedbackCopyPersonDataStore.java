package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Redacts Heph's messages in another person's conversation that delivered feedback about this person, exactly as
 * the feedback ledger references them. The conversation and Heph's other messages stay.
 */
final class MentorFeedbackCopyPersonDataStore extends JdbcPersonDataStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MentorRedaction redaction;
    private final List<PersonConversationCopySource> sources;

    MentorFeedbackCopyPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            MentorRedaction redaction,
            List<PersonConversationCopySource> sources) {
        super(
                jdbc,
                mapper,
                "mentor_entry_feedback_copy",
                "mentor_entry",
                "FALSE",
                "id,conversation_id,kind,text,created_at",
                "id",
                "",
                -240);
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.redaction = redaction;
        this.sources = List.copyOf(sources);
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
                SELECT e.id,e.workspace_id,c."workspaceId"
                FROM jsonb_to_recordset(CAST(:copies AS jsonb)) c("workspaceId" bigint,"entryId" uuid)
                JOIN mentor_entry e ON e.id=c."entryId"
                JOIN mentor_conversation m ON m.id=e.conversation_id
                WHERE NOT (m.user_id=ANY(:users)) AND e.redacted_at IS NULL
                ORDER BY e.id
                """, params, (rs, row) -> {
            if (rs.getLong(2) != rs.getLong(3)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A conversation copy has a different workspace. Correct the exact delivery reference of the copy.");
            }
            return new PersonDataSelection.RowKey(Map.of("id", Objects.requireNonNull(rs.getString(1))));
        });
        return new PersonDataSelection(rows.stream().distinct().toList());
    }

    @Override
    public long erase(PersonDataSelection selection) {
        redaction.redact(selection.rows().stream()
                .map(row -> UUID.fromString(Objects.requireNonNull(row.columns().get("id"))))
                .toList());
        return selection.rows().size();
    }
}
