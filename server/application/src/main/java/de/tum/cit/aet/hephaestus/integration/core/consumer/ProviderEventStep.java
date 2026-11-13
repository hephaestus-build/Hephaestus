package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A handler step that one attempt of an event committed; a retry or a replay of the event skips it. */
@Entity
@Table(name = "provider_event_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderEventStep {

    @EmbeddedId
    private Id id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "event_id", nullable = false)
        private Long eventId;

        @Column(name = "step_name", nullable = false, length = 200)
        private String stepName;
    }
}
