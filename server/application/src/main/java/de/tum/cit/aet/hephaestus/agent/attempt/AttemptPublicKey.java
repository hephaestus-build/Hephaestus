package de.tum.cit.aet.hephaestus.agent.attempt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The public half of one worker's {@link AttemptSigningKey}. Its private half never leaves that worker's memory. The
 * row outlives its worker by the longest token life, so an attempt keeps its token when its worker goes.
 */
@Entity
@Table(name = "attempt_public_key")
@Getter
@NoArgsConstructor
public class AttemptPublicKey {

    @Id
    @Column(name = "kid", length = 36)
    private String kid;

    /** The key in X.509 {@code SubjectPublicKeyInfo} form. */
    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "not_after", nullable = false)
    private Instant notAfter;
}
