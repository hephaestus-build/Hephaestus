package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A survey published by an instance administrator. The questions and the purpose are frozen at
 * publication so every stored answer keeps its meaning and its legal footing; the title,
 * introduction, schedule and pause flag stay editable because they change who is invited, never
 * what an answer meant.
 */
@Entity
@Table(
        name = "product_survey",
        indexes = @Index(name = "idx_product_survey_summary", columnList = "summary_queued_at,ends_at"))
@Getter
@NoArgsConstructor
public class Survey {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode questions;

    /**
     * Set on a research survey: the organisation it was published for, which makes it one. Consent
     * names the controller, so the survey is served only while the instance still names the same
     * one; a renamed programme is a different study.
     */
    @Column(name = "research_organization", length = 200)
    private @Nullable String researchOrganization;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private @Nullable Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by_account_id")
    private @Nullable Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "summary_queued_at")
    private @Nullable Instant summaryQueuedAt;

    public Survey(
            String title,
            String description,
            @Nullable String researchOrganization,
            JsonNode questions,
            @Nullable Long workspaceId,
            Instant startsAt,
            @Nullable Instant endsAt,
            Long createdByAccountId) {
        this.title = title;
        this.description = description;
        this.researchOrganization = researchOrganization;
        this.questions = questions;
        this.workspaceId = workspaceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = true;
        this.createdByAccountId = createdByAccountId;
    }

    public void edit(String title, String description, Instant startsAt, @Nullable Instant endsAt, boolean active) {
        if (!Objects.equals(this.endsAt, endsAt)) {
            this.summaryQueuedAt = null;
        }
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = active;
    }

    public boolean isOpenFor(Long workspaceId, Instant now) {
        return active
                && !startsAt.isAfter(now)
                && (endsAt == null || endsAt.isAfter(now))
                && (this.workspaceId == null || this.workspaceId.equals(workspaceId));
    }

    public Purpose getPurpose() {
        return researchOrganization == null ? Purpose.PRODUCT : Purpose.RESEARCH;
    }

    public enum Purpose {
        /** Improving this instance; read by its administrators. */
        PRODUCT,
        /** Part of the study by the survey's research organisation; offered only to participants. */
        RESEARCH
    }
}
