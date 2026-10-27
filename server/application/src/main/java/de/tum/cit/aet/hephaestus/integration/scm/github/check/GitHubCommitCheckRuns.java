package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHApp;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckConclusionState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckRun;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckStatusState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckSuite;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckSuiteConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusCheckRollup;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusCheckRollupContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusState;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * Reads the check runs and commit statuses of one commit from GitHub. Best effort: a failed read is logged and
 * records nothing, so the stored runs stay as the last read left them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubCommitCheckRuns {

    private static final String QUERY_DOCUMENT = "GetCommitCheckRuns";

    private final GitHubGraphQlClientProvider clients;
    private final GitHubSyncProperties syncProperties;
    private final GitHubProperties gitHubProperties;

    public Optional<HeadCheckRuns> read(long scopeId, String nameWithOwner, String sha) {
        var name = GitHubRepositoryNameParser.parse(nameWithOwner).orElse(null);
        if (name == null) return Optional.empty();
        try {
            Instant observedAt = Instant.now();
            ClientGraphQlResponse response = clients.forScope(scopeId)
                    .documentName(QUERY_DOCUMENT)
                    .variable("owner", name.owner())
                    .variable("name", name.name())
                    .variable("oid", sha)
                    .execute()
                    .block(syncProperties.graphqlTimeout());
            if (response == null || !response.isValid() || !response.getErrors().isEmpty()) {
                log.warn("Check run read got no whole answer: repoName={}, sha={}", sanitizeForLog(nameWithOwner), sha);
                return Optional.empty();
            }
            clients.trackRateLimit(scopeId, response);
            GHStatusCheckRollup rollup =
                    response.field("repository.object.statusCheckRollup").toEntity(GHStatusCheckRollup.class);
            GHCheckSuiteConnection suites =
                    response.field("repository.object.checkSuites").toEntity(GHCheckSuiteConnection.class);
            var runs = toRuns(sha, rollup, suites, gitHubProperties.app().id(), observedAt);
            if (runs.isEmpty())
                log.info(
                        "Check runs not stored: repoName={}, sha={}, reason=noRollup",
                        sanitizeForLog(nameWithOwner),
                        sha);
            return runs;
        } catch (RuntimeException e) {
            log.warn(
                    "Check run read failed: repoName={}, sha={}, error={}",
                    sanitizeForLog(nameWithOwner),
                    sha,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * The runs the rollup lists. A read only follows a suite or status that ended, so a missing rollup or list says
     * the read lagged, not that the commit has no runs, and gives nothing.
     *
     * <p>The list is complete only when nothing GitHub knows of is still to come: it fits one page, the rollup waits
     * for no expected status, every listed run has ended, and every check suite with runs has completed. A failed
     * rollup is no such sign: GitHub reports a failure while other runs still go, and a suite still under way can add
     * a run that waits for another. A suite that waits for approval of its workflows makes the list incomplete,
     * whatever the rollup says. A CI that has posted nothing yet cannot be seen at all. A pending rollup shows in
     * the listed runs and suites, so they decide, without the runs and suite of Hephaestus's own app
     * ({@code ownAppId}): its run follows the review and is no evidence about the work.
     */
    static Optional<HeadCheckRuns> toRuns(
            String sha,
            @Nullable GHStatusCheckRollup rollup,
            @Nullable GHCheckSuiteConnection suites,
            long ownAppId,
            Instant observedAt) {
        var contexts = rollup == null ? null : rollup.getContexts();
        if (rollup == null || contexts == null) return Optional.empty();
        List<HeadCheckRuns.Run> runs = new ArrayList<>();
        for (GHStatusCheckRollupContext context :
                Objects.requireNonNullElse(contexts.getNodes(), List.<GHStatusCheckRollupContext>of())) {
            if (context instanceof GHCheckRun run) {
                if (!ownApp(
                        run.getCheckSuite() == null ? null : run.getCheckSuite().getApp(), ownAppId))
                    runs.add(checkRun(run));
            } else if (context instanceof GHStatusContext status) runs.add(commitStatus(status));
        }
        boolean complete = whole(contexts.getPageInfo())
                && rollup.getState() != GHStatusState.EXPECTED
                && runs.stream().allMatch(HeadCheckRuns.Run::finished)
                && suitesSettled(suites, ownAppId);
        return Optional.of(new HeadCheckRuns(
                sha,
                runs,
                complete,
                observedAt,
                awaitingApproval(suites, ownAppId) ? HeadCheckRuns.AWAITING_APPROVAL : null));
    }

    /**
     * Whether every check suite of the commit that has runs has completed. A suite without runs is left aside: an app
     * that never posts a run leaves its suite queued for ever.
     */
    private static boolean suitesSettled(@Nullable GHCheckSuiteConnection suites, long ownAppId) {
        if (suites == null || !whole(suites.getPageInfo())) return false;
        return Objects.requireNonNullElse(suites.getNodes(), List.<GHCheckSuite>of()).stream()
                .filter(suite -> suite != null && !ownApp(suite.getApp(), ownAppId))
                .allMatch(suite -> suite.getStatus() == GHCheckStatusState.COMPLETED
                        || suite.getCheckRuns() == null
                        || suite.getCheckRuns().getTotalCount() == 0);
    }

    /**
     * Whether a check suite waits for a maintainer to approve its workflows. GitHub then completes the suite as
     * {@code ACTION_REQUIRED} with no runs, and the rollup can still read success: the CI has not run at all.
     */
    private static boolean awaitingApproval(@Nullable GHCheckSuiteConnection suites, long ownAppId) {
        return suites != null
                && Objects.requireNonNullElse(suites.getNodes(), List.<GHCheckSuite>of()).stream()
                        .anyMatch(suite -> suite != null
                                && !ownApp(suite.getApp(), ownAppId)
                                && suite.getConclusion() == GHCheckConclusionState.ACTION_REQUIRED
                                && (suite.getCheckRuns() == null
                                        || suite.getCheckRuns().getTotalCount() == 0));
    }

    /** Whether {@code app} is Hephaestus's own GitHub App; without a configured app id, none is. */
    private static boolean ownApp(@Nullable GHApp app, long ownAppId) {
        return ownAppId > 0 && app != null && app.getDatabaseId() != null && app.getDatabaseId() == ownAppId;
    }

    private static boolean whole(@Nullable GHPageInfo page) {
        return page == null || !page.getHasNextPage();
    }

    private static HeadCheckRuns.Run checkRun(GHCheckRun run) {
        boolean finished = run.getStatus() == GHCheckStatusState.COMPLETED;
        Enum<?> word = finished && run.getConclusion() != null ? run.getConclusion() : run.getStatus();
        var suite = run.getCheckSuite();
        String group = suite != null && suite.getApp() != null ? suite.getApp().getName() : null;
        return new HeadCheckRuns.Run(
                run.getName(),
                group,
                lower(word),
                finished,
                text(run.getDetailsUrl()),
                instant(run.getStartedAt()),
                instant(run.getCompletedAt()));
    }

    /** A commit status keeps only its latest state, so its one time is when it reported that state. */
    private static HeadCheckRuns.Run commitStatus(GHStatusContext status) {
        GHStatusState state = status.getState();
        boolean finished = state != GHStatusState.PENDING && state != GHStatusState.EXPECTED;
        Instant reportedAt = instant(status.getCreatedAt());
        return new HeadCheckRuns.Run(
                status.getContext(),
                null,
                lower(state),
                finished,
                text(status.getTargetUrl()),
                finished ? null : reportedAt,
                finished ? reportedAt : null);
    }

    private static String lower(@Nullable Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String text(@Nullable URI uri) {
        return uri == null ? null : uri.toString();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
