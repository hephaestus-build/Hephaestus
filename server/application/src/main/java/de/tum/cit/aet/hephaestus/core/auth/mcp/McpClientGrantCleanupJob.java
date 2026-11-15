package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks MCP client grants whose consent request or code ran out as expired, and deletes ended grants after
 * their retention. Not gated on the endpoint being on: grants made while it was on still age out after an
 * operator turns it off.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Retention expires and removes MCP client grant rows across all workspaces")
public class McpClientGrantCleanupJob {

    /** An approved grant waits for its exchange as long as its code lives. */
    static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** Ended grants stay this long, so the history can still name the client (ADR 0110 decision 9). */
    static final Duration RETENTION = Duration.ofDays(365);

    private final McpClientGrantRepository grants;
    private final Clock clock;

    McpClientGrantCleanupJob(McpClientGrantRepository grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @Scheduled(cron = "0 25 * * * *")
    @SchedulerLock(name = "mcp-client-grant-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        grants.expireStale(now, now.minus(CODE_TTL));
        grants.deleteEndedBefore(now.minus(RETENTION));
    }
}
