package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.config.CredentialReadabilityExecutor;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way a read path turns a connection's stored credential into plaintext. A credential the
 * configured keys cannot read is recorded on the connection and surfaces as
 * {@link CredentialUnreadableException} rather than as an unexplained server error; a credential
 * that reads again clears the record. A key version this server holds no key for is an instance
 * fault and passes through untouched, as it does for the rotation job.
 *
 * <p>Every decryption failure other than a missing key is treated the same way: a truncated or
 * unsupported ciphertext, an authentication failure under the configured keys and an undeserializable
 * bundle are each a property of the stored row against this server, and only rewriting the row or
 * restoring the key it was written with changes any of them.
 *
 * <p>The record is written by a conditional update bound to the exact ciphertext that failed, so a
 * credential replaced between the failed read and the write is never marked, and it is handed to
 * {@link CredentialReadabilityExecutor} rather than run on the caller's thread, so a burst of failing
 * reads never holds two pooled connections each and a stalled database never delays the answer. It
 * skips a row another transaction holds locked rather than waiting, and a failure to write it is
 * logged; neither ever replaces the answer.
 */
@Service
public class CredentialReader {

    private static final Logger log = LoggerFactory.getLogger(CredentialReader.class);

    private final ConnectionRepository connectionRepository;
    private final CredentialBundleConverter converter;
    private final TransactionTemplate markTransaction;
    private final TaskExecutor recorder;
    private final Clock clock;

    public CredentialReader(
            ConnectionRepository connectionRepository,
            CredentialBundleConverter converter,
            PlatformTransactionManager transactionManager,
            @Qualifier(CredentialReadabilityExecutor.BEAN_NAME) TaskExecutor recorder,
            Clock clock) {
        this.connectionRepository = connectionRepository;
        this.converter = converter;
        this.markTransaction = new TransactionTemplate(transactionManager);
        this.markTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recorder = recorder;
        this.clock = clock;
    }

    /**
     * The credential for every use but provider teardown. A disconnected connection keeps its credential only
     * until {@link ProviderTeardown} revokes it at the provider, so for that connection this is empty.
     */
    public Optional<CredentialBundle> credentialsOf(Connection connection) {
        if (connection.getState() == IntegrationState.UNINSTALLED) {
            return Optional.empty();
        }
        return read(connection);
    }

    /** The credential a disconnect retained, read only to remove what it controls at the provider. */
    Optional<CredentialBundle> retainedForTeardown(Connection connection) {
        return read(connection);
    }

    private Optional<CredentialBundle> read(Connection connection) {
        byte[] ciphertext = connection.getCredentialsEncrypted();
        try {
            Optional<CredentialBundle> credentials = connection.credentials(converter);
            if (ciphertext != null && connection.getCredentialsRotationFailedAt() != null) {
                Long id = connection.getId();
                Long workspaceId = connection.getWorkspace().getId();
                record(() -> connectionRepository.markCredentialsReadable(id, workspaceId, ciphertext));
            }
            return credentials;
        } catch (MissingCredentialKeyException unconfiguredKey) {
            throw unconfiguredKey;
        } catch (EncryptionException undecryptable) {
            Long id = connection.getId();
            if (id != null && ciphertext != null) {
                Long workspaceId = connection.getWorkspace().getId();
                Instant now = clock.instant();
                record(() -> connectionRepository.markCredentialsUnreadable(id, workspaceId, ciphertext, now));
            }
            throw new CredentialUnreadableException(id == null ? -1 : id, connection.getKind(), undecryptable);
        }
    }

    /** Runs the update in a transaction of its own, and logs rather than replaces the caller's answer. */
    private void record(Runnable update) {
        recorder.execute(() -> {
            try {
                markTransaction.executeWithoutResult(status -> update.run());
            } catch (RuntimeException e) {
                log.warn("Could not record the readability of a stored credential", e);
            }
        });
    }
}
