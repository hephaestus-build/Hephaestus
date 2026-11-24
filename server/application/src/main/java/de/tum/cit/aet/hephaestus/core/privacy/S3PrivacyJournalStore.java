package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Slf4j
@Component
@ConditionalOnServerRole
@Conditional(S3PrivacyJournalStore.BucketEnabled.class)
final class S3PrivacyJournalStore implements PrivacyJournalObjectStore, AutoCloseable {

    private static final String PROBE_KEY = "_checks/if-none-match-sha256-v1.json";
    private static final byte[] PROBE = "{\"privacyJournalProbe\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OVERWRITE = "{}".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PrivacyJournalProperties.S3 settings;
    private final Clock clock;
    private final S3Client client;
    private final ReentrantLock verification = new ReentrantLock();
    private volatile boolean verified;
    private volatile boolean incompatible;

    @Autowired
    S3PrivacyJournalStore(PrivacyJournalProperties properties, Clock clock) {
        this(properties.s3(), clock);
    }

    S3PrivacyJournalStore(PrivacyJournalProperties.S3 settings, Clock clock) {
        if (settings.accessKeyId().isBlank() || settings.secretAccessKey().isBlank()) {
            throw new IllegalStateException(
                    "Configure the privacy journal bucket's separate access key and secret key");
        }
        if (!settings.prefix().endsWith("/") || settings.prefix().startsWith("/") || settings.objectLockDays() < 0) {
            throw new IllegalStateException(
                    "Configure a relative privacy journal prefix ending in / and a non-negative retention");
        }
        this.settings = settings;
        this.clock = clock;
        var transport =
                UrlConnectionHttpClient.builder().connectionTimeout(TIMEOUT).socketTimeout(TIMEOUT);
        var privateCa = settings.privateCa();
        if (privateCa != null) {
            var managers = trustManagers(privateCa);
            transport.tlsTrustManagersProvider(managers::getTrustManagers);
        }
        var builder = S3Client.builder()
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey())))
                .serviceConfiguration(configuration -> configuration
                        .pathStyleAccessEnabled(settings.pathStyle())
                        .chunkedEncodingEnabled(false)
                        // Let the bucket refuse a conditional write before the client sends its body.
                        .expectContinueThresholdInBytes(0L))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(transport)
                .overrideConfiguration(
                        config -> config.apiCallTimeout(TIMEOUT).retryStrategy(retry -> retry.maxAttempts(1)));
        var endpoint = settings.endpoint();
        if (endpoint != null) builder.endpointOverride(endpoint);
        client = builder.build();
    }

    @PostConstruct
    void verifyBucket() {
        try {
            probeBucket();
        } catch (IllegalStateException incompatibleBucket) {
            close();
            throw incompatibleBucket;
        }
    }

    private void probeBucket() {
        var key = settings.prefix() + PROBE_KEY;
        String failure = "the bucket accepted a conditional overwrite";
        try {
            var expected = checksum(PROBE);
            try {
                put(key, PROBE, expected);
            } catch (S3Exception exists) {
                if (exists.statusCode() != 412) throw exists;
            }
            try {
                put(key, OVERWRITE, checksum(OVERWRITE));
            } catch (S3Exception refused) {
                if (refused.statusCode() != 412) throw refused;
                if (matches(key, expected)) {
                    verified = true;
                    return;
                }
                failure = "the stored object did not return the expected SHA-256 checksum";
            }
        } catch (S3Exception unavailable) {
            int status = unavailable.statusCode();
            failure = "the bucket returned HTTP " + status;
            if (status >= 500 || status == 429 || status == 408) {
                logTemporaryProbeFailure(failure);
                return;
            }
        } catch (SdkClientException unavailable) {
            var cause = unavailable.getCause();
            logTemporaryProbeFailure("the client could not complete the request ("
                    + (cause == null ? unavailable : cause).getClass().getSimpleName() + ")");
            return;
        }
        incompatible = true;
        log.atError()
                .addKeyValue("event.name", "privacy.journal.bucket_check_failed")
                .addKeyValue("privacy.journal.failure", failure)
                .log("Privacy journal bucket capability check failed");
        throw new IllegalStateException(
                "Privacy journal bucket must support conditional PutObject and SHA-256 HeadObject within the journal prefix: "
                        + failure);
    }

    private static void logTemporaryProbeFailure(String reason) {
        log.atWarn()
                .addKeyValue("event.name", "privacy.journal.bucket_check_failed")
                .addKeyValue("privacy.journal.failure", reason)
                .log("Privacy journal bucket is unavailable for verification");
    }

    @Override
    public boolean verified() {
        return verified;
    }

    private boolean ensureVerified() {
        if (verified) return true;
        if (incompatible || !verification.tryLock()) return false;
        try {
            if (!verified && !incompatible) probeBucket();
            return verified;
        } catch (IllegalStateException unsupported) {
            return false;
        } finally {
            verification.unlock();
        }
    }

    @Override
    public boolean store(String relativeKey, byte[] body) {
        if (!ensureVerified()) return false;
        var key = settings.prefix() + relativeKey;
        var expected = checksum(body);
        try {
            try {
                put(key, body, expected);
            } catch (S3Exception failure) {
                // A conflicting create or a server error may already have written the exact bytes.
                if (failure.statusCode() != 412 && failure.statusCode() != 409 && failure.statusCode() < 500) {
                    return false;
                }
            } catch (SdkClientException ambiguous) {
                // The connection can fail after S3 commits the write.
            }
            if (matches(key, expected)) return true;
            log.atError()
                    .addKeyValue("event.name", "privacy.journal.checksum_mismatch")
                    .log("Privacy journal object checksum did not match");
            return false;
        } catch (S3Exception | SdkClientException unavailable) {
            return false;
        }
    }

    private void put(String key, byte[] body, String checksum) {
        var request = PutObjectRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .contentType("application/json")
                .ifNoneMatch("*")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .checksumSHA256(checksum);
        if (settings.objectLockDays() > 0) {
            request.objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(clock.instant().plus(Duration.ofDays(settings.objectLockDays())));
        }
        client.putObject(request.build(), RequestBody.fromBytes(body));
    }

    boolean matches(String key, String expected) {
        var stored = client.headObject(
                request -> request.bucket(settings.bucket()).key(key).checksumMode(ChecksumMode.ENABLED));
        return expected.equals(stored.checksumSHA256());
    }

    private static String checksum(byte[] body) {
        try {
            return Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static TrustManagerFactory trustManagers(Path privateCa) {
        try (var pem = Files.newInputStream(privateCa)) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(pem);
            if (certificates.isEmpty()) throw new IllegalArgumentException("Privacy journal CA file is empty");
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null);
            int index = 0;
            for (var certificate : certificates) {
                trust.setCertificateEntry("privacy-journal-ca-" + index++, certificate);
            }
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            return managers;
        } catch (IOException | GeneralSecurityException invalid) {
            throw new IllegalStateException("Cannot load the configured privacy journal bucket CA", invalid);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        client.close();
    }

    static final class BucketEnabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("hephaestus.privacy.journal.s3.bucket"));
        }
    }
}
