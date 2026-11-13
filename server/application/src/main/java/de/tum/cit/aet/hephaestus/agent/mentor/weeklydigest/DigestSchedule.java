package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Enqueues opt-in proactive work; the conversation sandbox writes and admits its text. */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
@WorkspaceAgnostic("Weekly digest admission selects each opted-in account's real workspace before it enqueues work")
public class DigestSchedule {
    private static final Logger log = LoggerFactory.getLogger(DigestSchedule.class);
    static final LocalTime DUE = LocalTime.of(9, 0);
    static final LocalTime TOO_LATE = LocalTime.of(21, 0);

    private final WeeklyDigestSubscriptions subscriptions;
    private final WeeklyDigestAvailability availability;
    private final AccountDirectory accounts;
    private final WeeklyDigestFacts facts;
    private final MentorConversationService conversations;
    private final MentorEntryService entries;
    private final MentorTurnService turns;
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
                && local.toLocalTime().isBefore(TOO_LATE);
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @SchedulerLock(name = "weekly-digest", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public int enqueueDue() {
        Instant now = clock.instant();
        int queued = 0;
        for (long accountId : subscriptions.subscribedAccountIds()) {
            ZoneId zone = accounts.timeZone(accountId).orElse(AccountDirectory.DEFAULT_TIME_ZONE);
            if (!isDue(now, zone)) continue;
            WeeklyDigestWindow window = WeeklyDigestWindow.previousWeek(now, zone);
            Duration startWithin =
                    Duration.between(now, now.atZone(zone).with(TOO_LATE).toInstant());
            if (startWithin.compareTo(Duration.ofSeconds(1)) < 0) continue;
            for (var membership : availability.memberships(accountId)) {
                long workspaceId = membership.workspaceId();
                try {
                    if (enqueue(workspaceId, accountId, window, startWithin)) queued++;
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

    private boolean enqueue(long workspaceId, long accountId, WeeklyDigestWindow window, Duration startWithin) {
        String requestId = requestId(workspaceId, accountId, window);
        if (entries.exists(workspaceId, requestId)
                || !availability.available(workspaceId, accountId)
                || !facts.anythingIn(workspaceId, accountId, window)) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!writeFence.holdForAccountWrite(accountId)
                    || subscriptions.enabledSince(accountId).isEmpty()
                    || !availability.available(workspaceId, accountId)) return false;
            var payload = facts.compose(workspaceId, accountId, window);
            if (payload.isEmpty()) return false;
            var main = conversations.findOrCreateMain(workspaceId, accountId);
            return turns.submitDigest(
                    workspaceId,
                    main.getId(),
                    new MentorTurnService.DigestRequest(
                            requestId,
                            "Write a short weekly recap for " + window.daysWithYear()
                                    + " from inputs/context/weekly_digest.json. Summarize only its visible facts."
                                    + " Do not add assessments or deliver new feedback. Invite a reply if useful.",
                            payload.get().toJson(mapper),
                            startWithin));
        }));
    }
}
