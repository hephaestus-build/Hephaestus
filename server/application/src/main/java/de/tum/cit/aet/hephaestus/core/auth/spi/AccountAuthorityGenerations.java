package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.OptionalLong;

/**
 * A person's authorization generation, which only a narrowing of their AI choice, a removal of their instance role or
 * the end of their active account raises (ADR 0134 decision 1).
 */
public interface AccountAuthorityGenerations {

    /** The generation as committed now; empty when the account no longer exists. */
    OptionalLong generation(long accountId);

    /** The generation, held until the caller's transaction ends, so no narrowing commits meanwhile. */
    OptionalLong generationHeld(long accountId);
}
