package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The single check runs of one commit as one read of the provider showed them: GitHub's check runs and commit
 * statuses. {@link CheckState} is the provider's verdict over them.
 *
 * <p>The runs come from the reviewed work's own CI configuration and from any status poster, so they are bounded
 * here: at most {@link #MAX_RUNS} runs, after which the list is incomplete, names of at most {@link #MAX_TEXT}
 * characters, and links without credentials or a query.
 *
 * @param sha the commit the runs are about
 * @param runs each run as the provider named it, in the provider's order
 * @param complete whether the read listed every run the provider knew of with nothing still to come: the source
 *     contract's {@code head_check_runs} rule. It never proves that a run is absent.
 * @param observedAt when Hephaestus asked the provider, or received the hook
 * @param limitation why the list cannot be complete, or null: {@link #AWAITING_APPROVAL} when a check suite waits for
 *     a maintainer to approve its workflows, so its CI has not run at all
 */
public record HeadCheckRuns(
        String sha,
        List<Run> runs,
        boolean complete,
        Instant observedAt,
        @Nullable String limitation) {

    /** A suite waits for approval to run its workflows, as for a pull request from a fork. */
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";

    static final int MAX_RUNS = 200;
    static final int MAX_TEXT = 200;

    public HeadCheckRuns {
        if (runs.size() > MAX_RUNS) {
            runs = runs.subList(0, MAX_RUNS);
            complete = false;
        }
        runs = List.copyOf(runs);
        if (limitation != null) complete = false;
    }

    /**
     * One check run, commit status or pipeline job.
     *
     * @param name the run's name: a check run's or job's name, or a commit status's context
     * @param group what the run belongs to: the GitHub app
     * @param state the provider's word for the run in lower case: its conclusion once finished, its status before
     * @param finished whether the run has ended
     * @param url where the provider shows the run
     */
    public record Run(
            String name,
            @Nullable String group,
            String state,
            boolean finished,
            @Nullable String url,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt) {

        public Run {
            name = clip(name);
            group = group == null ? null : clip(group);
            state = clip(state);
            url = url == null ? null : withoutSecrets(url);
        }
    }

    /** Every run of the commit was read and has ended. A commit with no runs has none to finish. */
    public boolean finished() {
        return complete && !runs.isEmpty() && runs.stream().allMatch(Run::finished);
    }

    /** Whether {@code other} shows the same runs of the same commit, whenever it was read. */
    boolean sameRunsAs(@Nullable HeadCheckRuns other) {
        return other != null
                && sha.equals(other.sha)
                && complete == other.complete
                && runs.equals(other.runs)
                && Objects.equals(limitation, other.limitation);
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    /** The link without user information, query or fragment, which a status poster can fill with a token. */
    private static @Nullable String withoutSecrets(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return clip(
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
