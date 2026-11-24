package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Checkouts of one monitored repository at one ref, for a reader that must not see the Git credential. The
 * implementation fetches with the workspace's token and gives back a directory that holds no credential and no
 * remote.
 */
@SourceControlContract
public interface RepositoryCheckouts {
    /** Whether this process can make checkouts: Git mirrors are enabled. */
    boolean enabled();

    /**
     * A checkout of {@code repository} at {@code ref}. A ref is a full commit id, a branch, a tag, or a review: {@code
     * pull/N} on GitHub, {@code merge-request/N} on GitLab. The caller closes the checkout.
     *
     * @param repository the provider path, such as {@code owner/repo}, matched exactly
     * @param base an optional second ref that the checkout must also hold, such as the target of a diff
     * @throws RepositoryCheckoutException with one of its codes
     */
    default Checkout checkout(long workspaceId, String repository, String ref, @Nullable String base) {
        return checkout(workspaceId, repository, ref, base, () -> false);
    }

    /**
     * As {@link #checkout(long, String, String, String)}, for a reader that may stop waiting: once {@code abandoned}
     * holds, no further fetch or checkout starts, and the call throws {@code CancellationException}.
     * A reader that waits for the mirror's lock checks it when it gets the lock.
     */
    Checkout checkout(
            long workspaceId, String repository, String ref, @Nullable String base, BooleanSupplier abandoned);

    /** A directory with its own {@code .git} and HEAD detached at {@link #head()}; {@link #close()} deletes it. */
    @SourceControlContract
    interface Checkout extends AutoCloseable {
        String repository();

        /** The commit id that {@code ref} resolved to. */
        String head();

        /** The commit id that {@code base} resolved to, or null when the caller gave no base. */
        @Nullable
        String base();

        Path directory();

        @Override
        void close();
    }
}
