package de.tum.cit.aet.hephaestus.core.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Per-account feature opt-ins. These are the app's authorization flags (where
 * {@code mentor_access}, {@code run_practice_review} etc. live; replaces the former
 * Keycloak realm roles, ADR 0017).
 *
 * <p>Composite PK {@code (account_id, flag)} — natural fit for "is this flag enabled
 * for this account?" lookups. The {@code FeatureFlag} enum remains the canonical
 * registry of valid keys; this table just records which accounts have them enabled.
 */
@Entity
@Table(name = "account_feature")
@Getter
@Setter
@NoArgsConstructor
public class AccountFeature {

    @EmbeddedId
    private Id id;

    @CreationTimestamp
    @Column(name = "enabled_at", nullable = false, updatable = false)
    private @Nullable Instant enabledAt;

    public AccountFeature(Long accountId, String flag) {
        this.id = new Id(accountId, flag);
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "account_id", nullable = false)
        private Long accountId;

        @Column(name = "flag", nullable = false, length = 64)
        private String flag;
    }
}
