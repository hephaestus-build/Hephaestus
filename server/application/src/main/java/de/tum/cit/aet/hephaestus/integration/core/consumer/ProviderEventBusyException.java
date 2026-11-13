package de.tum.cit.aet.hephaestus.integration.core.consumer;

import org.springframework.dao.TransientDataAccessResourceException;

/** Rolls back a write that cannot take its inbox fence without waiting while it holds domain locks. */
final class ProviderEventBusyException extends TransientDataAccessResourceException {
    private static final long serialVersionUID = 1L;

    enum Reason {
        LEASE_ROW("lease-row-busy"),
        WORKSPACE_ROW("workspace-row-busy"),
        ERASURE("person-erasure-in-progress");

        final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }

    private final Reason reason;

    ProviderEventBusyException(Reason reason) {
        super(reason.tag);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
