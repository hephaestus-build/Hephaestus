package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** A broker delivery's committed write step; it contains no payload or routing headers. */
@MappedSuperclass
@Getter
@Setter
public abstract class InboxStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "stream_sequence", nullable = false)
    private long streamSequence;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "step_name", nullable = false, length = 200)
    private String stepName;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
