package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.WorkspaceElevationContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fluent front door for writing {@link AuthEvent} rows. Delegates the actual persistence
 * to {@link AuthEventWriter} (a separate bean, so the {@code REQUIRES_NEW} transaction
 * boundary is not bypassed by self-invocation).
 *
 * <p>Usage:
 * <pre>{@code
 * authEventLogger.event(LOGIN, SUCCESS).account(accountId).gitProvider(providerId).record();
 * }</pre>
 */
@ConditionalOnServerRole
@Component
public class AuthEventLogger {

    private static final Logger log = LoggerFactory.getLogger(AuthEventLogger.class);

    private final AuthEventWriter writer;

    public AuthEventLogger(AuthEventWriter writer) {
        this.writer = writer;
    }

    public Draft event(AuthEvent.EventType type, AuthEvent.Result result) {
        return new Draft(type, result);
    }

    /** Fluent, null-tolerant builder. Terminal {@link #record()} persists via the writer. */
    public final class Draft {

        private final AuthEvent.EventType type;
        private final AuthEvent.Result result;
        private @Nullable Long accountId;
        private @Nullable Long actingAccountId;
        private @Nullable String failureReason;
        private @Nullable Long gitProviderId;
        private @Nullable Long workspaceId;
        private @Nullable Long identityLinkId;
        private @Nullable String details;

        private Draft(AuthEvent.EventType type, AuthEvent.Result result) {
            this.type = type;
            this.result = result;
        }

        public Draft account(@Nullable Long id) {
            this.accountId = id;
            return this;
        }

        public Draft actingAccount(@Nullable Long id) {
            this.actingAccountId = id;
            return this;
        }

        public Draft failureReason(@Nullable String reason) {
            this.failureReason = reason;
            return this;
        }

        public Draft gitProvider(@Nullable Long id) {
            this.gitProviderId = id;
            return this;
        }

        public Draft workspace(@Nullable Long id) {
            this.workspaceId = id;
            return this;
        }

        public Draft identityLink(@Nullable Long id) {
            this.identityLinkId = id;
            return this;
        }

        public Draft details(@Nullable String json) {
            this.details = json;
            return this;
        }

        /**
         * Persists the drafted event. Never throws.
         *
         * @return whether the row reached the database — a caller that de-duplicates its own writes
         *     must not claim success on {@code false}
         */
        public boolean record() {
            try {
                return writer.write(new AuthEventData(
                        type,
                        result,
                        accountId,
                        actingAccountId,
                        failureReason,
                        gitProviderId,
                        workspaceId,
                        identityLinkId,
                        details,
                        // Read here, from the request's security context, so no producer can forget the
                        // flag or assert one it did not earn.
                        WorkspaceElevationContext.isElevated(workspaceId)));
            } catch (RuntimeException e) {
                // An audit write must NEVER break the caller's business transaction. AuthEventWriter
                // already swallows the insert failure, but its REQUIRES_NEW boundary can still surface an
                // UnexpectedRollbackException at commit when the failed statement aborted that inner tx
                // (the inner tx is independent, so the caller's tx stays intact once we absorb this).
                log.warn("auth.audit: {} event could not be persisted; suppressed to protect the request", type, e);
                return false;
            }
        }
    }
}
