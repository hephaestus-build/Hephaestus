package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItemCopiedIdentity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemCopiedIdentityRepository extends JpaRepository<ReviewItemCopiedIdentity, Long> {

    /** Runs at once, so the member's new identities insert after it at the next flush. */
    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM ReviewItemCopiedIdentity c
            WHERE c.workspaceId = :workspaceId AND c.reviewId = :reviewId AND c.practiceId = :practiceId
              AND c.itemId = :itemId
            """)
    int deleteMember(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("practiceId") long practiceId,
            @Param("itemId") long itemId);

    /** One row for each copied identity of a member, or one with no identity for a member that copied nobody. */
    interface CustodyRow {
        UUID getReviewId();

        long getPracticeId();

        long getItemId();

        String getCustody();

        @Nullable
        String getProviderType();

        @Nullable
        String getProviderOrigin();

        @Nullable
        String getSubject();

        @Nullable
        String getTeamId();
    }

    /**
     * The stored custody of exactly the given members and their copied identities, in one statement, so both come from
     * one snapshot. {@code members} is a JSON array of {@code {review_id, practice_id, item_id}}: complete keys, so
     * two scans that covered the same files return only the pairs asked for.
     */
    @Query(value = """
                    SELECT m.review_id AS "reviewId", m.practice_id AS "practiceId", m.item_id AS "itemId",
                           CAST(m.detail->'custody' AS text) AS "custody", c.provider_type AS "providerType",
                           c.provider_origin AS "providerOrigin", c.subject AS "subject", c.team_id AS "teamId"
                    FROM jsonb_to_recordset(CAST(:members AS jsonb)) AS wanted(review_id uuid, practice_id bigint,
                                                                                 item_id bigint)
                    JOIN review_item m ON m.workspace_id = :workspaceId AND m.review_id = wanted.review_id
                      AND m.practice_id = wanted.practice_id AND m.item_id = wanted.item_id
                    LEFT JOIN review_item_copied_identity c ON c.review_id = m.review_id
                      AND c.practice_id = m.practice_id AND c.item_id = m.item_id AND c.workspace_id = m.workspace_id
                    WHERE jsonb_typeof(m.detail->'custody') = 'object'
                    ORDER BY m.review_id, m.practice_id, m.item_id, c.provider_type, c.provider_origin, c.subject,
                      c.team_id NULLS FIRST
                    """, nativeQuery = true)
    List<CustodyRow> findCustody(@Param("workspaceId") long workspaceId, @Param("members") String members);
}
