package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ScmItemQueryRepository extends Repository<Item, Long> {
    @Query(value = """
        SELECT i.id, i.source_id AS sourceId, i.parent_id AS parentId
        FROM item i
        JOIN issue work ON work.id=i.source_id AND work.issue_type=CASE :kind WHEN 'scm.pull_request' THEN 'PULL_REQUEST' ELSE 'ISSUE' END
          AND work.deleted_at IS NULL
        JOIN repository r ON r.id=work.repository_id AND r.provider_id=work.provider_id
        JOIN identity_provider provider ON provider.id=r.provider_id
        JOIN item parent ON parent.id=i.parent_id AND parent.workspace_id=i.workspace_id
          AND parent.kind='scm.repository' AND parent.source_id=r.id AND parent.removed_at IS NULL
        WHERE i.workspace_id=:workspaceId AND i.kind=:kind AND i.removed_at IS NULL
          AND provider.type=:providerType AND provider.server_url=:serverUrl
          AND r.name_with_owner=:repositoryName AND work.number=:number
          AND EXISTS (SELECT 1 FROM repository_to_monitor monitor
            WHERE monitor.workspace_id=i.workspace_id AND monitor.name_with_owner=r.name_with_owner
              AND monitor.native_id=r.native_id)
        ORDER BY i.id
        LIMIT 2
        """, nativeQuery = true)
    List<AddressRow> atAddress(
            long workspaceId, String providerType, String serverUrl, String repositoryName, String kind, int number);

    interface AddressRow {
        long getId();

        long getSourceId();

        long getParentId();
    }

    @Query(value = """
        WITH selected AS (SELECT * FROM item WHERE workspace_id=:workspaceId AND id IN (:ids))
        SELECT idx.id,
          CASE idx.kind WHEN :#{#kinds.repository()} THEN repository.name_with_owner
            WHEN :#{#kinds.file()} THEN idx.path WHEN :#{#kinds.review()} THEN 'Review of '||work.title
            ELSE work.title END AS title,
          CASE idx.kind WHEN :#{#kinds.repository()} THEN 'repository' WHEN :#{#kinds.file()} THEN 'file'
            WHEN :#{#kinds.review()} THEN 'review' WHEN :#{#kinds.issue()} THEN 'issue'
            ELSE CASE provider.type WHEN 'GITLAB' THEN 'merge request' ELSE 'pull request' END END AS noun,
          work.number, repository.name_with_owner AS container, provider.type AS provider_type,
          CASE WHEN idx.removed_at IS NOT NULL OR work.deleted_at IS NOT NULL THEN NULL
            WHEN idx.kind=:#{#kinds.repository()} THEN repository.html_url
            WHEN idx.kind=:#{#kinds.file()} THEN NULL
            WHEN idx.kind=:#{#kinds.review()} THEN review.html_url
            ELSE work.html_url END AS url
        FROM selected idx
        LEFT JOIN item holder ON holder.workspace_id=idx.workspace_id AND holder.id=idx.parent_id
        LEFT JOIN pull_request_review review ON idx.kind=:#{#kinds.review()} AND review.id=idx.source_id
        LEFT JOIN issue work ON work.id=CASE WHEN idx.kind=:#{#kinds.review()} THEN review.pull_request_id
          WHEN idx.kind IN (:#{#kinds.issue()},:#{#kinds.pullRequest()}) THEN idx.source_id END
        JOIN repository ON repository.id=CASE idx.kind WHEN :#{#kinds.repository()} THEN idx.source_id
          WHEN :#{#kinds.file()} THEN holder.source_id ELSE work.repository_id END
        JOIN identity_provider provider ON provider.id=repository.provider_id
        WHERE provider.type IN ('GITHUB','GITLAB') AND (
          (idx.kind=:#{#kinds.issue()} AND work.issue_type='ISSUE')
          OR (idx.kind=:#{#kinds.pullRequest()} AND work.issue_type='PULL_REQUEST')
          OR (idx.kind=:#{#kinds.review()} AND holder.kind=:#{#kinds.pullRequest()} AND holder.source_id=review.pull_request_id)
          OR idx.kind=:#{#kinds.repository()} OR (idx.kind=:#{#kinds.file()} AND holder.kind=:#{#kinds.repository()}))
        """, nativeQuery = true)
    List<LabelRow> labels(
            @Param("workspaceId") long workspaceId, @Param("ids") List<Long> ids, @Param("kinds") KindNames kinds);

    record KindNames(String repository, String file, String review, String pullRequest, String issue) {}

    @Query(value = """
                SELECT i.id, work.id AS pull_id, work.issue_type, work.is_merged, work.merged_at, work.repository_id,
                    repository_item.id AS repository_item_id
                FROM item i
                LEFT JOIN item parent ON parent.id=i.parent_id AND parent.workspace_id=i.workspace_id
                LEFT JOIN pull_request_review review ON i.kind=:reviewKind
                    AND review.id=i.source_id AND parent.kind=:pullKind
                    AND parent.source_id=review.pull_request_id
                LEFT JOIN issue work ON work.id=CASE WHEN i.kind=:pullKind THEN i.source_id
                    WHEN i.kind=:reviewKind THEN review.pull_request_id END
                    AND (review.id IS NULL OR review.provider_id=work.provider_id)
                LEFT JOIN item repository_item ON repository_item.workspace_id=i.workspace_id
                    AND repository_item.kind=:repositoryKind AND repository_item.source_id=work.repository_id
                    AND repository_item.id=CASE WHEN i.kind=:pullKind THEN i.parent_id ELSE parent.parent_id END
                WHERE i.workspace_id=:workspaceId AND i.id IN (:ids)
        """, nativeQuery = true)
    List<MergeRow> mergeFacts(
            @Param("workspaceId") long workspaceId,
            @Param("ids") List<Long> ids,
            @Param("repositoryKind") String repositoryKind,
            @Param("reviewKind") String reviewKind,
            @Param("pullKind") String pullKind);

    @Query(value = """
                SELECT i.id AS item_id, m.id AS monitor_id, r.name_with_owner, p.server_url
                FROM item i JOIN repository r ON r.id=i.source_id
                JOIN identity_provider p ON p.id=r.provider_id
                JOIN repository_to_monitor m ON m.workspace_id=i.workspace_id
                  AND m.name_with_owner=r.name_with_owner
                  AND m.native_id=r.native_id
                WHERE i.workspace_id=:workspaceId AND i.kind=:repositoryKind AND i.parent_id IS NULL
                  AND i.removed_at IS NULL AND p.type=:providerType ORDER BY i.id
        """, nativeQuery = true)
    List<MonitorRow> monitored(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryKind") String repositoryKind,
            @Param("providerType") String providerType);

    /** Active repository items whose source no monitor of the workspace covers by native id. */
    @Query(value = """
                SELECT i.id AS item_id, r.id AS repository_id, r.native_id, r.name_with_owner, p.server_url
                FROM item i JOIN repository r ON r.id=i.source_id
                JOIN identity_provider p ON p.id=r.provider_id
                WHERE i.workspace_id=:workspaceId AND i.kind=:repositoryKind AND i.parent_id IS NULL
                  AND i.removed_at IS NULL AND p.type=:providerType
                  AND NOT EXISTS (SELECT 1 FROM repository_to_monitor m WHERE m.workspace_id=i.workspace_id
                    AND m.native_id=r.native_id)
                ORDER BY r.name_with_owner, i.id
        """, nativeQuery = true)
    List<UnmonitoredRow> unmonitored(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryKind") String repositoryKind,
            @Param("providerType") String providerType);

    @WorkspaceAgnostic("Reads native SCM mirror facts after the container repository passes workspace authorization")
    @Query(value = """
        SELECT id AS source_id, NULL::bigint AS parent_source_id, repository_id
        FROM issue WHERE repository_id=:repositoryId AND deleted_at IS NULL
          AND issue_type=:discriminator ORDER BY created_at,id
        """, nativeQuery = true)
    List<WorkRow> listWork(@Param("repositoryId") long repositoryId, @Param("discriminator") String discriminator);

    @Query(value = """
        SELECT work.id AS source_id, NULL::bigint AS parent_source_id, work.repository_id
        FROM issue work WHERE work.repository_id=:repositoryId AND work.deleted_at IS NULL
          AND work.issue_type=:discriminator
          AND NOT EXISTS (SELECT 1 FROM item i WHERE i.workspace_id=:workspaceId AND i.kind=:kind
            AND i.source_id=work.id AND i.removed_at IS NULL)
        ORDER BY work.created_at,work.id
        """, nativeQuery = true)
    List<WorkRow> unindexedWork(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("kind") String kind,
            @Param("discriminator") String discriminator);

    @WorkspaceAgnostic("Reads native SCM mirror facts after the container repository passes workspace authorization")
    @Query(value = """
        SELECT review.id AS source_id, review.pull_request_id AS parent_source_id, work.repository_id
        FROM pull_request_review review JOIN issue work ON work.id=review.pull_request_id
        WHERE work.repository_id=:repositoryId AND work.deleted_at IS NULL
          AND review.submitted_at IS NOT NULL ORDER BY review.submitted_at,review.id
        """, nativeQuery = true)
    List<WorkRow> listReviews(@Param("repositoryId") long repositoryId);

    @WorkspaceAgnostic("Reads native SCM mirror facts after the container repository passes workspace authorization")
    @Query(value = """
        SELECT work.id AS source_id, NULL::bigint AS parent_source_id, work.repository_id
        FROM issue work WHERE NOT :reviews AND work.id IN (:sourceIds)
        UNION ALL
        SELECT review.id AS source_id, review.pull_request_id AS parent_source_id, work.repository_id
        FROM pull_request_review review JOIN issue work ON work.id=review.pull_request_id
        WHERE :reviews AND review.id IN (:sourceIds)
        """, nativeQuery = true)
    List<WorkRow> owners(@Param("sourceIds") List<Long> sourceIds, @Param("reviews") boolean reviews);

    /**
     * The stored facts of one issue or pull request that its version and its source relation read, without loading
     * the work: a stored pull request can leave fields outside them, such as its line counts, empty.
     */
    @WorkspaceAgnostic("Reads the native SCM mirror row of an indexed item; its repository is authorized before use")
    @Query(value = """
        SELECT work.repository_id AS repository_id, work.deleted_at IS NOT NULL AS deleted,
          work.base_ref_oid AS base_ref_oid, work.head_ref_oid AS head_ref_oid, work.title, work.body
        FROM issue work WHERE work.id=:sourceId
          AND work.issue_type=CASE :kind WHEN 'scm.pull_request' THEN 'PULL_REQUEST' WHEN 'scm.issue' THEN 'ISSUE' END
        """, nativeQuery = true)
    Optional<WorkFacts> work(@Param("kind") String kind, @Param("sourceId") long sourceId);

    /** Every issue the stored pull request closes, as its entity's closing issues hold them. */
    @WorkspaceAgnostic("Reads the native SCM mirror rows linked to an indexed pull request after its authorization")
    @Query("SELECT DISTINCT i FROM PullRequest p JOIN p.closingIssues i WHERE p.id = :pullRequestId")
    List<Issue> closingIssues(@Param("pullRequestId") long pullRequestId);

    @Query(value = """
        SELECT parent.id FROM item parent JOIN pull_request_review review ON review.pull_request_id=parent.source_id
        WHERE parent.workspace_id=:workspaceId AND parent.kind=:pullKind AND review.id=:reviewId
        """, nativeQuery = true)
    List<Long> reviewParents(
            @Param("workspaceId") long workspaceId,
            @Param("pullKind") String pullKind,
            @Param("reviewId") long reviewId);

    interface LabelRow {
        long getId();

        String getTitle();

        String getNoun();

        @Nullable
        Integer getNumber();

        String getContainer();

        @Nullable
        String getUrl();

        String getProviderType();
    }

    interface MergeRow {
        long getId();

        @Nullable
        Long getPullId();

        @Nullable
        String getIssueType();

        @Nullable
        Long getRepositoryItemId();

        boolean getIsMerged();

        @Nullable
        Instant getMergedAt();

        @Nullable
        Long getRepositoryId();
    }

    interface MonitorRow {
        long getItemId();

        long getMonitorId();

        String getNameWithOwner();

        String getServerUrl();
    }

    interface UnmonitoredRow {
        long getItemId();

        long getRepositoryId();

        long getNativeId();

        String getNameWithOwner();

        String getServerUrl();
    }

    interface WorkFacts {
        @Nullable
        Long getRepositoryId();

        boolean getDeleted();

        @Nullable
        String getBaseRefOid();

        @Nullable
        String getHeadRefOid();

        @Nullable
        String getTitle();

        @Nullable
        String getBody();
    }

    interface WorkRow {
        long getSourceId();

        @Nullable
        Long getParentSourceId();

        long getRepositoryId();
    }
}
