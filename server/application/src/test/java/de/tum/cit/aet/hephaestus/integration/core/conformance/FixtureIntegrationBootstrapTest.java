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
import de.tum.cit.aet.hephaestus.integration.core.framework.ArtifactDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.DeclaredSignalCoverage;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationFrameworkBootstrap;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.ReviewContractValidator;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.DormantBinding;
import de.tum.cit.aet.hephaestus.practices.review.PracticeSignalCoverage;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * An artifact kind that exists nowhere in {@code src/main}, driven through the real machinery — proving
 * the integration framework and practices module hold no knowledge of which artifact kinds exist.
 *
 * <p>Deliberately out of scope: the trigger gate, which takes a {@code PullRequest} or {@code Issue}
 * directly and so cannot reach a fixture kind ({@code PracticesIntegrationBoundaryTest} covers that gap).
 */
class FixtureIntegrationBootstrapTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;

    /** A second borrowed kind, for the one case that needs two integrations to differ in coverage. */
    private static final IntegrationKind UNCONNECTED_KIND = IntegrationKind.SLACK;

    @Test
    void theRealBootstrapAcceptsAnIntegrationForAKindThatDoesNotExist() {
        assertThatCode(bootstrap()::validate).doesNotThrowAnyException();
    }

    @Test
    void coverageIsComputedFromDeclarationsAlone() {
        DeclaredSignalCoverage coverage = coverage(false);

        assertThat(coverage.compiledCoverage())
                .as("both fixture signals are declared raised, so both are compiled-covered")
                .containsExactlyInAnyOrder(FixtureIntegration.WIDGET_ASSEMBLED, FixtureIntegration.WIDGET_SHIPPED);
        assertThat(coverage.raisedBy(FixtureIntegration.WIDGET_SHIPPED)).containsExactly(FixtureIntegration.KIND);
    }

    @Test
    void anUnconnectedWorkspaceCoversNothingWithoutFailing() {
        // Mid-onboarding is an ordinary state, not a reason to refuse to serve the workspace.
        DeclaredSignalCoverage coverage = coverage(false);

        assertThat(coverage.connectedCoverage(WORKSPACE_ID)).isEmpty();
        assertThat(coverage.compiledCoverage()).isNotEmpty();
    }

    @Test
    void aPracticeBoundToAnUncoveredSignalIsDormantWithAReason() {
        PracticeSignalCoverage practiceCoverage =
                practiceCoverage(false, practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED));

        List<DormantBinding> dormant = practiceCoverage.dormantBindings(WORKSPACE_ID);

        assertThat(dormant).hasSize(1);
        assertThat(dormant.getFirst().signals()).containsExactly(FixtureIntegration.WIDGET_ASSEMBLED);
        assertThat(dormant.getFirst().raisedByAnyOf())
                .as("the reason names what would report it, so it is actionable rather than merely true")
                .containsExactly(FixtureIntegration.KIND);
        assertThat(dormant.getFirst().reason(signal -> "Widget assembled", kind -> "Fixture"))
                .endsWith("(Widget assembled); Fixture would.");
    }

    @Test
    void connectingTheIntegrationEndsTheDormancy() {
        PracticeSignalCoverage practiceCoverage =
                practiceCoverage(true, practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED));

        assertThat(practiceCoverage.dormantBindings(WORKSPACE_ID)).isEmpty();
    }

    @Test
    void aPracticeStaysLiveWhileAnyOneOfItsSignalsIsCovered() {
        // Not dormant just because one of two watched signals is unreachable — that would make the
        // dormancy report noise.
        PracticeRepository repository = mock(PracticeRepository.class);
        when(repository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(
                        practiceBoundTo(FixtureIntegration.WIDGET_ASSEMBLED, FixtureIntegration.WIDGET_SHIPPED)));
        PracticeSignalCoverage practiceCoverage = new PracticeSignalCoverage(
                splitCoverage(),
                new PracticeSignalOptions(FixtureIntegration.artifactCatalog()),
                repository,
                workspaceDefaults());

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
                List.of(FixtureIntegration.feedbackChannel()),
                List.of(),
                List.of(),
                List.of(FixtureIntegration.lifecycleListener()),
                validator(),
                true);
    }

    private static ReviewContractValidator validator() {
        return new ReviewContractValidator(
                new ArtifactDescriptorRegistry(List.of(FixtureIntegration.descriptor())),
                new IntegrationMessageHandlerRegistry(List.of(
                        FixtureIntegration.handler(FixtureIntegration.ASSEMBLY_EVENT),
                        FixtureIntegration.handler(FixtureIntegration.SHIPMENT_EVENT))),
                List.of(FixtureIntegration.contextBuilder()),
                FixtureIntegration.executionCatalog());
    }

    private static DeclaredSignalCoverage coverage(boolean connected) {
        ConnectionService connections = mock(ConnectionService.class);
        // Lenient: half these tests only ask the compiled question, which never consults a connection.
        lenient()
                .when(connections.findActive(anyLong(), eq(FixtureIntegration.KIND)))
                .thenReturn(connected ? Optional.of(mock(Connection.class)) : Optional.empty());
        return new DeclaredSignalCoverage(
                new IntegrationManifestRegistry(List.of(FixtureIntegration.manifest())), connections);
    }

    private static DeclaredSignalCoverage splitCoverage() {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.findActive(WORKSPACE_ID, FixtureIntegration.KIND))
                .thenReturn(Optional.of(mock(Connection.class)));
        when(connections.findActive(WORKSPACE_ID, UNCONNECTED_KIND)).thenReturn(Optional.empty());
        return new DeclaredSignalCoverage(
                new IntegrationManifestRegistry(List.of(
                        FixtureIntegration.manifest(
                                FixtureIntegration.KIND, Set.of(), raises(FixtureIntegration.WIDGET_ASSEMBLED)),
                        FixtureIntegration.manifest(
                                UNCONNECTED_KIND, Set.of(), raises(FixtureIntegration.WIDGET_SHIPPED)))),
                connections);
    }

    private static IntegrationManifest.ReviewContribution raises(SignalName signal) {
        return new IntegrationManifest.ReviewContribution(
                Set.of(FixtureIntegration.WIDGET),
                Map.of(FixtureIntegration.WIDGET, Set.of(signal)),
                Map.of(FixtureIntegration.WIDGET, Set.of(FeedbackLane.IN_CONTEXT_SUMMARY)));
    }

    private static PracticeSignalCoverage practiceCoverage(boolean connected, Practice... practices) {
        PracticeRepository repository = mock(PracticeRepository.class);
        when(repository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practices));
        return new PracticeSignalCoverage(
                coverage(connected),
                new PracticeSignalOptions(FixtureIntegration.artifactCatalog()),
                repository,
                workspaceDefaults());
    }

    /** A workspace with no opinion set, so the fixture practices inherit the default and stay admitted. */
    private static WorkspaceReviewDefaultsProvider workspaceDefaults() {
        WorkspaceReviewDefaultsProvider defaults = mock(WorkspaceReviewDefaultsProvider.class);
        when(defaults.forWorkspace(WORKSPACE_ID)).thenReturn(WorkspaceReviewDefaults.UNSET);
        return defaults;
    }

    private static Practice practiceBoundTo(SignalName... signals) {
        Practice practice = new Practice();
        practice.setId(1L);
        practice.setSlug("assemble-widgets-carefully");
        practice.setBindings(List.of(new PracticeBinding(List.of(signals), List.of(FixtureIntegration.need()), false)));
        return practice;
    }
}
