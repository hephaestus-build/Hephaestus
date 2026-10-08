package de.tum.cit.aet.hephaestus.core.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A release the server role started on this instance, recorded when it differs from the one before. */
@Entity
@Table(name = "release_start")
@Getter
@NoArgsConstructor
public class ReleaseStart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Nullable
    private Long id;

    @Column(name = "version", nullable = false, length = 128)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private ReleaseChannel channel;

    @Column(name = "environment", nullable = false, length = 64)
    private String environment;

    @Column(name = "commit_sha", length = 40)
    @Nullable
    private String commit;

    @Column(name = "image", length = 512)
    @Nullable
    private String image;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    ReleaseStart(
            String version,
            ReleaseChannel channel,
            String environment,
            @Nullable String commit,
            @Nullable String image,
            Instant startedAt) {
        this.version = version;
        this.channel = channel;
        this.environment = environment;
        this.commit = commit;
        this.image = image;
        this.startedAt = startedAt;
    }
}
