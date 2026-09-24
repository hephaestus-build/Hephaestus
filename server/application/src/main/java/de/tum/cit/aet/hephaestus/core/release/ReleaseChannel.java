package de.tum.cit.aet.hephaestus.core.release;

/** What kind of build the deployment handed this process, read from the shape of its version. */
public enum ReleaseChannel {
    /** A published {@code X.Y.Z} release: the only channel with something to compare against. */
    RELEASE,
    /** A build of the default branch, identified by its commit. */
    COMMIT,
    /** No deployment-supplied version: a local build, or the placeholder left in the JAR. */
    DEVELOPMENT
}
