package de.tum.cit.aet.hephaestus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class RedactionContractTest {

    @Test
    void shouldRedactKnownCredentialFormatsFromConfiguredEncoder() throws JoranException {
        String line = encode(event -> {
            event.setMDCPropertyMap(Map.of("workspace.id", "42", "token", "structured-secret", "level", "FORGED"));
            event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("Bearer exception-secret")));
            event.setMessage("credentials: Bearer eyJhbGciOi.test glpat-secret_123 "
                    + "https://example.test/cb?token=url-secret&x=1 github_pat_secret_123");
        });
        JsonNode json = new ObjectMapper().readTree(line);
        String message = json.get("message").asString();
        assertTrue(message.contains("Bearer ***"), line);
        assertTrue(message.contains("glpat-***"));
        assertTrue(message.contains("?token=***&x=1"));
        assertTrue(message.contains("github_pat_***"));
        assertFalse(line.contains("eyJhbGciOi"));
        assertFalse(line.contains("secret_123"));
        assertFalse(line.contains("structured-secret"));
        assertFalse(line.contains("exception-secret"));
        assertTrue(json.has("timestamp"));
        assertTrue(json.has("logger"));
        assertTrue(json.has("stacktrace"));
        assertEquals("INFO", json.get("level").asString());
        assertEquals("42", json.get("mdc").get("workspace.id").asString());
        assertEquals("***", json.get("mdc").get("token").asString());
        assertEquals("FORGED", json.get("mdc").get("level").asString());
        assertTrue(line.endsWith(System.lineSeparator()));
        assertFalse(line.substring(0, line.length() - System.lineSeparator().length())
                .contains(System.lineSeparator()));
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
        "https://example.test?access_token=synthetic-private&keep=1, https://example.test?access_token=***&keep=1",
        "https://example.test?refresh_token=synthetic-private, https://example.test?refresh_token=***",
        "https://example.test?client_secret=synthetic-private, https://example.test?client_secret=***",
        "https://example.test?api_key=synthetic-private, https://example.test?api_key=***",
        "https://example.test?X-Amz-Credential=synthetic-private, https://example.test?X-Amz-Credential=***",
        "https://example.test?X-Amz-Signature=synthetic-private, https://example.test?X-Amz-Signature=***",
        "https://example.test?X-Amz-Security-Token=synthetic-private, https://example.test?X-Amz-Security-Token=***"
    })
    void shouldRedactCredentialValuesInMessagesFieldsAndExceptionCauses(String credential, String masked)
            throws JoranException {
        String line = encode(event -> {
            event.setMessage(credential);
            event.setMDCPropertyMap(Map.of());
            event.addKeyValuePair(new KeyValuePair("diagnostic", credential));
            event.setThrowableProxy(
                    new ThrowableProxy(new IllegalStateException("outer", new IllegalArgumentException(credential))));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(masked, json.get("message").asString());
        assertEquals(masked, json.get("diagnostic").asString());
        assertTrue(json.get("stacktrace").asString().contains(masked));
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
                "X-Api-Key"
            })
    void shouldMaskOpaqueSecretsByFieldNameWithoutGuessingTheirFormat(String field) throws JoranException {
        String line = encode(event -> {
            event.setMessage("Credential excluded");
            event.setMDCPropertyMap(Map.of(field, "synthetic-private-mdc"));
            event.addKeyValuePair(new KeyValuePair(field, "synthetic-private-field"));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals("***", json.get(field).asString());
        assertEquals("***", json.get("mdc").get(field).asString());
        assertFalse(line.contains("synthetic-private"));
    }

    @Test
    void shouldPreserveTypedFieldsCorrelationAndOneJsonRecordPerEvent() throws JoranException {
        String message = "untrusted\r\n{\"level\":\"FORGED\"}\t\u001b[31m";
        String line = encode(event -> {
            event.setLevel(Level.ERROR);
            event.setMessage(message);
            event.setMDCPropertyMap(Map.of(
                    "traceId", "0123456789abcdef0123456789abcdef", "spanId", "0123456789abcdef", "level", "FORGED"));
            event.addKeyValuePair(new KeyValuePair("event.name", "logging.contract"));
            event.addKeyValuePair(new KeyValuePair("duration.ms", 123L));
            event.addKeyValuePair(new KeyValuePair("runtime.worker.enabled", true));
        });
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals(message, json.get("message").asString());
        assertEquals("ERROR", json.get("level").asString());
        assertEquals("2026-09-10T10:11:12.123Z", json.get("timestamp").asString());
        assertEquals("logging.contract", json.get("event.name").asString());
        assertTrue(json.get("duration.ms").isNumber());
        assertEquals(123L, json.get("duration.ms").asLong());
        assertTrue(json.get("runtime.worker.enabled").isBoolean());
        assertTrue(json.get("runtime.worker.enabled").asBoolean());
        assertEquals(
                "0123456789abcdef0123456789abcdef",
                json.get("mdc").get("traceId").asString());
        assertEquals("0123456789abcdef", json.get("mdc").get("spanId").asString());
        assertEquals(1, line.lines().count());
        assertTrue(line.endsWith(System.lineSeparator()));
        String record = line.substring(0, line.length() - System.lineSeparator().length());
        assertFalse(record.contains("\r"));
        assertFalse(record.contains("\n"));
        assertFalse(record.contains("\u001b"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"Bearer ghp_synthetic_private", "bearer xoxb-synthetic-private", "Bearer glpat-synthetic-private"
            })
    void shouldKeepOverlappingCredentialPatternsSafeRegardlessOfMaskOrdering(String credential) throws JoranException {
        String line = encode(event -> {
            event.setMessage(credential);
            event.setMDCPropertyMap(Map.of());
        });
        assertFalse(line.contains("synthetic"));
        String message = new ObjectMapper().readTree(line).get("message").asString();
        assertTrue(message.matches("(?i)Bearer \\*+"));
    }

    private static String encode(Consumer<LoggingEvent> configure) throws JoranException {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(((LoggerContext) LoggerFactory.getILoggerFactory()).getMDCAdapter());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(
                    Objects.requireNonNull(RedactionContractTest.class.getResource("/logback-spring.xml")));
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.INFO, root.getLevel());
            ConsoleAppender<ILoggingEvent> appender = (ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName(RedactionContractTest.class.getName());
            event.setLoggerContext(context);
            event.setInstant(Instant.parse("2026-09-10T10:11:12.123Z"));
            configure.accept(event);
            if (event.getLevel() == null) event.setLevel(Level.INFO);
            return new String(appender.getEncoder().encode(event), StandardCharsets.UTF_8);
        } finally {
            context.stop();
        }
    }
}
