package de.tum.cit.aet.hephaestus.core.release;

/** What kind of build the deployment reports it is running, read from the version it was handed. */
public enum ReleaseChannel {
    /** A published {@code X.Y.Z} release; the only channel with something to compare against. */
    RELEASE,
    /** A build of the default branch, identified by its commit; staging hosts run this. */
    COMMIT,
    /** No deployment-supplied version: a local build, or the version placeholder left in place. */
    DEVELOPMENT
}
