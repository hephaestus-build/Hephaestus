package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Who contributed to one stored piece of work. Each read is keyed by the id of that work or its repository, which the
 * caller has already resolved within the workspace (ADR 0077).
 */
public interface ScmContributorQueryRepository extends Repository<Issue, Long> {
    @Query(value = """
        SELECT c.author_id FROM issue_comment c WHERE c.issue_id=:id AND c.author_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> issueCommentAuthors(@Param("id") long id);

    @Query(value = """
        SELECT c.author_id FROM pull_request_review_comment c WHERE c.pull_request_id=:id AND c.author_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> reviewCommentAuthors(@Param("id") long id);

    @Query(value = """
        SELECT r.author_id FROM pull_request_review r WHERE r.pull_request_id=:id AND r.author_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> reviewAuthors(@Param("id") long id);

    @Query(value = """
        SELECT t.resolved_by_id FROM pull_request_review_thread t WHERE t.pull_request_id=:id AND t.resolved_by_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> threadResolvers(@Param("id") long id);

    @Query(value = """
        SELECT DISTINCT roles.user_id FROM commit_pull_request cp JOIN (SELECT id AS commit_id,author_id AS user_id FROM git_commit UNION ALL SELECT id,committer_id FROM git_commit UNION ALL SELECT commit_id,user_id FROM commit_contributor) roles ON roles.commit_id=cp.commit_id WHERE cp.pull_request_id=:id AND roles.user_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> diffContributors(@Param("id") long id);

    @Query(value = """
        SELECT DISTINCT roles.user_id FROM git_commit c JOIN (SELECT id AS commit_id,author_id AS user_id FROM git_commit UNION ALL SELECT id,committer_id FROM git_commit UNION ALL SELECT commit_id,user_id FROM commit_contributor) roles ON roles.commit_id=c.id WHERE c.repository_id=:id AND roles.user_id IS NOT NULL
        """, nativeQuery = true)
    List<Long> treeContributors(@Param("id") long id);
}
