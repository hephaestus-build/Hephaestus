package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * A repository that no workspace may monitor, whatever a provider's installation covers. The answer can change while
 * the application runs, so a caller asks each time and keeps no copy.
 */
public interface WorkspaceRepositoryExclusion {
    /** @param nameWithOwner the repository as {@code owner/name}, in any case */
    boolean excludes(String nameWithOwner);
}
