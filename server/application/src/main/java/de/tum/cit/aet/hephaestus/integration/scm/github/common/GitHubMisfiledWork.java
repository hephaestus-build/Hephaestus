package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GitHubMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Repairs an issue or pull request row that an earlier version filed under the wrong repository. Webhooks once
 * selected the repository row by name, and after a rename another repository can take the old name. GitHub keeps an
 * issue's or pull request's id within its repository, so a row with the id under another repository is misfiled.
 * It moves to the repository that GitHub reports. Work is skipped and counted instead when that repository already
 * has a row for the number, because merging two rows is an operator's decision, or when a workspace already
 * registered an item for it: an item's parent never changes, so a moved row would leave an item under the wrong
 * repository, and the next occasion of the work would be refused.
 */
@Component
public class GitHubMisfiledWork {

    private static final Logger log = LoggerFactory.getLogger(GitHubMisfiledWork.class);

    private final IssueRepository issues;
    private final MeterRegistry meters;

    public GitHubMisfiledWork(IssueRepository issues, MeterRegistry meters) {
        this.issues = issues;
        this.meters = meters;
    }

    /** The outcome for one row; {@link #SKIPPED} means the caller must not process the work. */
    public enum Outcome {
        IN_PLACE,
        MOVED,
        SKIPPED
    }

    /**
     * @param issueType {@code ISSUE} or {@code PULL_REQUEST}, as stored
     * @param kind the workspace item kind of the work
     */
    public Outcome relocate(
            Repository repository, long providerId, long nativeId, int number, String issueType, ItemKind kind) {
        var holding = issues.findHolding(providerId, nativeId, issueType).orElse(null);
        if (holding == null || holding.getRepositoryId() == repository.getId()) return Outcome.IN_PLACE;
        if (issues.moveToRepository(providerId, nativeId, issueType, number, repository.getId(), kind.value()) == 1) {
            count(issueType, "moved", "none");
            log.info(
                    "Moved misfiled work to the repository GitHub reports: type={}, number={}, rowId={},"
                            + " fromRepositoryId={}, repositoryId={}",
                    issueType,
                    number,
                    holding.getId(),
                    holding.getRepositoryId(),
                    repository.getId());
            return Outcome.MOVED;
        }
        var now = issues.findHolding(providerId, nativeId, issueType).orElse(null);
        if (now == null || now.getRepositoryId() == repository.getId()) return Outcome.IN_PLACE;
        String reason =
                issues.registeredAsItem(kind.value(), now.getId()) ? "itemRegistered" : "idHeldByAnotherRepository";
        count(issueType, "skipped", reason);
        log.warn(
                "Skipped misfiled work: reason={}, type={}, number={}, rowId={}, holdingRepositoryId={},"
                        + " repositoryId={}",
                reason,
                issueType,
                number,
                now.getId(),
                now.getRepositoryId(),
                repository.getId());
        return Outcome.SKIPPED;
    }

    private void count(String issueType, String outcome, String reason) {
        meters.counter(
                        GitHubMetrics.GITHUB_MISFILED_WORK,
                        "type",
                        issueType.toLowerCase(Locale.ROOT),
                        "outcome",
                        outcome,
                        "reason",
                        reason)
                .increment();
    }
}
