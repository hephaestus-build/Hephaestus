package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * How many contacts of one kind a person got on their current local date, across every workspace and channel. One
 * row for each person and meter: the next day's first count replaces the earlier day's.
 */
@Entity
@PersonOwned
@Table(name = "account_meter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountMeter {

    @EmbeddedId
    private Id id;

    /** The person's local date that {@link #count} belongs to. */
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Column(name = "count", nullable = false)
    private int count;

    /** When the last counted contact was sent, also on an earlier date. */
    @Column(name = "last_at", nullable = false)
    private Instant lastAt;

    enum Meter {
        /** Unprompted notifications, which share one daily limit (ADR 0139 decision 4). */
        DAILY_UPDATES,
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "account_id", nullable = false)
        private Long accountId;

        @Enumerated(EnumType.STRING)
        @Column(name = "meter", nullable = false, length = 32)
        private Meter meter;
    }
}
