package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DueEntry;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erases every conversation entry that reaches {@link FeedbackRetentionProperties#feedbackRetention()}, as a delete
 * erases it. It runs once a week and erases an entry up to two weeks before its limit, so a missed run still keeps it:
 * an erased entry also erases every summary over it, and a nightly run would cost each long conversation a full
 * compaction every night.
 *
 * <p>The owner function {@code mentoring_due_work} names the due entries of every person, oldest first, a page at a
 * time ({@link ConversationEntryRepository#findDueForRetention}). Each person's entries are erased in a transaction
 * bound to that person, the owner the due row names. A run erases at most {@link #MAX_PER_RUN} entries for each
 * workspace and says so when it stops there. A workspace whose erasure fails is logged and passed over until the next
 * run.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@WorkspaceAgnostic("Finds the due entries of every workspace first, then erases them person by person")
public class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    /** Two weekly runs: one can be missed without an entry outliving its limit. */
    static final Duration EARLY = Duration.ofDays(14);

    static final int BATCH = 500;
    static final int MAX_PER_RUN = 20_000;

    /** Before the oldest entry: every entry sorts after it. */
    private static final Instant START = Instant.EPOCH;

    private static final UUID START_ID = new UUID(0, 0);

    private final ConversationEntryRepository entries;
    private final ConversationRedaction redaction;
    private final AccountDirectory accounts;
    private final OwnerScope ownerScope;
    private final TransactionTemplate transaction;
    private final FeedbackRetentionProperties properties;
    private final Clock clock;

    public ConversationRetentionSweeper(
            ConversationEntryRepository entries,
            ConversationRedaction redaction,
            AccountDirectory accounts,
            OwnerScope ownerScope,
            PlatformTransactionManager transactionManager,
            FeedbackRetentionProperties properties,
            Clock clock) {
        this.entries = entries;
        this.redaction = redaction;
        this.accounts = accounts;
        this.ownerScope = ownerScope;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * SUN")
    @SchedulerLock(name = "conversation-retention-sweep", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /** @return how many due entries it submitted for erasure in committed transactions, across every workspace */
    public long sweepNow() {
        Instant cutoff = clock.instant().minus(properties.feedbackRetention().minus(EARLY));
        Map<Long, Integer> handed = new HashMap<>();
        Set<Long> failed = new HashSet<>();
        Instant afterDue = START;
        UUID afterId = START_ID;
        List<DueEntry> due;
        do {
            due = entries.findDueForRetention(cutoff, afterDue, afterId, BATCH);
            erase(due, handed, failed);
            if (!due.isEmpty()) {
                // The next page starts after this one, so a workspace that failed or is full holds back no other.
                afterDue = due.getLast().getDueAt();
                afterId = due.getLast().getId();
            }
        } while (due.size() == BATCH);
        handed.forEach((workspaceId, count) -> {
            if (count >= MAX_PER_RUN) {
                log.warn(
                        "conversation.retention: workspaceId={} has more expired entries than one run erases",
                        workspaceId);
            }
        });
        long total = handed.values().stream().mapToLong(Integer::longValue).sum();
        if (total > 0) {
            log.info(
                    "conversation.retention: erased {} conversation entries past the retention in committed transactions",
                    total);
        }
        return total;
    }

    private void erase(List<DueEntry> due, Map<Long, Integer> handed, Set<Long> failed) {
        Map<Owner, List<UUID>> byOwner = new LinkedHashMap<>();
        for (DueEntry entry : due) {
            long workspaceId = entry.getWorkspaceId();
            if (failed.contains(workspaceId) || handed.getOrDefault(workspaceId, 0) >= MAX_PER_RUN) {
                continue;
            }
            handed.merge(workspaceId, 1, Integer::sum);
            byOwner.computeIfAbsent(new Owner(workspaceId, entry.getAccountId()), owner -> new ArrayList<>())
                    .add(entry.getId());
        }
        for (Map.Entry<Owner, List<UUID>> owner : byOwner.entrySet()) {
            long workspaceId = owner.getKey().workspaceId();
            if (failed.contains(workspaceId)) {
                handed.merge(workspaceId, -owner.getValue().size(), Integer::sum);
                continue;
            }
            try {
                var eligible = transaction.execute(status -> {
                    if (!accounts.holdForRetention(owner.getKey().accountId())) return false;
                    ownerScope.ofJob(owner.getKey().accountId());
                    redaction.redact(owner.getValue());
                    return true;
                });
                if (!Boolean.TRUE.equals(eligible)) {
                    handed.merge(workspaceId, -owner.getValue().size(), Integer::sum);
                }
            } catch (RuntimeException e) {
                failed.add(workspaceId);
                handed.merge(workspaceId, -owner.getValue().size(), Integer::sum);
                log.warn("conversation.retention: sweep failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
    }

    private record Owner(long workspaceId, long accountId) {}
}
