package de.tum.cit.aet.hephaestus.integration.core.consumer;

/** A removed event or a replaced lease cannot commit another write step. */
final class ProviderEventFenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    enum Reason {
        ERASED_DURING_PROCESSING("erased-during-processing"),
        LEASE_LOST("lease-lost");

        final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }

    private final Reason reason;

    ProviderEventFenceException(Reason reason) {
        super(reason.tag);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
