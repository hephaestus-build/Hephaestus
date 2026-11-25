package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Framework boundaries that write request or row content before application code sees the failure. */
class ServerLogPrivacyIntegrationTest extends BaseIntegrationTest {

    private static final String TOMCAT_PARSER = "org.apache.coyote.http11.Http11Processor";

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private Handler[] inheritedRootHandlers = new Handler[0];

    private record Rejection(String sqlState, String message) {}

    @Test
    void shouldKeepRejectedRowValuesOutOfTheDriverMessage() {
        var owner = PostgreSQLTestContainer.ownerJdbc();
        owner.execute("CREATE TABLE public.log_privacy_canary (email text CONSTRAINT uq_log_privacy_canary UNIQUE)");
        try {
            Rejection rejection = Objects.requireNonNull(jdbc.execute((ConnectionCallback<Rejection>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    String insert = "INSERT INTO public.log_privacy_canary VALUES ('private-row-canary@example.test')";
                    statement.execute(insert);
                    try {
                        statement.execute(insert);
                        throw new IllegalStateException("the second insert was not rejected");
                    } catch (SQLException duplicate) {
                        return new Rejection(
                                Objects.requireNonNull(duplicate.getSQLState()),
                                Objects.requireNonNull(duplicate.getMessage()));
                    }
                }
            }));

            assertThat(rejection.sqlState()).isEqualTo("23505");
            assertThat(rejection.message()).contains("uq_log_privacy_canary").doesNotContain("private-row-canary");
        } finally {
            owner.execute("DROP TABLE public.log_privacy_canary");
        }
    }

    @Test
    void shouldKeepTheRawTargetOfAnUnparseableRequestOutOfTheLog() throws IOException {
        assertTomcatParserKeepsTheTargetOutOfTheLog();
    }

    /**
     * The parser privacy boundary remains testable when an earlier context left the JVM logging bridge absent.
     */
    @Test
    void shouldKeepTheRawTargetOutOfTheLogAfterTheTestRemovedTheLoggingBridge() throws IOException {
        SLF4JBridgeHandler.uninstall();
        assertThat(SLF4JBridgeHandler.isInstalled()).isFalse();
        assertTomcatParserKeepsTheTargetOutOfTheLog();
    }

    // Tomcat logs through java.util.logging. Its records reach Logback only through a bridge, which is one handler of
    // the JVM-wide root logger. Spring Boot installs that bridge when it initializes the shared Logback context, which
    // a later context in this JVM can find already initialized, and a closing application can remove it. Thus, the
    // check installs exactly one bridge for itself, and each test puts back the root handlers that it found.
    @BeforeEach
    void rememberTheRootLogHandlers() {
        inheritedRootHandlers = LogManager.getLogManager().getLogger("").getHandlers();
    }

    @AfterEach
    void restoreTheRootLogHandlers() {
        var root = LogManager.getLogManager().getLogger("");
        for (Handler handler : root.getHandlers()) root.removeHandler(handler);
        for (Handler handler : inheritedRootHandlers) root.addHandler(handler);
    }

    private void assertTomcatParserKeepsTheTargetOutOfTheLog() throws IOException {
        Logger parser = (Logger) LoggerFactory.getLogger(TOMCAT_PARSER);
        Level shipped = parser.getLevel();
        assertThat(shipped).as("application.yml pins " + TOMCAT_PARSER).isEqualTo(Level.WARN);
        SLF4JBridgeHandler.uninstall();
        SLF4JBridgeHandler.install();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        parser.addAppender(appender);
        try {
            assertThat(sendUnparseableRequest()).startsWith("HTTP/1.1 400");
            assertThat(appender.list).isEmpty();

            // Unbounded, the same logger receives the target: the pinned name is the one Tomcat uses.
            parser.setLevel(Level.DEBUG);
            assertThat(Objects.requireNonNull(LogManager.getLogManager().getLogger(TOMCAT_PARSER))
                            .isLoggable(java.util.logging.Level.FINE))
                    .as("Tomcat's java.util.logging logger follows the Logback level")
                    .isTrue();
            assertThat(sendUnparseableRequest()).startsWith("HTTP/1.1 400");
            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getThrowableProxy())
                            .isNotNull()
                            .satisfies(thrown -> assertThat(thrown.getMessage()).contains("private-token-canary")));
        } finally {
            parser.setLevel(shipped);
            parser.detachAppender(appender);
            appender.stop();
        }
    }

    private String sendUnparseableRequest() throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /unsubscribe?token=private-token-canary{} HTTP/1.1\r\n"
                            + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
