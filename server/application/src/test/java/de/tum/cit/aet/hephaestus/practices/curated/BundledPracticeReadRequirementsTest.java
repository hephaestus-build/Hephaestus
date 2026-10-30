package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.FileItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestReviewItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.RepositoryItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BundledPracticeReadRequirementsTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final ClasspathSourceCatalogRegistry registry =
            new ClasspathSourceCatalogRegistry(objectMapper, Clock.systemUTC());
    private final PracticeItemOptions itemOptions = new PracticeItemOptions(new ItemDescriptorRegistry(List.of(
            new PullRequestItemDescriptor(),
            new PullRequestReviewItemDescriptor(),
            new IssueItemDescriptor(),
            new FileItemDescriptor(),
            new RepositoryItemDescriptor(),
            new ThreadItemDescriptor(),
            new DocumentItemDescriptor())));
    private final BundledPracticeCatalogLoader loader =
            new BundledPracticeCatalogLoader(objectMapper, new PracticeDefinitionValidator(registry, itemOptions));

    @Test
    void shouldProvideApplicableSourcesForEveryAuthorableItemKind() {
        for (var descriptor : itemOptions.all()) {
            var kind = descriptor.kind();
            assertThat(registry.current().sourcesFor(kind.value()))
                    .as(
                            "item kind '%s' can be authored against but no source declares it applies; a practice "
                                    + "written on it would refuse every review that selected it",
                            kind)
                    .isNotEmpty();
        }
    }

    @Test
    void shouldRequireCompleteCaptureOnlyFromSourcesThatCanProvideIt() {
        loader.catalog()
                .practices()
                .forEach(practice -> practice.definition().definition().reads().sources().stream()
                        .filter(need -> need.need() == ReadNeed.COMPLETE)
                        .forEach(need -> assertThat(registry.requireSource(need.source())
                                        .completenessPolicy()
                                        .supportsComplete())
                                .as("%s requires complete capture of %s", practice.slug(), need.source())
                                .isTrue()));
    }

    @Test
    void shouldKeepChangedCodeAbsenceClaimsWithinTheDiff() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> Set.of(
                                "handles-errors-instead-of-swallowing-them",
                                "validates-and-escapes-untrusted-input",
                                "keeps-views-free-of-networking-and-persistence")
                        .contains(practice.slug()))
                .hasSize(3)
                .allSatisfy(practice -> assertThat(practice.definition().definition().reads().sources().stream()
                                .filter(need -> need.need() == ReadNeed.COMPLETE)
                                .map(need -> need.source()))
                        .containsExactly(new SourceKind("scm.pull-request.diff")));
    }

    @Test
    void shouldCaptureCompleteFileEvidenceForEveryBundledScan() {
        var scans = loader.catalog().practices().stream()
                .map(entry -> entry.definition().definition().scan())
                .filter(Objects::nonNull)
                .map(Objects::requireNonNull)
                .toList();
        assertThat(scans).hasSize(13).allSatisfy(scan -> {
            assertThat(scan.reads().contract()).isEqualTo("3.0.0");
            assertThat(scan.reads().sources())
                    .extracting(source -> source.source().value())
                    .containsExactly("scm.repository.tree");
            assertThat(scan.reads().sources())
                    .extracting(source -> source.need())
                    .containsExactly(ReadNeed.COMPLETE);
        });
    }
}
