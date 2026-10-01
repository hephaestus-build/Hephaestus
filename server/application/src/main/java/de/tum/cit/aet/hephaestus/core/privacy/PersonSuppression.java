package de.tum.cit.aet.hephaestus.core.privacy;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Minimal exact identity held separately from non-content erasure receipts to prevent reprocessing. */
@Entity
@Table(
        name = "person_suppression",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_person_suppression_key",
                        columnNames = {"provider_id", "subject", "team_key"}))
@Getter
@Setter
public class PersonSuppression {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "active_request_id")
    private @Nullable UUID activeRequestId;

    @Column(name = "provider_id", nullable = false)
    private long providerId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "team_key", nullable = false, length = 255)
    private String teamKey = "";
}
