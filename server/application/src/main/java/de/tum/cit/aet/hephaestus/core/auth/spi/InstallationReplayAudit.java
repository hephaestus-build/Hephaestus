package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Records installation replay without exposing the auth module's persistence types. */
public interface InstallationReplayAudit {
    /** @return whether the audit event was staged in the caller's transaction */
    boolean record(long actingAccountId, long deadLetterId);
}
