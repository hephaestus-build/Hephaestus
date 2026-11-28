package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per account: the serialization point of the account's commitment and control transitions (ADR 0158
 * decision 2). Every such transition locks it before the account's sources, requests and attempts.
 */
@Entity
@PersonOwned
@Table(name = "mentor_owner")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorOwner {
    @Id
    private long accountId;

    @Column(nullable = false)
    private Instant createdAt;
}
