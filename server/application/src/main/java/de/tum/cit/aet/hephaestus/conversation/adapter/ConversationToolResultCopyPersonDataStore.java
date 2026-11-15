package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationContextChanges;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationSandboxCopies;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redacts the tool results that may copy anyone's data ({@link ConversationEntryRepository#COPIES_PERSON_DATA}) in each
 * workspace where another person's Hephaestus conversation may have read this person's data, together with answers derived
 * from those results. A tool result that copies no
 * one's, such as a {@code show_feedback} result, stays: the app reads it to show the feedback Hephaestus showed.
 *
 * <p>The selection freezes every workspace of the person, not entries, so tool results written after the preview are
 * redacted too. This step runs last, after every step that erases a source that a context folder result ({@code read},
 * {@code bash}, {@code load_context}) reads. A turn that started before it stores its results when it ends; that end finds this step's receipt and erases them
 * ({@link ConversationRedaction#eraseStaleCopies}). A turn that starts after it reads erased sources. They are Hephaestus's runtime
 * memory, not anyone's record, and Hephaestus reads current data again when it needs it. They are never exported.
 */
final class ConversationToolResultCopyPersonDataStore extends JdbcPersonDataStore {
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

    private static final TypeReference<Set<UUID>> SANDBOX_IDS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationRedaction redaction;
    private final ConversationContextChanges contextChanges;
    private final ConversationSandboxCopies sandboxCopies;

    ConversationToolResultCopyPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            ConversationRedaction redaction,
            ConversationContextChanges contextChanges,
            ConversationSandboxCopies sandboxCopies) {
        super(
                jdbc,
                mapper,
                ConversationEntryRepository.COPIES_PERSON_DATA_STORE,
                "conversation_entry",
                "FALSE",
                "workspace_id",
                "workspace_id",
                "",
                ORDER);
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.redaction = redaction;
        this.contextChanges = contextChanges;
        this.sandboxCopies = sandboxCopies;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        List<Long> workspaces =
                jdbc.query("""
                SELECT person.workspace_id FROM (%s) person
                WHERE person.workspace_id IS NOT NULL
                ORDER BY person.workspace_id
                """.formatted(PERSON_WORKSPACES), parameters(person, mapper), (rs, row) -> rs.getLong(1));
        var copies = sandboxCopies.snapshot(Set.copyOf(workspaces));
        return new PersonDataSelection(workspaces.stream()
                .map(workspace -> new PersonDataSelection.RowKey(Map.of(
                        "workspace_id", workspace.toString(),
                        "sandbox_ids",
                                mapper.writeValueAsString(Objects.requireNonNull(copies.get(workspace)).stream()
                                        .sorted()
                                        .toList()))))
                .toList());
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        return List.of();
    }

    @Override
    public void prepareErasure(PersonDataSelection selection) {
        sandboxCopies.erase(selection.rows().stream()
                .collect(Collectors.toUnmodifiableMap(
                        row -> Long.valueOf(Objects.requireNonNull(row.columns().get("workspace_id"))),
                        this::frozenSandboxes)));
    }

    private Set<UUID> frozenSandboxes(PersonDataSelection.RowKey row) {
        String frozen = row.columns().get("sandbox_ids");
        // A persisted request from before the sandbox cutover has only its workspace key.
        return frozen == null ? Set.of() : Objects.requireNonNull(mapper.readValue(frozen, SANDBOX_IDS));
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
                SELECT e.id FROM conversation_entry e
                WHERE e.workspace_id = ANY(:workspaces) AND %s AND e.redacted_at IS NULL
                """.formatted(ConversationEntryRepository.COPIES_PERSON_DATA),
                Map.of("workspaces", new SqlArrayValue("bigint", workspaces)),
                (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)));
        redaction.redactPersonCopies(toolResults);
        // A running turn of these workspaces may hold the person's data in its sandbox.
        for (Object workspace : workspaces) contextChanges.changed((Long) workspace);
        return selection.rows().size();
    }
}
