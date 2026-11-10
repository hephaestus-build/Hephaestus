package de.tum.cit.aet.hephaestus.core.auth.spi;

/**
 * Cross-module port: record an instance admin's decision on a GitHub App installation on the auth-event trail, in
 * the transaction that changes the installation's record. The record is instance-level, so the workspace-scoped
 * config trail cannot carry it. The details name the installation only, never the account login.
 */
public interface GitHubInstallationDecisionAudit {
    /** @return whether the event is staged; the caller rolls its change back otherwise */
    boolean adopted(long actingAccountId, long installationId, long workspaceId);

    /** @return whether the event is staged; the caller rolls its change back otherwise */
    boolean declined(long actingAccountId, long installationId);
}
