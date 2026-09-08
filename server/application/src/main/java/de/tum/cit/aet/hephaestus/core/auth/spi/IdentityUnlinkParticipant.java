package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Removes identity-derived access in the unlink transaction while its account is locked. */
public interface IdentityUnlinkParticipant {
    void beforeUnlink(Long accountId, Long providerId, String subject);
}
