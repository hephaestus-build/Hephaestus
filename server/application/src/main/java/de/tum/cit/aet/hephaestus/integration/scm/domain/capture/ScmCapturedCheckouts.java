package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedCheckouts;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException.Reason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitCloneUrl;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * Builds a captured checkout again on this server. The workspace must still monitor the repository through its active
 * SCM connection; the token goes only to {@link GitRepositoryManager}. A commit that the mirror lacks is fetched with
 * the mirror's branches, then by the review's provider ref.
 */
@Component
@ConditionalOnServerRole
public class ScmCapturedCheckouts implements CapturedCheckouts {
    private final GitRepositoryManager git;
    private final ReviewRepositoryPreparer preparer;
    private final MeterRegistry meters;

    public ScmCapturedCheckouts(GitRepositoryManager git, ReviewRepositoryPreparer preparer, MeterRegistry meters) {
        this.git = git;
        this.preparer = preparer;
        this.meters = meters;
        // Registered at start, so an alert on a count that is still zero has a series to read.
        counter("hit");
        counter("rebuilt");
        for (Reason reason : Reason.values()) counter(reason.tag());
    }

    @Override
    public void copy(long workspaceId, CheckoutKey key, Path target, Predicate<Path> verified) {
        var repository = new RepositoryKey(workspaceId, key.repositoryId());
        try {
            if (!git.isEnabled()) throw unverified(Reason.UNAVAILABLE, "Git mirrors are disabled");
            boolean built;
            try {
                if (!holds(repository, key.commit())) fetch(repository, key);
                built = git.copyCheckout(repository, key.commit(), key.tree(), key.paths(), target);
            } catch (GitRepositoryManager.MirrorUnavailableException evicted) {
                // An eviction under disk pressure took the mirror after the check: fetch it once more.
                FileSystemUtils.deleteRecursively(target);
                fetch(repository, key);
                built = git.copyCheckout(repository, key.commit(), key.tree(), key.paths(), target);
            }
            if (!verified.test(target)) {
                FileSystemUtils.deleteRecursively(target);
                throw unverified(Reason.MISMATCH, "The rebuilt checkout differs from the recorded checkout digest");
            }
            count(built ? "rebuilt" : "hit");
        } catch (IOException failure) {
            count(Reason.FETCH_FAILED.tag());
            throw new CheckoutUnverifiedException(
                    Reason.FETCH_FAILED, "The unverified checkout could not be removed", failure);
        } catch (CheckoutUnverifiedException failure) {
            count(failure.reason().tag());
            throw failure;
        } catch (GitRepositoryManager.TreeMismatchException mismatch) {
            count(Reason.MISMATCH.tag());
            throw new CheckoutUnverifiedException(Reason.MISMATCH, "The rebuilt checkout has another tree", mismatch);
        } catch (GitRepositoryManager.GitOperationException failure) {
            count(Reason.FETCH_FAILED.tag());
            throw new CheckoutUnverifiedException(
                    Reason.FETCH_FAILED, "The captured checkout could not be built", failure);
        }
    }

    private void fetch(RepositoryKey repository, CheckoutKey key) {
        long workspaceId = repository.workspaceId();
        var permitted = preparer.permittedRepositories(workspaceId).stream()
                .filter(candidate -> candidate.getId() == key.repositoryId())
                .findFirst()
                .orElseThrow(() -> unverified(Reason.UNAVAILABLE, "The workspace no longer monitors the repository"));
        var source = preparer.activeTokenSource(workspaceId)
                .orElseThrow(() -> unverified(Reason.UNAVAILABLE, "SCM connection is unavailable"));
        String cloneUrl = source.serverUrl(workspaceId)
                .flatMap(serverUrl -> GitCloneUrl.of(serverUrl, permitted.getNameWithOwner()))
                .orElseThrow(() -> unverified(Reason.UNAVAILABLE, "SCM server is unavailable"));
        String token = source.accessToken(workspaceId)
                .orElseThrow(() -> unverified(Reason.UNAVAILABLE, "SCM credentials are unavailable"));
        git.ensureRepository(repository, cloneUrl, token);
        Integer review = key.review();
        if (!holds(repository, key.commit()) && review != null) {
            source.reviewHeadRef(review)
                    .ifPresent(ref -> git.fetchRemoteCommit(repository, cloneUrl, ref, key.commit(), token));
        }
        if (!holds(repository, key.commit()))
            throw unverified(Reason.FETCH_FAILED, "The provider no longer has the captured commit");
    }

    private boolean holds(RepositoryKey repository, String commit) {
        return git.isRepositoryCloned(repository) && git.commitExists(repository, commit);
    }

    private void count(String outcome) {
        counter(outcome).increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder(ScmMetrics.EVIDENCE_CHECKOUT_COPIES)
                .description("Copies of captured checkouts, by whether this server had, built or could not build them")
                .tag("outcome", outcome)
                .register(meters);
    }

    private static CheckoutUnverifiedException unverified(Reason reason, String message) {
        return new CheckoutUnverifiedException(reason, message);
    }
}
