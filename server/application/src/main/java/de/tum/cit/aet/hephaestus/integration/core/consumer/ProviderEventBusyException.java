package de.tum.cit.aet.hephaestus.integration.core.consumer;

import org.springframework.dao.TransientDataAccessResourceException;

/** Rolls back a write that cannot take its inbox fence without waiting while it holds domain locks. */
final class ProviderEventBusyException extends TransientDataAccessResourceException {
    private static final long serialVersionUID = 1L;

    ProviderEventBusyException() {
        super("Provider event row is locked");
    }
}
