package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConnectionTest extends BaseUnitTest {

    private static final String OLD_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NEW_KEY = "ABCDEFabcdef0123ABCDEFabcdef0123";
    private static final BearerToken TOKEN = new BearerToken("glpat-rotate-me", null);

    @Test
    void shouldClearQuarantineWhenCredentialsAreReplaced() {
        CredentialBundleConverter oldConverter = new CredentialBundleConverter(OLD_KEY, false);
        CredentialBundleConverter rotatingConverter = new CredentialBundleConverter(NEW_KEY, 2, OLD_KEY, 1, false);
        Connection connection = connection();
        connection.setCredentials(TOKEN, oldConverter);
        connection.markCredentialRotationFailed(Instant.parse("2026-09-03T12:00:00Z"));

        connection.setCredentials(TOKEN, rotatingConverter);

        assertThat(connection.getCredentialsRotationFailedAt()).isNull();
        assertThat(connection.getCredentialsKeyVersion()).isEqualTo(2);
    }

    @Test
    void shouldReportStoredCredentialsWithoutAKeyThatReadsThem() {
        Connection connection = connection();
        assertThat(connection.hasCredentials()).isFalse();

        connection.setCredentials(TOKEN, new CredentialBundleConverter(OLD_KEY, false));

        assertThat(connection.hasCredentials()).isTrue();
        assertThatThrownBy(() -> connection.credentials(new CredentialBundleConverter(NEW_KEY, false)))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void shouldReportTheMarkOnlyForAConnectionThatRunsOnItsStoredCredential() {
        Instant failedAt = Instant.parse("2026-09-05T08:00:00Z");
        Connection pat = new Connection(
                new Workspace(),
                IntegrationKind.GITHUB,
                "acme",
                new ConnectionConfig.GitHubPatConfig("x", "x", Set.of()));
        pat.markCredentialRotationFailed(failedAt);
        Connection app = new Connection(
                new Workspace(),
                IntegrationKind.GITHUB,
                "acme",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        app.markCredentialRotationFailed(failedAt);

        assertThat(pat.credentialsUnreadableSince()).isEqualTo(failedAt);
        // The App connection runs on its installation, not on the stored blob.
        assertThat(app.credentialsUnreadableSince()).isNull();
        assertThat(app.getCredentialsRotationFailedAt()).isEqualTo(failedAt);
    }

    private static Connection connection() {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        return new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
    }
}
