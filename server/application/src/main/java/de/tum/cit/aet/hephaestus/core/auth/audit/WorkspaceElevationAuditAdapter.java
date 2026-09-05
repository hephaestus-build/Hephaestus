package de.tum.cit.aet.hephaestus.core.auth.audit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceElevationAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code WORKSPACE_ELEVATION} marker that makes an instance admin's non-member access to
 * a workspace visible in the audit viewer.
 *
 * <p>It records an access <em>window</em>, not a request: an admin browsing one workspace issues
 * dozens of requests, and a row per request would bury the impersonation and role-change events the
 * viewer exists for. So the marker is de-duplicated per {@code (account, workspace)} for
 * {@link #DEDUP_WINDOW}. The cache is bounded and per-process, so eviction, concurrent requests and
 * a second replica may each produce an extra marker — over-reporting an access window is harmless,
 * losing one is not. The elevation bit on the config-audit rows themselves is never de-duplicated;
 * every configuration change carries its own.
 */
@ConditionalOnServerRole
@Component
public class WorkspaceElevationAuditAdapter implements WorkspaceElevationAudit {

    static final Duration DEDUP_WINDOW = Duration.ofMinutes(15);

    private static final int MAX_TRACKED_WINDOWS = 10_000;

    private final AuthEventLogger authEventLogger;

    private final Cache<String, Boolean> recordedWindows = Caffeine.newBuilder()
            .expireAfterWrite(DEDUP_WINDOW)
            .maximumSize(MAX_TRACKED_WINDOWS)
            .build();

    public WorkspaceElevationAuditAdapter(AuthEventLogger authEventLogger) {
        this.authEventLogger = authEventLogger;
    }

    @Override
    public void recordElevatedAccess(long accountId, long workspaceId) {
        String window = accountId + ":" + workspaceId;
        if (recordedWindows.getIfPresent(window) != null) {
            return;
        }
        boolean persisted = authEventLogger
                .event(AuthEvent.EventType.WORKSPACE_ELEVATION, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .workspace(workspaceId)
                .record();
        // Claim the window only once a row actually exists. Claiming it on a failed write would
        // suppress every retry for the whole window, turning one lost row into an unaudited window.
        if (persisted) {
            recordedWindows.put(window, Boolean.TRUE);
        }
    }
}
