package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.InboxItem;
import de.tum.cit.aet.hephaestus.conversation.InboxItems;
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
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Enqueues opt-in proactive work as an inbox item on the person's main thread; the conversation sandbox writes and
 * admits its text. {@link DigestStarts} starts the item's turn only while the thread is idle, and a message of the
 * person stops it: the digest waits for the next idle moment until the week's contact cutoff.
 */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
@WorkspaceAgnostic("Weekly digest admission selects each opted-in account's real workspace before it enqueues work")
public class DigestSchedule {
    private static final Logger log = LoggerFactory.getLogger(DigestSchedule.class);
    static final LocalTime DUE = LocalTime.of(9, 0);

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
    private final InboxItems inbox;
    private final DigestStarts starts;
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
    EvaluationClockStep digestClockStep(ObjectProvider<DigestSchedule> schedule, ObjectProvider<DigestStarts> starts) {
        return instant -> {
            schedule.getObject().enqueueDue();
            starts.getObject().startDue();
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
            var payload = DigestStarts.payload(mapper, window, since.get());
            return inbox.enqueue(
                            new InboxItems.NewItem(
                                    workspaceId,
                                    main.getId(),
                                    InboxItem.Kind.SCHEDULE_DUE,
                                    sourceKey(window),
                                    InboxItem.Custody.OPTIONAL,
                                    now,
                                    startBy,
                                    payload),
                            now)
                    .orElse(null);
        });
        if (enqueued == null) return false;
        // Decided at once: an idle thread starts its digest now, as before the inbox, and a busy one waits for the
        // pass.
        starts.decide(enqueued, now);
        return true;
    }
}
