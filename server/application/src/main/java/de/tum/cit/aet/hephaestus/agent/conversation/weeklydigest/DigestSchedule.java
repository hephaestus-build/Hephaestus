package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.InboxItem;
import de.tum.cit.aet.hephaestus.conversation.InboxItems;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationClockStep;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Enqueues opt-in proactive work as an inbox item on the person's main thread; the conversation sandbox writes and
 * admits its text. The item's turn starts only while the thread is idle, and a message of the person stops it: the
 * digest waits for the next idle moment until the week's contact cutoff.
 */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
@WorkspaceAgnostic("Weekly digest admission selects each opted-in account's real workspace before it enqueues work")
public class DigestSchedule {
    private static final Logger log = LoggerFactory.getLogger(DigestSchedule.class);
    static final LocalTime DUE = LocalTime.of(9, 0);

    /** The due items one pass decides. */
    static final int START_PAGE = 200;

    private static final String WEEK_START = "weekStart";
    private static final String ZONE = "zone";
    private static final String ENABLED_SINCE = "enabledSince";

    /** The digest's item on the person's main thread: the thread is the person's, so the week names it. */
    static String sourceKey(WeeklyDigestWindow window) {
        return "weekly-digest:" + window.weekStart();
    }

    private final WeeklyDigestSubscriptions subscriptions;
    private final WeeklyDigestAvailability availability;
    private final AccountDirectory accounts;
    private final WeeklyDigestFacts facts;
    private final ConversationService conversations;
    private final ConversationEntryService entries;
    private final ConversationTurnService turns;
    private final InboxItems inbox;
    private final DigestAdmission admission;
    private final ConversationAccess ledger;
    private final PersonDataWriteFence writeFence;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public static String requestId(long workspaceId, long accountId, WeeklyDigestWindow window) {
        return "weekly-digest:" + workspaceId + ":" + accountId + ":" + window.weekStart();
    }

    static boolean isDue(Instant now, ZoneId zone) {
        var local = now.atZone(zone);
        return local.getDayOfWeek() == DayOfWeek.MONDAY
                && !local.toLocalTime().isBefore(DUE)
                && local.toLocalTime().isBefore(WeeklyDigestWindow.CONTACT_END);
    }

    @Bean
    @ConditionalOnEvaluation
    EvaluationClockStep digestClockStep(ObjectProvider<DigestSchedule> schedule) {
        return instant -> {
            schedule.getObject().enqueueDue();
            schedule.getObject().startDue();
        };
    }

    /**
     * The scheduled pass. ShedLock's method proxy refuses a locked method that returns a primitive, so the locked entry
     * returns nothing and delegates to {@link #enqueueDue()}.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @SchedulerLock(name = "weekly-digest", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void enqueueDueDigests() {
        enqueueDue();
    }

    /** Queues the digests that are due now, and returns how many it queued. */
    public int enqueueDue() {
        Instant now = clock.instant();
        int queued = 0;
        for (long accountId : subscriptions.subscribedAccountIds()) {
            ZoneId zone = accounts.timeZone(accountId).orElse(AccountDirectory.DEFAULT_TIME_ZONE);
            if (!isDue(now, zone)) continue;
            WeeklyDigestWindow window = WeeklyDigestWindow.previousWeek(now, zone);
            Instant startBy = window.contactCutoff();
            if (now.plusSeconds(1).isAfter(startBy)) continue;
            for (var membership : availability.memberships(accountId)) {
                long workspaceId = membership.workspaceId();
                try {
                    if (enqueue(workspaceId, accountId, window, startBy)) queued++;
                } catch (RuntimeException failure) {
                    log.atWarn()
                            .addKeyValue("event.name", "digest.enqueue.failed")
                            .addKeyValue(StructuredLogKeys.WORKSPACE_ID, workspaceId)
                            .addKeyValue("failure.class", failure.getClass().getName())
                            .log("Weekly digest could not be queued");
                }
            }
        }
        return queued;
    }

    private boolean enqueue(long workspaceId, long accountId, WeeklyDigestWindow window, Instant startBy) {
        String requestId = requestId(workspaceId, accountId, window);
        if (entries.exists(workspaceId, requestId)
                || !availability.available(workspaceId, accountId)
                || !facts.anythingIn(workspaceId, accountId, window)) return false;
        Instant now = clock.instant();
        var enqueued = transactions.execute(status -> {
            var since = subscriptions.enabledSince(accountId);
            if (!writeFence.holdForAccountWrite(accountId)
                    || since.isEmpty()
                    || !availability.available(workspaceId, accountId)
                    || facts.compose(workspaceId, accountId, window).isEmpty()) return null;
            var main = conversations.findOrCreateMain(workspaceId, accountId);
            // The item is bound to the consent it was scheduled under: a later opt-out and opt-in starts no turn for
            // it.
            var payload = mapper.createObjectNode()
                    .put(WEEK_START, window.weekStart().toString())
                    .put(ZONE, window.zone().getId())
                    .put(ENABLED_SINCE, since.get().toString());
            return inbox.enqueue(
                            workspaceId,
                            main.getId(),
                            InboxItem.Kind.SCHEDULE_DUE,
                            sourceKey(window),
                            InboxItem.Custody.OPTIONAL,
                            now,
                            startBy,
                            payload,
                            now)
                    .orElse(null);
        });
        if (enqueued == null) return false;
        // Decided at once: an idle thread starts its digest now, as before the inbox, and a busy one waits for the
        // pass.
        inbox.decide(enqueued, producer, now);
        return true;
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
        String requestId = requestId(workspaceId, accountId, window) + (start == 0 ? "" : "#" + start);
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
