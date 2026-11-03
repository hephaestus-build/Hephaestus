package de.tum.cit.aet.hephaestus.integration.slack.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the nightly Slack reconciliation ({@link SlackDataSyncScheduler}), the Slack counterpart of
 * {@code hephaestus.sync.*} for SCM.
 *
 * <p>The budgets exist because of Slack's May-2025 rate-limit change: non-Marketplace apps get
 * <strong>1 request/minute and at most 15 objects per request</strong> on {@code conversations.history} and
 * {@code conversations.replies}. At that ceiling a nightly window is a scarce resource — the sync self-paces
 * ({@code historyRequestInterval}) instead of provoking 429s, spends at most {@code historyRequestBudget} history
 * requests per workspace per run, and orders channels stalest-first so a large workspace converges across nights
 * rather than starving its tail. {@code reconcileRequestBudget} bounds the separate check of older stored messages
 * for deletions, which would otherwise take the whole budget every night. Marketplace-approved and internal apps keep
 * the old tiers — raise the budgets and shrink the interval for those installs.
 */
@ConfigurationProperties(prefix = "hephaestus.sync.slack")
public record SlackSyncProperties(
        @DefaultValue("0 0 4 * * *") String cron,
        @DefaultValue("120") int historyRequestBudget,
        @DefaultValue("15") int historyPageLimit,
        @DefaultValue("60s") Duration historyRequestInterval,
        @DefaultValue("true") boolean repliesEnabled,
        @DefaultValue("30") int repliesRequestBudget,
        @DefaultValue("true") boolean metadataEnabled,
        @DefaultValue("30") int reconcileRequestBudget) {
    public SlackSyncProperties {
        if (historyRequestBudget < 1) {
            throw new IllegalArgumentException("sync.slack.historyRequestBudget must be >= 1");
        }
        if (historyPageLimit < 1 || historyPageLimit > 1000) {
            throw new IllegalArgumentException("sync.slack.historyPageLimit must be in [1, 1000]");
        }
        if (historyRequestInterval.isNegative()) {
            throw new IllegalArgumentException("sync.slack.historyRequestInterval must not be negative");
        }
        if (repliesRequestBudget < 0) {
            throw new IllegalArgumentException("sync.slack.repliesRequestBudget must be >= 0");
        }
        if (reconcileRequestBudget < 0) {
            throw new IllegalArgumentException("sync.slack.reconcileRequestBudget must be >= 0");
        }
    }
}
