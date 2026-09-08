package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Records the genuine GitHub organization owner's consent without exposing auth ledger internals. */
public interface GitHubAccessAuthorizationAudit {
    void authorized(long accountId, long workspaceId, long identityLinkId);
}
