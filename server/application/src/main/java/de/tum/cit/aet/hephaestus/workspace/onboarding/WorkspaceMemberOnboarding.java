package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One account's first-visit setup in one workspace: the settings revision it finished or skipped
 * at. The AI choice itself is the account's ({@link AccountAiChoice}); this row only keeps the setup
 * page from returning until the owner changes what it asks for.
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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
