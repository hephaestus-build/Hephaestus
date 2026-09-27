package de.tum.cit.aet.hephaestus.integration.outline.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.InitiateRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient.OutlineIdentity;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEventRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineWebhookRegistrar;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class OutlineConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private OutlineApiClient outlineApiClient;

    @Mock
    private OutlineWebhookRegistrar webhookRegistrar;

    @Mock
    private OutlineDocumentRepository outlineDocumentRepository;

    @Mock
    private OutlineCollectionRepository outlineCollectionRepository;

    @Mock
    private OutlineDocumentEventRepository outlineDocumentEventRepository;

    private OutlineConnectionStrategy strategy() {
        return new OutlineConnectionStrategy(
                outlineApiClient,
                webhookRegistrar,
                outlineDocumentRepository,
                outlineCollectionRepository,
                outlineDocumentEventRepository,
                new OutlineOriginPolicy(Set.of("https://app.getoutline.com")));
    }

    private InitiateRequest request(Map<String, String> userInput) {
        return new InitiateRequest(1L, IntegrationKind.OUTLINE, userInput, "admin");
    }

    @Test
    void initiate_validatesTokenAndAcceptsInlineWithTheTeamAsInstanceKey() {
        when(outlineApiClient.validateToken("https://app.getoutline.com", "tok-123"))
                .thenReturn(new OutlineIdentity("team-9", "Acme", "user-1"));
        OutlineConnectionStrategy strategy = strategy();

        ConnectInitiation result =
                strategy.initiate(request(Map.of("server_url", "https://app.getoutline.com", "token", "tok-123")));

        assertThat(result).isInstanceOf(ConnectInitiation.AcceptInline.class);
        ConnectInitiation.AcceptInline inline = (ConnectInitiation.AcceptInline) result;
        assertThat(inline.instanceKey()).isEqualTo("team-9");
        assertThat(inline.credentials()).isInstanceOf(BearerToken.class);
        assertThat(((BearerToken) inline.credentials()).token()).isEqualTo("tok-123");
    }

    @Test
    void initiate_rejectsMissingToken() {
        assertThatThrownBy(() -> strategy().initiate(request(Map.of("server_url", "https://app.getoutline.com"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void initiate_rejectsAnOriginOutsideTheOperatorAllowlist() {
        assertThatThrownBy(() -> strategy()
                        .initiate(request(Map.of("server_url", "https://outline.example.com", "token", "tok-123"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved");

        verifyNoInteractions(outlineApiClient);
    }

    @Test
    void eraseLocalData_erasesMirroredDocumentsWithoutCallingOutline() {
        strategy().eraseLocalData(new IntegrationRef(IntegrationKind.OUTLINE, 5L, "team-9", 7L));

        verify(outlineDocumentRepository).deleteByWorkspaceId(5L);
        verify(outlineCollectionRepository).deleteByWorkspaceId(5L);
        // GDPR erase on disconnect covers the event log too — actor subjects are personal data.
        verify(outlineDocumentEventRepository).deleteByWorkspaceId(5L);
        verifyNoInteractions(webhookRegistrar);
    }

    @Test
    void prepareProviderTeardown_isTheSubscriptionDeregistration() {
        Runnable deletion = () -> {};
        when(webhookRegistrar.prepareDeregistration(5L, 7L)).thenReturn(Optional.of(deletion));

        assertThat(strategy().prepareProviderTeardown(new IntegrationRef(IntegrationKind.OUTLINE, 5L, "team-9", 7L)))
                .containsSame(deletion);
        verifyNoInteractions(outlineDocumentRepository, outlineCollectionRepository, outlineDocumentEventRepository);
    }
}
