package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import java.io.Serial;

/** The name now identifies another repository. It must not be persisted as the monitored identity. */
public class RepositoryIdentityMismatchException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RepositoryIdentityMismatchException(String nameWithOwner, long expectedId, long actualId) {
        super("Repository identity changed: " + nameWithOwner + ", expected=" + expectedId + ", actual=" + actualId);
    }
}
