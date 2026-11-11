package de.tum.cit.aet.hephaestus.core.event;

/**
 * Asks every workspace to give up a repository now. Published after the change commits. Each workspace gives it up in
 * a transaction of its own, so one failure keeps no other workspace holding it. It is safe to publish again: a
 * repository that no workspace monitors changes nothing.
 */
public record RepositoryExcludedFromWorkspacesEvent(String nameWithOwner) {}
