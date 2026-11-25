package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A MinIO bucket whose journal credential may only put and get objects under {@link #PREFIX}, as the chart's scoped
 * credential may. The administration client reads objects with the root credential.
 */
final class JournalMinio implements AutoCloseable {

    static final String BUCKET = "test-archive";
    static final String PREFIX = "privacy-journal/";
    private static final String JOURNAL_USER = "journal-user";
    private static final String JOURNAL_PASSWORD = "journal-password-fixture";

    // The legacy Bitnami distribution ships the MinIO server and its administration client in one image.
    private final GenericContainer<?> minio = new GenericContainer<>(
                    "bitnamilegacy/minio:2025.7.23-debian-12-r5@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20")
            // The image setup opens the port on a temporary server before the final server starts.
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("minio"))
            .withCommand("server", "--address", ":9000", "/tmp/journal-fixture")
            .withEnv("MINIO_ROOT_USER", "fixture-admin")
            .withEnv("MINIO_ROOT_PASSWORD", "fixture-admin-password")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    JournalMinio() throws Exception {
        minio.start();
        mc("alias", "set", "test", "http://127.0.0.1:9000", "fixture-admin", "fixture-admin-password");
        mc("mb", "test/" + BUCKET);
        minio.copyFileToContainer(Transferable.of("""
                        {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Action": ["s3:PutObject", "s3:GetObject"],
                            "Resource": "arn:aws:s3:::test-archive/privacy-journal/*"
                          }]
                        }
                        """), "/tmp/journal-policy.json");
        mc("admin", "user", "add", "test", JOURNAL_USER, JOURNAL_PASSWORD);
        mc("admin", "policy", "create", "test", "journal-prefix", "/tmp/journal-policy.json");
        mc("admin", "policy", "attach", "test", "journal-prefix", "--user", JOURNAL_USER);
    }

    /** A store with the journal credential under {@code prefix}. */
    S3PrivacyJournalStore store(String prefix) {
        return store(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)), prefix);
    }

    static S3PrivacyJournalStore store(URI endpoint, String prefix) {
        return new S3PrivacyJournalStore(
                new PrivacyJournalProperties.S3(
                        BUCKET, prefix, endpoint, "us-east-1", true, null, JOURNAL_USER, JOURNAL_PASSWORD, 0),
                Clock.systemUTC());
    }

    /** The stored bytes of the object under {@code key}, read with the root credential. */
    String object(String key) throws Exception {
        return mc("cat", "test/" + BUCKET + "/" + key).getStdout();
    }

    /** Keeps every write of an object as its own version, so a rewrite of the same bytes is visible. */
    void keepVersions() throws Exception {
        mc("version", "enable", "test/" + BUCKET);
    }

    /** How many versions of the object under {@code key} the bucket holds. */
    long versions(String key) throws Exception {
        return mc("ls", "--versions", "test/" + BUCKET + "/" + key)
                .getStdout()
                .lines()
                .filter(line -> !line.isBlank())
                .count();
    }

    private Container.ExecResult mc(String... args) throws Exception {
        var command = new String[args.length + 1];
        command[0] = "mc";
        System.arraycopy(args, 0, command, 1, args.length);
        var result = minio.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result;
    }

    @Override
    public void close() {
        minio.close();
    }
}
