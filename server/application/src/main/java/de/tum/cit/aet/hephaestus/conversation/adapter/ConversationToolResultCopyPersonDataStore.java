package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationContextChanges;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationSandboxCopies;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redacts the tool results that may copy anyone's data ({@link ConversationEntryRepository#COPIES_PERSON_DATA}) in each
 * workspace where another person's Hephaestus conversation may have read this person's data, together with answers derived
 * from those results. A tool result that copies no
 * one's, such as a {@code show_feedback} result, stays: the app reads it to show the feedback Hephaestus showed. Before
 * any step of the erasure, it deletes the physical sandbox copies of those workspaces and of the person's personal space,
 * and a failed deletion stops the erasure until a retry deletes them.
 *
 * <p>Outline copies are selected by the exact instance and team recorded before a turn receives its context.
 * The selection freezes scope, not entries, so tool results written after the preview are redacted too.
 * This step runs last, after every step that erases a source that a context folder result ({@code read},
 * {@code bash}, {@code load_context}) reads. A turn that started before it stores its results when it ends; that end finds this step's receipt and erases them
 * ({@link ConversationRedaction#eraseStaleCopies}). A turn that starts after it reads erased sources. They are Hephaestus's runtime
 * memory, not anyone's record, and Hephaestus reads current data again when it needs it. They are never exported.
 */
final class ConversationToolResultCopyPersonDataStore extends JdbcPersonDataStore {
    /** After every other step, including the steps that erase the sources the context folder reads. */
    static final int ORDER = Integer.MAX_VALUE;

    private static final String PERSON_WORKSPACES = """
            SELECT workspace_id FROM slack_thread WHERE id = ANY(:conversations)
            UNION SELECT workspace_id FROM item
                WHERE kind IN ('scm.issue', 'scm.pull_request') AND source_id = ANY(:items)
            UNION SELECT workspace_id FROM review_job WHERE id = ANY(:derivedJobs)
            """;

    private static final TypeReference<Set<UUID>> SANDBOX_IDS = new TypeReference<>() {};
    private static final TypeReference<List<OutlinePersonCopyScope.Source>> OUTLINE_SOURCES = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationRedaction redaction;
    private final ConversationContextChanges contextChanges;
    private final ConversationSandboxCopies sandboxCopies;
    private final WorkspaceMemberships memberships;
    private final Optional<OutlinePersonCopyScope> outline;

    ConversationToolResultCopyPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            ConversationRedaction redaction,
            ConversationContextChanges contextChanges,
            ConversationSandboxCopies sandboxCopies,
            WorkspaceMemberships memberships,
            Optional<OutlinePersonCopyScope> outline) {
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
        this.memberships = memberships;
        this.outline = outline;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        boolean onlyOutline = outline.filter(source -> source.onlyOutline(person)).isPresent();
        Set<Long> broad = onlyOutline ? Set.of() : new TreeSet<>(jdbc.query("""
                SELECT person.workspace_id FROM (%s) person
                WHERE person.workspace_id IS NOT NULL
                """.formatted(PERSON_WORKSPACES), parameters(person, mapper), (rs, row) -> rs.getLong(1)));
        Map<Long, List<OutlinePersonCopyScope.Source>> sources =
                outline.map(source -> source.sources(person)).orElseGet(Map::of);
        var found = new TreeSet<>(broad);
        found.addAll(sources.keySet());
        // The person's personal space too: no other person's conversation runs there, but its sandboxes hold the
        // person's own copies, and the space's erasure step relies on this store to delete them.
        if (person.accountId() != null) {
            var memberWorkspaces = memberships.workspaceIds(person.accountId());
            found.addAll(memberWorkspaces);
            if (!onlyOutline) broad.addAll(memberWorkspaces);
        }
        List<Long> workspaces = List.copyOf(found);
        var copies = sandboxCopies.snapshot(Set.copyOf(workspaces));
        return new PersonDataSelection(workspaces.stream()
                .map(workspace -> new PersonDataSelection.RowKey(Map.of(
                        "workspace_id", workspace.toString(),
                        "broad", Boolean.toString(broad.contains(workspace)),
                        "outline_sources", mapper.writeValueAsString(sources.getOrDefault(workspace, List.of())),
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

    /**
     * Also every current workspace of the frozen account, its personal space included: a resumed erasure deletes their
     * physical copies whatever an interrupted attempt left, and a selection frozen before the personal space was
     * selected here still reaches it.
     */
    @Override
    public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
        var copies = new HashMap<>(frozenCopies(selection));
        outline.ifPresent(source -> {
            source.verify(frozenScope);
            Map<Long, List<OutlinePersonCopyScope.Source>> required = source.sources(frozenScope);
            for (var row : selection.rows()) {
                long workspaceId = workspace(row);
                var sources = outlineSources(row);
                if (!sources.containsAll(required.getOrDefault(workspaceId, List.of())))
                    throw new IllegalStateException("The frozen erasure is missing Outline copy sources");
                if (!sources.isEmpty()) requireCopyProvenance(workspaceId, frozenScope);
            }
            if (!copies.keySet().containsAll(required.keySet()))
                throw new IllegalStateException("The frozen erasure is missing Outline copy workspaces");
        });
        if (frozenScope.accountId() != null)
            memberships
                    .workspaceIds(frozenScope.accountId())
                    .forEach(workspace -> copies.putIfAbsent(workspace, Set.of()));
        sandboxCopies.erase(copies);
    }

    private Map<Long, Set<UUID>> frozenCopies(PersonDataSelection selection) {
        return selection.rows().stream()
                .collect(Collectors.toUnmodifiableMap(
                        row -> Long.valueOf(Objects.requireNonNull(row.columns().get("workspace_id"))),
                        this::frozenSandboxes));
    }

    private Set<UUID> frozenSandboxes(PersonDataSelection.RowKey row) {
        return Objects.requireNonNull(
                mapper.readValue(Objects.requireNonNull(row.columns().get("sandbox_ids")), SANDBOX_IDS));
    }

    private static long workspace(PersonDataSelection.RowKey row) {
        return Long.parseLong(Objects.requireNonNull(row.columns().get("workspace_id")));
    }

    private List<OutlinePersonCopyScope.Source> outlineSources(PersonDataSelection.RowKey row) {
        return Objects.requireNonNull(mapper.readValue(
                row.columns().getOrDefault("outline_sources", "[]"), OUTLINE_SOURCES));
    }

    private void requireCopyProvenance(long workspaceId, PersonScope scope) {
        Boolean unknown = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM conversation_entry e
                  LEFT JOIN conversation_turn t ON t.id=e.turn_id AND t.workspace_id=e.workspace_id
                  WHERE e.workspace_id=:workspaceId AND e.redacted_at IS NULL AND %s
                    AND e.account_id<>:erasedAccountId
                    AND t.outline_context IS NULL)
                """.formatted(ConversationEntryRepository.COPIES_PERSON_DATA),
                Map.of("workspaceId", workspaceId, "erasedAccountId", Objects.requireNonNullElse(scope.accountId(), -1L)),
                Boolean.class);
        if (Boolean.TRUE.equals(unknown))
            throw new IllegalStateException("Outline conversation copies have no recorded source");
    }

    /** Counts workspaces, matching the preview. */
    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        for (var row : selection.rows()) {
            long workspaceId = workspace(row);
            var sources = outlineSources(row);
            if (!sources.isEmpty()) {
                requireCopyProvenance(workspaceId, frozenScope);
                var affected = jdbc.query("""
                    SELECT e.id FROM conversation_entry e
                    JOIN conversation_turn t ON t.id=e.turn_id AND t.workspace_id=e.workspace_id
                    WHERE e.workspace_id=:workspaceId AND e.redacted_at IS NULL
                      AND (e.kind IN ('ANSWER','DIGEST','SYSTEM') OR (%s))
                      AND EXISTS (SELECT 1 FROM jsonb_array_elements(CAST(:sources AS jsonb)) AS k(value)
                                  WHERE t.outline_context @> jsonb_build_array(k.value))
                    """.formatted(ConversationEntryRepository.COPIES_PERSON_DATA),
                        Map.of("workspaceId", workspaceId, "sources", mapper.writeValueAsString(sources)),
                        (rs, index) -> Objects.requireNonNull(rs.getObject(1, UUID.class)));
                redaction.redact(affected);
            }
            if (Boolean.parseBoolean(row.columns().getOrDefault("broad", "true"))) {
                var toolResults = jdbc.query("""
                    SELECT e.id FROM conversation_entry e
                    LEFT JOIN conversation_turn t ON t.id=e.turn_id AND t.workspace_id=e.workspace_id
                    WHERE e.workspace_id=:workspaceId AND e.redacted_at IS NULL
                      AND ((%s) OR (NOT :allSources AND e.kind IN ('ANSWER','DIGEST','SYSTEM')))
                      AND (:allSources OR t.outline_context='[]'::jsonb)
                    """.formatted(ConversationEntryRepository.COPIES_PERSON_DATA),
                        Map.of("workspaceId", workspaceId, "allSources", sources.isEmpty()),
                        (rs, index) -> Objects.requireNonNull(rs.getObject(1, UUID.class)));
                if (sources.isEmpty()) redaction.redactPersonCopies(toolResults);
                else redaction.redact(toolResults);
            }
            // A running turn may hold a physical copy even when it stored no ledger entry yet.
            contextChanges.changed(workspaceId);
        }
        return selection.rows().size();
    }
}
