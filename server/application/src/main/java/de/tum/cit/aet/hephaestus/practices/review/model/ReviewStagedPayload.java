package de.tum.cit.aet.hephaestus.practices.review.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Cold columns of the existing membership row. Ordinary membership reads never load cited source bytes. */
@Entity
@Table(name = "review_item")
@IdClass(ReviewItem.Id.class)
@Getter
public class ReviewStagedPayload {
    @Id
    @Column(name = "review_id")
    private UUID reviewId;

    @Id
    @Column(name = "practice_id")
    private long practiceId;

    @Id
    @Column(name = "item_id")
    private long itemId;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "staged_payload", columnDefinition = "jsonb")
    private @Nullable JsonNode payload;
}
