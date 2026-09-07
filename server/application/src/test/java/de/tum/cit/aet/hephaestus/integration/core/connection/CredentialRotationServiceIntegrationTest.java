package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class CredentialRotationServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String OLD_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NEW_KEY = "ABCDEFabcdef0123ABCDEFabcdef0123";
    private static final BearerToken TOKEN = new BearerToken("glpat-rotate-me", null);
    private static final Instant QUARANTINED_AT = Instant.parse("2026-09-03T12:00:00Z");

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldCommitHealthyRowsAndExcludeCorruptRowsFromLaterBatches() {
        User owner = persistUser("rotation-owner-" + System.nanoTime());
        Workspace workspace = createWorkspace(
                "rotation-ws-" + System.nanoTime(), "Rotation Test", "rotation-org", AccountType.ORG, owner);
        CredentialBundleConverter oldConverter = new CredentialBundleConverter(OLD_KEY, "test");
        CredentialBundleConverter rotatingConverter = new CredentialBundleConverter(NEW_KEY, 2, OLD_KEY, 1, "test");

        Connection corrupt = connectionRepository.save(connection(workspace, "corrupt"));
        corrupt.setCredentials(TOKEN, oldConverter);
        byte[] corrupted =
                Objects.requireNonNull(corrupt.getCredentialsEncrypted()).clone();
        corrupted[corrupted.length - 1] ^= 1;
        ReflectionTestUtils.setField(corrupt, "credentialsEncrypted", corrupted);
        corrupt = connectionRepository.save(corrupt);
        Connection healthy = connectionRepository.save(connection(workspace, "healthy"));
        healthy.setCredentials(TOKEN, oldConverter);
        healthy = connectionRepository.saveAndFlush(healthy);

        SecurityProperties properties = new SecurityProperties(null, NEW_KEY, 2, OLD_KEY, 1, true, 25);
        CredentialRotationService service = new CredentialRotationService(
                connectionRepository,
                rotatingConverter,
                properties,
                Clock.fixed(QUARANTINED_AT, ZoneOffset.UTC),
                new SimpleMeterRegistry());

        transactionTemplate.executeWithoutResult(__ -> service.rotateBatch());

        Connection persistedCorrupt =
                connectionRepository.findById(corrupt.getId()).orElseThrow();
        Connection persistedHealthy =
                connectionRepository.findById(healthy.getId()).orElseThrow();
        assertThat(persistedCorrupt.getCredentialsEncrypted()).isEqualTo(corrupted);
        assertThat(persistedCorrupt.getCredentialsKeyVersion()).isEqualTo(1);
        assertThat(persistedCorrupt.getCredentialsRotationFailedAt()).isEqualTo(QUARANTINED_AT);
        assertThat(persistedHealthy.getCredentialsKeyVersion()).isEqualTo(2);

        Connection pending = connectionRepository.save(connection(workspace, "pending"));
        pending.setCredentials(TOKEN, oldConverter);
        pending = connectionRepository.saveAndFlush(pending);
        List<Long> remaining = Objects.requireNonNull(
                transactionTemplate.execute(__ -> connectionRepository.lockCredentialRotationBatch(2, 25)));
        assertThat(remaining).contains(pending.getId()).doesNotContain(corrupt.getId());
    }

    private static Connection connection(Workspace workspace, String instanceKey) {
        return new Connection(
                workspace,
                IntegrationKind.GITHUB,
                instanceKey,
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
    }
}
