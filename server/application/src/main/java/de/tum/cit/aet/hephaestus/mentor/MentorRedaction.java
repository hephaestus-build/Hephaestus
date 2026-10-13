package de.tum.cit.aet.hephaestus.mentor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Erases entries and every copy of their words: the summaries that could have read them, and the
 * {@code search_conversation} results that quote them. An edit of a Slack message erases only the copies
 * ({@link #eraseCopiesOf}).
 *
 * <ul>
 *   <li>A redacted entry keeps its row and {@code seq}, and loses its text.
 *   <li>A redacted {@code HEPH} or {@code TOOL_RESULT} entry keeps a Pi skeleton: its tool calls without arguments,
 *       or its result without content, with {@code ERASED} as text. A provider request needs each tool call paired.
 *   <li>A redacted question keeps its Pi id and parent, so a summary that keeps the conversation from it still finds
 *       its place.
 *   <li>A {@code SUMMARY} covers its own conversation's entries in its {@code fromSeq..toSeq} payload range. A
 *       thread's summary may also have read its main conversation, so a redaction there erases the summaries of all
 *       its threads: one more compaction costs less than erased words in Heph's context.
 * </ul>
 *
 * <p>A redaction or an edit locks the main conversation row of every tree it changes, in id order, before it reads
 * an entry, then stamps the change by the database clock, as a turn's start is stamped. A turn's context read shares
 * that row and its end locks it, so {@link #eraseStaleCopies} finds every change that the read could have missed.
 */
@Component
@RequiredArgsConstructor
public class MentorRedaction {

    static final String ERASED = "[This content was erased.]";

    private static final String SEARCH_CONVERSATION = "search_conversation";

    private final MentorEntryRepository entries;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    /** Redacts the entries and every copy of them, in any workspace. */
    @Transactional
    public void redact(Collection<UUID> entryIds) {
        redactWithCopies(entryIds);
    }

    private void redactWithCopies(Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return;
        }
        UUID[] ids = entryIds.toArray(UUID[]::new);
        entries.lockTreesOf(ids);
        List<MentorEntryRepository.EntryRef> messages = entries.findUnredactedMessagesAmong(ids);
        entries.redactWithCopies(ids, ERASED, true);
        // Tool results, summaries and system entries are never shown, so only messages change an open conversation.
        Map<Conversation, List<UUID>> byConversation = new LinkedHashMap<>();
        for (MentorEntryRepository.EntryRef message : messages) {
            byConversation
                    .computeIfAbsent(
                            new Conversation(message.getWorkspaceId(), message.getConversationId()),
                            conversation -> new ArrayList<>())
                    .add(message.getId());
        }
        byConversation.forEach((conversation, changed) -> MentorLedgerEvents.EntryChanged.of(
                        conversation.workspaceId(), conversation.id(), changed)
                .forEach(events::publishEvent));
    }

    private record Conversation(long workspaceId, UUID id) {}

    /**
     * Erases every copy of these entries' words but keeps the entries: an edit replaced their text. The caller locked
     * their trees before it changed them ({@link MentorEntryRepository#lockTreesOf}).
     */
    @Transactional
    public void eraseCopiesOf(Collection<UUID> entryIds) {
        if (!entryIds.isEmpty()) {
            entries.redactWithCopies(entryIds.toArray(UUID[]::new), ERASED, false);
        }
    }

    /**
     * Erases the copies an attempt just appended that read an entry erased or edited since the attempt started: a
     * {@code search_conversation} result that quotes one, or a summary whose range holds one. An entry erased or edited
     * before the attempt started was in its path as it is now. The caller holds the main conversation row of the
     * attempt's tree.
     *
     * @param personErased whether the person-erasure step that redacts the tool results that copy anyone's data
     *     ({@link MentorEntryRepository#COPIES_PERSON_DATA}) ran since the attempt started. That step runs after every
     *     step that erases a source, so the attempt may have read the person's data; its own such results, and the
     *     summaries over them, are erased too. The step records no workspace, so a step for a person in any workspace
     *     counts.
     */
    @Transactional
    public void eraseStaleCopies(List<MentorEntry> appended, Instant attemptStart, boolean personErased) {
        List<UUID> stale = new ArrayList<>();
        for (MentorEntry entry : appended) {
            if (isStaleSummary(entry, attemptStart) || quotesChanged(entry, attemptStart)) {
                stale.add(entry.getId());
            }
        }
        if (personErased) {
            appended.stream()
                    .filter(entry -> MentorPiEntries.copiesPersonData(entry.getPayload()))
                    .map(MentorEntry::getId)
                    .forEach(stale::add);
        }
        redactWithCopies(stale);
    }

    private boolean isStaleSummary(MentorEntry entry, Instant attemptStart) {
        JsonNode payload = entry.getPayload();
        if (entry.getKind() != MentorEntry.Kind.SUMMARY || payload == null) {
            return false;
        }
        // A summary that replaces none of its own entries still read the main conversation in a thread.
        JsonNode from = payload.path("fromSeq");
        JsonNode to = payload.path("toSeq");
        long fromSeq = from.isNumber() ? from.asLong() : Long.MAX_VALUE;
        long toSeq = to.isNumber() ? to.asLong() : Long.MIN_VALUE;
        return entries.countChangedSinceInCoverage(entry.getConversationId(), fromSeq, toSeq, attemptStart) > 0;
    }

    /**
     * Whether a {@code search_conversation} result quotes an erased message, which a search never returns, or a message
     * edited since the attempt started, whose earlier words the search may have read.
     */
    private boolean quotesChanged(MentorEntry entry, Instant attemptStart) {
        List<UUID> quoted = quotedEntryIds(entry);
        return !quoted.isEmpty()
                && entries.countRedactedOrEditedSince(entry.getWorkspaceId(), quoted.toArray(UUID[]::new), attemptStart)
                        > 0;
    }

    /** The messages a {@code search_conversation} result quotes: the entry id of each hit. */
    private List<UUID> quotedEntryIds(MentorEntry entry) {
        JsonNode payload = entry.getPayload();
        if (entry.getKind() != MentorEntry.Kind.TOOL_RESULT
                || payload == null
                || !SEARCH_CONVERSATION.equals(
                        payload.path("message").path("toolName").asString(""))) {
            return List.of();
        }
        List<UUID> quoted = new ArrayList<>();
        for (JsonNode block : payload.path("message").path("content")) {
            if (!block.path("text").isString()) {
                continue;
            }
            try {
                for (JsonNode hit :
                        mapper.readTree(block.path("text").asString()).path("hits")) {
                    quoted.add(UUID.fromString(hit.path("entryId").asString("")));
                }
            } catch (JacksonException | IllegalArgumentException notHits) {
                // A refused search answers with an error text, which quotes nothing.
            }
        }
        return quoted;
    }
}
