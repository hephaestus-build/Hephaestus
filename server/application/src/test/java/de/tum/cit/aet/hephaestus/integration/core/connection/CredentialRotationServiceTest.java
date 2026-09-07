package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class CredentialRotationServiceTest extends BaseUnitTest {

    private static final String OLD_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NEW_KEY = "ABCDEFabcdef0123ABCDEFabcdef0123";
    private static final BearerToken TOKEN = new BearerToken("glpat-rotate-me", null);
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private ConnectionRepository connectionRepository;

    private CredentialBundleConverter oldConverter;
    private CredentialBundleConverter rotatingConverter;
    private CredentialRotationService service;
    private Workspace workspace;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        oldConverter = new CredentialBundleConverter(OLD_KEY, "dev");
        rotatingConverter = new CredentialBundleConverter(NEW_KEY, 2, OLD_KEY, 1, "dev");
        SecurityProperties properties = new SecurityProperties(null, NEW_KEY, 2, OLD_KEY, 1, true, 25);
        meterRegistry = new SimpleMeterRegistry();
        service = new CredentialRotationService(
                connectionRepository, rotatingConverter, properties, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
        workspace = new Workspace();
        workspace.setId(7L);
    }

    @Test
    void shouldReEncryptUnderTheActiveKeyAndStampItsVersionWhenABatchIsLocked() {
        Connection stale = connection(55L);
        stale.setCredentials(TOKEN, oldConverter);
        byte[] staleBlob = stale.getCredentialsEncrypted();
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(55L));
        when(connectionRepository.findAllById(List.of(55L))).thenReturn(List.of(stale));

        service.rotateBatch();

        assertThat(stale.getCredentialsKeyVersion()).isEqualTo(2);
        assertThat(stale.getCredentialsEncrypted()).isNotEqualTo(staleBlob);
        assertThat(stale.credentials(rotatingConverter)).contains(TOKEN);
        // The rewritten blob must not need the prior key any more.
        CredentialBundleConverter activeOnly = new CredentialBundleConverter(NEW_KEY, 2, null, null, "dev");
        assertThat(stale.credentials(activeOnly)).contains(TOKEN);
    }

    @Test
    void shouldDecryptThroughThePriorKeyWhenARowPredatesKeyVersionStamping() {
        Connection legacy = connection(56L);
        legacy.setCredentials(TOKEN, oldConverter);
        setKeyVersion(legacy, null); // Rows written before the credentials_key_version column exist as NULL.
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(56L));
        when(connectionRepository.findAllById(List.of(56L))).thenReturn(List.of(legacy));

        service.rotateBatch();

        assertThat(legacy.getCredentialsKeyVersion()).isEqualTo(2);
        assertThat(legacy.credentials(rotatingConverter)).contains(TOKEN);
    }

    @Test
    void shouldNotLoadAnyRowWhenEveryCredentialAlreadyUsesTheActiveKey() {
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of());

        service.rotateBatch();

        verify(connectionRepository, never()).findAllById(ArgumentMatchers.anyIterable());
    }

    @Test
    void shouldQuarantineAnUndecryptableRowAndContinueTheBatch() {
        Connection orphaned = connection(57L);
        orphaned.setCredentials(TOKEN, oldConverter);
        byte[] corrupted =
                Objects.requireNonNull(orphaned.getCredentialsEncrypted()).clone();
        corrupted[corrupted.length - 1] ^= 1;
        ReflectionTestUtils.setField(orphaned, "credentialsEncrypted", corrupted);
        Connection stale = connection(58L);
        stale.setCredentials(TOKEN, oldConverter);
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(57L, 58L));
        when(connectionRepository.findAllById(List.of(57L, 58L))).thenReturn(List.of(orphaned, stale));

        service.rotateBatch();

        assertThat(orphaned.getCredentialsEncrypted()).isEqualTo(corrupted);
        assertThat(orphaned.getCredentialsKeyVersion()).isEqualTo(1);
        assertThat(orphaned.getCredentialsRotationFailedAt()).isEqualTo(NOW);
        assertThat(stale.getCredentialsKeyVersion()).isEqualTo(2);
        assertThat(meterRegistry
                        .counter(IntegrationCoreMetrics.CREDENTIAL_ROTATION_FAILURES)
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldQuarantineTheOnlyRowInTheBatchWhenItCannotBeDecrypted() {
        Connection orphaned = connection(61L);
        orphaned.setCredentials(TOKEN, oldConverter);
        byte[] corrupted =
                Objects.requireNonNull(orphaned.getCredentialsEncrypted()).clone();
        corrupted[corrupted.length - 1] ^= 1;
        ReflectionTestUtils.setField(orphaned, "credentialsEncrypted", corrupted);
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(61L));
        when(connectionRepository.findAllById(List.of(61L))).thenReturn(List.of(orphaned));

        service.rotateBatch();

        assertThat(orphaned.getCredentialsRotationFailedAt()).isEqualTo(NOW);
        assertThat(meterRegistry
                        .counter(IntegrationCoreMetrics.CREDENTIAL_ROTATION_FAILURES)
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldAbortTheBatchWhenARowsKeyVersionHasNoConfiguredKey() {
        Connection orphaned = connection(59L);
        orphaned.setCredentials(TOKEN, oldConverter);
        setKeyVersion(orphaned, 99);
        byte[] blob = orphaned.getCredentialsEncrypted();
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(59L));
        when(connectionRepository.findAllById(List.of(59L))).thenReturn(List.of(orphaned));

        assertThatThrownBy(service::rotateBatch).isInstanceOf(MissingCredentialKeyException.class);

        assertThat(orphaned.getCredentialsRotationFailedAt()).isNull();
        assertThat(orphaned.getCredentialsEncrypted()).isEqualTo(blob);
        assertThat(orphaned.getCredentialsKeyVersion()).isEqualTo(99);
        assertThat(meterRegistry
                        .counter(IntegrationCoreMetrics.CREDENTIAL_ROTATION_FAILURES)
                        .count())
                .isZero();
    }

    @Test
    void shouldAbortInsteadOfQuarantiningWhenReEncryptionFails() {
        Connection stale = connection(60L);
        stale.setCredentials(TOKEN, oldConverter);
        CredentialBundleConverter failingConverter = spy(rotatingConverter);
        doThrow(new EncryptionException("Credential encryption failed"))
                .when(failingConverter)
                .encrypt(any(), any());
        SecurityProperties properties = new SecurityProperties(null, NEW_KEY, 2, OLD_KEY, 1, true, 25);
        service = new CredentialRotationService(
                connectionRepository, failingConverter, properties, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
        when(connectionRepository.lockCredentialRotationBatch(2, 25)).thenReturn(List.of(60L));
        when(connectionRepository.findAllById(List.of(60L))).thenReturn(List.of(stale));

        assertThatThrownBy(service::rotateBatch).isInstanceOf(EncryptionException.class);
        assertThat(stale.getCredentialsRotationFailedAt()).isNull();
        assertThat(meterRegistry
                        .counter(IntegrationCoreMetrics.CREDENTIAL_ROTATION_FAILURES)
                        .count())
                .isZero();
    }

    private Connection connection(long id) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", id);
        return connection;
    }

    private static void setKeyVersion(Connection connection, @Nullable Integer version) {
        ReflectionTestUtils.setField(connection, "credentialsKeyVersion", version);
    }
}
