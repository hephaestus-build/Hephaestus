package de.tum.cit.aet.hephaestus.core.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** The backup job's observation of the oldest retained recovery point. */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "privacy_journal_backup_horizon")
class PrivacyJournalBackupHorizonRow {

    @Id
    private short id;

    @Column(nullable = false)
    private Instant observedAt;

    @Column(name = "oldest_backup_at")
    private @Nullable Instant oldestBackupAt;
}
