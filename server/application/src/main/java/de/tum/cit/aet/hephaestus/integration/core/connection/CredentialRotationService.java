package de.tum.cit.aet.hephaestus.integration.core.connection;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.security", name = "credential-rotation-enabled", havingValue = "true")
public class CredentialRotationService {

    private static final Logger log = LoggerFactory.getLogger(CredentialRotationService.class);

    private final ConnectionRepository connectionRepository;
    private final CredentialBundleConverter converter;
    private final SecurityProperties properties;
    private final Clock clock;
    private final Counter failureCounter;

    public CredentialRotationService(
            ConnectionRepository connectionRepository,
            CredentialBundleConverter converter,
            SecurityProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.converter = converter;
        this.properties = properties;
        this.clock = clock;
        this.failureCounter = Counter.builder(IntegrationCoreMetrics.CREDENTIAL_ROTATION_FAILURES)
                .description("Credentials newly quarantined because key rotation could not decrypt them")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hephaestus.security.credential-rotation-delay:PT1S}")
    @Transactional
    @WorkspaceAgnostic("Rotates integration credentials across all workspaces")
    public void rotateBatch() {
        List<Long> ids = connectionRepository.lockCredentialRotationBatch(
                converter.activeKeyVersion(), properties.credentialRotationBatchSize());
        if (ids.isEmpty()) {
            return;
        }
        List<Quarantined> quarantined = new ArrayList<>();
        for (Connection connection : connectionRepository.findAllById(ids)) {
            CredentialBundle credentials;
            // The decrypt call is inside the try and the re-encrypt below is not: a failure to
            // re-encrypt is an instance fault and must roll the batch back.
            try {
                credentials = connection.credentials(converter).orElseThrow();
            } catch (MissingCredentialKeyException unconfiguredKey) {
                // A key version this instance holds no key for is an instance fault as well; rolling
                // back leaves its rows unmarked for a later tick, once the key is configured.
                throw unconfiguredKey;
            } catch (EncryptionException undecryptable) {
                connection.markCredentialRotationFailed(clock.instant());
                quarantined.add(new Quarantined(
                        connection.getId(),
                        connection.getWorkspace().getId(),
                        connection.getKind(),
                        undecryptable.getMessage()));
                continue;
            }
            connection.reencryptCredentials(credentials, converter);
        }
        afterCommit(() -> report(ids.size() - quarantined.size(), quarantined));
    }

    private void report(int rotated, List<Quarantined> quarantined) {
        for (Quarantined row : quarantined) {
            failureCounter.increment();
            log.error(
                    "Credential key rotation quarantined undecryptable row: connectionId={}, workspaceId={}, kind={}, reason={}",
                    row.connectionId(),
                    row.workspaceId(),
                    row.kind(),
                    row.reason());
        }
        log.info(
                "Credential key rotation progress: rotated={}, quarantined={}, activeKeyVersion={}",
                rotated,
                quarantined.size(),
                converter.activeKeyVersion());
    }

    /** Carries only values, because the log line runs after the transaction closed its entities. */
    private record Quarantined(
            Long connectionId,
            Long workspaceId,
            IntegrationKind kind,
            @Nullable String reason) {}
}
