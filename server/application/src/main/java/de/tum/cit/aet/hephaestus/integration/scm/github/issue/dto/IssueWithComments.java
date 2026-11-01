package de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Container for an Issue DTO and its embedded comments.
 * <p>
 * Used during sync to process comments inline with issues, eliminating N+1 queries.
 * <p>
 * <h2>Embedded Data</h2>
 * <ul>
 *   <li>{@code embeddedComments} - First 10 comments (pagination for issues with more)</li>
 * </ul>
 */
public record IssueWithComments(GitHubIssueDTO issue, EmbeddedCommentsDTO embeddedComments) {
    /**
     * Creates an IssueWithComments from a GraphQL GHIssue model.
     *
     * @param ghIssue the GraphQL GHIssue (may be null)
     * @return IssueWithComments or null if ghIssue is null
     */
    @Nullable
    public static IssueWithComments fromIssue(@Nullable GHIssue ghIssue) {
        if (ghIssue == null) {
            return null;
        }

        String context = "Issue #" + ghIssue.getNumber();

        GitHubIssueDTO dto = Objects.requireNonNull(GitHubIssueDTO.fromIssue(ghIssue));
        EmbeddedCommentsDTO comments = EmbeddedCommentsDTO.fromConnection(ghIssue.getComments(), context);

        return new IssueWithComments(dto, comments);
    }
}
