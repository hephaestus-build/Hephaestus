package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.io.Serial;
import java.util.Locale;

/**
 * A captured checkout that this server cannot build again with the identity that the capture recorded. It is a capture
 * failure: it says nothing about the reviewed work, so it never becomes missing evidence.
 */
public class CheckoutUnverifiedException extends ItemCaptureException {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The repository is no longer monitored or visible, or no credential is available. */
        UNAVAILABLE,
        /** The provider could not supply the recorded commit. */
        FETCH_FAILED,
        /** The rebuilt checkout differs from the recorded tree or checkout digest. */
        MISMATCH;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Reason reason;

    public CheckoutUnverifiedException(Reason reason, String message) {
        super(ItemCaptureCode.CHECKOUT_UNVERIFIED, message);
        this.reason = reason;
    }

    public CheckoutUnverifiedException(Reason reason, String message, Throwable cause) {
        super(ItemCaptureCode.CHECKOUT_UNVERIFIED, message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
