package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use store backing {@link HmacOAuthStateService}. Each issued state writes
 * a row keyed by its nonce; consumption flips {@code consumed_at} via an atomic
 * conditional UPDATE so concurrent callbacks resolve to at-most-one winner.
 *
 * <p>The HMAC guarantees authenticity + TTL; this store guarantees uniqueness.
 * Together they bound an attacker to a single callback even with a captured token
 * inside the TTL window.
 *
 * <p>Kept as a separate component (rather than inlining repository calls into
 * {@link HmacOAuthStateService}) so the store can be unit-tested with mock
 * repositories without dragging in the HMAC machinery and vice-versa.
 */
@ConditionalOnServerRole
@Component
public class OAuthStateNonceStore {

    private final OAuthStateNonceRepository repository;

    private final ConnectionRepository connections;
    private final WorkspaceRepository workspaces;

    public OAuthStateNonceStore(
            OAuthStateNonceRepository repository, ConnectionRepository connections, WorkspaceRepository workspaces) {
        this.repository = repository;
        this.connections = connections;
        this.workspaces = workspaces;
    }

    /**
     * Record a freshly minted nonce. Persists immediately so a subsequent consume sees
     * the row even if the issuing transaction is separate.
     */
    @Transactional
    public void issue(@Nullable String nonce, long workspaceId, IntegrationKind kind, Instant issuedAt) {
        issueRows(nonce, workspaceId, kind, issuedAt, null);
    }

    @Transactional
    public void issue(
            @Nullable String nonce,
            long workspaceId,
            IntegrationKind kind,
            Instant issuedAt,
            @Nullable Long actorAccountId) {
        issueRows(nonce, workspaceId, kind, issuedAt, actorAccountId);
    }

    private void issueRows(
            @Nullable String nonce,
            long workspaceId,
            IntegrationKind kind,
            Instant issuedAt,
            @Nullable Long actorAccountId) {
        if (nonce == null || nonce.isEmpty()) {
            throw new IllegalArgumentException("nonce must be non-empty");
        }
        if (repository.existsById(nonce)) {
            throw new IllegalStateException("OAuth state nonce collision");
        }
        // Serialize initiation before choosing or creating the workspace's null-instance attempt.
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var candidates = connections.findByWorkspaceId(workspaceId).stream()
                .filter(connection -> connection.getKind() == kind)
                .sorted(Comparator.comparing(Connection::getCreatedAt).reversed())
                .toList();
        Connection owner = candidates.stream()
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE
                        || connection.getState() == IntegrationState.PENDING)
                .findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElseGet(() -> {
                    ConnectionConfig config = switch (kind) {
                        case GITHUB -> new ConnectionConfig.GitHubAppConfig(null, null, null, Set.of());
                        case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, Set.of());
                        case GITLAB, OUTLINE ->
                            throw new IllegalArgumentException("This provider does not use OAuth callbacks");
                    };
                    return connections.save(new Connection(workspace, kind, null, config));
                }));
        connections.acquireLifecycleLock(owner.getId(), workspaceId);
        repository.save(new OAuthStateNonce(
                nonce,
                workspaceId,
                kind.name(),
                issuedAt,
                actorAccountId,
                owner.getId(),
                owner.getLifecycleGeneration()));
    }

    @Transactional(readOnly = true)
    public OAuthStateService.StateBinding boundAttempt(String nonce, OAuthStateService.StateBinding verified) {
        OAuthStateNonce stored = repository
                .findByNonceAndWorkspaceId(nonce, verified.workspaceId())
                .orElseThrow(() ->
                        new IllegalArgumentException("The connection request expired. Start the connection again."));
        return new OAuthStateService.StateBinding(
                verified.workspaceId(),
                verified.kind(),
                verified.issuedAt(),
                verified.actorAccountId(),
                stored.getConnectionId(),
                stored.getLifecycleGeneration());
    }

    /**
     * Attempt to consume {@code nonce}. Returns true exactly once per nonce; all
     * subsequent calls return false (whether the previous caller is still in
     * flight or already committed).
     *
     * <p>If the nonce row doesn't exist at all the call also returns false — this
     * covers the case where an attacker forged a state with a guessed nonce but
     * the HMAC happened to (somehow) validate; without a backing row the consume
     * still fails closed. Callers should treat false as "reject and audit".
     */
    @Transactional
    public boolean tryConsume(@Nullable String nonce, OAuthStateService.StateBinding binding) {
        if (nonce == null || nonce.isEmpty()) {
            return false;
        }
        int updated = repository.markConsumedInWorkspace(
                nonce,
                Instant.now(),
                binding.workspaceId(),
                binding.kind().name(),
                binding.issuedAt(),
                binding.actorAccountId());
        return updated == 1;
    }
}
