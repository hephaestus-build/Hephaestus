package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "github_access_membership",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_github_access_membership_target_user",
                    columnNames = {"target_id", "github_user_id"}),
            @UniqueConstraint(
                    name = "uq_github_access_membership_scope",
                    columnNames = {"id", "target_id", "workspace_id"})
        })
@Getter
@Setter
@NoArgsConstructor
public class GitHubAccessMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_github_access_membership_workspace"))
    private Workspace workspace;

    @ManyToOne(optional = false)
    @JoinColumn(
            name = "target_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_github_access_membership_target"))
    private GitHubAccessTarget target;

    /** Erasure clears attribution, not the minimal native identity needed to finish a pending revocation. */
    @Column(name = "account_id")
    private @Nullable Long accountId;

    @Column(name = "github_user_id", nullable = false)
    private long githubUserId;

    @Column(name = "github_identity_link_id")
    private @Nullable Long githubIdentityLinkId;

    @Column(name = "directory_identity_link_id")
    private @Nullable Long directoryIdentityLinkId;

    @Column(name = "directory_subject", length = 512)
    private @Nullable String directorySubject;

    @Column(nullable = false)
    private boolean enrolled = true;

    /** Only explicit adoption or a confirmed new grant establishes ownership of existing external access. */
    @Column(nullable = false)
    private boolean managed;

    @Column(name = "manual_exception", nullable = false)
    private boolean manualException;

    @Column(name = "exception_reason", length = 512)
    private @Nullable String exceptionReason;

    @Column(name = "revocation_requested", nullable = false)
    private boolean revocationRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_state", length = 32)
    private GitHubAccessClient.@Nullable State externalState;

    @Column(name = "invitation_id")
    private @Nullable Long invitationId;

    @Column(name = "github_login", length = 100)
    private @Nullable String githubLogin;

    @Column(name = "confirmed_at")
    private @Nullable Instant confirmedAt;

    @Column(length = 512)
    private @Nullable String blocker;

    @Version
    @Column(nullable = false)
    private long version;
}
