package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** One account's own, reversible AI preference and first-visit completion in one workspace. */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_choice", length = 24)
    private @Nullable MemberAiChoice aiChoice;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Nullable
    @Column(name = "welcomed_at")
    private Instant welcomedAt;

    @Nullable
    @Column(name = "completed_at")
    private Instant completedAt;
}
