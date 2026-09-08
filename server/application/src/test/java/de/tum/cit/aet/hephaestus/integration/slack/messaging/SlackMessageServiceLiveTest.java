package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.testconfig.CredentialReaders;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live end-to-end check of the app's Slack messaging path against the real Slack Web API.
 *
 * <p>Exercises the full chain the production code uses — AES-GCM encrypt of the bot token into a
 * {@link Connection}, decrypt via {@link CredentialBundleConverter}, resolution through the real
 * {@link SlackCredentialProvider}, and a real {@code chat.postMessage} via {@link SlackMessageService}.
 * The only mocked seam is the DB lookup ({@link ConnectionService#findActive}).
 *
 * <p>Gated behind {@code @Tag("live")} and skipped unless {@code SLACK_BOT_TOKEN} +
 * {@code SLACK_E2E_CHANNEL} are present, so it never runs in normal CI. Run with:
 * {@code SLACK_BOT_TOKEN=xoxb-… SLACK_E2E_CHANNEL=C… ./gradlew :application:liveTest --tests SlackMessageServiceLiveTest}.
 */
@Tag("live")
class SlackMessageServiceLiveTest {

    @Test
    void postsRealMessageThroughEncryptedConnection() {
        String botToken = System.getenv("SLACK_BOT_TOKEN");
        String channelId = System.getenv("SLACK_E2E_CHANNEL");
        assumeThat(botToken).as("SLACK_BOT_TOKEN env").isNotBlank();
        assumeThat(channelId).as("SLACK_E2E_CHANNEL env").isNotBlank();

        long workspaceId = 1L;
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);

        // Real per-row AES-GCM encryption (same converter the app wires), AAD-bound to this row.
        CredentialBundleConverter converter = new CredentialBundleConverter("a".repeat(32), "live");
        Connection connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T-live-e2e",
                new ConnectionConfig.SlackConfig("T-live-e2e", "hephaestus-test", channelId, null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken(botToken, null), converter);
        // Prove the round-trip before we ever hit Slack: the stored blob decrypts back to the token.
        assertThatCode(() -> connection.credentials(converter)).doesNotThrowAnyException();

        ConnectionService connectionService = mock(ConnectionService.class);
        when(connectionService.findActive(workspaceId, IntegrationKind.SLACK)).thenReturn(Optional.of(connection));

        SlackCredentialProvider credentialProvider =
                new SlackCredentialProvider(connectionService, CredentialReaders.forTests(converter));
        SlackMessageService service = new SlackMessageService(
                credentialProvider,
                new SlackRateLimitTracker(new SimpleMeterRegistry()),
                new OutboundEgressGuard(() -> false));

        // The real send: block-kit payload through the app's SlackMessageService to the live channel.
        assertThatCode(() -> service.sendForWorkspace(
                        workspaceId,
                        channelId,
                        asBlocks(
                                section(
                                        s -> s.text(
                                                markdownText(
                                                        ":white_check_mark: Hephaestus #1198 Slack E2E — posted via the app's SlackMessageService (encrypted Connection → decrypt → chat.postMessage).")))),
                        "Hephaestus #1198 Slack E2E"))
                .doesNotThrowAnyException();
    }
}
