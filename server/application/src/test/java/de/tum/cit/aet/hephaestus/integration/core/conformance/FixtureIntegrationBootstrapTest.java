package de.tum.cit.aet.hephaestus.integration.core.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.framework.DeclaredItemEventCoverage;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationFrameworkBootstrap;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.ReviewContractValidator;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.webhook.IntegrationKindRouting;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidance;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.review.DormantBinding;
import de.tum.cit.aet.hephaestus.practices.review.PracticeItemEventCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class FixtureIntegrationBootstrapTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;

    private static final IntegrationKind UNCONNECTED_KIND = IntegrationKind.SLACK;

    @Test
    void theRealBootstrapAcceptsAnIntegrationForAKindThatDoesNotExist() {
        assertThatCode(bootstrap()::validate).doesNotThrowAnyException();
    }

    @Test
    void coverageIsComputedFromDeclarationsAlone() {
        DeclaredItemEventCoverage coverage = coverage(false);

        assertThat(coverage.compiledCoverage())
                .as("both fixture events are declared raised, so both are compiled-covered")
                .containsExactlyInAnyOrder(FixtureIntegration.WIDGET_ASSEMBLED, FixtureIntegration.WIDGET_SHIPPED);
        assertThat(coverage.raisedBy(FixtureIntegration.WIDGET_SHIPPED)).containsExactly(FixtureIntegration.KIND);
    }

    @Test
    void anUnconnectedWorkspaceCoversNothingWithoutFailing() {
        // Mid-onboarding is an ordinary state, not a reason to refuse to serve the workspace.
        DeclaredItemEventCoverage coverage = coverage(false);

        assertThat(coverage.connectedCoverage(WORKSPACE_ID)).isEmpty();
        assertThat(coverage.compiledCoverage()).isNotEmpty();
    }

    @Test
    void aPracticeBoundToAnUncoveredEventIsDormantWithAReason() {
        PracticeItemEventCoverage practiceCoverage =
                practiceCoverage(false, practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED));

        List<DormantBinding> dormant = practiceCoverage.dormantBindings(WORKSPACE_ID);

        assertThat(dormant).hasSize(1);
        assertThat(dormant.getFirst().events()).containsExactly(FixtureIntegration.WIDGET_ASSEMBLED);
        assertThat(dormant.getFirst().raisedByAnyOf())
                .as("the reason names what would report it, so it is actionable rather than" + " merely true")
                .containsExactly(FixtureIntegration.KIND);
        assertThat(dormant.getFirst().reason(event -> "Widget assembled", kind -> "Fixture"))
                .endsWith("(Widget assembled). Fixture can report them.");
    }

    @Test
    void connectingTheIntegrationEndsTheDormancy() {
        PracticeItemEventCoverage practiceCoverage =
                practiceCoverage(true, practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED));

        assertThat(practiceCoverage.dormantBindings(WORKSPACE_ID)).isEmpty();
    }

    @Test
    void aPracticeStaysLiveWhileAnyOneOfItsEventsIsCovered() {
        // Not dormant just because one of two watched events is unreachable — that would make the
        // dormancy report noise.
        PracticeRepository repository = mock(PracticeRepository.class);
        when(repository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(
                        practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED, FixtureIntegration.WIDGET_SHIPPED)));
        PracticeItemEventCoverage practiceCoverage = new PracticeItemEventCoverage(
                splitCoverage(),
                new PracticeItemOptions(FixtureIntegration.itemCatalog()),
                repository,
                new JsonMapper());

        assertThat(practiceCoverage.dormantBindings(WORKSPACE_ID)).isEmpty();
    }

    private IntegrationFrameworkBootstrap bootstrap() {
        return new IntegrationFrameworkBootstrap(
                new IntegrationManifestRegistry(List.of(FixtureIntegration.manifest())),
                List.of(),
                List.of(),
                List.of(),
                List.of(FixtureIntegration.credentialProvider()),
                List.of(),
                List.of(FixtureIntegration.summaryWriter()),
                List.of(),
                List.of(),
                List.of(FixtureIntegration.lifecycleListener()),
                validator(),
                new IntegrationKindRouting(),
                true);
    }

    private static ReviewContractValidator validator() {
        return new ReviewContractValidator(
                new ItemDescriptorRegistry(List.of(FixtureIntegration.descriptor())),
                new IntegrationMessageHandlerRegistry(List.of(
                        FixtureIntegration.handler(FixtureIntegration.ASSEMBLY_EVENT),
                        FixtureIntegration.handler(FixtureIntegration.SHIPMENT_EVENT))));
    }

    private static DeclaredItemEventCoverage coverage(boolean connected) {
        ConnectionService connections = mock(ConnectionService.class);
        // Lenient: half these tests only ask the compiled question, which never consults a
        // connection.
        lenient()
                .when(connections.findActive(anyLong(), eq(FixtureIntegration.KIND)))
                .thenReturn(connected ? Optional.of(mock(Connection.class)) : Optional.empty());
        return new DeclaredItemEventCoverage(
                new IntegrationManifestRegistry(List.of(FixtureIntegration.manifest())), connections);
    }

    private static DeclaredItemEventCoverage splitCoverage() {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.findActive(WORKSPACE_ID, FixtureIntegration.KIND))
                .thenReturn(Optional.of(mock(Connection.class)));
        when(connections.findActive(WORKSPACE_ID, UNCONNECTED_KIND)).thenReturn(Optional.empty());
        return new DeclaredItemEventCoverage(
                new IntegrationManifestRegistry(List.of(
                        FixtureIntegration.manifest(
                                FixtureIntegration.KIND, Set.of(), raises(FixtureIntegration.WIDGET_ASSEMBLED)),
                        FixtureIntegration.manifest(
                                UNCONNECTED_KIND, Set.of(), raises(FixtureIntegration.WIDGET_SHIPPED)))),
                connections);
    }

    private static IntegrationManifest.ReviewContribution raises(ItemEventName event) {
        return new IntegrationManifest.ReviewContribution(
                Set.of(FixtureIntegration.WIDGET),
                Map.of(FixtureIntegration.WIDGET, Set.of(event)),
                Map.of(FixtureIntegration.WIDGET, Set.of(CopyCapability.SUMMARY)));
    }

    private static PracticeItemEventCoverage practiceCoverage(boolean connected, Practice... practices) {
        PracticeRepository repository = mock(PracticeRepository.class);
        when(repository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practices));
        return new PracticeItemEventCoverage(
                coverage(connected),
                new PracticeItemOptions(FixtureIntegration.itemCatalog()),
                repository,
                new JsonMapper());
    }

    private static Practice practiceBoundTo(ItemEventName... events) {
        Practice practice = new Practice();
        practice.setId(1L);
        practice.setSlug("assemble-widgets-carefully");
        var workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        practice.setWorkspace(workspace);
        practice.setKind(FixtureIntegration.WIDGET);
        var definition = new PracticeDefinition(
                "Assemble widgets carefully",
                new ReviewOn(List.of(events), Map.of()),
                null,
                AboutRole.AUTHOR,
                new PracticeReads("2.0.0", List.of(FixtureIntegration.need())),
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                "Check the widget.",
                null,
                null,
                false,
                new PracticeGuidance(null, null, null, null, null));
        practice.setCurrentVersion(new PracticeVersion(practice, 1, new ObjectMapper().valueToTree(definition), null));
        return practice;
    }
}
