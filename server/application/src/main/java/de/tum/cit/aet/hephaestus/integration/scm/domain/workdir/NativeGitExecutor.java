package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Executes trusted Git operations outside the application process; output is never silently truncated. */
public interface NativeGitExecutor {
    /**
     * The most a serialized {@link Request} may occupy, in UTF-8 bytes; the helper in
     * {@code docker/agents/git/operation.ts} refuses the same size on its stdin.
     */
    int MAX_REQUEST_BYTES = 64 * 1024;

    void execute(RepositoryKey repository, Request request, Duration timeout, OutputStream output);

    /** The integration adapter authorizes both IDs before dispatch; callers never choose a storage path. */
    record RepositoryKey(long workspaceId, long repositoryId) {
        public RepositoryKey {
            if (workspaceId <= 0 || repositoryId <= 0) throw new IllegalArgumentException("Invalid repository scope");
        }
    }

    void deleteRepository(long repositoryId);

    void executeInSnapshot(Path trustedRepository, Request request, Duration timeout, OutputStream output);

    enum Operation {
        SCAN_SECRETS,
        CITED_BLOBS,
        HISTORICAL_BLOB,
        FETCH,
        FETCH_COMMIT,
        STATUS,
        RESOLVE,
        TREE_ID,
        TREE_ENTRIES,
        COMMIT_IDS,
        COMMIT_RANGE,
        COMMIT_SUBJECTS,
        COMMIT_DETAILS,
        SNAPSHOT,
        RESOLVE_DIFF_RANGE,
        REVIEW_DIFF,
        REVIEW_COMMITS;

        /**
         * Whether the operation reads a job's canonical evidence rather than a mirror: only
         * {@link #executeInSnapshot(Path, Request, Duration, OutputStream)} may run it, and never on
         * behalf of another server.
         */
        public boolean readsCanonicalEvidence() {
            return this == CITED_BLOBS || this == HISTORICAL_BLOB || this == SCAN_SECRETS;
        }
    }

    record Request(
            Operation operation,
            List<String> revisions,
            @Nullable String cloneUrl,
            @Nullable String token) {
        public Request {
            Objects.requireNonNull(operation);
            revisions = List.copyOf(revisions);
            if (operation != Operation.FETCH
                    && operation != Operation.FETCH_COMMIT
                    && (cloneUrl != null || token != null)) {
                throw new IllegalArgumentException("Offline operations cannot receive credentials");
            }
        }

        @Override
        public String toString() {
            return "GitRequest[operation=" + operation + "]";
        }
    }
}
