package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "product_survey_email_invitation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_survey_email_invitation_account",
                        columnNames = {"survey_id", "account_id"}),
        indexes = {
            @Index(name = "idx_survey_email_invitation_account", columnList = "account_id"),
            @Index(
                    name = "idx_survey_email_reminders",
                    columnList = "reminder_enabled,reminder_requested_at,cancelled_at,expires_at,accepted_at")
        })
@Getter
@NoArgsConstructor
class SurveyEmailInvitation {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "survey_id", nullable = false)
    private UUID surveyId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Column(name = "requested_by_account_id")
    private @Nullable Long requestedByAccountId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private @Nullable Instant acceptedAt;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @ColumnDefault("false")
    @Column(name = "reminder_enabled", nullable = false)
    private boolean reminderEnabled;

    @Column(name = "reminder_requested_at")
    private @Nullable Instant reminderRequestedAt;

    @Column(name = "reminder_accepted_at")
    private @Nullable Instant reminderAcceptedAt;

    @ColumnDefault("0")
    @Column(name = "request_generation", nullable = false)
    private long requestGeneration;

    SurveyEmailInvitation(
            Survey survey,
            long accountId,
            long actorId,
            Instant requestedAt,
            Instant expiresAt,
            boolean reminderEnabled) {
        this.surveyId = survey.getId();
        this.workspaceId = survey.getWorkspaceId();
        this.accountId = accountId;
        this.requestedByAccountId = actorId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.reminderEnabled = reminderEnabled;
    }
}
