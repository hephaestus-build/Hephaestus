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
import de.tum.cit.aet.hephaestus.core.privacy.spi.CopyingSourceRetention;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erases the conversation entries that reach their retention, as a delete erases them, by two rules with one sweep:
 *
 * <ul>
 *   <li>{@link Rule#ENTRIES}: every entry, at {@link FeedbackRetentionProperties#feedbackRetention()}. The owner
 *       function {@code mentoring_due_work} names the due entries of every workspace.
 *   <li>{@link Rule#COPIES}: the tool results that can copy anyone's data, at the shortest retention of a source that
 *       they could have read in their workspace ({@link CopyingSourceRetention}), such as Slack's. A workspace without
 *       such a source has no such limit; its tool results keep the conversation retention.
 * </ul>
 *
 * <p>By the database clock, an entry is due when it is older than its limit less {@link #EARLY} at the start of the
 * current retention week, which starts at 03:45 UTC every Sunday. The due set grows once a week, so an entry becomes
 * due with seven to fourteen days left before its limit. An erased entry also erases every summary over it, so a long
 * conversation is summarized again about once a week: more often only while a backlog drains over several runs.
 *
 * <p>Each run of a rule reads at most {@link #MAX_PAGES} pages of {@link #BATCH} due entries, across workspaces in the
 * order in which they reach their limit, and erases each person's entries of a page in a transaction bound to that
 * person, the owner the due row names. It starts no new work after {@link #RUN_BUDGET}; the person's transaction it started last still finishes.
 * Progress is the erasure itself: an erased entry is no longer due, so the next run, a crash or another replica starts
 * again at the oldest entry still due, and a run that finds an entry already erased changes nothing. A person whose
 * erasure fails is passed over for the rest of the run, so the other people of the workspace still progress. The
 * replica remembers the people whose erasure failed and tries them only after everyone else, until a run gets back to
 * them, so people whose conversations stay in use cannot spend every run's budget before the others. Each rule runs
 * every 15 minutes.
 *
 * <p>Every replica reads the oldest due entry of each rule each minute and publishes how long it has left before its
 * limit, and when it last read it. A run erases a new week's due entries within hours, so less than seven days left
 * means that the sweep has fallen behind or keeps failing for that person.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@WorkspaceAgnostic("Finds the due entries of every workspace first, then erases them person by person")
public class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    /** Two weekly cohorts: an entry becomes due at least a week before its limit. */
    static final Duration EARLY = Duration.ofDays(14);

    /** The shortest age at which an entry becomes due, for a limit of two weeks or less. */
    static final Duration MIN_AGE = Duration.ofDays(1);

    static final int BATCH = 500;
    static final int MAX_PAGES = 40;

    /**
     * A run starts no page and no person's transaction after this. It stays far below the scheduler lock's
     * {@code lockAtMostFor}, so only a transaction that runs for most of an hour lets two runs overlap, and then one of
     * them finds the other's entries erased.
     */
    static final Duration RUN_BUDGET = Duration.ofMinutes(10);

    /** How long the margin reuses a read of the copying sources' retentions: one run's interval. */
    static final Duration COPY_LIMITS_FOR = Duration.ofMinutes(15);

    /**
     * By due time, then as PostgreSQL orders {@code uuid}: by its bytes, unsigned. The copies of several workspaces
     * merge in that order, so a cursor skips none.
     */
    private static final Comparator<DueEntry> OLDEST_FIRST = Comparator.comparing(DueEntry::getDueAt)
            .thenComparing(DueEntry::getId, (a, b) -> {
                int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
                return high != 0
                        ? high
                        : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
            });

    /** The two limits of a conversation entry. */
    enum Rule {
        ENTRIES("entries", Job.CONVERSATION_RETENTION),
        COPIES("copies", Job.CONVERSATION_COPY_RETENTION);

        private final String tag;
        private final Job job;

        Rule(String tag, Job job) {
            this.tag = tag;
            this.job = job;
        }
    }

    private final ConversationEntryRepository entries;
    private final ConversationRedaction redaction;
    private final AccountDirectory accounts;
    private final OwnerScope ownerScope;
    private final TransactionTemplate transaction;
    private final FeedbackRetentionProperties properties;
    private final Supplier<List<CopyingSourceRetention>> copyingSources;
    private final PrivacyJobMetrics metrics;
    private final Clock clock;
    private volatile @Nullable CopyLimits copyLimits;
    private final Sweep entriesSweep;
    private final Sweep copiesSweep;
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
            ObjectProvider<CopyingSourceRetention> copyingSources,
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
                () -> copyingSources.orderedStream().toList(),
                metrics,
                meters,
                clock,
                BATCH,
                MAX_PAGES);
    }

    /** A sweeper with its own sources, page size and page budget, so a test drives several runs over a backlog. */
    ConversationRetentionSweeper(
            ConversationEntryRepository entries,
            ConversationRedaction redaction,
            AccountDirectory accounts,
            OwnerScope ownerScope,
            PlatformTransactionManager transactionManager,
            FeedbackRetentionProperties properties,
            Supplier<List<CopyingSourceRetention>> copyingSources,
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
        this.copyingSources = copyingSources;
        this.metrics = metrics;
        this.clock = clock;
        this.batch = batch;
        this.maxPages = maxPages;
        this.entriesSweep = new Sweep(Rule.ENTRIES, meters);
        this.copiesSweep = new Sweep(Rule.COPIES, meters);
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "conversation-retention-sweep", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    @Scheduled(cron = "0 5/15 * * * *")
    @SchedulerLock(name = "conversation-copy-retention-sweep", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    public void sweepCopies() {
        sweepCopiesNow();
    }

    /** One bounded run of {@link Rule#ENTRIES}. */
    public Run sweepNow() {
        return run(Rule.ENTRIES);
    }

    /** One bounded run of {@link Rule#COPIES}. */
    public Run sweepCopiesNow() {
        return run(Rule.COPIES);
    }

    /**
     * Reads the oldest due entry of each rule and publishes, by the database clock, how long it has left before its
     * limit, and when it read it. A failed read keeps both and counts, so the margin alert uses a margin only while its
     * read is recent.
     */
    @Scheduled(fixedDelay = 60, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void sampleDeadlineMargin() {
        entriesSweep.sample();
        copiesSweep.sample();
    }

    /**
     * One bounded run of a rule. {@link Outcome#INCOMPLETE} means a person's erasure failed, or a workspace's due
     * entries could not be read: due work that this run could not do. A run that spends its budget leaves the rest to
     * the next run, which is the design, so it is a success; the deadline margin shows a sweep that falls behind.
     */
    private Run run(Rule rule) {
        Progress progress = new Progress();
        try {
            Run run = (rule == Rule.ENTRIES ? entriesSweep : copiesSweep).run(progress);
            metrics.record(
                    rule.job,
                    run.failedOwners() > 0 || run.failedWorkspaces() > 0 ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            return run;
        } catch (RuntimeException e) {
            metrics.record(rule.job, Outcome.FAILURE);
            throw e;
        } finally {
            metrics.recordAffected(rule.job, progress.erased);
        }
    }

    /** The age at which an entry with this limit becomes due, at the start of a retention week. */
    static Duration dueAge(Duration limit) {
        Duration age = limit.minus(EARLY);
        return age.compareTo(MIN_AGE) < 0 ? MIN_AGE : age;
    }

    /**
     * The due entries of a rule: a page in the order of their limits, and the oldest due entry with its limit for the
     * margin. One source serves one run.
     */
    private interface DueSource {
        List<DueEntry> page(DuePage page, int limit);

        /** Seconds until the oldest due entry reaches its limit, by the database clock; +Inf when none is due. */
        double margin();

        /** The workspaces whose due entries this source could not read, and passed over for the rest of the run. */
        default int failedWorkspaces() {
            return 0;
        }
    }

    /** Every entry, through the owner function. */
    private DueSource entriesSource() {
        Duration limit = properties.feedbackRetention();
        long age = dueAge(limit).toSeconds();
        return new DueSource() {
            @Override
            public List<DueEntry> page(DuePage page, int size) {
                return entries.findDueForRetention(age, DueWorkKind.ENTRY_RETENTION, null, page, size);
            }

            @Override
            public double margin() {
                return marginOf(
                        entries.findDueForRetention(age, DueWorkKind.ENTRY_RETENTION_DEADLINE, null, DuePage.FIRST, 1),
                        limit);
            }
        };
    }

    /**
     * The copying tool results of each workspace with a copying source. The owner function names each workspace's due
     * copies at that workspace's shortest retention; the page merges them by when they reach that retention, so the
     * copies with the least time left come first whatever their workspace's limit. A page's {@code dueAt} is that
     * time, and the cursor of each workspace is that time less the workspace's limit. A workspace whose copies cannot
     * be read is passed over for the rest of the run, so it does not hold back the others.
     */
    private DueSource copiesSource(boolean fresh) {
        Map<Long, Duration> limits = copyLimits(fresh);
        Set<Long> failed = new HashSet<>();
        return new DueSource() {
            @Override
            public List<DueEntry> page(DuePage page, int size) {
                List<DueEntry> due = new ArrayList<>();
                limits.forEach((workspaceId, limit) -> {
                    if (failed.contains(workspaceId)) {
                        return;
                    }
                    @Nullable Instant afterDue = page.afterDue();
                    DuePage inWorkspace =
                            afterDue == null ? page : new DuePage(afterDue.minus(limit), page.afterId(), page.skip());
                    try {
                        entries.findDueForRetention(
                                        dueAge(limit).toSeconds(),
                                        DueWorkKind.COPY_RETENTION,
                                        workspaceId,
                                        inWorkspace,
                                        size)
                                .forEach(copy -> due.add(new Expiring(
                                        copy.getId(),
                                        copy.getWorkspaceId(),
                                        copy.getAccountId(),
                                        copy.getDueAt().plus(limit),
                                        copy.getReadAt())));
                    } catch (RuntimeException e) {
                        failed.add(workspaceId);
                        log.warn(
                                "conversation.retention: reading the due copies of workspaceId={} failed, passed over"
                                        + " for the rest of the run: {}",
                                workspaceId,
                                e.toString());
                    }
                });
                due.sort(OLDEST_FIRST);
                return due.size() > size ? List.copyOf(due.subList(0, size)) : due;
            }

            @Override
            public int failedWorkspaces() {
                return failed.size();
            }

            // The least margin of the workspaces whose copies can be read; each other workspace counts as a failed
            // read. When no workspace can be read, the read fails: +Inf would claim that no copy is due.
            @Override
            public double margin() {
                double margin = Double.POSITIVE_INFINITY;
                for (Map.Entry<Long, Duration> workspace : limits.entrySet()) {
                    try {
                        List<DueEntry> oldest = entries.findDueForRetention(
                                dueAge(workspace.getValue()).toSeconds(),
                                DueWorkKind.COPY_RETENTION_DEADLINE,
                                workspace.getKey(),
                                DuePage.FIRST,
                                1);
                        margin = Math.min(margin, marginOf(oldest, workspace.getValue()));
                    } catch (RuntimeException e) {
                        failed.add(workspace.getKey());
                        log.warn(
                                "conversation.retention: reading the oldest due copy of workspaceId={} failed: {}",
                                workspace.getKey(),
                                e.toString());
                    }
                }
                if (!limits.isEmpty() && failed.size() == limits.size()) {
                    throw new IllegalStateException("no workspace's due copies could be read");
                }
                return margin;
            }
        };
    }

    /**
     * For each workspace with a copying source, the shortest retention of those sources. The sources change rarely and
     * one of them scans its table to answer, so the margin, which is read every minute, reuses a read for
     * {@link #COPY_LIMITS_FOR}. A run always reads them afresh.
     */
    private Map<Long, Duration> copyLimits(boolean fresh) {
        CopyLimits cached = copyLimits;
        Instant now = clock.instant();
        if (!fresh && cached != null && now.isBefore(cached.readAt().plus(COPY_LIMITS_FOR))) {
            return cached.limits();
        }
        Map<Long, Duration> limits = new TreeMap<>();
        for (CopyingSourceRetention source : copyingSources.get()) {
            source.retentionByWorkspace()
                    .forEach((workspaceId, limit) ->
                            limits.merge(workspaceId, limit, (a, b) -> a.compareTo(b) <= 0 ? a : b));
        }
        copyLimits = new CopyLimits(Map.copyOf(limits), now);
        return limits;
    }

    private record CopyLimits(Map<Long, Duration> limits, Instant readAt) {}

    /** A due copy whose {@code dueAt} is when it reaches its limit. */
    private record Expiring(UUID getId, long getWorkspaceId, long getAccountId, Instant getDueAt, Instant getReadAt)
            implements DueEntry {}

    /**
     * Erases one person's due entries in a transaction bound to that person. A person who entered erasure after the
     * page was read is passed over: the erasure removes the entries.
     *
     * @return whether it erased them
     */
    private boolean eraseAsOwner(Owner owner, List<UUID> ids) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!accounts.holdForRetention(owner.accountId())) {
                return false;
            }
            ownerScope.ofJob(owner.accountId());
            redaction.redact(ids);
            return true;
        }));
    }

    private static double marginOf(List<DueEntry> oldest, Duration limit) {
        return oldest.isEmpty()
                ? Double.POSITIVE_INFINITY
                : (double) Duration.between(
                                oldest.getFirst().getReadAt(),
                                oldest.getFirst().getDueAt().plus(limit))
                        .toSeconds();
    }

    /** One rule's runs, the people it passes over and its margin. */
    private final class Sweep {

        private final Rule rule;
        private final AtomicReference<Double> deadlineMargin = new AtomicReference<>(Double.NaN);
        private final Counter samplerFailures;

        /** When this replica last read the margin, in seconds since the epoch, so an alert sees a stale margin. */
        private final AtomicLong lastRead = new AtomicLong();

        /** The people whose erasure failed and that a run did not get back to yet: tried after everyone else. */
        private volatile Set<Owner> failedLastRun = Set.of();

        Sweep(Rule rule, MeterRegistry meters) {
            this.rule = rule;
            Gauge.builder(ConversationMetrics.RETENTION_DEADLINE_MARGIN, deadlineMargin, AtomicReference::get)
                    .description("Seconds until the oldest due conversation entry of the rule reaches its retention"
                            + " limit; +Inf when no entry is due, NaN before the first read; a failed read keeps the"
                            + " last value")
                    .tag("rule", rule.tag)
                    .baseUnit("seconds")
                    .register(meters);
            samplerFailures = Counter.builder(ConversationMetrics.RETENTION_SAMPLER_FAILURES)
                    .description("Reads of the oldest due conversation entry that failed; the margin kept its last"
                            + " value")
                    .tag("rule", rule.tag)
                    .register(meters);
            lastRead.set(clock.instant().getEpochSecond());
            Gauge.builder(ConversationMetrics.RETENTION_MARGIN_READ_TIMESTAMP, lastRead, AtomicLong::get)
                    .description("When this replica last read the oldest due conversation entry of the rule, in"
                            + " seconds since the epoch; its start until the first read")
                    .tag("rule", rule.tag)
                    .baseUnit("seconds")
                    .register(meters);
        }

        /** A run reads the copying sources afresh; a sample may reuse a recent read. */
        private DueSource source(boolean fresh) {
            return rule == Rule.ENTRIES ? entriesSource() : copiesSource(fresh);
        }

        /**
         * A workspace whose copies cannot be read counts as a failed read, while the margin of the others is published,
         * so the sampler alert reports it without hiding the other workspaces from the margin alert.
         */
        void sample() {
            try {
                DueSource source = source(false);
                deadlineMargin.set(source.margin());
                lastRead.set(clock.instant().getEpochSecond());
                samplerFailures.increment(source.failedWorkspaces());
            } catch (RuntimeException e) {
                samplerFailures.increment();
                log.warn(
                        "conversation.retention: reading the oldest due {} failed, the margin keeps its last read: {}",
                        rule.tag,
                        e.toString());
            }
        }

        Run run(Progress progress) {
            DueSource source = source(true);
            Instant deadline = clock.instant().plus(RUN_BUDGET);
            Set<Owner> deferred = failedLastRun;
            SequencedSet<Owner> failed = new LinkedHashSet<>();
            boolean retried = deferred.isEmpty();
            try {
                // Everyone but the people who failed before first, then those people, while the budget lasts.
                boolean drained = pass(source, deferred, failed, deadline, progress);
                if (drained && !retried) {
                    drained = pass(source, Set.of(), failed, deadline, progress);
                    retried = drained;
                }
                Run run = new Run(progress.erased, failed.size(), source.failedWorkspaces(), drained);
                if (progress.erased > 0 || !drained || !failed.isEmpty() || run.failedWorkspaces() > 0) {
                    log.info(
                            "conversation.retention: handed {} due {} to erasure in committed transactions; {}{}",
                            progress.erased,
                            rule.tag,
                            !drained
                                    ? "the next run continues with the due entries left"
                                    : failed.isEmpty()
                                            ? "no due entry left"
                                            : "no due entry left but those of " + failed.size()
                                                    + " people whose erasure failed",
                            run.failedWorkspaces() == 0
                                    ? ""
                                    : "; the due entries of " + run.failedWorkspaces()
                                            + " workspaces could not be read");
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
         * @return whether it found no due entry left but those it passed over; {@code false} when the page or time
         *     budget ended it
         */
        private boolean pass(
                DueSource source,
                Set<Owner> deferred,
                SequencedSet<Owner> failed,
                Instant deadline,
                Progress progress) {
            @Nullable Instant afterDue = null;
            @Nullable UUID afterId = null;
            while (progress.pages < maxPages && clock.instant().isBefore(deadline)) {
                List<SkippedOwner> skip = new ArrayList<>();
                deferred.forEach(owner -> skip.add(owner.skipped()));
                failed.stream().filter(owner -> !deferred.contains(owner)).forEach(owner -> skip.add(owner.skipped()));
                List<DueEntry> due = source.page(new DuePage(afterDue, afterId, skip), batch);
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
                byOwner.computeIfAbsent(
                                new Owner(entry.getWorkspaceId(), entry.getAccountId()), owner -> new ArrayList<>())
                        .add(entry.getId());
            }
            for (Map.Entry<Owner, List<UUID>> owner : byOwner.entrySet()) {
                if (!clock.instant().isBefore(deadline)) {
                    return false;
                }
                try {
                    if (eraseAsOwner(owner.getKey(), owner.getValue())) {
                        progress.erased += owner.getValue().size();
                    }
                } catch (RuntimeException e) {
                    failed.add(owner.getKey());
                    log.warn(
                            "conversation.retention: erasure of {} failed for workspaceId={} accountId={}, tried again"
                                    + " after everyone else: {}",
                            rule.tag,
                            owner.getKey().workspaceId(),
                            owner.getKey().accountId(),
                            e.toString());
                }
            }
            return true;
        }
    }

    /**
     * What one run did.
     *
     * @param erased due entries handed to erasure in committed transactions; an entry that another run erased first
     *     counts too
     * @param failedOwners the people whose erasure failed in this run
     * @param failedWorkspaces the workspaces whose due entries this run could not read
     * @param drained whether the run found no due entry left but those of the failed people and the failed workspaces;
     *     {@code false} when it spent its page or time budget
     */
    public record Run(long erased, int failedOwners, int failedWorkspaces, boolean drained) {}

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
