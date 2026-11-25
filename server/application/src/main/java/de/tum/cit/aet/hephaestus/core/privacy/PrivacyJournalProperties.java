package de.tum.cit.aet.hephaestus.core.privacy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param s3 Journal object storage settings.
 * @param retention Time a fact stays in the database when backups are explicitly off.
 * @param backupsEnabled Whether a backup job must report a recovery point before pruning.
 */
@ConfigurationProperties("hephaestus.privacy.journal")
record PrivacyJournalProperties(
        @DefaultValue("35d") Duration retention,
        @DefaultValue("true") boolean backupsEnabled,
        @DefaultValue S3 s3) {
    PrivacyJournalProperties {
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Privacy journal retention must be positive");
        }
    }

    /**
     * @param bucket Bucket that stores the journal. Leave empty to keep the journal in the database only.
     * @param prefix Key prefix for the journal credential.
     * @param endpoint HTTPS endpoint of the bucket. Leave empty to use the AWS regional endpoint.
     * @param region Region that signs requests to the journal bucket.
     * @param pathStyle Use path-style bucket addressing.
     * @param privateCa PEM file with the CA certificate for a private bucket endpoint.
     * @param accessKeyId Access key of the journal credential.
     * @param secretAccessKey Secret key of the journal credential.
     * @param objectLockDays Retention in days set on each journal object. Zero uses the bucket setting.
     */
    record S3(
            @DefaultValue("") String bucket,
            @DefaultValue("privacy-journal/") String prefix,
            @Nullable URI endpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("false") boolean pathStyle,
            @Nullable Path privateCa,
            @DefaultValue("") String accessKeyId,
            @DefaultValue("") String secretAccessKey,
            @DefaultValue("0") int objectLockDays) {
        @Override
        public String toString() {
            return "PrivacyJournalS3[bucketConfigured=" + !bucket.isBlank() + "]";
        }
    }
}
