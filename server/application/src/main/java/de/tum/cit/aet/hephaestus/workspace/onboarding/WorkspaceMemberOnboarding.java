package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * One account's independent first-visit steps in one workspace. The revision records AI setup;
 * publicActivitySeen records the public activity notice. Neither field grants membership or access.
 */
@Entity
@Table(
        name = "workspace_member_onboarding",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_member_onboarding_workspace_account",
                        columnNames = {"workspace_id", "account_id"}))
@Getter
@Setter
class WorkspaceMemberOnboarding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_onboarding_workspace"))
    private Workspace workspace;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "seen_revision", nullable = false)
    private long seenRevision;

    @Column(name = "public_activity_seen", nullable = false)
    @ColumnDefault("false")
    private boolean publicActivitySeen;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
