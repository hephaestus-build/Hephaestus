package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecretsWithPrior;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class SystemSecretsTest extends BaseUnitTest {

    private final Logger keyLogger = (Logger) LoggerFactory.getLogger(SystemSecrets.class);
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
        List<ILoggingEvent> snapshot;
        // AppenderBase.doAppend writes the list while it holds the appender's monitor.
        synchronized (appender) {
            snapshot = List.copyOf(appender.list);
        }
        return snapshot.stream()
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
        assertThatThrownBy(() -> systemSecrets(multibyte, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte")
                .hasMessageContaining("Got 64 bytes from 32 characters")
                .hasMessageContaining("non-ASCII")
                .hasMessageContaining("openssl rand -hex 16")
                .as("never echo the key material itself")
                .hasMessageNotContaining(multibyte);
    }

    /** An ASCII key of the wrong length is the ordinary case, and "32 characters" is true for it. */
    @Test
    void tellsAnAsciiKeyOfTheWrongLengthTheCountInCharacters() {
        assertThatThrownBy(() -> systemSecrets("tooshort", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Got 8 bytes from 8 characters")
                .hasMessageContaining("that is 32 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldTreatMissingArtifactKeysAsExpectedWithoutWeakeningProduction(String profile) {
        assertThat(systemSecrets(null, profile).isEnabled()).isFalse();
        assertThat(keyLogEvents()).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
        assertThatThrownBy(() -> systemSecrets(null, profile, "prod"))
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

        assertThat(systemSecrets(null, "specs").isEnabled()).isFalse();

        assertThat(keyLogEvents()).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
    }

    @Test
    void shouldStillWarnWhenRuntimeEncryptionIsUnavailable() {
        assertThat(systemSecrets(null, "dev").isEnabled()).isFalse();
        assertThat(keyLogEvents())
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .asString()
                .contains("Skipped encryption configuration: reason=missing_key");
    }

    private static final String OLD_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NEW_KEY = "fedcba9876543210fedcba9876543210";
    private static final SecretBinding ROW = SecretBinding.of("llm_connection.api_key", "primary");

    @Test
    void shouldOpenAValueOfThePriorKeyAfterTheNewKeyBecameActive() {
        SystemSecrets before = systemSecrets(OLD_KEY, "test");
        byte[] sealed = before.seal(bytes("provider-key"), ROW);

        SystemSecrets during = systemSecretsWithPrior(NEW_KEY, OLD_KEY, "test");

        assertThat(during.open(sealed, ROW)).isEqualTo(bytes("provider-key"));
        assertThat(during.isSealedWithActiveKey(sealed)).isFalse();
        byte[] resealed = during.seal(during.open(sealed, ROW), ROW);
        assertThat(during.isSealedWithActiveKey(resealed)).isTrue();
        assertThat(systemSecrets(NEW_KEY, "test").open(resealed, ROW)).isEqualTo(bytes("provider-key"));
    }

    @Test
    void shouldOpenAValueOfTheNewKeyWhileTheNewKeyIsOnlyThePriorKey() {
        // The first step of a rotation: a server that still seals with the old key reads what a switched server wrote.
        byte[] sealedByNew = systemSecretsWithPrior(NEW_KEY, OLD_KEY, "test").seal(bytes("v"), ROW);

        assertThat(systemSecretsWithPrior(OLD_KEY, NEW_KEY, "test").open(sealedByNew, ROW))
                .isEqualTo(bytes("v"));
    }

    @Test
    void shouldNotOpenAValueMovedToAnotherRowOrColumn() {
        SystemSecrets secrets = systemSecrets(OLD_KEY, "test");
        byte[] sealed = secrets.seal(bytes("provider-key"), ROW);

        assertThatThrownBy(() -> secrets.open(sealed, SecretBinding.of("llm_connection.api_key", "other")))
                .isInstanceOf(EncryptionException.class);
        assertThatThrownBy(() -> secrets.open(sealed, SecretBinding.of("login_provider.client_secret", "primary")))
                .isInstanceOf(EncryptionException.class);
        assertThatThrownBy(() ->
                        secrets.open(sealed, SecretBinding.of("workspace_llm_connection.api_key", "1", "primary")))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void shouldNotOpenAValueWhoseKeyIdWasChanged() {
        SystemSecrets secrets = systemSecretsWithPrior(NEW_KEY, OLD_KEY, "test");
        byte[] sealed = secrets.seal(bytes("provider-key"), ROW);
        byte[] underPrior = systemSecrets(OLD_KEY, "test").seal(bytes("other"), ROW);
        System.arraycopy(underPrior, 1, sealed, 1, Integer.BYTES);

        assertThatThrownBy(() -> secrets.open(sealed, ROW)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void shouldNameTheKeyIdWhenNoConfiguredKeyMatchesIt() {
        SystemSecrets newKey = systemSecrets(NEW_KEY, "test");
        byte[] sealed = newKey.seal(bytes("v"), ROW);

        assertThatThrownBy(() -> systemSecrets(OLD_KEY, "test").open(sealed, ROW))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining(newKey.activeKeyId());
    }

    @Test
    void shouldSealTextAsBase64WithoutThePlaintext() {
        SystemSecrets secrets = systemSecrets(OLD_KEY, "test");
        String sealed = secrets.sealText("provider-key", ROW);

        assertThat(sealed).doesNotContain("provider-key");
        assertThat(secrets.openText(sealed, ROW)).isEqualTo("provider-key");
    }

    @Test
    void shouldRefuseSecretWritesWithoutAKey() {
        assertThatThrownBy(() -> systemSecrets(null, "test").seal(bytes("v"), ROW))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void shouldRefuseAPriorKeyWithoutAnActiveKey() {
        assertThatThrownBy(() -> TestSystemSecrets.properties(null, OLD_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseTheSameKeyAsActiveAndPrior() {
        assertThatThrownBy(() -> TestSystemSecrets.properties(NEW_KEY, NEW_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDescribeAValueOfAnotherKeyByBothKeyIdsAndNoKey() {
        SystemSecrets sealedWith = systemSecrets(OLD_KEY, "test");
        byte[] sealed = sealedWith.seal(bytes("v"), ROW);
        SystemSecrets holder = systemSecretsWithPrior(NEW_KEY, "abcdefabcdefabcdefabcdefabcdefab", "test");

        assertThat(holder.describeMismatch(sealed))
                .contains("sealed by the system key with id " + sealedWith.activeKeyId())
                .contains("the active key " + holder.activeKeyId())
                .contains("the prior key "
                        + systemSecrets("abcdefabcdefabcdefabcdefabcdefab", "test")
                                .activeKeyId())
                .doesNotContain(OLD_KEY)
                .doesNotContain(NEW_KEY);
    }

    @Test
    void shouldDescribeAValueOfTheConfiguredKeyThatDoesNotOpenAsDamagedOrMoved() {
        SystemSecrets secrets = systemSecrets(OLD_KEY, "test");
        byte[] sealed = secrets.seal(bytes("v"), ROW);
        sealed[sealed.length - 1] ^= 1;

        assertThat(secrets.describeMismatch(sealed)).contains("damaged").contains(secrets.activeKeyId());
    }

    @Test
    void shouldDescribeAValueThatIsNoSealedSecretOrAServerWithoutAKey() {
        byte[] sealed = systemSecrets(OLD_KEY, "test").seal(bytes("v"), ROW);

        assertThat(systemSecrets(OLD_KEY, "test").describeMismatch(new byte[] {1, 2, 3}))
                .isEqualTo("the value is not a sealed system secret");
        assertThat(systemSecrets(null, "test").describeMismatch(sealed)).contains("holds no system key");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
