package de.tum.cit.aet.hephaestus.core.privacy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention Time a fact stays in the database when backups are explicitly off.
 * @param backupsEnabled Whether a backup job must report a recovery point before pruning.
 */
@ConfigurationProperties("hephaestus.privacy.journal")
record PrivacyJournalProperties(
        @DefaultValue("35d") Duration retention,
        @DefaultValue("true") boolean backupsEnabled) {
    PrivacyJournalProperties {
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Privacy journal retention must be positive");
        }
    }
}
