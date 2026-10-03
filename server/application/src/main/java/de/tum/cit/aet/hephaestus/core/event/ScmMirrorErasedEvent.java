package de.tum.cit.aet.hephaestus.core.event;

/**
 * Signals that a workspace's mirrored SCM data (repositories, issues, pull requests, reviews,
 * comments, discussions, labels, milestones, collaborators, teams and memberships) is being
 * <b>irreversibly erased</b> — the disconnect/purge erase trigger, not the upstream-drift
 * tombstone sweep. Published <b>synchronously and inside the erasing transaction</b> by
 * {@code workspace.ScmWorkspaceContentEraser} <em>before</em> the SCM rows are dropped, so
 * listeners that own SCM-<em>derived</em> rows can erase them while the artifacts they point at
 * still exist.
 *
 * <p>Lives in {@code core.event} as a deliberate dependency inversion: the workspace module publishes
 * while the practices and activity modules listen. Both of those modules already depend on the
 * workspace module ({@code Practice.workspace}, {@code workspace.spi.WorkspacePurgeContributor}),
 * so a direct {@code workspace → practices} port call would close a Spring Modulith cycle. This
 * mirrors {@code WorkspacesInitializedEvent} and the in-transaction
 * {@code RepositoryAboutToBeDeletedEvent} pattern.
 *
 * <p>Listeners must be plain {@code @EventListener}s (synchronous, joining the publisher's
 * transaction) and must be idempotent — the erase is re-runnable by design.
 *
 * @param workspaceId the tenant whose SCM mirror is being erased
 */
public record ScmMirrorErasedEvent(long workspaceId) {}
