package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "privacy_journal", indexes = @Index(name = "ix_privacy_journal_recorded", columnList = "recorded_at"))
class PrivacyJournalRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false, unique = true)
    private UUID entryId;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PrivacyJournalKind kind;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode refs;

    @Column(nullable = false)
    @ColumnDefault("clock_timestamp()")
    private Instant recordedAt;

    @Getter
    private @Nullable Instant retentionObservedAt;

    @Getter
    private @Nullable Instant shippedAt;

    PrivacyJournalRow(UUID entryId, PrivacyJournalKind kind, JsonNode refs, Instant recordedAt) {
        this.entryId = entryId;
        this.kind = kind;
        this.refs = refs;
        this.recordedAt = recordedAt;
    }

    PrivacyJournalManifest.Entry manifestEntry() {
        return new PrivacyJournalManifest.Entry(entryId, kind, refs, recordedAt);
    }
}
