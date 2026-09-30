package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The few columns it takes to name a merge request or an issue to a person. Its own repository rather than
 * two more methods on {@code IssueRepository} / {@code PullRequestRepository}: those are the sync engine's
 * write path, and a read surface that only ever wants a title has no business inheriting
 * {@code JpaRepository}'s unscoped {@code findAll} into a package that hands rows to an HTTP response.
 */
@WorkspaceAgnostic(
        "Labels ids the signal ledger already scoped to the workspace; the mirror carries no workspace column "
                + "of its own — ownership runs through repository_to_monitor — so the ledger is the tenancy boundary")
public interface ScmArtifactIdentityRepository extends Repository<Issue, Long> {
    /**
     * Merge requests only: JPQL on the subclass restricts by discriminator, so a same-numbered issue in
     * the same project cannot answer here.
     */
    @Query("""
        SELECT p.id AS id, p.number AS number, p.title AS title, p.htmlUrl AS url,
               p.repository.nameWithOwner AS container, p.deletedAt AS deletedAt, p.provider.type AS providerType
        FROM PullRequest p
        WHERE p.id IN :ids
        """)
    List<ScmArtifactLabel> findPullRequestLabels(@Param("ids") Collection<Long> ids);

    /**
     * Issues only. {@code TYPE(i) = Issue} is load-bearing: {@code Issue} and {@code PullRequest} share
     * one table, so an unqualified query over {@code Issue} would also return merge requests and label
     * {@code scm.issue #7} with a merge request's title.
     */
    @Query("""
        SELECT i.id AS id, i.number AS number, i.title AS title, i.htmlUrl AS url,
               i.repository.nameWithOwner AS container, i.deletedAt AS deletedAt, i.provider.type AS providerType
        FROM Issue i
        WHERE TYPE(i) = Issue AND i.id IN :ids
        """)
    List<ScmArtifactLabel> findIssueLabels(@Param("ids") Collection<Long> ids);

    interface ScmArtifactLabel {
        Long getId();

        Integer getNumber();

        String getTitle();

        @Nullable
        String getUrl();

        @Nullable
        String getContainer();

        /** Non-null for a row the mirror tombstoned; such an artifact is named but no longer linked. */
        @Nullable
        Instant getDeletedAt();

        /** GitHub or GitLab, which decides the work's noun and how its number is written. */
        IdentityProviderType getProviderType();
    }
}
