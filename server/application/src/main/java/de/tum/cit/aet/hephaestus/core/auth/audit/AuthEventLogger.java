package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.WorkspaceElevationContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Builds audit events; {@link AuthEventWriter} owns their transactional persistence. */
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

    public final class Draft {

        private final AuthEvent.EventType type;
        private final AuthEvent.Result result;
        private @Nullable Long accountId;
        private @Nullable Long actingAccountId;
        private @Nullable String failureReason;
        private @Nullable Long gitProviderId;
        private @Nullable Long workspaceId;
        private @Nullable Long identityId;
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

        public Draft identity(@Nullable Long id) {
            this.identityId = id;
            return this;
        }

        public Draft details(@Nullable String json) {
            this.details = json;
            return this;
        }

        /**
         * @return whether the event committed; callers decide whether a failed audit must block
         *     their operation
         */
        public boolean record() {
            return record(false);
        }

        /**
         * Stages the event in the caller's transaction, so it rolls back with the operation.
         * A successful return does not mean that the owning transaction has committed.
         */
        public boolean recordInCurrentTransaction() {
            return record(true);
        }

        private boolean record(boolean currentTransaction) {
            try {
                var data = new AuthEventData(
                        type,
                        result,
                        accountId,
                        // Only the removed View as user wrote these; its earlier rows keep them.
                        null,
                        null,
                        actingAccountId,
                        failureReason,
                        gitProviderId,
                        workspaceId,
                        identityId,
                        details,
                        // Read here, from the request's security context, so no producer
                        // can forget the
                        // flag or assert one it did not earn.
                        WorkspaceElevationContext.isElevated(workspaceId));
                return currentTransaction ? writer.writeInCurrentTransaction(data) : writer.write(data);
            } catch (RuntimeException e) {
                // The writer proxy can fail when joining or committing its transaction.
                log.warn("auth.audit: {} event could not be committed", type, e);
                return false;
            }
        }
    }
}
