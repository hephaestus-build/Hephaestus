package de.tum.cit.aet.hephaestus.core.privacy;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Transient identifying scope; completed audit receipts contain no subject identity or content. */
@Entity
@Table(name = "person_data_request")
@Getter
@Setter
public class PersonDataRequest {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "administrator_account_id")
    private @Nullable Long administratorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State state = State.PREVIEW;

    @Column(name = "scope_json", columnDefinition = "text")
    private @Nullable String scopeJson;

    @Column(name = "selections_json", columnDefinition = "text")
    private @Nullable String selectionsJson;

    @Column(name = "counts_json", nullable = false, columnDefinition = "text")
    private String countsJson = "{}";

    @Column(name = "completed_json", nullable = false, columnDefinition = "text")
    private String completedJson = "{}";

    @Column(name = "failure_code", length = 48)
    private @Nullable String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(172800);

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Version
    private long version;

    public enum State {
        PREVIEW,
        ERASING,
        FAILED,
        COMPLETE,
        EXPIRED
    }
}
