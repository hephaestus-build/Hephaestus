package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.config.SpringAsyncConfig;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("unit")
class CredentialReaderTest extends BaseUnitTest {

    private static final String KEY_A = "0123456789abcdef0123456789abcdef";
    private static final String KEY_B = "ABCDEFabcdef0123ABCDEFabcdef0123";
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private static final BearerToken TOKEN = new BearerToken("xoxb-stored-under-key-a", null);

    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private Connection connection;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T0974LHQU7K",
                new ConnectionConfig.SlackConfig("T0974LHQU7K", "hephaestus-test", null, null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", 55L);
    }

    /** Runs each record where the assertion can see it; the pool that runs it in production is wiring. */
    private CredentialReader readerWith(CredentialBundleConverter converter) {
        return readerWith(converter, new SyncTaskExecutor());
    }

    private CredentialReader readerWith(CredentialBundleConverter converter, TaskExecutor recorder) {
        return new CredentialReader(
                connectionRepository, converter, transactionManager, recorder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldHandTheRecordToItsExecutorRatherThanRunItInline() {
        connection.setCredentials(TOKEN, new CredentialBundleConverter(KEY_A, false));
        List<Runnable> handedOver = new ArrayList<>();

        assertThatThrownBy(() -> readerWith(new CredentialBundleConverter(KEY_B, false), handedOver::add)
                        .credentialsOf(connection))
                .isInstanceOf(CredentialUnreadableException.class);

        // The caller has its answer before anything is written, so a stalled database cannot delay it.
        verify(connectionRepository, never()).markCredentialsUnreadable(any(), any(), any(), any());
        handedOver.forEach(Runnable::run);
        verify(connectionRepository)
                .markCredentialsUnreadable(eq(55L), eq(7L), eq(connection.getCredentialsEncrypted()), eq(NOW));
    }

    /** The lane production writes the record on: one worker, a queue of 64, and a discard beyond that. */
    private static ThreadPoolTaskExecutor productionRecorder() {
        ThreadPoolTaskExecutor recorder =
                (ThreadPoolTaskExecutor) new SpringAsyncConfig().credentialReadabilityExecutor();
        recorder.initialize();
        return recorder;
    }

    @Test
    @Timeout(10)
    void shouldStillAnswerAtOnceWhenTheRecorderIsStalledAndItsQueueIsFull() throws InterruptedException {
        connection.setCredentials(TOKEN, new CredentialBundleConverter(KEY_A, false));
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    recording.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return 1;
                })
                .when(connectionRepository)
                .markCredentialsUnreadable(any(), any(), any(), any());
        ThreadPoolTaskExecutor recorder = productionRecorder();
        try {
            CredentialReader reader = readerWith(new CredentialBundleConverter(KEY_B, false), recorder);
            int queueCapacity = recorder.getQueueCapacity();

            // The first record occupies the only worker until released; the next fill the queue.
            assertThatThrownBy(() -> reader.credentialsOf(connection))
                    .isExactlyInstanceOf(CredentialUnreadableException.class);
            assertThat(recording.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < queueCapacity + 1; i++) {
                assertThatThrownBy(() -> reader.credentialsOf(connection))
                        .isExactlyInstanceOf(CredentialUnreadableException.class);
            }

            // The recorder is still stalled and its queue full: the answer neither waits nor changes.
            assertThatThrownBy(() -> reader.credentialsOf(connection))
                    .isExactlyInstanceOf(CredentialUnreadableException.class);
            assertThat(recorder.getThreadPoolExecutor().getQueue()).hasSize(queueCapacity);
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            recorder.shutdown();
        }
    }

    @Test
    void shouldStillAnswerWithTheUnreadableCredentialWhenTheRecorderHasShutDown() {
        connection.setCredentials(TOKEN, new CredentialBundleConverter(KEY_A, false));
        ThreadPoolTaskExecutor recorder = productionRecorder();
        recorder.shutdown();

        assertThatThrownBy(() -> readerWith(new CredentialBundleConverter(KEY_B, false), recorder)
                        .credentialsOf(connection))
                .isExactlyInstanceOf(CredentialUnreadableException.class);
        // A record submitted after shutdown is discarded, not run and not thrown.
        verify(connectionRepository, never()).markCredentialsUnreadable(any(), any(), any(), any());
    }

    @Test
    void shouldReturnTheCredentialWhenTheKeyReadsIt() {
        CredentialBundleConverter converter = new CredentialBundleConverter(KEY_A, false);
        connection.setCredentials(TOKEN, converter);

        assertThat(readerWith(converter).credentialsOf(connection)).contains(TOKEN);
        verify(connectionRepository, never()).markCredentialsReadable(any(), any(), any());
    }

    @Test
    void shouldMarkTheConnectionAndNameItWhenTheKeyCannotReadTheCredential() {
        connection.setCredentials(TOKEN, new CredentialBundleConverter(KEY_A, false));

        assertThatThrownBy(() ->
                        readerWith(new CredentialBundleConverter(KEY_B, false)).credentialsOf(connection))
                .isInstanceOf(CredentialUnreadableException.class)
                .satisfies(e -> {
                    CredentialUnreadableException unreadable = (CredentialUnreadableException) e;
                    assertThat(unreadable.connectionId()).isEqualTo(55L);
                    assertThat(unreadable.kind()).isEqualTo(IntegrationKind.SLACK);
                })
                .hasMessageContaining("connection 55")
                .hasMessageContaining("Replace the credential");
        // Bound to the ciphertext that failed, so a credential replaced since is left alone.
        verify(connectionRepository)
                .markCredentialsUnreadable(eq(55L), eq(7L), eq(connection.getCredentialsEncrypted()), eq(NOW));
    }

    @Test
    void shouldClearTheRecordWhenAMarkedCredentialReadsAgain() {
        CredentialBundleConverter converter = new CredentialBundleConverter(KEY_A, false);
        connection.setCredentials(TOKEN, converter);
        connection.markCredentialRotationFailed(NOW.minusSeconds(60));

        assertThat(readerWith(converter).credentialsOf(connection)).contains(TOKEN);
        verify(connectionRepository).markCredentialsReadable(eq(55L), eq(7L), eq(connection.getCredentialsEncrypted()));
    }

    @Test
    void shouldLetAMissingKeyVersionThroughWithoutMarking() {
        // Written under key version 2; a server that only holds version 1 has an instance fault,
        // not a credential to give up on.
        connection.setCredentials(TOKEN, new CredentialBundleConverter(KEY_A, 2, null, null, false));

        assertThatThrownBy(() ->
                        readerWith(new CredentialBundleConverter(KEY_A, false)).credentialsOf(connection))
                .isInstanceOf(MissingCredentialKeyException.class);
        verify(connectionRepository, never()).markCredentialsUnreadable(any(), any(), any(), any());
    }
}
