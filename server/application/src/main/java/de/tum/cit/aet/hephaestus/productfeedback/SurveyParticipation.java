package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One account's relationship with one survey. The invitation and the eventual response or decline
 * share a row so a completion rate can be read from the table.
 */
@Entity
@Table(
        name = "product_survey_participation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_survey_participation_account",
                        columnNames = {"survey_id", "account_id"}),
        // Account erasure filters on account_id alone, which the unique constraint above cannot serve:
        // its leading column is survey_id.
        indexes = @Index(name = "idx_survey_participation_account", columnList = "account_id"))
@Getter
@NoArgsConstructor
public class SurveyParticipation {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "survey_id", nullable = false)
    private UUID surveyId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_json", columnDefinition = "jsonb")
    private @Nullable JsonNode answers;

    @CreationTimestamp
    @Column(name = "invited_at", nullable = false, updatable = false)
    private @Nullable Instant invitedAt;

    @Column(name = "decided_at")
    private @Nullable Instant decidedAt;

    public SurveyParticipation(UUID surveyId, Long accountId, @Nullable Long workspaceId) {
        this.surveyId = surveyId;
        this.accountId = accountId;
        this.workspaceId = workspaceId;
        this.status = Status.INVITED;
    }

    public void respond(JsonNode answers, @Nullable Long workspaceId, Instant now) {
        this.status = Status.RESPONDED;
        this.answers = answers;
        this.workspaceId = workspaceId;
        this.decidedAt = now;
    }

    public void decline(@Nullable Long workspaceId, Instant now) {
        this.status = Status.DECLINED;
        this.workspaceId = workspaceId;
        this.decidedAt = now;
    }

    public void reinvite() {
        this.status = Status.INVITED;
        this.decidedAt = null;
    }

    public enum Status {
        INVITED,
        RESPONDED,
        DECLINED
    }
}
