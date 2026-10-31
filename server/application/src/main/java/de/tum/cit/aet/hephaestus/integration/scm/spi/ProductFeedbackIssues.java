package de.tum.cit.aet.hephaestus.integration.scm.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The issue tracker product feedback files to, through the instance's own app. Every call names the repository by the
 * numeric id and installation that routing froze, so a renamed or reconfigured destination never receives a queued
 * report. A call that cannot tell whether GitHub applied a write throws {@link Unknown}; the caller then reconciles and
 * never writes again blindly.
 */
public interface ProductFeedbackIssues {

    /** A repository as routing freezes it. */
    record Repository(long id, String nodeId, String fullName, long installationId, boolean isPrivate) {}

    /** One issue as filing or following sees it. */
    record Issue(long number, String nodeId, String url) {}

    /** The state of a followed issue; {@code stateReason} is GitHub's {@code completed}, {@code not_planned} or {@code duplicate}. */
    record IssueState(
            String nodeId,
            boolean open,
            @Nullable String stateReason,
            List<Comment> comments,
            @Nullable String duplicateOfNodeId) {}

    /** A comment on a followed issue; {@code byApp} is true for the instance app's own comments. */
    record Comment(long id, String authorLogin, boolean byApp, String body, Instant createdAt) {}

    /** The tracker could not tell whether a write was applied: a timeout, a dropped connection or a 5xx after sending. */
    final class Unknown extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unknown(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** The tracker refused the write for good: a 4xx other than a rate limit. */
    final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /** The app's installation on {@code owner/name}, or empty when the app cannot reach it. */
    Optional<Repository> repository(String fullName);

    /** The current visibility of a frozen repository; empty when it is gone or the app lost access. */
    Optional<Boolean> isPrivate(Repository repository);

    /** The issue types the repository's owner defines, such as {@code Bug} and {@code Feature}; empty for a user-owned repository. */
    List<String> issueTypes(Repository repository);

    /** Up to {@code limit} open issues (pull requests excluded), most recently updated first, as number and title. */
    List<OpenIssue> openIssues(Repository repository, int limit);

    record OpenIssue(long number, String title) {}

    /** Creates an issue. {@code type} is omitted when null. */
    Issue create(Repository repository, String title, String body, @Nullable String type);

    /**
     * Finds the app's own issue whose body carries {@code marker}, among issues of any state updated since
     * {@code since}, reading every page. Empty only after a complete read; an incomplete read throws {@link Unknown}.
     */
    Optional<Issue> findByMarker(Repository repository, String marker, Instant since);
}
