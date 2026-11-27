package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Audits a completed request by a person to erase their own record. */
public interface PersonDataErasureAudit {
    void requestedByOwner(long accountId);
}
