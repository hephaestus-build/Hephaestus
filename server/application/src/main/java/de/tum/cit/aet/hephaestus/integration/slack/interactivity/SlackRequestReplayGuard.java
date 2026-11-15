package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles each signed Slack interactivity request once. Slack's signature stays valid for five minutes, so a copy of a
 * signed request could otherwise repeat an action, such as an opt-out after a later opt-in. The claim reads the
 * signature, which authenticates the body and its timestamp.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackRequestReplayGuard {

    /** Longer than the signature's validity in both directions, so no valid copy outlives its claim. */
    static final Duration RETENTION = Duration.ofMinutes(15);

    private final SlackRequestClaimRepository claims;
    private final Clock clock;

    public SlackRequestReplayGuard(SlackRequestClaimRepository claims, Clock clock) {
        this.claims = claims;
        this.clock = clock;
    }

    /** True for the first request with {@code signature}, false for every copy. */
    @Transactional
    public boolean claim(String signature) {
        return claims.claim(signature, clock.instant()) == 1;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @SchedulerLock(name = "slack-request-claim-cleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    @WorkspaceAgnostic("Claims are keyed by the signature of the instance's Slack app, not by a workspace")
    public void deleteExpiredClaims() {
        claims.deleteClaimedBefore(clock.instant().minus(RETENTION));
    }
}
