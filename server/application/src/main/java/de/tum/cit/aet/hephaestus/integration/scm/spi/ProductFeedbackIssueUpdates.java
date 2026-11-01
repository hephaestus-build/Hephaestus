package de.tum.cit.aet.hephaestus.integration.scm.spi;

import java.util.List;

/**
 * What product feedback changes on issues it already filed, and what it reads to follow them. The same rules as
 * {@link ProductFeedbackIssues} apply: a call that cannot tell whether GitHub applied a write throws
 * {@link ProductFeedbackIssues.Unknown}.
 */
public interface ProductFeedbackIssueUpdates {

    /** Moves an issue to another repository of the same owner, and returns it as it is there. */
    ProductFeedbackIssues.Issue transfer(ProductFeedbackIssues.Issue issue, ProductFeedbackIssues.Repository target);

    /** Replaces an issue's title and body. */
    void edit(ProductFeedbackIssues.Repository repository, long number, String title, String body);

    /** Closes an issue; {@code reason} is {@code completed} or {@code not_planned}. */
    void close(ProductFeedbackIssues.Repository repository, long number, String reason);

    /** Adds a comment as the app. */
    void comment(ProductFeedbackIssues.Repository repository, long number, String body);

    /** The states of followed issues, read in batches; an issue that no longer exists is missing from the result. */
    List<ProductFeedbackIssues.IssueState> states(List<String> nodeIds);

    /** A person's current permission on a repository: {@code admin}, {@code write}, {@code read} or {@code none}. */
    String permission(ProductFeedbackIssues.Repository repository, String login);
}
