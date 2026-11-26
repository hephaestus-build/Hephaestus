package de.tum.cit.aet.hephaestus.practices.review.model;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One person whose words a review item's capture copied: an exact provider instance and native subject. A member keeps
 * these here, not in its bounded {@code detail}, so a capture that copied many people still records each of them.
 * {@link de.tum.cit.aet.hephaestus.practices.review.ReviewItemCustody} writes and reads them.
 */
@Entity
@Table(
        name = "review_item_copied_identity",
        indexes = {
            @Index(
                    name = "idx_review_item_copied_identity_member",
                    columnList = "review_id, practice_id, item_id, workspace_id"),
            @Index(
                    name = "idx_review_item_copied_identity_person",
                    columnList = "provider_type, provider_origin, subject"),
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewItemCopiedIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Column(name = "provider_type", nullable = false, length = 16)
    private String providerType;

    @Column(name = "provider_origin", nullable = false, length = 1024)
    private String providerOrigin;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "team_id", length = 255)
    private @Nullable String teamId;

    public static ReviewItemCopiedIdentity of(ReviewItem member, PersonCopyIdentity identity) {
        var copy = new ReviewItemCopiedIdentity();
        copy.workspaceId = member.getWorkspaceId();
        copy.reviewId = member.getReviewId();
        copy.practiceId = member.getPracticeId();
        copy.itemId = member.getItemId();
        copy.providerType = identity.providerType();
        copy.providerOrigin = identity.providerOrigin();
        copy.subject = identity.subject();
        copy.teamId = identity.teamId();
        return copy;
    }

    public PersonCopyIdentity identity() {
        return new PersonCopyIdentity(providerType, providerOrigin, subject, teamId);
    }

    public ReviewItem.Id member() {
        return new ReviewItem.Id(reviewId, practiceId, itemId);
    }
}
