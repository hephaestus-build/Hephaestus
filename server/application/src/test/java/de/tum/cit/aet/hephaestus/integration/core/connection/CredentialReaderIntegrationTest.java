package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The record of an unreadable credential, against the real statements and row locks. */
class CredentialReaderIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";
    private static final BearerToken TOKEN = new BearerToken("glpat-unreadable", null);

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private CredentialReader credentialReader;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldRecordTheCiphertextItCannotReadAndClearItWhenItReadsAgain() {
        User owner = persistUser("reader-owner-" + System.nanoTime());
        Workspace workspace =
                createWorkspace("reader-ws-" + System.nanoTime(), "Reader Test", "reader-org", AccountType.ORG, owner);
        Connection connection = writtenUnderAnotherKey(workspace, "unreadable");
        long version = connection.getVersion();

        assertThatThrownBy(() -> credentialReader.credentialsOf(connection))
                .isInstanceOf(CredentialUnreadableException.class);

        Connection marked = awaitMark(connection.getId());
        assertThat(marked.getCredentialsRotationFailedAt()).isNotNull();
        assertThat(marked.getVersion()).isEqualTo(version + 1);

        Integer cleared = transactionTemplate.execute(__ -> connectionRepository.markCredentialsReadable(
                marked.getId(), workspace.getId(), Objects.requireNonNull(marked.getCredentialsEncrypted())));
        assertThat(cleared).isEqualTo(1);
        assertThat(connectionRepository.findById(marked.getId()).orElseThrow().getCredentialsRotationFailedAt())
                .isNull();
    }

    @Test
    void shouldSkipARowAnotherTransactionHoldsLockedInsteadOfWaiting() throws InterruptedException {
        User owner = persistUser("reader-owner-" + System.nanoTime());
        Workspace workspace =
                createWorkspace("reader-ws-" + System.nanoTime(), "Reader Test", "reader-org", AccountType.ORG, owner);
        Connection connection = writtenUnderAnotherKey(workspace, "locked");
        long id = connection.getId();
        long workspaceId = workspace.getId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(__ -> {
            connectionRepository.acquireLifecycleLock(id, workspaceId);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            Integer marked = transactionTemplate.execute(__ -> connectionRepository.markCredentialsUnreadable(
                    id, workspaceId, Objects.requireNonNull(connection.getCredentialsEncrypted()), Instant.now()));
            assertThat(marked).isZero();
            // The holder keeps the lock for ten seconds; a mark that waited for it would not be back yet.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertThat(connectionRepository.findById(id).orElseThrow().getCredentialsRotationFailedAt())
                .isNull();
    }

    /** The record is written by the reader's own worker, so the row is read until it carries it. */
    private Connection awaitMark(long id) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (true) {
            Connection row = connectionRepository.findById(id).orElseThrow();
            if (row.getCredentialsRotationFailedAt() != null || Instant.now().isAfter(deadline)) {
                return row;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return row;
            }
        }
    }

    private Connection writtenUnderAnotherKey(Workspace workspace, String instanceKey) {
        Connection connection = connectionRepository.save(new Connection(
                workspace,
                IntegrationKind.GITHUB,
                instanceKey,
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of())));
        connection.setCredentials(TOKEN, new CredentialBundleConverter(OTHER_KEY, false));
        return connectionRepository.saveAndFlush(connection);
    }
}
