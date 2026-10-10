package de.tum.cit.aet.hephaestus.core.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A publication-only objection survives account and identity-link deletion. */
@Entity
@Table(
        name = "public_activity_objection",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_public_activity_objection_identity",
                        columnNames = {"provider_id", "subject"}))
@Getter
@Setter
public class PublicActivityObjection {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "provider_id", nullable = false)
    private long providerId;

    @Column(nullable = false, length = 255)
    private String subject;
}
