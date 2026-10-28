package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.CompletenessPolicy;
import de.tum.cit.aet.hephaestus.evidence.SourceCatalog;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestReviewItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PracticeDefinitionValidatorTest extends BaseUnitTest {
    private PracticeDefinitionValidator validator;
    private ClasspathSourceCatalogRegistry sources;
    private SourceCatalog catalog;
    private ItemDescriptor descriptor;

    @BeforeEach
    void setUp() {
        sources = mock(ClasspathSourceCatalogRegistry.class);
        catalog = mock(SourceCatalog.class);
        var options = mock(PracticeItemOptions.class);
        descriptor = mock(ItemDescriptor.class);
        var event = mock(ItemEventDefinition.class);
        lenient().when(sources.current()).thenReturn(catalog);
        lenient().when(catalog.version()).thenReturn(new SourceContractVersion("3.0.0"));
        lenient()
                .when(catalog.sourcesFor("scm.pull_request"))
                .thenReturn(Set.of(new SourceKind("scm.pull-request.core")));
        lenient().when(options.require(new ItemKind("scm.pull_request"))).thenReturn(descriptor);
        lenient().when(descriptor.roles()).thenReturn(Set.of(AboutRole.AUTHOR));
        lenient().when(descriptor.events()).thenReturn(List.of(event));
        lenient().when(event.name()).thenReturn(new ItemEventName("scm.pull_request.opened"));
        lenient().when(event.bindableForAuthoring()).thenReturn(true);
        lenient()
                .when(descriptor.reviewCapabilities())
                .thenReturn(new ReviewCapabilities(List.of(), List.of(), Set.of(), Set.of(), Set.of()));
        validator = new PracticeDefinitionValidator(sources, options);
    }

    @Test
    void shouldAcceptExplicitNobodyWithoutChoosingAnAuthor() {
        var original = DefinitionTestFixtures.definition();
        var nobody = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                null,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                null,
                null,
                true,
                original.guidance());
        validator.validate(new ItemKind("scm.pull_request"), nobody);
        assertThat(nobody.about()).isNull();
    }

    @Test
    void shouldRefuseAnUnsupportedRoleBeforeAReviewCanRun() {
        var original = DefinitionTestFixtures.definition();
        var unsupported = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                AboutRole.MERGER,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                null,
                null,
                true,
                original.guidance());
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), unsupported))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseEventsAndRolesThatTheExactItemDescriptorDoesNotOffer() {
        var registered = new PracticeDefinitionValidator(
                sources,
                new PracticeItemOptions(new ItemDescriptorRegistry(
                        List.of(new PullRequestItemDescriptor(), new PullRequestReviewItemDescriptor()))));
        lenient()
                .when(catalog.sourcesFor("scm.pull_request_review"))
                .thenReturn(Set.of(new SourceKind("scm.pull-request.core")));
        var pullRequest = new ItemKind("scm.pull_request");
        var review = new ItemKind("scm.pull_request_review");
        var submitted = new ReviewOn(List.of(new ItemEventName("scm.pull_request_review.submitted")), Map.of());
        var author = DefinitionTestFixtures.withReviewOn(DefinitionTestFixtures.definition(), submitted);

        // The pull request descriptor does not merge its member kinds' events.
        assertThatThrownBy(() -> registered.validate(pullRequest, author))
                .hasMessage("Choose review events offered for this item kind.");
        // An offered event that authoring cannot bind stays refused for a new definition.
        assertThatThrownBy(() -> registered.validate(
                        pullRequest,
                        DefinitionTestFixtures.withReviewOn(
                                DefinitionTestFixtures.definition(),
                                new ReviewOn(
                                        List.of(new ItemEventName("scm.pull_request.linked_issue_updated")),
                                        Map.of()))))
                .hasMessage("Choose review events offered for this item kind.");
        assertThatThrownBy(() -> registered.validate(review, author))
                .hasMessage("Choose an About role offered for this item kind.");
        registered.validate(review, DefinitionTestFixtures.withAbout(author, AboutRole.REVIEWER));
        assertThat(UnofferedItemCapabilities.of(new PullRequestItemDescriptor(), author))
                .isEqualTo(new UnofferedItemCapabilities(
                        List.of(new ItemEventName("scm.pull_request_review.submitted")), null));
        assertThat(UnofferedItemCapabilities.of(
                        new PullRequestItemDescriptor(),
                        DefinitionTestFixtures.withAbout(DefinitionTestFixtures.definition(), null)))
                .isNull();
    }

    @Test
    void shouldRefuseWhitespaceOnlyCriteria() {
        var invalid = DefinitionTestFixtures.definition("Valid name", " \n");
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAScriptWhenPeopleReviewThePractice() {
        var original = DefinitionTestFixtures.definition();
        var people = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                original.about(),
                original.reads(),
                new ReviewedBy(
                        ReviewedByKind.PEOPLE,
                        new ReviewLimit("HUMAN_JUDGMENT", "People need to judge this work."),
                        List.of()),
                original.criteria(),
                "export default {};",
                null,
                false,
                original.guidance());
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), people))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldValidateTheScanSourcesSeparatelyFromTheItemSources() {
        enableScan(true);
        var definition = withScan(scanReads("3.0.0", ReadNeed.COMPLETE));
        validator.validate(new ItemKind("scm.pull_request"), definition);
        assertThat(definition.reads().sources())
                .extracting(PracticeReadSource::source)
                .containsExactly(new SourceKind("scm.pull-request.core"));
        assertThat(Objects.requireNonNull(definition.scan()).reads().sources())
                .extracting(PracticeReadSource::source)
                .containsExactly(new SourceKind("scm.repository.tree"));
    }

    @Test
    void shouldRejectARequiredItemSourceInTheScanReadRequirements() {
        enableScan(true);
        var definition = withScan(DefinitionTestFixtures.definition().reads());
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item kind");
    }

    @Test
    void shouldRequireTheCurrentContractForTheScanSources() {
        enableScan(true);
        var definition = withScan(scanReads("1.3.0", ReadNeed.COMPLETE));
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current source contract");
    }

    @Test
    void shouldRejectCompleteScanReadsWhenTheSourceSupportsOnlyPartialCapture() {
        enableScan(false);
        var definition = withScan(scanReads("3.0.0", ReadNeed.COMPLETE));
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete capture");
    }

    @Test
    void shouldRejectAnAutomatedScanWithOnlyOptionalContext() {
        enableScan(true);
        var definition = withScan(scanReads("3.0.0", ReadNeed.OPTIONAL));
        assertThatThrownBy(() -> validator.validate(new ItemKind("scm.pull_request"), definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required read source");
    }

    private void enableScan(boolean supportsComplete) {
        when(descriptor.reviewCapabilities())
                .thenReturn(new ReviewCapabilities(
                        List.of(), List.of(), Set.of(), Set.of(), Set.of(new ItemKind("scm.file"))));
        lenient().when(catalog.sourcesFor("scm.file")).thenReturn(Set.of(new SourceKind("scm.repository.tree")));
        var source = mock(SourceContract.class);
        lenient()
                .when(sources.requireSource(new SourceKind("scm.repository.tree")))
                .thenReturn(source);
        lenient().when(source.completenessPolicy()).thenReturn(new CompletenessPolicy(supportsComplete, true, true));
    }

    private static PracticeReads scanReads(String contract, ReadNeed need) {
        return new PracticeReads(
                contract, List.of(new PracticeReadSource(new SourceKind("scm.repository.tree"), need)));
    }

    private static PracticeDefinition withScan(PracticeReads reads) {
        var original = DefinitionTestFixtures.definition();
        return new PracticeDefinition(
                original.name(),
                new ReviewOn(List.of(), Map.of()),
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                new ScanDefinition(new ItemKind("scm.file"), List.of("**/*.ts"), List.of(), List.of("**/*"), reads),
                original.lineNotes(),
                original.guidance());
    }
}
