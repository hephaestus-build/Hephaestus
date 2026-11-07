package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.nats.client.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Installs a private test CA as the default TLS context")
class NatsTlsIntegrationTest {

    private static final char[] STORE_PASSWORD = "test-only-password".toCharArray();
    private static final DockerImageName IMAGE = DockerImageName.parse("nats:2.10.27-alpine");

    @TempDir
    private static Path directory;

    private final List<GenericContainer<?>> brokers = new ArrayList<>();
    private @Nullable SSLContext originalContext;
    private int matchingPort;
    private int differentPort;
    private int plainPort;

    @BeforeAll
    void startBrokers() throws Exception {
        keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-dname",
                "CN=Test CA",
                "-ext",
                "bc=ca:true",
                "-validity",
                "2",
                "-keystore",
                "ca.p12");
        var ca = KeyStore.getInstance(directory.resolve("ca.p12").toFile(), STORE_PASSWORD);
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", ca.getCertificate("ca"));
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        originalContext = SSLContext.getDefault();
        SSLContext.setDefault(context);

        matchingPort = tlsBroker("matching", "dns:localhost", ca);
        // The IP is valid for this certificate, but the URL's DNS name is different.
        differentPort = tlsBroker("different", "dns:different.invalid,ip:127.0.0.1", ca);
        plainPort = startBroker("port: 4222\n", false);
    }

    @AfterAll
    void stopBrokers() {
        try {
            brokers.forEach(GenericContainer::close);
        } finally {
            if (originalContext != null) {
                SSLContext.setDefault(originalContext);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldConnectWhenTheCertificateNamesTheBroker(RuntimeRole role) {
        assertConnects(role, "tls://localhost:" + matchingPort);
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldRefuseWhenTheCertificateNamesAnotherBroker(RuntimeRole role) {
        assertRefuses(role, "tls://localhost:" + differentPort);
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldConnectWhenTheCertificateNamesTheIpAddress(RuntimeRole role) {
        assertConnects(role, "tls://127.0.0.1:" + differentPort);
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldRefuseWhenAnIpAddressHasOnlyADnsCertificate(RuntimeRole role) {
        assertRefuses(role, "tls://127.0.0.1:" + matchingPort);
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldConnectToANatsBrokerWithoutTls(RuntimeRole role) {
        assertConnects(role, "nats://localhost:" + plainPort);
    }

    private void assertConnects(RuntimeRole role, String server) {
        runner(role, server).run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(RuntimeRole.enabled(application.getEnvironment())).containsExactly(role);
            var connection = application.getBean(Connection.class);
            assertThat(connection.getStatus()).isEqualTo(Connection.Status.CONNECTED);
            connection.flush(Duration.ofSeconds(2));
        });
    }

    private void assertRefuses(RuntimeRole role, String server) {
        runner(role, server)
                .run(application ->
                        assertThat(application).hasFailed().getFailure().hasRootCauseInstanceOf(IOException.class));
    }

    private ApplicationContextRunner runner(RuntimeRole role, String server) {
        return new ApplicationContextRunner()
                .withUserConfiguration(NatsConfig.class)
                .withBean(
                        NatsConnectionProperties.class,
                        () -> new NatsConnectionProperties(true, server, null, null, null, null))
                .withPropertyValues("hephaestus.sync.nats.enabled=true")
                .withPropertyValues(Arrays.stream(RuntimeRole.values())
                        .map(candidate -> candidate.property() + "=" + (candidate == role))
                        .toArray(String[]::new));
    }

    private int tlsBroker(String name, String names, KeyStore ca) throws Exception {
        keytool(
                "-genkeypair",
                "-alias",
                "broker",
                "-keyalg",
                "EC",
                "-dname",
                "CN=Test Broker",
                "-keystore",
                name + ".p12");
        keytool("-certreq", "-alias", "broker", "-keystore", name + ".p12", "-file", name + ".csr");
        keytool(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-infile",
                name + ".csr",
                "-outfile",
                name + ".crt",
                "-rfc",
                "-ext",
                "san=" + names,
                "-ext",
                "eku=serverAuth",
                "-validity",
                "1");
        var store = KeyStore.getInstance(directory.resolve(name + ".p12").toFile(), STORE_PASSWORD);
        var key = store.getKey("broker", STORE_PASSWORD);
        assertThat(key).isNotNull();
        Files.writeString(directory.resolve("broker.key"), pem("PRIVATE KEY", key.getEncoded()));
        try (var input = Files.newInputStream(directory.resolve(name + ".crt"))) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            Files.writeString(
                    directory.resolve("broker.crt"),
                    pem("CERTIFICATE", certificate.getEncoded())
                            + pem("CERTIFICATE", ca.getCertificate("ca").getEncoded()));
        }
        return startBroker(
                "port: 4222\ntls { cert_file: /etc/nats/broker.crt\n"
                        + "key_file: /etc/nats/broker.key\nverify: false }\n",
                true);
    }

    private int startBroker(String configuration, boolean tls) {
        var broker = new GenericContainer<>(IMAGE)
                .withExposedPorts(4222)
                .withCopyToContainer(Transferable.of(configuration), "/etc/nats/nats.conf")
                .withCommand("-c", "/etc/nats/nats.conf")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        if (tls) {
            broker.withCopyFileToContainer(
                            MountableFile.forHostPath(directory.resolve("broker.key")), "/etc/nats/broker.key")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(directory.resolve("broker.crt")), "/etc/nats/broker.crt");
        }
        brokers.add(broker);
        broker.start();
        assertThat(broker.getHost()).isIn("localhost", "127.0.0.1");
        return broker.getMappedPort(4222);
    }

    private void keytool(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storepass", new String(STORE_PASSWORD), "-noprompt"));
        var log = directory.resolve("keytool.log");
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("keytool completes")
                    .isTrue();
            assertThat(process.exitValue()).as("keytool succeeds").isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
