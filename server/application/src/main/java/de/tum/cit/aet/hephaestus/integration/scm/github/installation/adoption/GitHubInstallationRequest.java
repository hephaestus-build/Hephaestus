package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub App installation on this instance and an instance admin's decision on it. The row is instance-level:
 * {@code workspaceId} only names the workspace that an adoption created, and the workspace purge clears it.
 */
@Entity
@Table(
        name = "github_installation_request",
        indexes = {
            @Index(name = "idx_github_installation_request_decided_by", columnList = "decided_by_account_id"),
            @Index(name = "idx_github_installation_request_workspace", columnList = "workspace_id"),
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubInstallationRequest {

    @Id
    @Column(name = "installation_id")
    private Long installationId;

    @Column(name = "account_id")
    private @Nullable Long accountId;

    /** A personal account's login is cleared when its adopted workspace is purged or the person is erased. */
    @Column(name = "account_login")
    private @Nullable String accountLogin;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_kind", nullable = false, length = 16)
    private GitHubInstallationAccountKind accountKind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GitHubInstallationRequestStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "decided_by_account_id")
    private @Nullable Long decidedByAccountId;

    @Column(name = "decided_at")
    private @Nullable Instant decidedAt;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    void adopt(long adminAccountId, long accountId, String accountLogin, long workspaceId, Instant at) {
        this.status = GitHubInstallationRequestStatus.ADOPTED;
        this.accountId = accountId;
        this.accountLogin = accountLogin;
        this.workspaceId = workspaceId;
        this.decidedByAccountId = adminAccountId;
        this.decidedAt = at;
    }

    void decline(long adminAccountId, Instant at) {
        this.status = GitHubInstallationRequestStatus.DECLINED;
        this.decidedByAccountId = adminAccountId;
        this.decidedAt = at;
    }
}
