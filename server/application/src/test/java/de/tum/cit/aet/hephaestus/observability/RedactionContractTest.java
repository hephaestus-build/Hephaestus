package de.tum.cit.aet.hephaestus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.net.ssl.SSLHandshakeException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Encodes events with Boot's own ECS encoder, configured from the shipped {@code application*.yml}. The masks cover
 * the credential formats listed here and exception messages are left out; a message that a call site writes itself
 * is not redacted, as the last test pins.
 */
@Tag("unit")
class RedactionContractTest {

    private static final String EMAIL = "ada@example.test";

    @Test
    void shouldRedactKnownCredentialFormatsFromTheShippedConfiguration() throws IOException {
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("workspace.id", "42", "token", "structured-secret"));
            event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("Bearer exception-secret")));
            event.setMessage("credentials: Bearer eyJhbGciOi.test glpat-secret_123 "
                    + "https://example.test/cb?token=url-secret&x=1 github_pat_secret_123");
        });
        JsonNode json = new ObjectMapper().readTree(line);
        String message = text(json, "message");
        assertTrue(message.contains("Bearer ***"), line);
        assertTrue(message.contains("glpat-***"));
        assertTrue(message.contains("?token=***&x=1"));
        assertTrue(message.contains("github_pat_***"));
        assertFalse(line.contains("eyJhbGciOi"));
        assertFalse(line.contains("secret_123"));
        assertFalse(line.contains("structured-secret"));
        assertFalse(line.contains("exception-secret"));
        assertEquals("42", text(json, "context.workspace.id"));
        assertEquals("***", text(json, "context.token"));
        assertEquals(IllegalStateException.class.getName(), text(json, "error.type"));
        assertTrue(text(json, "error.stack_trace").contains(RedactionContractTest.class.getName()), line);
    }

    @Test
    void shouldReportWrappedDriverAndHttpFailuresByClassAndFramesOnly() throws Exception {
        SQLException driver =
                postgresUniqueViolation("ERROR: duplicate key value violates unique constraint \"uk_account_email\"\n"
                        + "  Detail: Key (email)=(" + EMAIL + ") already exists.");
        var database = new DataIntegrityViolationException(
                "could not execute statement [" + driver.getMessage() + "] [insert into account (email) values (?)]",
                driver);
        var provider = WebClientResponseException.create(
                404,
                "Not Found",
                new HttpHeaders(),
                "{\"title\":\"private-body-canary\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                new MockClientHttpRequest(
                        HttpMethod.GET, URI.create("https://api.example.test/repos/acme/private-repo-canary")));
        database.addSuppressed(provider);

        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("traceId", "0123456789abcdef0123456789abcdef", "job.id", "7"));
            event.setMessage("Request conflicts with database constraints");
            event.setThrowableProxy(new ThrowableProxy(database));
        });

        for (String privateText :
                List.of(EMAIL, "Key (email)", "private-body-canary", "private-repo-canary", "Not Found")) {
            assertFalse(line.contains(privateText), privateText + " leaked: " + line);
        }
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(DataIntegrityViolationException.class.getName(), text(json, "error.type"));
        assertNull(node(json, "error.message"), line);
        String trace = text(json, "error.stack_trace");
        // The root cause comes first; each exception that wraps it follows.
        assertTrue(trace.contains("Wrapped by: "), trace);
        assertTrue(trace.contains(driver.getClass().getName()), trace);
        assertTrue(
                trace.indexOf(driver.getClass().getName())
                        < trace.indexOf(DataIntegrityViolationException.class.getName()),
                trace);
        assertTrue(trace.contains("Suppressed: "), trace);
        assertTrue(trace.contains(WebClientResponseException.class.getName()), trace);
        assertTrue(trace.contains("at " + RedactionContractTest.class.getName()), trace);
        assertTrue(trace.contains("<#"), "frame hashes group repeated failures: " + trace);
        assertEquals("0123456789abcdef0123456789abcdef", text(json, "context.traceId"));
        assertEquals("7", text(json, "context.job.id"));
    }

    /**
     * Docker splits a log line at 16 KiB, and the journald driver stores the pieces as separate entries that no
     * collector can parse or correlate. A deep failure must stay one record, with its trace id, below that.
     */
    @Test
    void shouldKeepADeepFailureInOneParseableRecordBelowTheJournalSplit() throws IOException {
        Throwable failure = deepFailure(300);
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("traceId", "0123456789abcdef0123456789abcdef", "workspace.id", "42"));
            event.setMessage("Sync job failed");
            event.setThrowableProxy(new ThrowableProxy(failure));
        });

        assertTrue(line.getBytes(StandardCharsets.UTF_8).length < 16 * 1024, "record is " + line.length());
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals("0123456789abcdef0123456789abcdef", text(json, "context.traceId"));
        assertEquals("42", text(json, "context.workspace.id"));
        assertEquals(RuntimeException.class.getName(), text(json, "error.type"));
        String trace = text(json, "error.stack_trace");
        assertTrue(trace.length() <= 8192, "stack trace is " + trace.length());
        assertTrue(trace.startsWith("<#"), "the first frame line keeps its hash: " + trace.substring(0, 80));
        assertTrue(trace.contains("filtered"), "frames beyond the depth bound are elided");
        assertFalse(trace.contains("wrap 0"), "messages stay out of a bounded trace too");
    }

    private static Throwable deepFailure(int depth) {
        if (depth > 0) return deepFailure(depth - 1);
        Throwable failure = new IllegalStateException("root");
        for (int wrap = 0; wrap < 6; wrap++) {
            failure = new RuntimeException("wrap " + wrap, failure);
        }
        failure.addSuppressed(new IllegalArgumentException("suppressed"));
        return failure;
    }

    /**
     * Spring wraps a failure at startup in one bean creation exception per bean that depended on the failed one. With
     * the outermost exception first, the bound cut the failure that started it; the root cause must survive the bound.
     */
    @Test
    void shouldKeepTheRootCauseWhenWrappersFillTheBoundedTrace() throws IOException {
        Throwable failure = new SSLHandshakeException("synthetic-root-secret PKIX path building failed");
        failure.setStackTrace(frames("sun.security.ssl.TransportContext", "fatal", 8, 0));
        for (int layer = 0; layer < 8; layer++) {
            failure = new BeanCreationException(
                    "synthetic-bean-" + layer, "synthetic-wrapper-secret Bearer synthetic-wrapper-token", failure);
            failure.setStackTrace(
                    layer < 7
                            ? frames(
                                    "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory",
                                    "doCreateBean",
                                    40,
                                    1000 * (layer + 1))
                            : frames(
                                    "org.springframework.context.support.AbstractApplicationContext",
                                    "refresh",
                                    40,
                                    0));
        }
        StringWriter unbounded = new StringWriter();
        failure.printStackTrace(new PrintWriter(unbounded));
        assertTrue(unbounded.toString().length() > 2 * 8192, "the chain must overflow the bound");

        Throwable startup = failure;
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("traceId", "0123456789abcdef0123456789abcdef"));
            event.setMessage("Application run failed");
            event.setThrowableProxy(new ThrowableProxy(startup));
        });

        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(BeanCreationException.class.getName(), text(json, "error.type"));
        assertNull(node(json, "error.message"), line);
        assertEquals("0123456789abcdef0123456789abcdef", text(json, "context.traceId"));
        String trace = text(json, "error.stack_trace");
        assertTrue(trace.length() <= 8192, "stack trace is " + trace.length());
        int root = trace.indexOf(SSLHandshakeException.class.getName());
        assertTrue(root >= 0, "the root cause survives the bound: " + trace);
        assertTrue(root < trace.indexOf(BeanCreationException.class.getName()), trace);
        assertTrue(trace.contains("Wrapped by: "), trace);
        assertFalse(trace.contains("AbstractApplicationContext.refresh"), "the outermost frames are the ones cut");
        for (String privateText : List.of("synthetic-root-secret", "synthetic-wrapper", "synthetic-bean-", "PKIX")) {
            assertFalse(line.contains(privateText), privateText + " leaked: " + line);
        }
    }

    /** {@code count} distinct frames of one method, so no wrapper shares a frame with the one it wraps. */
    private static StackTraceElement[] frames(String className, String method, int count, int firstLine) {
        StackTraceElement[] frames = new StackTraceElement[count];
        String file = className.substring(className.lastIndexOf('.') + 1) + ".java";
        for (int frame = 0; frame < count; frame++) {
            frames[frame] = new StackTraceElement(className, method, file, firstLine + frame + 1);
        }
        return frames;
    }

    /** Before the context prefix, either pair beside these members made the encoder throw and drop the event. */
    @Test
    void shouldKeepContextPairsThatShareANameWithRecordMembers() throws IOException {
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("job.id", "7"));
            event.addKeyValuePair(new KeyValuePair("job.type", "review"));
            event.addKeyValuePair(new KeyValuePair("runtime.worker.enabled", true));
            event.addKeyValuePair(new KeyValuePair("error.type", "caller-supplied"));
            event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("x")));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals("server", text(json, "runtime.role"));
        assertTrue(node(json, "context.runtime.worker.enabled") != null, line);
        assertEquals("caller-supplied", text(json, "context.error.type"));
        assertEquals(IllegalStateException.class.getName(), text(json, "error.type"));
        assertEquals("7", text(json, "context.job.id"));
        assertEquals("review", text(json, "context.job.type"));
    }

    /** Boot's own context pairs refused a name that the MDC and the event both carry, and the event was lost. */
    @Test
    void shouldKeepAnEventThatNamesAnMdcKeyAgainWithTheEventsValue() throws IOException {
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("job.id", "from-mdc", "workspace.id", "42", "agent", "scope"));
            event.addKeyValuePair(new KeyValuePair("event.name", "agent.job.started"));
            event.addKeyValuePair(new KeyValuePair("job.id", "from-event"));
            event.addKeyValuePair(new KeyValuePair("workspace.id", 42L));
            event.addKeyValuePair(new KeyValuePair("agent.jobType", "scm.pull_request"));
            event.addKeyValuePair(new KeyValuePair("review.id", null));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals("agent.job.started", text(json, "context.event.name"));
        assertEquals("from-event", text(json, "context.job.id"));
        JsonNode workspace = node(json, "context.workspace.id");
        assertTrue(workspace != null && workspace.isNumber(), line);
        assertEquals("scm.pull_request", text(json, "context.agent.jobType"));
        assertNull(node(json, "context.review"), line);
    }

    @Test
    void shouldOmitTheContextObjectOfAnEventWithoutContext() throws IOException {
        assertNull(node(new ObjectMapper().readTree(encode(event -> event.setMessage("ready"))), "context"));
    }

    /**
     * Logging starts while SpringApplication ranks its {@code applicationInfo} source, the JAR manifest's version, above
     * application.yml; it moves that source to the end only after the environment-prepared listeners ran.
     */
    @Test
    void shouldNameTheDeployedReleaseNotTheBuildVersion() throws IOException {
        StandardEnvironment environment = shippedEnvironment("application.yml", -1);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "applicationInfo", Map.of("spring.application.version", "0.0.0-development")));
        environment.getPropertySources().addLast(new MapPropertySource("deployment", Map.of("APP_VERSION", "1.2.3")));

        JsonNode json = new ObjectMapper().readTree(encode(environment, event -> event.setMessage("ready")));

        assertEquals("1.2.3", text(json, "service.version"));
    }

    /** A local or specs run has no APP_VERSION: the record names the build version, as before. */
    @Test
    void shouldNameTheBuildVersionWithoutADeployedRelease() throws IOException {
        StandardEnvironment environment = shippedEnvironment("application.yml", -1);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource("applicationInfo", Map.of("spring.application.version", "9.9.9-build")));

        JsonNode json = new ObjectMapper().readTree(encode(environment, event -> event.setMessage("ready")));

        assertEquals("9.9.9-build", text(json, "service.version"));
    }

    @ParameterizedTest
    @CsvSource({
        "application.yml, -1, server",
        "application.yml, 1, all",
        "application-worker.yml, 0, worker",
        "application-webhook.yml, 0, webhook"
    })
    void shouldNameTheRuntimeRoleOfEveryProfile(String file, int document, String role) throws IOException {
        JsonNode json = new ObjectMapper()
                .readTree(encode(shippedEnvironment(file, document), event -> event.setMessage("ready")));
        assertEquals(role, text(json, "runtime.role"));
        assertEquals("Hephaestus", text(json, "service.name"));
        assertFalse(text(json, "service.version").isBlank());
        assertFalse(text(json, "service.environment").isBlank());
        assertEquals("INFO", text(json, "log.level"));
        assertEquals(RedactionContractTest.class.getName(), text(json, "log.logger"));
    }

    @ParameterizedTest
    @CsvSource({
        "Bearer synthetic-private-token, Bearer ***",
        "Basic c3ludGhldGljOnByaXZhdGU=, Basic ***",
        "glpat-synthetic-private, glpat-***",
        "github_pat_synthetic_private, github_pat_***",
        "ghp_synthetic_private, ghp_***",
        "gho_synthetic_private, gho_***",
        "ghu_synthetic_private, ghu_***",
        "ghs_synthetic_private, ghs_***",
        "ghr_synthetic_private, ghr_***",
        "xoxb-synthetic-private, xoxb-***",
        "xoxp-synthetic-private, xoxp-***",
        "xoxe-1-synthetic-private, xoxe-***",
        "xapp-1-synthetic-private, xapp-***",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.c3ludGhldGljLXNpZ25hdHVyZQ, ***",
        "https://bot:synthetic-private@git.example.test/repo.git, https://***@git.example.test/repo.git",
        "https://example.test?access_token=synthetic-private&keep=1, https://example.test?access_token=***&keep=1",
        "https://example.test?refresh_token=synthetic-private, https://example.test?refresh_token=***",
        "https://example.test?client_secret=synthetic-private, https://example.test?client_secret=***",
        "https://example.test?api_key=synthetic-private, https://example.test?api_key=***",
        "https://example.test?X-Amz-Credential=synthetic-private, https://example.test?X-Amz-Credential=***",
        "https://example.test?X-Amz-Signature=synthetic-private, https://example.test?X-Amz-Signature=***",
        "https://example.test?X-Amz-Security-Token=synthetic-private, https://example.test?X-Amz-Security-Token=***"
    })
    void shouldRedactCredentialValuesInMessagesAndFieldsAndOmitThemFromExceptions(String credential, String masked)
            throws IOException {
        String line = encode(event -> {
            event.setMessage(credential);
            event.addKeyValuePair(new KeyValuePair("diagnostic", credential));
            event.setThrowableProxy(
                    new ThrowableProxy(new IllegalStateException("outer", new IllegalArgumentException(credential))));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(masked, text(json, "message"));
        assertEquals(masked, text(json, "context.diagnostic"));
        assertTrue(text(json, "error.stack_trace").contains(IllegalArgumentException.class.getName()), line);
        assertFalse(line.contains(credential));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "secret",
                "token",
                "access_token",
                "accessToken",
                "refresh_token",
                "refreshToken",
                "authorization",
                "Authorization",
                "api_key",
                "apiKey",
                "client_secret",
                "clientSecret",
                "cookie",
                "Cookie",
                "set-cookie",
                "Set-Cookie",
                "x-api-key",
                "X-Api-Key",
                "PRIVATE-TOKEN",
                "private-token",
                "X-Gitlab-Token",
                "privateKey",
                "private_key"
            })
    void shouldMaskOpaqueSecretsByFieldNameWithoutGuessingTheirFormat(String field) throws IOException {
        String mdc = encode(event -> {
            event.setMessage("Credential excluded");
            event.setMDCPropertyMap(Map.of(field, "synthetic-private-mdc"));
        });
        String pair = encode(event -> {
            event.setMessage("Credential excluded");
            event.addKeyValuePair(new KeyValuePair(field, "synthetic-private-field"));
        });
        assertEquals("***", text(new ObjectMapper().readTree(mdc), "context." + field));
        assertEquals("***", text(new ObjectMapper().readTree(pair), "context." + field));
        assertFalse(mdc.contains("synthetic-private"));
        assertFalse(pair.contains("synthetic-private"));
    }

    @Test
    void shouldPreserveTypedFieldsCorrelationAndOneJsonRecordPerEvent() throws IOException {
        String message = "untrusted\r\n{\"log.level\":\"FORGED\"}\t\u001b[31m";
        String line = encode(event -> {
            event.setLevel(Level.ERROR);
            event.setMessage(message);
            event.setMDCPropertyMap(
                    Map.of("traceId", "0123456789abcdef0123456789abcdef", "spanId", "0123456789abcdef"));
            event.addKeyValuePair(new KeyValuePair("event.name", "logging.contract"));
            event.addKeyValuePair(new KeyValuePair("duration.ms", 123L));
            event.addKeyValuePair(new KeyValuePair("runtime.worker.enabled", true));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(message, text(json, "message"));
        assertEquals("ERROR", text(json, "log.level"));
        assertEquals("logging.contract", text(json, "context.event.name"));
        JsonNode duration = node(json, "context.duration.ms");
        assertTrue(duration != null && duration.isNumber(), line);
        JsonNode enabled = node(json, "context.runtime.worker.enabled");
        assertTrue(enabled != null && enabled.isBoolean() && enabled.asBoolean(), line);
        assertEquals("0123456789abcdef0123456789abcdef", text(json, "context.traceId"));
        assertEquals("0123456789abcdef", text(json, "context.spanId"));
        assertEquals(1, line.strip().lines().count());
        String record = line.strip();
        assertFalse(record.contains("\r"));
        assertFalse(record.contains("\n"));
        assertFalse(record.contains("\u001b"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"Bearer ghp_synthetic_private", "bearer xoxb-synthetic-private", "Bearer glpat-synthetic-private"
            })
    void shouldKeepOverlappingCredentialPatternsSafeRegardlessOfMaskOrdering(String credential) throws IOException {
        String line = encode(event -> event.setMessage(credential));
        assertFalse(line.contains("synthetic"));
        assertTrue(text(new ObjectMapper().readTree(line), "message").matches("(?i)Bearer \\*+"));
    }

    @Test
    void shouldLeaveContentWithoutACredentialFormatToTheCallSite() throws IOException {
        // The masks are not redaction: a call site that writes a person's address or text into its message leaks it.
        String line = encode(event -> event.setMessage("reviewer " + EMAIL + " wrote a private comment"));
        assertTrue(text(new ObjectMapper().readTree(line), "message").contains(EMAIL));
    }

    /** pgJDBC is a runtime-only dependency, so its exception is built reflectively from its public API. */
    private static SQLException postgresUniqueViolation(String message) throws ReflectiveOperationException {
        Class<?> state = Class.forName("org.postgresql.util.PSQLState");
        Object uniqueViolation = state.getField("UNIQUE_VIOLATION").get(null);
        return Class.forName("org.postgresql.util.PSQLException")
                .asSubclass(SQLException.class)
                .getConstructor(String.class, state)
                .newInstance(message, uniqueViolation);
    }

    private static String encode(Consumer<LoggingEvent> configure) throws IOException {
        return encode(shippedEnvironment("application.yml", -1), configure);
    }

    private static String encode(Environment environment, Consumer<LoggingEvent> configure) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(RedactionContractTest.class.getName());
        event.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
        event.setInstant(Instant.parse("2026-09-10T10:11:12.123Z"));
        configure.accept(event);
        if (event.getLevel() == null) event.setLevel(Level.INFO);
        return ShippedLogEncoder.encode(environment, event);
    }

    private static StandardEnvironment shippedEnvironment(String file, int document) {
        return ShippedLogEncoder.environment(file, document);
    }

    /** Boot's ECS writer nests dotted names; read either shape. */
    private static @Nullable JsonNode node(JsonNode json, String dottedName) {
        JsonNode flat = json.get(dottedName);
        if (flat != null) return flat;
        JsonNode nested = json.at("/" + dottedName.replace('.', '/'));
        return nested.isMissingNode() ? null : nested;
    }

    private static String text(JsonNode json, String dottedName) {
        JsonNode value = node(json, dottedName);
        if (value == null) throw new AssertionError(dottedName + " missing from " + json);
        return value.asString();
    }
}
