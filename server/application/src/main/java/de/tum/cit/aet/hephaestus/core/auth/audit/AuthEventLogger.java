package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.WorkspaceElevationContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Builds audit events; {@link AuthEventWriter} owns their independent transaction. */
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
        private @Nullable Long viewedUserId;
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

        public Draft viewedUser(@Nullable Long id) {
            this.viewedUserId = id;
            return this;
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
         * @return whether the event committed; callers decide whether a failed audit must block their operation
         */
        public boolean record() {
            try {
                return writer.write(new AuthEventData(
                        type,
                        result,
                        accountId,
                        viewedUserId,
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
                // Commit failures arise outside the writer method, at its transaction proxy.
                log.warn("auth.audit: {} event could not be committed", type, e);
                return false;
            }
        }
    }
}
