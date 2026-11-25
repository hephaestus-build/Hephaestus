package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Conversation entries as the app shows them. It reads any member's rows: the caller decides whose entries it maps.
 *
 * <p>A message in the main conversation carries a summary of its thread. A Hephaestus message shows what its successful
 * link tool calls showed: results, problems and prepared feedback, with where to read about them. A question carries
 * the outcome of its turn, so a stopped or failed turn survives a reload. A Slack question carries how its reply stands.
 * A weekly digest is rendered by {@link WeeklyDigestFacts}.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationEntryViews {

    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final WeeklyDigestFacts weeklyDigests;
    private final AssessmentPort assessments;
    private final FeedbackHistoryQuery feedback;
    private final ObjectMapper objectMapper;
    private final AgentActions actions;

    /** The messages among {@code rows} of one conversation that the app shows, as it shows them. */
    List<ConversationEntryDTO> shown(Conversation conversation, List<ConversationEntry> rows) {
        return dtos(conversation, rows).stream()
                .filter(ConversationEntryViews::shows)
                .toList();
    }

    /** A Hephaestus message shows only when it has text or shows feedback. */
    static boolean shows(ConversationEntryDTO entry) {
        return entry.kind() != ConversationEntryDTO.Kind.ANSWER
                || !entry.text().isEmpty()
                || !entry.links().isEmpty();
    }

    /**
     * Maps entries of one conversation, with the threads on top-level ones, the feedback Hephaestus showed, the outcome of
     * each question's turn and each weekly digest as it reads now.
     */
    List<ConversationEntryDTO> dtos(Conversation conversation, List<ConversationEntry> rows) {
        long workspaceId = conversation.getWorkspaceId();
        Map<UUID, ConversationThreadSummaryDTO> threads = Map.of();
        if (!conversation.isThread() && !rows.isEmpty()) {
            threads =
                    entries
                            .findThreadSummaries(
                                    workspaceId,
                                    rows.stream().map(ConversationEntry::getId).toList())
                            .stream()
                            .map(ConversationThreadSummaryDTO::from)
                            .collect(
                                    Collectors.toMap(ConversationThreadSummaryDTO::parentEntryId, Function.identity()));
        }
        Set<UUID> linkingTurns = new HashSet<>();
        Set<UUID> questionTurns = new HashSet<>();
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (turnId != null
                    && row.getKind() == ConversationEntry.Kind.ANSWER
                    && !links(row).isEmpty()) {
                linkingTurns.add(turnId);
            }
            if (turnId != null && row.getKind() == ConversationEntry.Kind.USER) {
                questionTurns.add(turnId);
            }
        }
        Set<ConversationPiEntries.ShownCall> shownCalls =
                linkingTurns.isEmpty() ? Set.of() : entries.shownLinkCalls(workspaceId, linkingTurns);
        Map<UUID, List<ConversationLinkDTO>> links = links(conversation, rows, shownCalls);
        Map<UUID, ConversationTurn> questionTurnById = questionTurns.isEmpty()
                ? Map.of()
                : turns.findAllById(questionTurns).stream()
                        .filter(turn -> turn.getWorkspaceId() == workspaceId)
                        .collect(Collectors.toMap(ConversationTurn::getId, Function.identity()));
        Map<UUID, WeeklyDigestDTO> digests = digests(conversation, rows);
        Map<String, LaneAction> replies = actions.byKeys(
                workspaceId,
                rows.stream()
                        .filter(row -> row.getKind() == ConversationEntry.Kind.USER
                                && row.getSurface() == ConversationEntry.Surface.SLACK)
                        .map(row -> ConversationSlackReplies.replyKey(row.getId()))
                        .toList());
        List<ConversationEntryDTO> mapped = new ArrayList<>(rows.size());
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            mapped.add(dto(
                    conversation,
                    row,
                    threads.get(row.getId()),
                    links.getOrDefault(row.getId(), List.of()),
                    row.getKind() == ConversationEntry.Kind.USER && turnId != null
                            ? questionTurnById.get(turnId)
                            : null,
                    digests.get(row.getId()),
                    slackDelivery(row, replies)));
        }
        return mapped;
    }

    /**
     * What each Hephaestus message showed, in call order: the results, problems and prepared feedback of its successful link
     * calls. Each names its practice and the work it is about when the developer may still read them: a problem through
     * its result, and feedback by its own item and the practice of its results when it has one practice.
     */
    private Map<UUID, List<ConversationLinkDTO>> links(
            Conversation conversation, List<ConversationEntry> rows, Set<ConversationPiEntries.ShownCall> shownCalls) {
        Map<UUID, List<ConversationLinkDTO>> shown = new HashMap<>();
        Map<UUID, Optional<AssessmentPort.Observation>> read = new HashMap<>();
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (row.getKind() != ConversationEntry.Kind.ANSWER || row.isRedacted() || turnId == null) {
                continue;
            }
            List<ConversationLinkDTO> links = links(row).stream()
                    .filter(link -> shownCalls.contains(link.in(turnId)))
                    .map(link -> link(conversation, link, read))
                    .toList();
            if (!links.isEmpty()) {
                shown.put(row.getId(), links);
            }
        }
        return shown;
    }

    private ConversationLinkDTO link(
            Conversation conversation,
            ConversationPiEntries.Link link,
            Map<UUID, Optional<AssessmentPort.Observation>> read) {
        long workspaceId = conversation.getWorkspaceId();
        long accountId = conversation.getAccountId();
        return switch (link.kind()) {
            case RESULT -> {
                var result = read.computeIfAbsent(link.id(), id -> readable(conversation, id));
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.RESULT,
                        link.id(),
                        link.text(),
                        result.map(AssessmentPort.Observation::practiceSlug).orElse(null),
                        result.map(AssessmentPort.Observation::itemId).orElse(null));
            }
            case PROBLEM -> {
                var result = assessments.observationOfProblem(workspaceId, accountId, link.id());
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.PROBLEM,
                        link.id(),
                        link.text(),
                        result.map(AssessmentPort.Observation::practiceSlug).orElse(null),
                        result.map(AssessmentPort.Observation::itemId).orElse(null));
            }
            case FEEDBACK -> {
                var shown = feedback.find(workspaceId, link.id(), accountId);
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.FEEDBACK,
                        link.id(),
                        link.text(),
                        shown.map(FeedbackHistoryEntry::practiceSlugs)
                                .filter(slugs -> slugs.size() == 1)
                                .map(List::getFirst)
                                .orElse(null),
                        shown.map(FeedbackHistoryEntry::itemId).orElse(null));
            }
        };
    }

    private Optional<AssessmentPort.Observation> readable(Conversation conversation, UUID resultId) {
        return assessments.observation(conversation.getWorkspaceId(), conversation.getAccountId(), resultId);
    }

    /** The weekly digests among {@code rows} as they read now, rendered together, by entry id. */
    private Map<UUID, WeeklyDigestDTO> digests(Conversation conversation, List<ConversationEntry> rows) {
        List<UUID> ids = new ArrayList<>();
        List<WeeklyDigestPayload> payloads = new ArrayList<>();
        for (ConversationEntry row : rows) {
            if (row.getKind() != ConversationEntry.Kind.DIGEST || row.isRedacted()) {
                continue;
            }
            WeeklyDigestPayload.fromJson(row.getPayload(), objectMapper).ifPresent(payload -> {
                ids.add(row.getId());
                payloads.add(payload);
            });
        }
        List<WeeklyDigestFacts.Rendered> rendered =
                weeklyDigests.render(conversation.getWorkspaceId(), conversation.getAccountId(), payloads);
        Map<UUID, WeeklyDigestDTO> digests = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            digests.put(ids.get(i), rendered.get(i).digest());
        }
        return digests;
    }

    private static ConversationEntryDTO dto(
            Conversation conversation,
            ConversationEntry entry,
            @Nullable ConversationThreadSummaryDTO thread,
            List<ConversationLinkDTO> links,
            @Nullable ConversationTurn questionTurn,
            @Nullable WeeklyDigestDTO digest,
            ConversationEntry.@Nullable SlackDelivery slackDelivery) {
        return new ConversationEntryDTO(
                entry.getId(),
                entry.getConversationId(),
                conversation.getParentEntryId(),
                entry.getSeq(),
                switch (entry.getKind()) {
                    case USER -> ConversationEntryDTO.Kind.USER;
                    case DIGEST -> ConversationEntryDTO.Kind.DIGEST;
                    case ANSWER -> ConversationEntryDTO.Kind.ANSWER;
                    case TOOL_RESULT, SYSTEM, SUMMARY ->
                        throw new IllegalArgumentException("Only messages are shown, not " + entry.getKind());
                },
                entry.getSurface(),
                entry.getText(),
                entry.getTurnId(),
                questionTurn == null ? null : questionTurn.getStatus(),
                questionTurn == null ? null : questionTurn.getError(),
                entry.getCreatedAt(),
                entry.getEditedAt(),
                slackDelivery,
                entry.isRedacted(),
                entry.getVote(),
                links,
                thread,
                digest);
    }

    /** How a Slack question's reply stands: its outbox action, including its terminal outcome. */
    private static ConversationEntry.@Nullable SlackDelivery slackDelivery(
            ConversationEntry row, Map<String, LaneAction> replies) {
        if (row.getKind() != ConversationEntry.Kind.USER || row.getSurface() != ConversationEntry.Surface.SLACK)
            return null;
        return ConversationSlackReplies.delivery(replies.get(ConversationSlackReplies.replyKey(row.getId())));
    }

    /** The link tool calls in a Hephaestus message, in order. */
    private static List<ConversationPiEntries.Link> links(ConversationEntry entry) {
        return entry.getKind() == ConversationEntry.Kind.ANSWER
                ? ConversationPiEntries.links(entry.getPayload())
                : List.of();
    }
}
