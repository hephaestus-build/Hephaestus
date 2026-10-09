package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the Pi session path that a Heph host seeds a session with ({@code SessionManager.inMemory}). It is rebuilt
 * from rows for every turn and is the provider's cached prompt prefix, so identical rows must give identical bytes.
 * Entries are grouped by turn, so a follow-up asked while a turn ran follows that turn's answer, and an entry written
 * outside a turn never splits a tool call from its result. {@code docs/contributor/unified-pi-runtime.mdx} § The path
 * has the order, the range and the markers.
 */
@Component
@RequiredArgsConstructor
public class MentorContextPathBuilder {

    static final String STOPPED_MARKER = "[stopped by the developer]";
    static final String FAILED_MARKER = "[no answer: the turn failed]";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter PREFIX_TIME =
            DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.ENGLISH);

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;

    /**
     * What a host needs to run a turn: the path through everything before the turn, and the turn's question
     * rendered as the prompt. The live session then appends the entries that later path rebuilds read.
     */
    public record TurnContext(UUID sessionId, List<ObjectNode> entries, UUID questionEntryId, String prompt) {}

    record Coverage(@Nullable Long fromSeq, @Nullable Long toSeq) {}

    /** One entry without its payload: enough to order, select and mark the path. */
    record Head(
            UUID id,
            UUID conversationId,
            long seq,
            MentorEntry.Kind kind,
            @Nullable UUID turnId,
            String piId,
            @Nullable String firstKeptEntryId,
            boolean omitted) {

        Object group() {
            UUID turn = turnId;
            return turn != null ? turn : id;
        }
    }

    /** Reads under a shared lock of the main conversation row, so no redaction of the tree runs meanwhile. */
    @Transactional
    public TurnContext forTurn(long workspaceId, UUID turnId, ZoneId zone) {
        MentorTurn turn = turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        MentorConversation conversation = conversation(workspaceId, turn.getConversationId());
        conversations.shareByIdAndWorkspaceId(conversation.rootId(), workspaceId);
        List<Head> visible = visible(conversation);
        List<Head> before = new ArrayList<>();
        for (Head head : visible) {
            if (turnId.equals(head.turnId())) {
                break;
            }
            before.add(head);
        }
        MentorEntry question = entries.findQuestion(turn.getWorkspaceId(), turn.getId());
        if (question.isRedacted()) {
            throw new IllegalStateException("The question of mentor turn " + turnId + " was deleted");
        }
        return new TurnContext(
                conversation.getId(),
                render(workspaceId, fromNewestSummary(before), zone),
                question.getId(),
                userText(question, zone));
    }

    /** Which of the conversation's own entries a summary that keeps {@code firstKeptEntryId} replaces. */
    Coverage coverage(MentorConversation conversation, @Nullable String firstKeptEntryId) {
        List<Head> visible = visible(conversation);
        int firstKept = indexOf(visible, firstKeptEntryId);
        Long from = null;
        Long to = null;
        for (Head head : visible.subList(0, firstKept < 0 ? visible.size() : firstKept)) {
            if (head.conversationId().equals(conversation.getId())) {
                from = from == null ? head.seq() : Math.min(from, head.seq());
                to = to == null ? head.seq() : Math.max(to, head.seq());
            }
        }
        return new Coverage(from, to);
    }

    /** The prompt text of a question: its prefix, then the developer's words. */
    static String userText(MentorEntry entry, ZoneId zone) {
        return prefix(entry.getCreatedAt(), entry.getSurface(), zone) + " " + entry.getText();
    }

    static String prefix(Instant createdAt, MentorEntry.Surface surface, ZoneId zone) {
        String where =
                switch (surface) {
                    case APP -> "app";
                    case SLACK -> "Slack";
                    case SYSTEM -> "Hephaestus";
                };
        return "[" + time(createdAt, zone) + " · " + where + "]";
    }

    /** A point in time as Heph reads it, e.g. {@code Thu 2026-10-08 09:12 Europe/Berlin}. */
    static String time(Instant instant, ZoneId zone) {
        return PREFIX_TIME.format(instant.atZone(zone)) + " " + zone.getId();
    }

    private MentorConversation conversation(long workspaceId, UUID conversationId) {
        return conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
    }

    /** Every entry the conversation can see, in path order, including those the path omits. */
    private List<Head> visible(MentorConversation conversation) {
        UUID parentConversation = conversation.getParentConversationId();
        UUID parentEntry = conversation.getParentEntryId();
        if (parentConversation == null || parentEntry == null) {
            return grouped(heads(conversation.getWorkspaceId(), conversation.getId()));
        }
        List<Head> main = grouped(heads(conversation.getWorkspaceId(), parentConversation));
        List<Head> path = new ArrayList<>();
        for (Head head : main) {
            path.add(head);
            if (head.id().equals(parentEntry)) {
                break;
            }
        }
        path.addAll(grouped(heads(conversation.getWorkspaceId(), conversation.getId())));
        return path;
    }

    private List<Head> heads(long workspaceId, UUID conversationId) {
        return entries.findHeadsByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                .map(row -> {
                    String piId = row.getPiId();
                    return new Head(
                            row.getId(),
                            row.getConversationId(),
                            row.getSeq(),
                            MentorEntry.Kind.valueOf(row.getKind()),
                            row.getTurnId(),
                            piId != null ? piId : row.getId().toString(),
                            row.getFirstKept(),
                            Boolean.TRUE.equals(row.getOmitted()));
                })
                .toList();
    }

    /** Groups a conversation's entries (in sequence order) by turn; groups keep the order of their first entry. */
    private static List<Head> grouped(List<Head> bySeq) {
        Map<Object, List<Head>> groups = new LinkedHashMap<>();
        for (Head head : bySeq) {
            groups.computeIfAbsent(head.group(), key -> new ArrayList<>()).add(head);
        }
        return groups.values().stream().flatMap(List::stream).toList();
    }

    /**
     * The heads the path renders, without omitted ones, and the summary that starts it.
     *
     * @param summaryId the newest valid summary, when the path starts at its first kept entry
     */
    private record Selection(List<Head> heads, @Nullable UUID summaryId) {}

    /** From the newest valid summary's first kept entry on, without omitted heads. */
    private static Selection fromNewestSummary(List<Head> visible) {
        for (int i = visible.size() - 1; i >= 0; i--) {
            Head head = visible.get(i);
            if (head.kind() == MentorEntry.Kind.SUMMARY && !head.omitted()) {
                int firstKept = indexOf(visible.subList(0, i + 1), head.firstKeptEntryId());
                if (firstKept >= 0) {
                    return new Selection(shown(visible.subList(firstKept, visible.size())), head.id());
                }
                break;
            }
        }
        return new Selection(shown(visible), null);
    }

    private static List<Head> shown(List<Head> heads) {
        return heads.stream().filter(head -> !head.omitted()).toList();
    }

    private static int indexOf(List<Head> heads, @Nullable String piId) {
        if (piId == null) {
            return -1;
        }
        for (int i = 0; i < heads.size(); i++) {
            if (heads.get(i).piId().equals(piId)) {
                return i;
            }
        }
        return -1;
    }

    private List<ObjectNode> render(long workspaceId, Selection selection, ZoneId zone) {
        List<Head> selected = selection.heads();
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<UUID, MentorEntry> rows =
                entries
                        .findByWorkspaceIdAndIdIn(
                                workspaceId, selected.stream().map(Head::id).toList())
                        .stream()
                        .collect(Collectors.toMap(MentorEntry::getId, Function.identity()));
        Map<UUID, MentorTurn.Status> statuses = new HashMap<>();
        List<UUID> turnIds = selected.stream()
                .map(Head::turnId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!turnIds.isEmpty()) {
            for (MentorTurnRepository.TurnStatus turn : turns.findStatusesByWorkspaceIdAndIdIn(workspaceId, turnIds)) {
                statuses.put(turn.getId(), turn.getStatus());
            }
        }
        Map<UUID, UUID> answers = lastAnswers(selected);

        List<ObjectNode> path = new ArrayList<>(selected.size());
        String parentId = null;
        for (Head head : selected) {
            MentorEntry entry = Objects.requireNonNull(rows.get(head.id()));
            ObjectNode rendered = renderEntry(entry, head.piId(), parentId, zone);
            if (rendered == null) {
                continue;
            }
            String marker = marker(entry, statuses, answers);
            if (marker != null) {
                if (STOPPED_MARKER.equals(marker)) {
                    keepCutOffAnswer(rendered.path("message"));
                }
                appendText(rendered.path("message"), marker);
            }
            if (head.id().equals(selection.summaryId())) {
                // Pi keeps the entries from the one this names; an erased question there is not in the path.
                rendered.put(
                        "firstKeptEntryId",
                        (path.isEmpty() ? rendered : path.getFirst()).path("id").asString());
            }
            rendered.put("parentId", parentId);
            path.add(rendered);
            parentId = rendered.path("id").asString();
        }
        return path;
    }

    /** The last answer entry of each turn in the path. */
    private static Map<UUID, UUID> lastAnswers(List<Head> selected) {
        Map<UUID, UUID> last = new HashMap<>();
        for (Head head : selected) {
            UUID turn = head.turnId();
            if (turn != null && head.kind() == MentorEntry.Kind.HEPH) {
                last.put(turn, head.id());
            }
        }
        return last;
    }

    /**
     * A failed turn marks its question. A stopped turn marks its last answer entry, or its question when it was
     * stopped before Heph wrote anything.
     */
    private static @Nullable String marker(
            MentorEntry entry, Map<UUID, MentorTurn.Status> statuses, Map<UUID, UUID> answers) {
        UUID turn = entry.getTurnId();
        if (turn == null) {
            return null;
        }
        boolean question = entry.getKind() == MentorEntry.Kind.USER;
        MentorTurn.Status status = statuses.get(turn);
        if (status == MentorTurn.Status.FAILED && question) {
            return FAILED_MARKER;
        }
        UUID answer = answers.get(turn);
        boolean marked = answer == null ? question : entry.getId().equals(answer);
        return status == MentorTurn.Status.STOPPED && marked ? STOPPED_MARKER : null;
    }

    private static @Nullable ObjectNode renderEntry(
            MentorEntry entry, String piId, @Nullable String parentId, ZoneId zone) {
        JsonNode payload = entry.getPayload();
        boolean question = entry.getKind() == MentorEntry.Kind.USER || entry.getKind() == MentorEntry.Kind.DIGEST;
        if (question) {
            ObjectNode rendered;
            if (payload != null && payload.isObject() && entry.getKind() == MentorEntry.Kind.USER) {
                rendered = ((ObjectNode) payload).deepCopy();
                if (entry.getEditedAt() != null) {
                    ((ObjectNode) rendered.path("message")).put("content", userText(entry, zone));
                }
            } else {
                rendered = NODES.objectNode()
                        .put("type", "message")
                        .put("id", piId)
                        .put("parentId", parentId)
                        .put("timestamp", entry.getCreatedAt().toString());
                rendered.putObject("message")
                        .put("role", "user")
                        .put("content", userText(entry, zone))
                        .put("timestamp", entry.getCreatedAt().toEpochMilli());
            }
            return rendered;
        }
        return payload != null && payload.isObject() ? ((ObjectNode) payload).deepCopy() : null;
    }

    /**
     * Pi leaves an answer the developer cut off out of every later request, because it ended {@code aborted} (or
     * {@code error} when the stop hit a tool). The path keeps what Heph had written as an ordinary answer instead,
     * without the tool calls that never ran, so the stop marker reaches the model and no tool call lacks its result.
     */
    private static void keepCutOffAnswer(JsonNode message) {
        if (!(message instanceof ObjectNode object)
                || !"assistant".equals(object.path("role").asString(""))) {
            return;
        }
        String stopReason = object.path("stopReason").asString("");
        if (!"aborted".equals(stopReason) && !"error".equals(stopReason)) {
            return;
        }
        object.put("stopReason", "stop");
        object.remove("errorMessage");
        if (object.path("content") instanceof ArrayNode blocks) {
            ArrayNode kept = NODES.arrayNode();
            for (JsonNode block : blocks) {
                if (!"toolCall".equals(block.path("type").asString(""))) {
                    kept.add(block);
                }
            }
            object.set("content", kept);
        }
    }

    /** Appends a marker to a message's content, whether Pi stored it as a string or as content blocks. */
    private static void appendText(JsonNode message, String marker) {
        if (!(message instanceof ObjectNode object)) {
            return;
        }
        JsonNode content = object.path("content");
        if (content instanceof ArrayNode blocks) {
            blocks.addObject().put("type", "text").put("text", marker);
        } else {
            object.put("content", content.asString("") + "\n\n" + marker);
        }
    }
}
