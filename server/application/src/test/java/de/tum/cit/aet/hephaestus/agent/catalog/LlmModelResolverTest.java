package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class LlmModelResolverTest extends BaseUnitTest {

    @Mock
    private LlmConnectionRepository instanceConnections;

    @Mock
    private WorkspaceLlmConnectionRepository workspaceConnections;

    @Mock
    private LlmModelRepository instanceModels;

    @Mock
    private WorkspaceLlmModelRepository workspaceModels;

    @Mock
    private LlmModelWorkspaceGrantRepository grants;

    @InjectMocks
    private LlmModelResolver resolver;

    private LlmConnection connection;
    private LlmModel model;

    @BeforeEach
    void setUp() {
        connection = new LlmConnection();
        connection.setId(10L);
        connection.setEnabled(true);
        connection.setApiProtocol("openai-responses");
        connection.setBaseUrl("https://api.example.test/v1");
        connection.setAuthMode(LlmAuthMode.BEARER);
        connection.setApiKey("secret");
        model = new LlmModel();
        model.setId(20L);
        model.setUpstreamModelId("gpt-test");
        model.setConnection(connection);
        model.setEnabled(true);
        model.setVisibility(ModelVisibility.PUBLIC);
        lenient().when(instanceConnections.findById(10L)).thenReturn(Optional.of(connection));
        lenient().when(instanceModels.findById(20L)).thenReturn(Optional.of(model));
    }

    private static WorkspaceAgentBinding binding() {
        Workspace workspace = new Workspace();
        workspace.setId(30L);
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        return binding;
    }

    /**
     * Every field of the credential is the same shape as its neighbour, so a swapped {@code baseUrl}
     * and {@code apiProtocol} still produces a non-null credential that talks to the wrong provider.
     */
    @Test
    void shouldResolveEveryCredentialFieldForAnActivePublicInstanceModel() {
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, 20L, 30L);

        LlmModelResolver.ProxyCredential credential = resolver.resolveProxyCredential(ref);

        assertThat(credential).isNotNull();
        assertThat(credential.baseUrl()).isEqualTo("https://api.example.test/v1");
        assertThat(credential.apiProtocol()).isEqualTo("openai-responses");
        assertThat(credential.authMode()).isEqualTo(LlmAuthMode.BEARER);
        // The model's upstream id, not the catalog's own — the provider has never heard of our ids.
        assertThat(credential.upstreamModelId()).isEqualTo("gpt-test");
        assertThat(credential.apiKey()).isEqualTo("secret");
    }

    /**
     * An empty {@code Authorization: Bearer} header is a request the provider rejects with a confusing
     * 401, whereas no key at all lets the proxy fall through to unauthenticated access.
     */
    @Test
    void shouldReportABlankApiKeyAsNoCredentialAtAll() {
        connection.setApiKey("   ");
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, 20L, 30L);

        LlmModelResolver.ProxyCredential credential = resolver.resolveProxyCredential(ref);

        assertThat(credential).isNotNull();
        assertThat(credential.apiKey()).isNull();
    }

    @Test
    void shouldRejectDisabledInstanceModelEvenWhenConnectionIsActive() {
        model.setEnabled(false);
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, 20L, 30L);
        assertThat(resolver.resolveProxyCredential(ref)).isNull();
    }

    @Test
    void shouldRefuseUndeclaredOrMismatchedModelsForATierSlot() {
        var binding = binding();
        binding.setInstanceModel(model);
        binding.setDataHandlingTier(DataHandlingTier.IN_HOUSE);
        assertThat(resolver.isAvailable(binding)).isFalse();
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, null));
        assertThat(resolver.isAvailable(binding)).isFalse();
        assertThatThrownBy(() -> resolver.resolve(binding)).isInstanceOf(IllegalStateException.class);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, LlmDataRetention.NONE, null));
        assertThat(resolver.isAvailable(binding)).isTrue();
    }

    @Test
    void shouldRefuseAStricterModelInALooserSlotBecauseTheSlotRuleIsExact() {
        var binding = binding();
        binding.setInstanceModel(model);
        binding.setDataHandlingTier(DataHandlingTier.PROVIDER_NOT_KEPT);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, LlmDataRetention.NONE, null));
        assertThat(resolver.isAvailable(binding)).isFalse();
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, null));
        assertThat(resolver.isAvailable(binding)).isTrue();
    }

    @Test
    void shouldAcceptAnyModelInTheUndeclaredSlot() {
        var binding = binding();
        binding.setInstanceModel(model);
        assertThat(binding.getDataHandlingTier()).isEqualTo(DataHandlingTier.UNDECLARED);
        assertThat(resolver.isAvailable(binding)).isTrue();
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.FOR_SAFETY_CHECKS, null));
        assertThat(resolver.isAvailable(binding)).isTrue();
    }

    @Test
    void shouldReportTheModelsDerivedTierForAConnectionRef() {
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, 20L, 30L);
        assertThat(resolver.dataHandlingTier(ref)).isEqualTo(DataHandlingTier.UNDECLARED);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, null));
        assertThat(resolver.dataHandlingTier(ref)).isEqualTo(DataHandlingTier.PROVIDER_NOT_KEPT);
        assertThat(resolver.dataHandlingTier(LlmModelResolver.ConnectionRef.NONE))
                .isEqualTo(DataHandlingTier.UNDECLARED);
    }

    @Test
    void shouldRejectDisabledModelBeforeBuildingRuntimeConfiguration() {
        model.setEnabled(false);
        WorkspaceAgentBinding binding = binding();
        binding.setInstanceModel(model);

        assertThatThrownBy(() -> resolver.resolve(binding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void shouldRejectCatalogSnapshotWithoutModelIdentity() {
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, null, null);
        assertThat(resolver.resolveProxyCredential(ref)).isNull();
    }

    @Test
    void shouldRejectRevokedGrantedInstanceModel() {
        model.setVisibility(ModelVisibility.GRANTED);
        when(grants.existsByIdModelIdAndIdWorkspaceId(20L, 30L)).thenReturn(false);
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 10L, 20L, 30L);
        assertThat(resolver.resolveProxyCredential(ref)).isNull();
    }

    @Test
    void shouldRejectWorkspaceModelOwnedByAnotherWorkspace() {
        Workspace owner = new Workspace();
        owner.setId(31L);
        WorkspaceLlmConnection workspaceConnection = new WorkspaceLlmConnection();
        workspaceConnection.setId(11L);
        workspaceConnection.setWorkspace(owner);
        workspaceConnection.setEnabled(true);
        WorkspaceLlmModel workspaceModel = new WorkspaceLlmModel();
        workspaceModel.setId(21L);
        workspaceModel.setWorkspace(owner);
        workspaceModel.setConnection(workspaceConnection);
        workspaceModel.setEnabled(true);
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.WORKSPACE, 11L, 21L, 30L);
        assertThat(resolver.resolveProxyCredential(ref)).isNull();
    }

    @Test
    void shouldRejectBindingWithoutACatalogModel() {
        WorkspaceAgentBinding binding = binding(); // neither instance nor workspace model set

        assertThatThrownBy(() -> resolver.resolve(binding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bind");
    }

    @Test
    void shouldNotResolveACredentialForABindingWithoutACatalogModel() {
        WorkspaceAgentBinding binding = binding();

        assertThat(resolver.resolveCredential(binding)).isNull();
    }

    @Test
    void shouldNotResolveLegacyProxyCredential() {
        assertThat(resolver.resolveProxyCredential(LlmModelResolver.ConnectionRef.NONE))
                .isNull();
    }
}
