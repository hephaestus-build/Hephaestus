package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;
import java.util.Locale;

/**
 * The runtime has no room for the sandbox, because the sandbox quota is full or smaller than the sandbox. Nothing of
 * the sandbox ran, so the work spends no retry: this is neither a failure of the work nor of the infrastructure.
 */
public class SandboxCapacityException extends SandboxException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the runtime had no room. */
    public enum Reason {
        /** The quota has no room now; it frees room as running sandboxes end. */
        QUOTA_EXCEEDED,
        /** One sandbox needs more than the quota holds: no wait helps, the configuration must change. */
        QUOTA_CANNOT_FIT;

        /** The reason as a metric tag and a log token. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Reason reason;

    public SandboxCapacityException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
