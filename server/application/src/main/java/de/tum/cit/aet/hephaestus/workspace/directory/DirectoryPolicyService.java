package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery.Source;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.KeycloakDirectoryConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.ClientCredentials;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.directory.KeycloakDirectoryClient;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace locking serializes approval, capture publication and membership mutations. Network I/O lives outside these transactions. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DirectoryPolicyService {
    private final DirectoryPolicyRepository policies;
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final DirectoryIdentitySourceQuery sources;
    private final AccountIdentityQuery identities;
    private final DirectoryMembershipAdapter managedAccess;
    private final ConnectionRepository connections;
    private final ConnectionService connectionService;
    private final CredentialReader credentialReader;
    private final CredentialBundleConverter credentialConverter;
    private final ConfigAuditPort audit;
    private final Clock clock;

    public record Configuration(
            String registrationId,
            Set<String> groupIds,
            @Nullable ClientCredentials credentials) {}

    public record PolicyAudit(
            String registrationId,
            Set<String> groups,
            DirectoryPolicy.Status status,
            long configurationVersion,
            @Nullable Long approvedByAccountId)
            implements ConfigAuditSnapshot {
        static PolicyAudit of(DirectoryPolicy p) {
            return new PolicyAudit(
                    p.getRegistrationId(),
                    p.getDraftGroupIds(),
                    p.getStatus(),
                    p.getConfigurationVersion(),
                    p.getApprovedByAccountId());
        }
    }

    public record CaptureInput(
            long workspaceId,
            long connectionId,
            long configurationVersion,
            Source source,
            Set<String> groupIds,
            Set<String> previousSubjects,
            ClientCredentials credentials,
            boolean preview) {}

    @Transactional(readOnly = true)
    public Optional<DirectoryPolicy> inspect(long workspaceId) {
        requirePermission(workspaceId, false);
        return policies.findByWorkspace_Id(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<Source> availableSources(long workspaceId) {
        requirePermission(workspaceId, true);
        return sources.approvedSources();
    }

    @Transactional
    public DirectoryPolicy configure(long workspaceId, Configuration input) {
        Workspace workspace = lockActive(workspaceId);
        requirePermission(workspaceId, true);
        Source source = sources.approvedSourceForUpdate(input.registrationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ask an instance administrator to approve this directory source and its eligibility groups"));
        if (input.groupIds().isEmpty() || !source.groupIds().containsAll(input.groupIds()))
            throw new IllegalArgumentException("Select at least one operator-approved eligibility group");
        DirectoryPolicy policy = policies.findByWorkspace_Id(workspaceId).orElse(null);
        PolicyAudit before = policy == null ? null : PolicyAudit.of(policy);
        boolean replacing = policy == null || policy.getStatus() == DirectoryPolicy.Status.ENDED;
        if (policy != null
                && !replacing
                && (!policy.getRegistrationId().equals(source.registrationId())
                        || !policy.getIssuer().equals(source.issuer())))
            throw new IllegalArgumentException("End the existing directory policy before changing its source");
        if (replacing && input.credentials() == null)
            throw new IllegalArgumentException(
                    "Ask the directory operator for a dedicated read-only client ID and secret");
        if (policy == null) {
            policy = new DirectoryPolicy();
            policy.setWorkspace(workspace);
        }
        if (replacing) {
            policy.setRegistrationId(source.registrationId());
            policy.setIssuer(source.issuer());
            policy.setIdentityProviderId(source.providerId());
            policy.setStatus(DirectoryPolicy.Status.DRAFT);
            policy.setApprovedGroupIds(Set.of());
            policy.setActiveSnapshot(null);
            policy.setApprovedByAccountId(null);
            policy.setApprovedAt(null);
        }
        if (input.credentials() != null) {
            validateCredentials(input.credentials());
            Connection connection = connections
                    .findByWorkspaceIdAndKindAndInstanceKey(
                            workspaceId, IntegrationKind.KEYCLOAK_DIRECTORY, source.registrationId())
                    .orElseGet(() -> connections.saveAndFlush(new Connection(
                            workspace,
                            IntegrationKind.KEYCLOAK_DIRECTORY,
                            source.registrationId(),
                            new KeycloakDirectoryConfig(source.registrationId(), source.issuer(), Set.of()))));
            connection.setCredentials(input.credentials(), credentialConverter);
            connection.setConfig(new KeycloakDirectoryConfig(source.registrationId(), source.issuer(), Set.of()));
            connection.setDisplayName(source.displayName() + " directory");
            // ACTIVE permits read jobs only. UNVERIFIED policy health and explicit owner approval gate every grant.
            connectionService.transition(connection, transition(IntegrationState.ACTIVE, "INITIATE"));
            policy.setConnectionId(connection.getId());
        }
        policy.setDraftGroupIds(Set.copyOf(input.groupIds()));
        invalidate(policy);
        policies.save(policy);
        audit.record(new ConfigAuditEntry(
                ConfigAuditEntityType.DIRECTORY_POLICY,
                String.valueOf(policy.getId()),
                workspaceId,
                before,
                PolicyAudit.of(policy)));
        return policy;
    }

    @Transactional
    public DirectoryPolicy approve(long workspaceId, long configurationVersion) {
        lockActive(workspaceId);
        requirePermission(workspaceId, true);
        DirectoryPolicy policy = required(workspaceId);
        if (policy.getStatus() == DirectoryPolicy.Status.ENDED)
            throw new IllegalArgumentException("Configure a directory policy before approving it");
        Source source = approvedSource(policy);
        DirectorySnapshot preview = policy.getPreviewSnapshot();
        if (policy.getConfigurationVersion() != configurationVersion
                || !usable(policy, source, preview, policy.getDraftGroupIds()))
            throw new IllegalArgumentException("Run a fresh preview of this configuration before approving it");
        DirectorySnapshot snapshot = Objects.requireNonNull(preview);
        PolicyAudit before = PolicyAudit.of(policy);
        policy.setApprovedGroupIds(snapshot.groupIds());
        policy.setApprovedByAccountId(SecurityUtils.getCurrentAccountId().orElseThrow());
        policy.setApprovedAt(clock.instant());
        policy.setStatus(DirectoryPolicy.Status.ACTIVE);
        policy.setActiveSnapshot(snapshot);
        policy.setHealth(DirectoryPolicy.Health.HEALTHY);
        policy.setFailureReason(null);
        managedAccess.apply(policy, source.providerId(), snapshot, true);
        record(policy, before);
        return policy;
    }

    @Transactional
    public DirectoryPolicy changeStatus(long workspaceId, DirectoryPolicy.Status status) {
        Workspace workspace = workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        requirePermission(workspaceId, true);
        DirectoryPolicy policy = required(workspaceId);
        PolicyAudit before = PolicyAudit.of(policy);
        if (status == policy.getStatus()) return policy;
        if (status == DirectoryPolicy.Status.ENDED) {
            managedAccess.end(workspace);
            policy.setStatus(status);
            invalidate(policy);
            connections
                    .findByIdAndWorkspaceId(policy.getConnectionId(), workspaceId)
                    .filter(connection -> connection.getState() != IntegrationState.UNINSTALLED)
                    .ifPresent(connection -> connectionService.transition(
                            connection, transition(IntegrationState.UNINSTALLED, "DIRECTORY_END")));
        } else if (status == DirectoryPolicy.Status.PAUSED && policy.getStatus() == DirectoryPolicy.Status.ACTIVE) {
            policy.setStatus(status);
            invalidate(policy);
        } else if (status == DirectoryPolicy.Status.ACTIVE && policy.getStatus() == DirectoryPolicy.Status.PAUSED) {
            if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE)
                throw new IllegalArgumentException("Reactivate the workspace before resuming directory management");
            approvedSource(policy);
            policy.setStatus(status);
            invalidate(policy);
        } else
            throw new IllegalArgumentException(
                    "Activate a draft through preview approval; only an active policy can pause or resume");
        record(policy, before);
        return policy;
    }

    @Transactional
    public void adopt(long workspaceId, long accountId) {
        lockActive(workspaceId);
        requirePermission(workspaceId, true);
        DirectoryPolicy policy = required(workspaceId);
        Source source = approvedSource(policy);
        if (policy.getStatus() != DirectoryPolicy.Status.ACTIVE
                || !usable(policy, source, policy.getActiveSnapshot(), policy.getApprovedGroupIds()))
            throw new IllegalArgumentException("Adoption requires an active policy and a fresh approved snapshot");
        identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> new EntityNotFoundException("Account", accountId));
        String subject = eligibleSubject(accountId, source, Objects.requireNonNull(policy.getActiveSnapshot()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "This account has no verified eligible identity for the approved directory"));
        var membership = memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceAccountMembership", accountId));
        managedAccess.adopt(membership, subject);
    }

    @Transactional
    public CaptureInput prepareCapture(long workspaceId, long connectionId, boolean preview) {
        lockActive(workspaceId);
        DirectoryPolicy policy = required(workspaceId);
        if (policy.getStatus() == DirectoryPolicy.Status.ENDED
                || (!preview && policy.getStatus() == DirectoryPolicy.Status.DRAFT))
            throw new IllegalArgumentException("The directory policy has not been activated");
        Source source = approvedSource(policy);
        if (policy.getConnectionId() != connectionId)
            throw new IllegalArgumentException("The directory connection changed before this job started");
        Set<String> groups = preview ? policy.getDraftGroupIds() : policy.getApprovedGroupIds();
        if (groups.isEmpty() || !source.groupIds().containsAll(groups))
            throw new IllegalArgumentException("Ask the owner to revise the policy to use currently approved groups");
        var credentials = credentialReader
                .credentialsOf(connection(policy))
                .filter(ClientCredentials.class::isInstance)
                .map(ClientCredentials.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("Ask the owner to rotate the directory credentials"));
        Set<String> previous = new HashSet<>();
        DirectorySnapshot last = policy.getActiveSnapshot();
        if (last != null) previous.addAll(last.eligibleSubjects().keySet());
        memberships.findByWorkspace_Id(workspaceId).stream()
                .map(member -> member.getDirectorySubject())
                .filter(Objects::nonNull)
                .forEach(previous::add);
        policy.setLastAttemptAt(clock.instant());
        // A failed retry must not leave old evidence granting new access until its nominal expiry.
        policy.setHealth(DirectoryPolicy.Health.UNVERIFIED);
        return new CaptureInput(
                workspaceId,
                policy.getConnectionId(),
                policy.getConfigurationVersion(),
                source,
                Set.copyOf(groups),
                Set.copyOf(previous),
                credentials,
                preview);
    }

    @Transactional
    public void completeCapture(CaptureInput input, KeycloakDirectoryClient.Capture capture) {
        lockActive(input.workspaceId());
        DirectoryPolicy policy = required(input.workspaceId());
        Source source = approvedSource(policy);
        if (policy.getStatus() == DirectoryPolicy.Status.ENDED
                || policy.getConnectionId() != input.connectionId()
                || policy.getConfigurationVersion() != input.configurationVersion()
                || !source.equals(input.source()))
            throw new IllegalArgumentException("Directory policy or source approval changed during capture; retry");
        DirectorySnapshot snapshot = new DirectorySnapshot(
                capture.startedAt(),
                capture.completedAt(),
                input.configurationVersion(),
                source.updatedAt(),
                input.groupIds(),
                capture.groupNames(),
                capture.eligibleSubjects(),
                capture.confirmedDepartures());
        if (!snapshot.freshAt(clock.instant()))
            throw new IllegalArgumentException("Directory snapshot expired before completion; retry");
        connection(policy);
        if (input.preview()) policy.setPreviewSnapshot(snapshot);
        else {
            policy.setActiveSnapshot(snapshot);
            managedAccess.apply(
                    policy, source.providerId(), snapshot, policy.getStatus() == DirectoryPolicy.Status.ACTIVE);
        }
        policy.setHealth(DirectoryPolicy.Health.HEALTHY);
        policy.setFailureReason(null);
    }

    @Transactional
    public void failedCapture(
            long workspaceId, @Nullable Long connectionId, @Nullable CaptureInput input, String reason) {
        if (workspaces.findByIdForUpdate(workspaceId).isEmpty()) return;
        policies.findByWorkspace_Id(workspaceId)
                .filter(policy -> policy.getStatus() != DirectoryPolicy.Status.ENDED)
                .filter(policy -> Objects.equals(policy.getConnectionId(), connectionId))
                .filter(policy -> input == null || policy.getConfigurationVersion() == input.configurationVersion())
                .ifPresent(policy -> {
                    policy.setHealth(DirectoryPolicy.Health.FAILED);
                    policy.setFailureReason(reason.length() > 512 ? reason.substring(0, 512) : reason);
                    policy.setLastAttemptAt(clock.instant());
                });
    }

    @Transactional(readOnly = true)
    public long connectionForJob(long workspaceId, boolean preview) {
        requirePermission(workspaceId, preview);
        DirectoryPolicy policy = required(workspaceId);
        if (policy.getStatus() == DirectoryPolicy.Status.ENDED)
            throw new IllegalArgumentException("Configure a directory policy first");
        return policy.getConnectionId();
    }

    @Transactional(readOnly = true)
    public long scheduledConnection(long workspaceId) {
        DirectoryPolicy policy = required(workspaceId);
        if (policy.getStatus() != DirectoryPolicy.Status.ACTIVE && policy.getStatus() != DirectoryPolicy.Status.PAUSED)
            throw new IllegalArgumentException("Only managed directory policies may reconcile automatically");
        return policy.getConnectionId();
    }

    public boolean usable(
            DirectoryPolicy policy, Source source, @Nullable DirectorySnapshot snapshot, Set<String> groups) {
        return snapshot != null
                && policy.getHealth() == DirectoryPolicy.Health.HEALTHY
                && snapshot.freshAt(clock.instant())
                && snapshot.configurationVersion() == policy.getConfigurationVersion()
                && snapshot.sourceVersion().equals(source.updatedAt())
                && snapshot.groupIds().equals(groups)
                && source.groupIds().containsAll(groups)
                && source.issuer().equals(policy.getIssuer());
    }

    Optional<String> eligibleSubject(long accountId, Source source, DirectorySnapshot snapshot) {
        return identities.activeLinksForAccount(accountId).stream()
                .filter(link -> link.gitProviderId() == source.providerId())
                .map(AccountIdentityQuery.IdentityLinkView::subject)
                .filter(snapshot.eligibleSubjects()::containsKey)
                .findFirst();
    }

    private Source approvedSource(DirectoryPolicy policy) {
        return sources.approvedSourceForUpdate(policy.getRegistrationId())
                .filter(source -> source.issuer().equals(policy.getIssuer()))
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "The directory source is disabled or no longer approved; ask an instance administrator to restore it"));
    }

    private Connection connection(DirectoryPolicy policy) {
        return connections
                .findByIdAndWorkspaceId(
                        policy.getConnectionId(), policy.getWorkspace().getId())
                .filter(connection -> connection.getKind() == IntegrationKind.KEYCLOAK_DIRECTORY
                        && connection.getState() == IntegrationState.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The directory connection is unavailable; ask the owner to reconnect it"));
    }

    private DirectoryPolicy required(long workspaceId) {
        return policies.findByWorkspace_Id(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("DirectoryPolicy", workspaceId));
    }

    private Workspace lockActive(long workspaceId) {
        Workspace workspace = workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE)
            throw new IllegalArgumentException("Reactivate the workspace before changing directory access");
        return workspace;
    }

    private void requirePermission(long workspaceId, boolean ownerRequired) {
        var context = WorkspaceContextHolder.getContext();
        var caller = SecurityUtils.getCurrentAccountId()
                .flatMap(id -> memberships.findByWorkspace_IdAndAccountId(workspaceId, id))
                .filter(member -> !member.isSuspended());
        boolean owner =
                caller.map(member -> member.getRole() == WorkspaceRole.OWNER).orElse(false);
        boolean admin =
                caller.map(member -> member.getRole() == WorkspaceRole.ADMIN).orElse(false)
                        || SecurityUtils.isSuperAdmin();
        if (context == null || context.id() != workspaceId || !(owner || (!ownerRequired && admin)))
            throw new InsufficientWorkspacePermissionsException(
                    context == null ? "unknown" : context.slug(),
                    "Only the workspace owner may change directory eligibility; administrators may inspect and reconcile approved access");
    }

    private void invalidate(DirectoryPolicy policy) {
        policy.setConfigurationVersion(policy.getConfigurationVersion() + 1);
        policy.setPreviewSnapshot(null);
        policy.setHealth(DirectoryPolicy.Health.UNVERIFIED);
        policy.setFailureReason(null);
    }

    private void record(DirectoryPolicy policy, PolicyAudit before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.DIRECTORY_POLICY,
                policy.getId(),
                policy.getWorkspace().getId(),
                before,
                PolicyAudit.of(policy)));
    }

    private static void validateCredentials(ClientCredentials credentials) {
        if (credentials.clientId().isBlank()
                || credentials.clientId().length() > 512
                || credentials.clientSecret().isBlank()
                || credentials.clientSecret().length() > 4096)
            throw new IllegalArgumentException("Provide a valid dedicated directory client ID and secret");
    }

    private static ConnectionService.TransitionRequest transition(IntegrationState state, String event) {
        return new ConnectionService.TransitionRequest(
                state,
                event,
                "ADMIN",
                SecurityUtils.getCurrentAccountId().map(String::valueOf).orElse(null),
                UUID.randomUUID().toString(),
                "Workspace owner changed directory management");
    }
}
