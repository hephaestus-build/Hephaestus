package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DueEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DuePage;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.SkippedOwner;
import de.tum.cit.aet.hephaestus.conversation.metrics.ConversationMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.DueWorkKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erases every conversation entry that reaches {@link FeedbackRetentionProperties#feedbackRetention()}, as a delete
 * erases it.
 *
 * <p>The owner function {@code mentoring_due_work} decides by the database clock which entries are due: those older
 * than the retention less {@link #EARLY} at the start of the current retention week, which starts at 03:45 UTC every
 * Sunday. The due set grows once a week, so an entry becomes due with seven to fourteen days left before its limit. An
 * erased entry also erases every summary over it, so a long conversation is summarized again about once a week: more
 * often only while a backlog drains over several runs.
 *
 * <p>Each run reads at most {@link #MAX_PAGES} pages of {@link #BATCH} due entries, oldest first, and erases each
 * person's entries of a page in a transaction bound to that person, the owner the due row names. It starts no new work
 * after {@link #RUN_BUDGET}; the person's transaction it started last still finishes. Progress is the erasure itself:
 * an erased entry is no longer due, so the next run, a crash or another replica starts again at the oldest entry still
 * due, and a run that finds an entry already erased changes nothing. A person whose erasure fails is passed over for
 * the rest of the run, so the other people of the workspace still progress. The replica remembers the people whose
 * erasure failed and tries them only after everyone else, until a run gets back to them, so people whose conversations
 * stay in use cannot spend every run's budget before the others. A run starts every 15 minutes.
 *
 * <p>Every replica reads the oldest due entry each minute and publishes how long it has left before its limit, and when
 * it last read it. A run erases a new week's due entries within hours, so less than seven days left means that the
 * sweep has fallen behind or keeps failing for that person.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@WorkspaceAgnostic("Finds the due entries of every workspace first, then erases them person by person")
public class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    /** Two weekly cohorts: an entry becomes due at least a week before its limit. */
    static final Duration EARLY = Duration.ofDays(14);

    static final int BATCH = 500;
    static final int MAX_PAGES = 40;

    /**
     * A run starts no page and no person's transaction after this. It stays far below the scheduler lock's
     * {@code lockAtMostFor}, so only a transaction that runs for most of an hour lets two runs overlap, and then one of
     * them finds the other's entries erased.
     */
    static final Duration RUN_BUDGET = Duration.ofMinutes(10);

    private final ConversationEntryRepository entries;
    private final ConversationRedaction redaction;
    private final AccountDirectory accounts;
    private final OwnerScope ownerScope;
    private final TransactionTemplate transaction;
    private final FeedbackRetentionProperties properties;
    private final PrivacyJobMetrics metrics;
    private final Clock clock;
    private final AtomicReference<Double> deadlineMargin = new AtomicReference<>(Double.NaN);
    private final Counter samplerFailures;

    /** When this replica last read the margin, in seconds since the epoch, so an alert sees a stale margin. */
    private final AtomicLong lastRead = new AtomicLong();

    /** The people whose erasure failed in this replica's last run: the next run tries them after everyone else. */
    private volatile Set<Owner> failedLastRun = Set.of();

    private final int batch;
    private final int maxPages;

    @Autowired
    public ConversationRetentionSweeper(
            ConversationEntryRepository entries,
            ConversationRedaction redaction,
            AccountDirectory accounts,
            OwnerScope ownerScope,
            PlatformTransactionManager transactionManager,
            FeedbackRetentionProperties properties,
            PrivacyJobMetrics metrics,
            MeterRegistry meters,
            Clock clock) {
        this(
                entries,
                redaction,
                accounts,
                ownerScope,
                transactionManager,
                properties,
                metrics,
                meters,
                clock,
                BATCH,
                MAX_PAGES);
    }

    /** A sweeper with its own page size and page budget, so a test drives several runs over a small backlog. */
    ConversationRetentionSweeper(
            ConversationEntryRepository entries,
            ConversationRedaction redaction,
            AccountDirectory accounts,
            OwnerScope ownerScope,
            PlatformTransactionManager transactionManager,
            FeedbackRetentionProperties properties,
            PrivacyJobMetrics metrics,
            MeterRegistry meters,
            Clock clock,
            int batch,
            int maxPages) {
        this.entries = entries;
        this.redaction = redaction;
        this.accounts = accounts;
        this.ownerScope = ownerScope;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.batch = batch;
        this.maxPages = maxPages;
        Gauge.builder(ConversationMetrics.RETENTION_DEADLINE_MARGIN, deadlineMargin, AtomicReference::get)
                .description("Seconds until the oldest due conversation entry reaches its retention limit; +Inf when"
                        + " no entry is due, NaN before the first read; a failed read keeps the last value")
                .baseUnit("seconds")
                .register(meters);
        samplerFailures = Counter.builder(ConversationMetrics.RETENTION_SAMPLER_FAILURES)
                .description("Reads of the oldest due conversation entry that failed; the margin kept its last value")
                .register(meters);
        lastRead.set(clock.instant().getEpochSecond());
        Gauge.builder(ConversationMetrics.RETENTION_MARGIN_READ_TIMESTAMP, lastRead, AtomicLong::get)
                .description("When this replica last read the oldest due conversation entry, in seconds since the"
                        + " epoch; its start until the first read")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "conversation-retention-sweep", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /**
     * One bounded run. {@link Outcome#INCOMPLETE} means a person's erasure failed: due work that this run could not do.
     * A run that spends its budget leaves the rest to the next run, which is the design, so it is a success; the
     * deadline margin shows a sweep that falls behind.
     */
    public Run sweepNow() {
        Progress progress = new Progress();
        try {
            Run run = run(progress);
            metrics.record(Job.CONVERSATION_RETENTION, run.failedOwners() > 0 ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            return run;
        } catch (RuntimeException e) {
            metrics.record(Job.CONVERSATION_RETENTION, Outcome.FAILURE);
            throw e;
        } finally {
            metrics.recordAffected(Job.CONVERSATION_RETENTION, progress.erased);
        }
    }

    /**
     * Reads the oldest due entry and publishes, by the database clock, how long it has left before its limit, and when
     * it read it. A failed read keeps both and counts, so the margin alert uses a margin only while its read is recent.
     */
    @Scheduled(fixedDelay = 60, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void sampleDeadlineMargin() {
        try {
            List<DueEntry> oldest = entries.findDueForRetention(
                    maxAgeSeconds(), DuePage.first(DueWorkKind.ENTRY_RETENTION_DEADLINE), 1);
            deadlineMargin.set(
                    oldest.isEmpty()
                            ? Double.POSITIVE_INFINITY
                            : (double) Duration.between(
                                            oldest.getFirst().getReadAt(),
                                            oldest.getFirst().getDueAt().plus(properties.feedbackRetention()))
                                    .toSeconds());
            lastRead.set(clock.instant().getEpochSecond());
        } catch (RuntimeException e) {
            samplerFailures.increment();
            log.warn(
                    "conversation.retention: reading the oldest due entry failed, the margin keeps its last read: {}",
                    e.toString());
        }
    }

    private long maxAgeSeconds() {
        return properties.feedbackRetention().minus(EARLY).toSeconds();
    }

    private Run run(Progress progress) {
        long maxAgeSeconds = maxAgeSeconds();
        Instant deadline = clock.instant().plus(RUN_BUDGET);
        Set<Owner> deferred = failedLastRun;
        SequencedSet<Owner> failed = new LinkedHashSet<>();
        boolean retried = deferred.isEmpty();
        try {
            // Everyone but the people who failed last run first, then those people, while the budget lasts.
            boolean drained = pass(maxAgeSeconds, deferred, failed, deadline, progress);
            if (drained && !retried) {
                drained = pass(maxAgeSeconds, Set.of(), failed, deadline, progress);
                retried = drained;
            }
            Run run = new Run(progress.erased, failed.size(), drained);
            if (progress.erased > 0 || !drained || !failed.isEmpty()) {
                log.info(
                        "conversation.retention: handed {} due conversation entries to erasure in committed"
                                + " transactions; {}",
                        progress.erased,
                        !drained
                                ? "the next run continues with the due entries left"
                                : failed.isEmpty()
                                        ? "no due entry left"
                                        : "no due entry left but those of " + failed.size()
                                                + " people whose erasure failed");
            }
            return run;
        } finally {
            // People this run did not get back to stay behind everyone else in the next run.
            Set<Owner> next = new LinkedHashSet<>(failed);
            if (!retried) {
                next.addAll(deferred);
            }
            failedLastRun = Set.copyOf(next);
        }
    }

    /**
     * Reads pages from the oldest due entry, passing over {@code deferred} and every person who fails in this run.
     *
     * @return whether it found no due entry left but those it passed over; {@code false} when the page or time budget
     *     ended it
     */
    private boolean pass(
            long maxAgeSeconds, Set<Owner> deferred, SequencedSet<Owner> failed, Instant deadline, Progress progress) {
        @Nullable Instant afterDue = null;
        @Nullable UUID afterId = null;
        while (progress.pages < maxPages && clock.instant().isBefore(deadline)) {
            List<SkippedOwner> skip = new ArrayList<>();
            deferred.forEach(owner -> skip.add(owner.skipped()));
            failed.stream().filter(owner -> !deferred.contains(owner)).forEach(owner -> skip.add(owner.skipped()));
            List<DueEntry> due = entries.findDueForRetention(
                    maxAgeSeconds, new DuePage(DueWorkKind.ENTRY_RETENTION, afterDue, afterId, skip), batch);
            progress.pages++;
            if (!erase(due, failed, deadline, progress)) {
                return false;
            }
            if (due.size() < batch) {
                return true;
            }
            afterDue = due.getLast().getDueAt();
            afterId = due.getLast().getId();
        }
        return false;
    }

    /**
     * Erases the page person by person; a person whose erasure fails joins {@code failed}.
     *
     * @return {@code false} when the run budget ended before every person of the page was attempted
     */
    private boolean erase(List<DueEntry> due, SequencedSet<Owner> failed, Instant deadline, Progress progress) {
        Map<Owner, List<UUID>> byOwner = new LinkedHashMap<>();
        for (DueEntry entry : due) {
            byOwner.computeIfAbsent(new Owner(entry.getWorkspaceId(), entry.getAccountId()), owner -> new ArrayList<>())
                    .add(entry.getId());
        }
        for (Map.Entry<Owner, List<UUID>> owner : byOwner.entrySet()) {
            if (!clock.instant().isBefore(deadline)) {
                return false;
            }
            try {
                // A person who entered erasure after the page was read is passed over: erasure removes the entries.
                boolean erased = Boolean.TRUE.equals(transaction.execute(status -> {
                    if (!accounts.holdForRetention(owner.getKey().accountId())) {
                        return false;
                    }
                    ownerScope.ofJob(owner.getKey().accountId());
                    redaction.redact(owner.getValue());
                    return true;
                }));
                if (erased) {
                    progress.erased += owner.getValue().size();
                }
            } catch (RuntimeException e) {
                failed.add(owner.getKey());
                log.warn(
                        "conversation.retention: erasure failed for workspaceId={} accountId={}, tried again after"
                                + " everyone else: {}",
                        owner.getKey().workspaceId(),
                        owner.getKey().accountId(),
                        e.toString());
            }
        }
        return true;
    }

    /**
     * What one run did.
     *
     * @param erased due entries handed to erasure in committed transactions; an entry that another run erased first
     *     counts too
     * @param failedOwners the people whose erasure failed in this run
     * @param drained whether the run found no due entry left but those of the failed people; {@code false} when it
     *     spent its page or time budget
     */
    public record Run(long erased, int failedOwners, boolean drained) {}

    /** The entries a run committed so far, kept when a later page fails, and the pages it read. */
    private static final class Progress {
        private long erased;
        private int pages;
    }

    private record Owner(long workspaceId, long accountId) {

        SkippedOwner skipped() {
            return new SkippedOwner(workspaceId, accountId);
        }
    }
}
