package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCatalog;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventCoverage;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeDefinitionOptionsServiceTest extends BaseUnitTest {
    @Test
    void shouldUseTheRegisteredProviderAndConnectedCoverageForEventOptions() {
        var items = mock(PracticeItemOptions.class);
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var catalog = mock(SourceCatalog.class);
        var coverage = mock(ItemEventCoverage.class);
        var descriptor = mock(ItemDescriptor.class);
        var event = mock(ItemEventDefinition.class);
        var name = new ItemEventName("scm.pull_request.opened");
        when(items.all()).thenReturn(List.of(descriptor));
        when(sources.current()).thenReturn(catalog);
        when(catalog.version()).thenReturn(new SourceContractVersion("3.0.0"));
        when(catalog.sources()).thenReturn(List.of());
        when(descriptor.kind()).thenReturn(new ItemKind("scm.pull_request"));
        when(descriptor.displayName()).thenReturn("Pull request");
        when(descriptor.roles()).thenReturn(Set.of(AboutRole.AUTHOR));
        when(descriptor.events()).thenReturn(List.of(event));
        when(descriptor.reviewCapabilities())
                .thenReturn(new ReviewCapabilities(List.of(), List.of(), Set.of(), Set.of(), Set.of()));
        when(event.name()).thenReturn(name);
        when(event.displayName()).thenReturn("Opened");
        when(event.bindableForAuthoring()).thenReturn(true);
        when(coverage.connectedCoverage(10L)).thenReturn(Set.of(name));
        when(coverage.raisedBy(name)).thenReturn(Set.of(IntegrationKind.GITHUB));
        var result = new PracticeDefinitionOptionsService(items, sources, coverage).options(10L);
        assertThat(result.itemTypes())
                .singleElement()
                .satisfies(type -> assertThat(type.events()).singleElement().satisfies(option -> {
                    assertThat(option.connected()).isTrue();
                    assertThat(option.raisedBy()).containsExactly(IntegrationKind.GITHUB);
                }));
    }

    @Test
    void shouldKeepScanOnlySourcesOutOfTheParentItemReadOptions() {
        var registry = new ClasspathSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC());
        var tree = registry.requireSource(new SourceKind("scm.repository.tree"));
        var fileOnly = new SourceContract(
                new SourceKind("custom.file.content"),
                tree.displayName(),
                tree.description(),
                tree.selectionScope(),
                Set.of("scm.file"),
                tree.defaultRequirement(),
                tree.authority(),
                tree.identityPolicy(),
                tree.completenessPolicy(),
                tree.requiredQuality(),
                tree.privacyClass(),
                tree.supportedAbsenceStates(),
                tree.retentionPolicy(),
                tree.erasurePolicy(),
                tree.useDecisionIds(),
                tree.citation());
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        when(sources.current()).thenReturn(new SourceCatalog(registry.current().version(), List.of(fileOnly)));
        var items = mock(PracticeItemOptions.class);
        when(items.all()).thenReturn(List.of(new PullRequestItemDescriptor()));
        var result = new PracticeDefinitionOptionsService(items, sources, mock(ItemEventCoverage.class)).options(null);
        assertThat(result.itemTypes()).singleElement().satisfies(type -> {
            assertThat(type.scanItemKinds()).contains("scm.file");
            assertThat(type.readSources()).isEmpty();
        });
    }
}
