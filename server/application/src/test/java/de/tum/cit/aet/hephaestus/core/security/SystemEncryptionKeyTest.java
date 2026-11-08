package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemEncryptionKeys.systemKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeySealer;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SystemEncryptionKeyTest extends BaseUnitTest {

    private final Logger keyLogger = (Logger) LoggerFactory.getLogger(SystemEncryptionKey.class);
    // Logback reads the thread name lazily, from whichever thread asks, unless the event is prepared on append.
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureTheKeyLogger() {
        appender.start();
        keyLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheKeyLogger() {
        keyLogger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Only this class's own events, from this test's thread. Spring Boot's output capture reads the whole
     * JVM's stdout, so a test that runs concurrently and logs a WARN would fail an assertion about this class.
     */
    private List<ILoggingEvent> keyLogEvents() {
        String testThread = Thread.currentThread().getName();
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getThreadName().equals(testThread))
                .toList();
    }

    /**
     * The operator this message has to serve counted 32 characters and still got rejected. Saying only
     * "must be 32 bytes. Got: 64" sends them hunting for a length they already have, so the message
     * must state both counts and say which one is wrong and why.
     */
    @Test
    void rejectsKeyThatIsNot32BytesAndExplainsTheCharacterCountItGotInstead() {
        // 32 CHARS but multibyte ⇒ >32 bytes ⇒ must fail fast at construction, not at first encrypt.
        String multibyte = "ä".repeat(32); // 32 chars, 64 UTF-8 bytes
        assertThatThrownBy(() -> systemKey(multibyte, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte")
                .hasMessageContaining("Got 64 bytes from 32 characters")
                .hasMessageContaining("non-ASCII")
                .hasMessageContaining("openssl rand -base64 24 | cut -c1-32")
                .as("never echo the key material itself")
                .hasMessageNotContaining(multibyte);
    }

    /** An ASCII key of the wrong length is the ordinary case, and "32 characters" is true for it. */
    @Test
    void tellsAnAsciiKeyOfTheWrongLengthTheCountInCharacters() {
        assertThatThrownBy(() -> systemKey("tooshort", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Got 8 bytes from 8 characters")
                .hasMessageContaining("that is 32 characters");
    }

    @Test
    void shouldUseTheSameByteLengthValidationForJwtSealing() {
        var sealer = new JwtSigningKeySealer(systemKey("ä".repeat(16)));
        byte[] secret = {1, 2, 3};
        assertThat(sealer.unseal(sealer.seal(secret))).isEqualTo(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldTreatMissingArtifactKeysAsExpectedWithoutWeakeningProduction(String profile) {
        assertThat(systemKey(null, profile).key()).isNull();
        assertThat(keyLogEvents()).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
        assertThatThrownBy(() -> systemKey(null, profile, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required in production");
    }

    @Test
    void shouldIgnoreWarningsFromOtherLoggersAndThreadsWhileTheKeyIsBuilt() throws InterruptedException {
        var foreign = (Logger) LoggerFactory.getLogger("test.concurrent.neighbour");
        foreign.warn("java.util.concurrent.CompletionException: java.io.InterruptedIOException: executor rejected");
        Thread neighbour = new Thread(() -> keyLogger.warn("a concurrent test built a key without one"));
        neighbour.start();
        neighbour.join();

        assertThat(systemKey(null, "specs").key()).isNull();

        assertThat(keyLogEvents()).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
    }

    @Test
    void shouldStillWarnWhenRuntimeEncryptionIsUnavailable() {
        assertThat(systemKey(null, "dev").key()).isNull();
        assertThat(keyLogEvents())
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .asString()
                .contains("Skipped encryption configuration: reason=missing_key");
    }

    @Test
    void shouldShareValidatedStateWithHibernateCreatedConverters() {
        new ApplicationContextRunner()
                .withBean(
                        SecurityProperties.class,
                        () -> new SecurityProperties(
                                "0123456789abcdef0123456789abcdef", null, 1, null, null, false, 100))
                .withUserConfiguration(
                        SystemEncryptionKey.class, EncryptedStringConverter.class, JwtSigningKeySealer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var springConverter = context.getBean(EncryptedStringConverter.class);
                    // SpringBeanContainer's JPA-compliant path uses createBean rather than getBean.
                    var hibernateConverter =
                            context.getAutowireCapableBeanFactory().createBean(EncryptedStringConverter.class);
                    assertThat(hibernateConverter).isNotSameAs(springConverter);
                    assertThat(hibernateConverter.convertToEntityAttribute(
                                    springConverter.convertToDatabaseColumn("secret")))
                            .isEqualTo("secret");
                    var sealer = context.getBean(JwtSigningKeySealer.class);
                    byte[] secret = {1, 2, 3};
                    assertThat(sealer.unseal(sealer.seal(secret))).isEqualTo(secret);
                    assertThat(keyLogEvents())
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .filteredOn(message -> message.contains("Enabled system encryption"))
                            .hasSize(1);
                });
    }

    @Test
    void shouldRefuseSecretWritesWithoutSpringKeyConfiguration() {
        var converter = new EncryptedStringConverter();
        assertThatThrownBy(() -> converter.convertToDatabaseColumn("schema-only"))
                .isInstanceOf(EncryptionException.class);
    }
}
