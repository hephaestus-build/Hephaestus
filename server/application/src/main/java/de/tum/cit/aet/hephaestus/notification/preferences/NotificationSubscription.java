package de.tum.cit.aet.hephaestus.notification.preferences;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "notification_subscription",
        indexes = @Index(name = "idx_notification_subscription_delivery", columnList = "kind,enabled,account_id"),
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_notification_subscription_account_kind",
                    columnNames = {"account_id", "kind"}),
            @UniqueConstraint(name = "uk_notification_subscription_token", columnNames = "unsubscribe_token")
        })
@Getter
@NoArgsConstructor
class NotificationSubscription {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationSubscriptionKind kind;

    @Column(name = "unsubscribe_token", nullable = false, updatable = false)
    private UUID unsubscribeToken = UUID.randomUUID();

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationEmailFrequency frequency = NotificationEmailFrequency.IMMEDIATE;

    @Column(name = "enabled_since")
    private @Nullable Instant enabledSince;

    @Column(name = "last_digest_date")
    private @Nullable LocalDate lastDigestDate;

    @Version
    private long version;

    NotificationSubscription(long accountId, NotificationSubscriptionKind kind) {
        this.accountId = accountId;
        this.kind = kind;
    }

    void setEnabled(boolean enabled, Instant now) {
        if (enabled && !this.enabled) this.enabledSince = now;
        this.enabled = enabled;
    }

    void setFrequency(NotificationEmailFrequency frequency, Instant now) {
        if (frequency != this.frequency) this.enabledSince = now;
        this.frequency = frequency;
    }

    void setLastDigestDate(LocalDate date) {
        this.lastDigestDate = date;
    }
}
