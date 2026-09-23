package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds one check event into the head-check state of every pull request whose head it is about. A
 * suite or a commit status is one voice among the head's checks, so it can only worsen a state
 * already recorded for that head ({@link PullRequest#observeHeadChecks}); the next GraphQL sync
 * reads GitHub's own rollup and settles it.
 */
@Service
public class GitHubHeadCheckProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubHeadCheckProcessor.class);

    private final PullRequestRepository pullRequestRepository;

    public GitHubHeadCheckProcessor(PullRequestRepository pullRequestRepository) {
        this.pullRequestRepository = pullRequestRepository;
    }

    @Transactional
    public void observe(String sha, CheckState state, ProcessingContext context) {
        long repositoryId = Objects.requireNonNull(context.repository()).getId();
        for (PullRequest pr : pullRequestRepository.findAllByRepository_IdAndHeadRefOid(repositoryId, sha)) {
            if (pr.observeHeadChecks(sha, state, false)) {
                pullRequestRepository.save(pr);
                log.debug("Observed head checks: prId={}, sha={}, state={}", pr.getId(), sha, state);
            }
        }
    }

    /**
     * A completed suite's conclusion, or its status while it runs, as one {@link CheckState}. A
     * suite that ended {@code neutral}, {@code skipped} or {@code stale} said nothing about the head.
     */
    public static @Nullable CheckState fromCheckSuite(@Nullable String status, @Nullable String conclusion) {
        if (conclusion == null || conclusion.isBlank()) {
            return status == null ? null : CheckState.PENDING;
        }
        return switch (conclusion.toLowerCase(Locale.ROOT)) {
            case "success" -> CheckState.SUCCESS;
            case "failure", "timed_out", "action_required", "startup_failure" -> CheckState.FAILURE;
            case "cancelled" -> CheckState.CANCELLED;
            default -> null;
        };
    }

    /** A commit status state as one {@link CheckState}. */
    public static @Nullable CheckState fromStatus(@Nullable String state) {
        if (state == null) return null;
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "success" -> CheckState.SUCCESS;
            case "failure", "error" -> CheckState.FAILURE;
            case "pending" -> CheckState.PENDING;
            default -> null;
        };
    }
}
