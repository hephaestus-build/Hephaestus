package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The central integration aggregate.
 *
 * <p>One row per (workspace, kind, instance_key). Multi-instance supported — a workspace
 * may have two GitHub orgs as two Connection rows, distinct instance_key values.
 *
 * <p>Config is a sealed JSONB column. Credentials are an opaque AES-GCM-encrypted blob.
 */
@Entity
@Table(
        name = "connection",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_connection",
                    columnNames = {"workspace_id", "kind", "instance_key"}),
            @UniqueConstraint(
                    name = "ux_connection_id_workspace",
                    columnNames = {"id", "workspace_id"}),
        })
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private IntegrationKind kind;

    @Column(name = "instance_key", length = 128)
    @Nullable
    private String instanceKey;

    @Column(name = "display_name", length = 256)
    @Nullable
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @ColumnDefault("'PENDING'")
    private IntegrationState state = IntegrationState.PENDING;

    @Column(name = "state_reason", length = 512)
    @Nullable
    private String stateReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private ConnectionConfig config;

    @Column(name = "credentials_encrypted")
    private byte @Nullable [] credentialsEncrypted;

    @Column(name = "credentials_alg", length = 64)
    @Nullable
    private String credentialsAlg;

    @Column(name = "credentials_key_version")
    @Nullable
    private Integer credentialsKeyVersion;

    @Column(name = "credentials_rotation_failed_at")
    @Nullable
    private Instant credentialsRotationFailedAt;

    // Changes whenever the stored credential value changes, never on key rotation, so a pending provider teardown
    // can tell its own credential from one that a reconnect stored later.
    @Column(name = "lifecycle_generation", nullable = false)
    @ColumnDefault("0")
    private long lifecycleGeneration;

    @Column(name = "credentials_generation", nullable = false)
    @ColumnDefault("0")
    private long credentialsGeneration;

    // Set by a disconnect that left a credential, an installation or a webhook at the provider; cleared once removed.
    @Column(name = "provider_repair_capture_generation")
    private @Nullable Long providerRepairCaptureGeneration;

    @Column(name = "provider_teardown_requested_at")
    private @Nullable Instant providerTeardownRequestedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long version = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_problem", length = 32)
    private @Nullable Problem attentionProblem;

    @Column(name = "attention_revision", nullable = false)
    @ColumnDefault("0")
    private long attentionRevision;

    public @Nullable Problem getAttentionProblem() {
        return attentionProblem;
    }

    public long getAttentionRevision() {
        return attentionRevision;
    }

    void changeAttention(@Nullable Problem problem) {
        attentionProblem = problem;
        attentionRevision++;
    }

    protected Connection() {}

    public Connection(
            Workspace workspace, IntegrationKind kind, @Nullable String instanceKey, ConnectionConfig config) {
        this.workspace = workspace;
        this.kind = kind;
        this.instanceKey = instanceKey;
        this.config = config;
    }

    /**
     * Bind a vendor-supplied {@code instance_key} on a row that didn't have one yet
     * (the typical OAuth-finalize path: create PENDING with null key, vendor returns
     * the installation/team/workspace id, we stamp it). Throws if the row already
     * carries a different key — reconnecting an installation must reuse the existing
     * row to preserve audit history, not fork.
     */
    public void bindInstanceKey(String instanceKey) {
        if (instanceKey == null || instanceKey.isBlank()) {
            throw new IllegalArgumentException("instanceKey must be non-blank");
        }
        if (this.instanceKey == null) {
            this.instanceKey = instanceKey;
            return;
        }
        if (!this.instanceKey.equals(instanceKey)) {
            throw new IllegalStateException(
                    "Cannot rebind Connection " + id + " from instance_key=" + this.instanceKey + " to " + instanceKey);
        }
    }

    public IntegrationRef toRef() {
        return new IntegrationRef(kind, workspace.getId(), instanceKey);
    }

    public Long getId() {
        return Objects.requireNonNull(id, "Connection has not been persisted");
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public IntegrationKind getKind() {
        return kind;
    }

    @Nullable
    public String getInstanceKey() {
        return instanceKey;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    public IntegrationState getState() {
        return state;
    }

    @Nullable
    public String getStateReason() {
        return stateReason;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    public byte @Nullable [] getCredentialsEncrypted() {
        return credentialsEncrypted;
    }

    @Nullable
    public String getCredentialsAlg() {
        return credentialsAlg;
    }

    @Nullable
    public Integer getCredentialsKeyVersion() {
        return credentialsKeyVersion;
    }

    @Nullable
    public Instant getCredentialsRotationFailedAt() {
        return credentialsRotationFailedAt;
    }

    /**
     * When the credential this connection runs on was first found unreadable, or null while it reads
     * or while nothing runs on it. A GitHub App connection runs on its installation id and the app's
     * own key; the blob it stores is installation identity that the rotation job may quarantine but
     * that no request needs, so it is never reported as a state of the connection.
     */
    @Nullable
    public Instant credentialsUnreadableSince() {
        return config instanceof ConnectionConfig.GitHubAppConfig ? null : credentialsRotationFailedAt;
    }

    public Instant getCreatedAt() {
        return Objects.requireNonNull(createdAt, "Connection has not been persisted");
    }

    public Instant getUpdatedAt() {
        return Objects.requireNonNull(updatedAt, "Connection has not been persisted");
    }

    public Long getVersion() {
        return version;
    }

    public void setDisplayName(@Nullable String displayName) {
        this.displayName = displayName;
    }

    public void setState(IntegrationState state) {
        if (this.state != state) lifecycleGeneration++;
        this.state = state;
    }

    public void setStateReason(@Nullable String stateReason) {
        this.stateReason = stateReason;
    }

    public void setConfig(ConnectionConfig config) {
        if (instanceKey != null && kind != null && this.config != null) {
            var previous = PersonSourceNamespace.from(kind.name(), SourceProviderNamespaces.configuredUrl(this.config));
            var next = PersonSourceNamespace.from(kind.name(), SourceProviderNamespaces.configuredUrl(config));
            if (previous.isPresent()
                    && next.isPresent()
                    && !ScmOrigin.of(previous.orElseThrow().serverUrl())
                            .equals(ScmOrigin.of(next.orElseThrow().serverUrl())))
                throw new IllegalStateException(
                        "A bound connection cannot change its provider instance. Create a new connection.");
        }
        this.config = config;
    }

    /** Encrypts or clears this connection's credentials. */
    public void setCredentials(@Nullable CredentialBundle bundle, CredentialBundleConverter converter) {
        this.credentialsGeneration++;
        if (bundle == null) {
            this.credentialsEncrypted = null;
            this.credentialsAlg = null;
            this.credentialsKeyVersion = null;
            this.credentialsRotationFailedAt = null;
            return;
        }
        this.credentialsEncrypted = converter.encrypt(bundle, encryptionContext());
        this.credentialsAlg = CredentialBundleConverter.ALGORITHM_TAG;
        this.credentialsKeyVersion = converter.activeKeyVersion();
        this.credentialsRotationFailedAt = null;
    }

    /** Keeps the credential and the provider ids until {@link ProviderTeardown} has removed them at the provider. */
    void requestProviderTeardown(Instant requestedAt) {
        this.providerTeardownRequestedAt = requestedAt;
        // Each disconnect is its own generation, also for a connection whose credential never changes.
        this.credentialsGeneration++;
    }

    void finishProviderCredentialRetention() {
        this.providerTeardownRequestedAt = null;
    }

    public void markProviderRepairCaptured() {
        this.providerRepairCaptureGeneration = credentialsGeneration;
    }

    public boolean providerRepairWasCaptured() {
        return Objects.equals(providerRepairCaptureGeneration, credentialsGeneration);
    }

    public void detachProviderWebhook() {
        if (config instanceof ConnectionConfig.GitLabConfig gitlab) setConfig(gitlab.withGitlabWebhookId(null));
        else if (config instanceof ConnectionConfig.OutlineConfig outline)
            setConfig(outline.withWebhookSubscription(null, null));
    }

    public @Nullable Instant getProviderTeardownRequestedAt() {
        return providerTeardownRequestedAt;
    }

    /** Clears the credential and the provider ids that a disconnect leaves at the provider. */
    void releaseProviderFootprint(CredentialBundleConverter converter) {
        this.providerTeardownRequestedAt = null;
        setCredentials(null, converter);
        detachProviderWebhook();
    }

    /** Re-encrypts the same credential under the active key; the stored value and its generation stay. */
    void reencryptCredentials(CredentialBundle bundle, CredentialBundleConverter converter) {
        this.credentialsEncrypted = converter.encrypt(bundle, encryptionContext());
        this.credentialsAlg = CredentialBundleConverter.ALGORITHM_TAG;
        this.credentialsKeyVersion = converter.activeKeyVersion();
        this.credentialsRotationFailedAt = null;
    }

    public long getLifecycleGeneration() {
        return lifecycleGeneration;
    }

    public long getCredentialsGeneration() {
        return credentialsGeneration;
    }

    /** The stored credential could not be read with the configured keys; cleared when it is replaced. */
    void markCredentialRotationFailed(Instant failedAt) {
        this.credentialsRotationFailedAt = failedAt;
    }

    /**
     * Whether credentials are stored, without touching a key. Presence must not depend on the running
     * key being the one that wrote the row: a credential it cannot read is a state to surface, not a
     * reason to fail whatever page asked.
     */
    public boolean hasCredentials() {
        return credentialsEncrypted != null;
    }

    /** Returns the decrypted credentials, if configured. */
    Optional<CredentialBundle> credentials(CredentialBundleConverter converter) {
        if (credentialsEncrypted == null) {
            return Optional.empty();
        }
        return Optional.of(converter.decrypt(credentialsEncrypted, encryptionContext(), credentialsKeyVersion));
    }

    private EncryptionContext encryptionContext() {
        return EncryptionContext.forConnectionCredentials(workspace.getId(), kind, instanceKey);
    }

    /**
     * Guards against {@code (kind, config)} drift — e.g. a {@code kind=GITLAB} row pointing
     * at a {@code GitHubAppConfig} payload after a buggy mutator path. Pattern-matches on
     * the sealed {@link ConnectionConfig} hierarchy so adding a new subtype is a compile
     * error here, not a silent runtime mismatch.
     */
    @PrePersist
    @PreUpdate
    void assertKindMatchesConfigSubtype() {
        if (kind == null || config == null) {
            return;
        }
        IntegrationKind expected = switch (config) {
            case ConnectionConfig.GitHubAppConfig ignored -> IntegrationKind.GITHUB;
            case ConnectionConfig.GitHubPatConfig ignored -> IntegrationKind.GITHUB;
            case ConnectionConfig.GitLabConfig ignored -> IntegrationKind.GITLAB;
            case ConnectionConfig.SlackConfig ignored -> IntegrationKind.SLACK;
            case ConnectionConfig.OutlineConfig ignored -> IntegrationKind.OUTLINE;
        };
        if (kind != expected) {
            throw new IllegalStateException("Connection kind=" + kind
                    + " incompatible with config="
                    + config.getClass().getSimpleName()
                    + " (expected "
                    + expected
                    + ")");
        }
    }
}
