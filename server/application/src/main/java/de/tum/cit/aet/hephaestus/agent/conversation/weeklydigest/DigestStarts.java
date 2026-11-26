package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.InboxItem;
import de.tum.cit.aet.hephaestus.conversation.InboxItems;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Starts the weekly digests that came due as inbox items (ADR 0120), and settles each item by how its turn ended. The
 * inbox's start policy decides when; this class says what a digest needs at its start and when it is done.
 */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
@WorkspaceAgnostic("The start pass decides the due digest items of every workspace, each under its thread's lock")
public class DigestStarts {
    private static final Logger log = LoggerFactory.getLogger(DigestStarts.class);

    /** The due items one pass decides. */
    static final int START_PAGE = 200;

    private static final String WEEK_START = "weekStart";
    private static final String ZONE = "zone";
    private static final String ENABLED_SINCE = "enabledSince";

    private final InboxItems inbox;
    private final WeeklyDigestSubscriptions subscriptions;
    private final WeeklyDigestAvailability availability;
    private final WeeklyDigestFacts facts;
    private final ConversationTurnService turns;
    private final DigestAdmission admission;
    private final ConversationAccess ledger;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * The item's IDs and values: its week, and the consent it was scheduled under, so that a later opt-out and opt-in
     * starts no turn for it.
     */
    static ObjectNode payload(ObjectMapper mapper, WeeklyDigestWindow window, Instant enabledSince) {
        return mapper.createObjectNode()
                .put(WEEK_START, window.weekStart().toString())
                .put(ZONE, window.zone().getId())
                .put(ENABLED_SINCE, enabledSince.toString());
    }

    /** Decides one digest item now: starts its turn on an idle thread, defers it or settles it. */
    void decide(InboxItems.Due due, Instant now) {
        inbox.decide(due, producer, now);
    }

    /**
     * The start pass: decides every due digest item, so one that waited for an idle thread, or that a message stopped,
     * starts as soon as the thread is idle again.
     */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT1M")
    @SchedulerLock(name = "weekly-digest-start", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void startDueDigests() {
        startDue();
    }

    /** Decides the due digest items, and returns how many it decided. */
    public int startDue() {
        Instant now = clock.instant();
        inbox.yieldWhereQuestionsWait(START_PAGE);
        var due = inbox.due(InboxItem.Kind.SCHEDULE_DUE, now, START_PAGE);
        for (var item : due) {
            try {
                inbox.decide(item, producer, now);
            } catch (RuntimeException failure) {
                log.atWarn()
                        .addKeyValue("event.name", "digest.start.failed")
                        .addKeyValue(StructuredLogKeys.WORKSPACE_ID, item.workspaceId())
                        .addKeyValue("failure.class", failure.getClass().getName())
                        .log("Weekly digest could not be started");
            }
        }
        return due.size();
    }

    /** The digest as an inbox item's producer: how it starts, and how its ended turn settles the item. */
    private final InboxItems.Producer producer = new InboxItems.Producer() {
        @Override
        public InboxItems.Start start(
                long workspaceId, UUID conversationId, long accountId, int start, JsonNode payload) {
            return startDigest(workspaceId, conversationId, accountId, start, payload);
        }

        @Override
        public Optional<InboxItems.Ended> ended(long workspaceId, UUID turnId, ConversationTurn.Status status) {
            return endedDigest(workspaceId, turnId, status);
        }
    };

    /**
     * Starts the digest of an item under its thread's lock, with the facts as they are now. Silent when the person
     * turned the digest off since the item was scheduled, or became unavailable, or when nothing is left to say. The
     * first turn keeps the week's request id; a later one adds its number.
     */
    private InboxItems.Start startDigest(
            long workspaceId, UUID conversationId, long accountId, int start, JsonNode payload) {
        var window = new WeeklyDigestWindow(
                LocalDate.parse(payload.path(WEEK_START).asString()),
                ZoneId.of(payload.path(ZONE).asString()));
        var since = subscriptions.enabledSince(accountId);
        if (since.isEmpty()
                || !since.get().toString().equals(payload.path(ENABLED_SINCE).asString())
                || !availability.available(workspaceId, accountId))
            return new InboxItems.Silent(InboxItem.Reason.NO_LONGER_ELIGIBLE);
        var facts = this.facts.compose(workspaceId, accountId, window);
        if (facts.isEmpty()) return new InboxItems.Silent(InboxItem.Reason.NOTHING_TO_SAY);
        String requestId = DigestSchedule.requestId(workspaceId, accountId, window) + (start == 0 ? "" : "#" + start);
        return turns.submitDigest(
                        workspaceId,
                        conversationId,
                        new ConversationTurnService.DigestRequest(
                                requestId,
                                "Write a short weekly recap for " + window.daysWithYear()
                                        + " from inputs/context/weekly_digest.json. Summarize only its visible facts."
                                        + " Do not add assessments or deliver new feedback. Invite a reply if useful.",
                                facts.get().toJson(mapper),
                                window.contactCutoff()))
                .<InboxItems.Start>map(InboxItems.Started::new)
                .orElseGet(() -> new InboxItems.Silent(InboxItem.Reason.NO_LONGER_ELIGIBLE));
    }

    /**
     * How a digest's ended turn settles its item. A digest whose message the person erased, or that admission refuses
     * now, ends: it is never written again. One that completed is delivered when it answered, and has said nothing
     * otherwise. Any other end, as a stop for a question or a failed attempt, lets the item start again.
     */
    private Optional<InboxItems.Ended> endedDigest(long workspaceId, UUID turnId, ConversationTurn.Status status) {
        if (ledger.stimulus(workspaceId, turnId).redacted() || admission.refuses(workspaceId, turnId))
            return Optional.of(new InboxItems.Ended(InboxItem.State.DROPPED, InboxItem.Reason.NO_LONGER_ELIGIBLE));
        if (status != ConversationTurn.Status.DONE) return Optional.empty();
        return Optional.of(
                ledger.answered(workspaceId, turnId)
                        ? new InboxItems.Ended(InboxItem.State.CONSUMED, InboxItem.Reason.DELIVERED)
                        : new InboxItems.Ended(InboxItem.State.CONSUMED, InboxItem.Reason.NOTHING_TO_SAY));
    }
}
