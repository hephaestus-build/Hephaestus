package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Executes trusted Git operations outside the application process; output is never silently truncated. */
public interface NativeGitExecutor {
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
        REVIEW_COMMITS
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
