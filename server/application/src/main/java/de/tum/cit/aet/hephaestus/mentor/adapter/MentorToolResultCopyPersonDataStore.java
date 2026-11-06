package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.mentor.MentorContextChanges;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redacts the tool results that may copy anyone's data ({@link MentorEntryRepository#COPIES_PERSON_DATA}) in each
 * workspace where another person's Heph conversation may have read this person's data, together with answers derived
 * from those results. A tool result that copies no
 * one's, such as a {@code show_feedback} result, stays: the app reads it to show the feedback Heph showed.
 *
 * <p>The selection freezes every workspace of the person, not entries, so tool results written after the preview are
 * redacted too. This step runs last, after every step that erases a source that a context folder result ({@code read},
 * {@code bash}, {@code load_context}) reads. A turn that started before it stores its results when it ends; that end finds this step's receipt and erases them
 * ({@link MentorRedaction#eraseStaleCopies}). A turn that starts after it reads erased sources. They are Heph's runtime
 * memory, not anyone's record, and Heph reads current data again when it needs it. They are never exported.
 */
final class MentorToolResultCopyPersonDataStore extends JdbcPersonDataStore {
    /** After every other step, including the steps that erase the sources the context folder reads. */
    static final int ORDER = Integer.MAX_VALUE;

    private static final String PERSON_WORKSPACES = """
            SELECT workspace_id FROM membership WHERE account_id = :account
            UNION SELECT workspace_id FROM slack_thread WHERE id = ANY(:conversations)
            UNION SELECT workspace_id FROM outline_document WHERE id = ANY(:documents)
            UNION SELECT workspace_id FROM item
                WHERE kind IN ('scm.issue', 'scm.pull_request') AND source_id = ANY(:items)
            UNION SELECT workspace_id FROM review_job WHERE id = ANY(:derivedJobs)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MentorRedaction redaction;
    private final MentorContextChanges contextChanges;

    MentorToolResultCopyPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            MentorRedaction redaction,
            MentorContextChanges contextChanges) {
        super(
                jdbc,
                mapper,
                MentorEntryRepository.COPIES_PERSON_DATA_STORE,
                "mentor_entry",
                "FALSE",
                "workspace_id",
                "workspace_id",
                "",
                ORDER);
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.redaction = redaction;
        this.contextChanges = contextChanges;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        return new PersonDataSelection(jdbc.query(
                """
                SELECT person.workspace_id FROM (%s) person
                WHERE person.workspace_id IS NOT NULL
                ORDER BY person.workspace_id
                """.formatted(PERSON_WORKSPACES),
                parameters(person, mapper),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of("workspace_id", Long.toString(rs.getLong(1))))));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        return List.of();
    }

    /** Counts workspaces, matching the preview. */
    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        if (selection.rows().isEmpty()) return 0;
        Object[] workspaces = selection.rows().stream()
                .map(row -> Long.valueOf(Objects.requireNonNull(row.columns().get("workspace_id"))))
                .toArray();
        List<UUID> toolResults = jdbc.query(
                """
                SELECT e.id FROM mentor_entry e
                WHERE e.workspace_id = ANY(:workspaces) AND %s AND e.redacted_at IS NULL
                """.formatted(MentorEntryRepository.COPIES_PERSON_DATA),
                Map.of("workspaces", new SqlArrayValue("bigint", workspaces)),
                (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)));
        redaction.redactPersonCopies(toolResults);
        // A running turn of these workspaces may hold the person's data in its sandbox.
        for (Object workspace : workspaces) contextChanges.changed((Long) workspace);
        return selection.rows().size();
    }
}
