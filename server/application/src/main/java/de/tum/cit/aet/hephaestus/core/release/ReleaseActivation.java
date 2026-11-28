package de.tum.cit.aet.hephaestus.core.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One rollout of the server Deployment, keyed by the Deployment's UID and the revision that its controller gave the
 * rollout. Without a Deployment, one server start: both key columns are then absent.
 */
@Entity
@Table(
        name = "release_activation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_release_activation_rollout",
                        columnNames = {"environment", "deployment_uid", "revision"}),
        indexes = @Index(name = "ix_release_activation_environment", columnList = "environment, activated_at"))
@Getter
@NoArgsConstructor
public class ReleaseActivation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Nullable
    private Long id;

    @Column(name = "environment", nullable = false, length = 64)
    private String environment;

    @Column(name = "deployment_uid", length = 36)
    @Nullable
    private String deploymentUid;

    @Column(name = "revision")
    @Nullable
    private Long revision;

    @Column(name = "version", nullable = false, length = 128)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private ReleaseChannel channel;

    @Column(name = "commit_sha", length = 40)
    @Nullable
    private String commit;

    @Column(name = "image", length = 512)
    @Nullable
    private String image;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    ReleaseActivation(
            String environment,
            @Nullable String deploymentUid,
            @Nullable Long revision,
            String version,
            ReleaseChannel channel,
            @Nullable String commit,
            @Nullable String image,
            Instant activatedAt) {
        this.environment = environment;
        this.deploymentUid = deploymentUid;
        this.revision = revision;
        this.version = version;
        this.channel = channel;
        this.commit = commit;
        this.image = image;
        this.activatedAt = activatedAt;
    }
}
