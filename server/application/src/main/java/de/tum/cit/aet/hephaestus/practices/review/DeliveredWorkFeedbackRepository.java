package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredFeedbackPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredWorkFeedbackCopyDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredWorkFeedbackItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

@ConditionalOnServerRole
interface DeliveredWorkFeedbackRepository extends Repository<Feedback, UUID> {
    default List<DeliveredWorkFeedbackItemDTO> findDelivered(long ws, long developer, long item) {
        return delivered(ws, developer, item).stream()
                .map(row -> new DeliveredWorkFeedbackItemDTO(
                        row.getId(),
                        row.getDeliveredAt(),
                        practices(ws, row.getId()).stream()
                                .map(practice ->
                                        new DeliveredFeedbackPracticeDTO(practice.getSlug(), practice.getName()))
                                .toList(),
                        copies(ws, row.getId()).stream()
                                .map(copy -> {
                                    var side = copy.getSide();
                                    return new DeliveredWorkFeedbackCopyDTO(
                                            copy.getId(),
                                            FeedbackCopyKind.valueOf(copy.getKind()),
                                            copy.getExternalRef(),
                                            copy.getExternalUrl(),
                                            copy.getPath(),
                                            copy.getStartLine(),
                                            copy.getEndLine(),
                                            side == null ? null : CitationSide.valueOf(side),
                                            copy.getPostedAt());
                                })
                                .toList()))
                .toList();
    }

    @Query(value = """
        SELECT f.id,COALESCE(f.delivered_at,min(c.posted_at)) AS deliveredAt
        FROM feedback f JOIN feedback_copy c ON c.feedback_id=f.id AND c.workspace_id=f.workspace_id
        WHERE f.workspace_id=:ws AND f.person_account_id=:developer AND f.item_id=:item AND f.destination='ON_WORK'
          AND f.state<>'REPLACED' AND c.state='POSTED' AND c.posted_at IS NOT NULL
        GROUP BY f.id,f.delivered_at ORDER BY COALESCE(f.delivered_at,min(c.posted_at)) DESC,f.id::text DESC LIMIT 50
        """, nativeQuery = true)
    List<DeliveredRow> delivered(long ws, long developer, long item);

    @Query(
            value = "SELECT DISTINCT result_id FROM feedback_basis WHERE workspace_id=:ws AND feedback_id=:feedback",
            nativeQuery = true)
    List<UUID> basis(long ws, UUID feedback);

    @Query(value = """
        SELECT DISTINCT p.slug,v.name FROM feedback_basis b JOIN result r ON r.id=b.result_id AND r.workspace_id=b.workspace_id
          JOIN practice p ON p.id=r.practice_id AND p.workspace_id=r.workspace_id JOIN practice_version v ON v.id=r.practice_version_id AND v.workspace_id=r.workspace_id
        WHERE b.workspace_id=:ws AND b.feedback_id=:feedback ORDER BY p.slug
        """, nativeQuery = true)
    List<PracticeRow> practices(long ws, UUID feedback);

    @Query(value = """
        SELECT id,kind,external_ref AS externalRef,external_url AS externalUrl,path,start_line AS startLine,
          end_line AS endLine,side,posted_at AS postedAt FROM feedback_copy
        WHERE workspace_id=:ws AND feedback_id=:feedback AND state='POSTED' AND posted_at IS NOT NULL ORDER BY position,id::text
        """, nativeQuery = true)
    List<CopyRow> copies(long ws, UUID feedback);

    interface DeliveredRow {
        UUID getId();

        Instant getDeliveredAt();
    }

    interface PracticeRow {
        String getSlug();

        String getName();
    }

    interface CopyRow {
        UUID getId();

        String getKind();

        @Nullable
        String getExternalRef();

        @Nullable
        String getExternalUrl();

        @Nullable
        String getPath();

        @Nullable
        Integer getStartLine();

        @Nullable
        Integer getEndLine();

        @Nullable
        String getSide();

        Instant getPostedAt();
    }
}
