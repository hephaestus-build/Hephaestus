package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitCloneUrl;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checkouts of a monitored repository for a reader that must not hold the Git credential. The workspace sees the
 * repository only when {@link ReviewRepositoryPreparer#permittedRepositories} names it and no team hides it from
 * contributions. The token goes only to {@link GitRepositoryManager}; the checkout has no remote and no credential.
 *
 * <p>Not {@code @Transactional}: each read is one repository call with its own read-only transaction, so no database
 * connection is held while Git fetches.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmRepositoryCheckouts implements RepositoryCheckouts {
    static final String NOT_VISIBLE = "Repository is not visible to this workspace";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern REVIEW = Pattern.compile("(pull|merge-request)/([1-9][0-9]{0,8})");

    private final GitRepositoryManager git;
    private final ReviewRepositoryPreparer preparer;
    private final PullRequestRepository pullRequests;
    private final WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;

    /** What the Git calls need; the token stays in this class. */
    private record Remote(
            RepositoryKey key, Repository repository, String cloneUrl, ScmTokenSource source, String token) {}

    @Override
    public boolean enabled() {
        return git.isEnabled();
    }

    @Override
    public Checkout checkout(
            long workspaceId, String repository, String ref, @Nullable String base, BooleanSupplier abandoned) {
        var remote = remote(workspaceId, repository);
        try {
            // Checked again once the mirror's lock is taken: a reader that stopped waiting fetches nothing.
            git.ensureRepository(remote.key(), remote.cloneUrl(), remote.token(), abandoned);
            String head = resolve(remote, ref);
            @Nullable String baseSha = base == null ? null : resolve(remote, base);
            if (abandoned.getAsBoolean())
                throw new CancellationException("The reader stopped waiting for the checkout");
            // Only the head, the base and their history: no other branch, tag or review ref of the mirror.
            GitRepositoryManager.GitTreeSnapshot snapshot =
                    git.readOriginalSnapshot(remote.key(), head, baseSha == null ? List.of() : List.of(baseSha));
            try {
                if (baseSha != null && !holds(snapshot.stagingDir(), baseSha))
                    throw new RepositoryCheckoutException(
                            RepositoryCheckoutException.Code.REF_NOT_FOUND, "Base is not reachable from the checkout");
                name(snapshot.stagingDir(), ref, head);
                if (base != null && baseSha != null) name(snapshot.stagingDir(), base, baseSha);
                keepOnlyBranches(snapshot.stagingDir());
            } catch (RuntimeException e) {
                snapshot.close();
                throw e;
            }
            return new ScmCheckout(repository, head, baseSha, snapshot);
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new RepositoryCheckoutException(
                    RepositoryCheckoutException.Code.MIRROR_UNAVAILABLE, "Repository mirror is unavailable", e);
        }
    }

    private Remote remote(long workspaceId, String path) {
        var repository = preparer.permittedRepositories(workspaceId).stream()
                .filter(candidate -> candidate.getNameWithOwner().equals(path))
                .findFirst()
                .filter(candidate -> !teamRepositorySettings
                        .findHiddenRepositoryIdsByWorkspace(workspaceId)
                        .contains(candidate.getId()))
                .orElseThrow(() -> new RepositoryCheckoutException(
                        RepositoryCheckoutException.Code.REPOSITORY_NOT_VISIBLE, NOT_VISIBLE));
        if (!git.isEnabled()) throw unavailable("Git mirrors are disabled");
        var source =
                preparer.activeTokenSource(workspaceId).orElseThrow(() -> unavailable("SCM connection is unavailable"));
        String cloneUrl = source.serverUrl(workspaceId)
                .flatMap(serverUrl -> GitCloneUrl.of(serverUrl, path))
                .orElseThrow(() -> unavailable("SCM server is unavailable"));
        String token =
                source.accessToken(workspaceId).orElseThrow(() -> unavailable("SCM credentials are unavailable"));
        return new Remote(new RepositoryKey(workspaceId, repository.getId()), repository, cloneUrl, source, token);
    }

    /** A commit id, a review of this repository's provider, a branch, then a tag. */
    private String resolve(Remote remote, String ref) {
        String sha = ref.toLowerCase(Locale.ROOT);
        if (COMMIT.matcher(sha).matches()) {
            if (git.commitExists(remote.key(), sha)) return sha;
            throw refNotFound();
        }
        var review = REVIEW.matcher(ref);
        if (review.matches()
                && Objects.equals(
                        review.group(1),
                        reviewPrefix(remote.repository().getProvider().kind()))) {
            return reviewHead(remote, Integer.parseInt(review.group(2)));
        }
        String branch = git.resolveBranchHead(remote.key(), ref);
        if (branch != null) return branch;
        String tag = git.resolveTag(remote.key(), ref);
        if (tag != null) return tag;
        throw refNotFound();
    }

    /** The stored head of the review; a mirror without it fetches the provider's review ref, as a review does. */
    private String reviewHead(Remote remote, int number) {
        String head = pullRequests
                .findByRepositoryIdAndNumber(remote.repository().getId(), number)
                .filter(pullRequest -> pullRequest.getDeletedAt() == null)
                .flatMap(pullRequest -> Optional.ofNullable(pullRequest.getHeadRefOid()))
                .orElseThrow(ScmRepositoryCheckouts::refNotFound);
        if (!git.commitExists(remote.key(), head)) {
            remote.source()
                    .reviewHeadRef(number)
                    .ifPresent(ref -> git.fetchRemoteCommit(
                            remote.key(),
                            remote.cloneUrl(),
                            ref,
                            head,
                            remote.token(),
                            GitRepositoryManager.READER_TRANSPORT_TIMEOUT_SECONDS));
        }
        if (!git.commitExists(remote.key(), head)) throw refNotFound();
        return head;
    }

    private static @Nullable String reviewPrefix(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "pull";
            case GITLAB -> "merge-request";
            default -> null;
        };
    }

    /**
     * Gives the commit the name the program asked for, as a local branch of the checkout, so {@code git diff master...}
     * works there as in the developer's clone. A commit id needs no name, and a name the checkout already has stays.
     */
    private static void name(Path checkout, String ref, String sha) {
        if (COMMIT.matcher(ref).matches() || !org.eclipse.jgit.lib.Repository.isValidRefName(Constants.R_HEADS + ref))
            return;
        try (var repository = new FileRepositoryBuilder()
                .setGitDir(checkout.resolve(Constants.DOT_GIT).toFile())
                .setMustExist(true)
                .build()) {
            if (repository.exactRef(Constants.R_HEADS + ref) != null) return;
            var update = repository.updateRef(Constants.R_HEADS + ref);
            update.setNewObjectId(ObjectId.fromString(sha));
            update.setForceUpdate(true);
            update.update();
        } catch (IOException e) {
            throw new GitRepositoryManager.GitOperationException("Cannot name the checked-out commit", e);
        }
    }

    /** Deletes every ref but the local branches named above: the snapshot pins its commits under review refs. */
    private static void keepOnlyBranches(Path checkout) {
        try (var repository = new FileRepositoryBuilder()
                .setGitDir(checkout.resolve(Constants.DOT_GIT).toFile())
                .setMustExist(true)
                .build()) {
            for (var other : repository.getRefDatabase().getRefs()) {
                if (other.getName().startsWith(Constants.R_HEADS)
                        || other.getName().equals(Constants.HEAD)) continue;
                var delete = repository.updateRef(other.getName());
                delete.setForceUpdate(true);
                delete.delete();
            }
        } catch (IOException e) {
            throw new GitRepositoryManager.GitOperationException("Cannot clean the checkout's refs", e);
        }
    }

    /** The checkout copies only what its branches, tags and head reach; a base on none of them is absent. */
    private static boolean holds(Path checkout, String sha) {
        try (var repository = new FileRepositoryBuilder()
                .setGitDir(checkout.resolve(Constants.DOT_GIT).toFile())
                .setMustExist(true)
                .build()) {
            return repository.getObjectDatabase().has(ObjectId.fromString(sha));
        } catch (IOException e) {
            throw new GitRepositoryManager.GitOperationException("Cannot read the checkout", e);
        }
    }

    private static RepositoryCheckoutException refNotFound() {
        return new RepositoryCheckoutException(
                RepositoryCheckoutException.Code.REF_NOT_FOUND, "Ref does not resolve to a commit");
    }

    private static RepositoryCheckoutException unavailable(String message) {
        return new RepositoryCheckoutException(RepositoryCheckoutException.Code.MIRROR_UNAVAILABLE, message);
    }

    private record ScmCheckout(
            String repository, String head, @Nullable String base, GitRepositoryManager.GitTreeSnapshot snapshot)
            implements Checkout {
        @Override
        public Path directory() {
            return snapshot.stagingDir();
        }

        @Override
        public void close() {
            snapshot.close();
        }
    }
}
