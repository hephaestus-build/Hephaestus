package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The situation a turn runs in, as text for the transient message the Heph host appends after the path on every
 * model request. It is never stored: it changes with every turn, so it sits after the cached prefix. It tells Heph
 * the time, where the question was asked, how long the conversation was quiet, and what it cannot see in its own
 * path: for a thread, the parent and what the main conversation said since; for the main conversation, its open
 * threads.
 */
@Component
@RequiredArgsConstructor
public class MentorSituation {

    static final int MAX_THREADS = 10;
    private static final int EXCERPT_CHARS = 120;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final List<MentorEntry.Kind> MESSAGES = Arrays.stream(MentorEntry.Kind.values())
            .filter(MentorEntry.Kind::isMessage)
            .toList();

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final Clock clock;

    @Transactional(readOnly = true)
    public String render(long workspaceId, UUID turnId, ZoneId zone) {
        MentorTurn turn = turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        MentorConversation conversation = conversations
                .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "MentorConversation", turn.getConversationId().toString()));
        MentorEntry question = entries.findQuestion(turn.getWorkspaceId(), turn.getId());
        Instant now = clock.instant();

        StringBuilder text = new StringBuilder("<situation>\n");
        text.append("Now: ").append(at(now, zone)).append(".\n");
        text.append(
                switch (turn.getSurface()) {
                    case SLACK -> "The developer asked this in Slack. Your answer appears there.\n";
                    case APP -> "The developer asked this in the app. Your answer appears there.\n";
                });
        String page = turn.getPageContext();
        if (page != null && !page.isBlank()) {
            text.append("They asked from this page in Hephaestus: ")
                    .append(WHITESPACE.matcher(page).replaceAll(" ").strip())
                    .append(".\n");
        }
        previousMessage(workspaceId, conversation, question)
                .ifPresentOrElse(
                        previous -> text.append("Their conversation was quiet for ")
                                .append(span(Duration.between(previous.getCreatedAt(), question.getCreatedAt())))
                                .append(" before this message; the message before it is from ")
                                .append(at(previous.getCreatedAt(), zone))
                                .append(".\n"),
                        () -> text.append("This is the first message of the conversation.\n"));
        if (conversation.isThread()) {
            thread(workspaceId, conversation, zone, text);
        } else {
            openThreads(workspaceId, conversation, zone, text);
        }
        return text.append("</situation>").toString();
    }

    /** The message before the question; a thread's first question follows the message it replies to. */
    private Optional<MentorEntry> previousMessage(
            long workspaceId, MentorConversation conversation, MentorEntry question) {
        Optional<MentorEntry> own =
                entries.findFirstByWorkspaceIdAndConversationIdAndSeqLessThanAndKindInAndRedactedAtIsNullOrderBySeqDesc(
                        workspaceId, conversation.getId(), question.getSeq(), MESSAGES);
        UUID parent = conversation.getParentEntryId();
        if (own.isPresent() || parent == null) {
            return own;
        }
        return entries.findByIdAndWorkspaceId(parent, workspaceId).filter(entry -> !entry.isRedacted());
    }

    private void thread(long workspaceId, MentorConversation thread, ZoneId zone, StringBuilder text) {
        UUID parentId = thread.getParentEntryId();
        Optional<MentorEntry> parent =
                parentId == null ? Optional.empty() : entries.findByIdAndWorkspaceId(parentId, workspaceId);
        if (parent.isEmpty()) {
            return;
        }
        MentorEntry message = parent.get();
        text.append("This is a thread on the message from ")
                .append(at(message.getCreatedAt(), zone))
                .append(message.isRedacted() ? " (deleted since)" : ": \"" + excerpt(message.getText()) + "\"")
                .append(". The thread opened ")
                .append(at(thread.getCreatedAt(), zone))
                .append(". You see the main conversation only up to that message.\n");
        MentorEntryRepository.MessageSpan since =
                entries.findMessageSpan(workspaceId, thread.rootId(), message.getSeq());
        Instant newest = since.getNewest();
        if (since.getCount() == 0 || newest == null) {
            text.append("The main conversation has no newer message.\n");
        } else {
            text.append("The main conversation has ")
                    .append(since.getCount())
                    .append(since.getCount() == 1 ? " newer message" : " newer messages")
                    .append(", the newest from ")
                    .append(at(newest, zone))
                    .append(". Use search_conversation to read them.\n");
        }
    }

    private void openThreads(long workspaceId, MentorConversation main, ZoneId zone, StringBuilder text) {
        List<MentorEntryRepository.ThreadSummary> threads =
                entries.findThreadSummariesOfMain(workspaceId, main.getId(), MAX_THREADS + 1);
        if (threads.isEmpty()) {
            text.append("The conversation has no threads.\n");
            return;
        }
        List<MentorEntryRepository.ThreadSummary> listed = threads.subList(0, Math.min(MAX_THREADS, threads.size()));
        Map<UUID, MentorEntry> parents = entries
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        listed.stream()
                                .map(MentorEntryRepository.ThreadSummary::getParentEntryId)
                                .toList())
                .stream()
                .collect(Collectors.toMap(MentorEntry::getId, Function.identity()));
        text.append("Threads on messages of this conversation, latest activity first")
                .append(threads.size() > MAX_THREADS ? " (the " + MAX_THREADS + " latest)" : "")
                .append(". You do not see their messages; use search_conversation to read them:\n");
        for (MentorEntryRepository.ThreadSummary thread : listed) {
            MentorEntry parent = parents.get(thread.getParentEntryId());
            long messages = thread.getReplies();
            text.append("- On ")
                    .append(
                            parent == null
                                    ? "a message"
                                    : parent.isRedacted()
                                            ? "a deleted message"
                                            : "\"" + excerpt(parent.getText()) + "\" ("
                                                    + at(parent.getCreatedAt(), zone) + ")")
                    .append(": ")
                    .append(messages)
                    .append(messages == 1 ? " message" : " messages");
            Instant lastReply = thread.getLastReplyAt();
            if (lastReply != null) {
                text.append(", the newest from ").append(at(lastReply, zone));
            }
            text.append(".\n");
        }
    }

    private static String at(Instant instant, ZoneId zone) {
        return MentorContextPathBuilder.time(instant, zone);
    }

    private static String excerpt(String text) {
        String flat = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS - 1) + "…";
    }

    /** A gap in the largest unit that fits it. */
    static String span(Duration gap) {
        long days = gap.toDays();
        if (days >= 1) {
            return days == 1 ? "1 day" : days + " days";
        }
        long hours = gap.toHours();
        if (hours >= 1) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        long minutes = Math.max(0, gap.toMinutes());
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
