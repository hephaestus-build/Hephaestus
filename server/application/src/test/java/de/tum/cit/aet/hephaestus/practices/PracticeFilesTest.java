package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.FileItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestReviewItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.RepositoryItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeFilesTest extends BaseUnitTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final PracticeDefinitionValidator validator = new PracticeDefinitionValidator(
            new ClasspathSourceCatalogRegistry(json, Clock.systemUTC()),
            new PracticeItemOptions(new ItemDescriptorRegistry(List.of(
                    new PullRequestItemDescriptor(),
                    new PullRequestReviewItemDescriptor(),
                    new IssueItemDescriptor(),
                    new FileItemDescriptor(),
                    new RepositoryItemDescriptor(),
                    new ThreadItemDescriptor(),
                    new DocumentItemDescriptor()))));
    private final PracticeFiles files = new PracticeFiles(json, validator);
    private final BundledPracticeCatalogLoader catalog = new BundledPracticeCatalogLoader(json, validator);

    @Test
    void shouldParseEveryBundledPracticeBackToAnEqualDefinition() {
        var practices = catalog.catalog().practices();
        assertThat(practices).isNotEmpty();
        for (var practice : practices) {
            var written = files.write(practice.definition());
            var read = files.read(practice.slug(), written);
            assertThat(read.problems()).as(practice.slug()).isEmpty();
            assertThat(read.document()).as(practice.slug()).isEqualTo(practice.definition());
        }
    }

    @Test
    void shouldWriteTheSameFilesAgainFromAParsedFolder() {
        for (var practice : catalog.catalog().practices()) {
            var written = files.write(practice.definition());
            var again = files.write(
                    Objects.requireNonNull(files.read(practice.slug(), written).document()));
            assertThat(again).as(practice.slug()).isEqualTo(written);
        }
    }

    @Test
    void shouldRoundTripFieldsThatNoBundledPracticeUses() {
        var practice = catalog.catalog().practices().getFirst();
        ObjectNode tree = json.valueToTree(practice.definition().definition());
        tree.putNull("about");
        // Text that a YAML writer could leave bare as a number or a boolean.
        tree.put("name", "1e3");
        ((ObjectNode) tree.get("guidance")).put("goingWell", ".inf");
        ((ObjectNode) tree.get("guidance")).put("whyItMatters", "yes");
        ((ObjectNode) tree.get("reviewedBy"))
                .putArray("limits")
                .addObject()
                .put("code", "private-history")
                .put("description", "The review cannot read history before the repository was connected.");
        var document = new CatalogPracticeDocument(
                practice.definition().kind(), null, json.treeToValue(tree, PracticeDefinition.class));

        var read = files.read(practice.slug(), files.write(document));

        assertThat(read.problems()).isEmpty();
        assertThat(read.document()).isEqualTo(document);
    }

    @Test
    void shouldKeepATrailingNewlineOfTheCriteria() {
        var practice = catalog.catalog().practices().getFirst();
        ObjectNode tree = json.valueToTree(practice.definition().definition());
        tree.put("criteria", tree.get("criteria").asString() + "\n");
        var document = new CatalogPracticeDocument(
                practice.definition().kind(), null, json.treeToValue(tree, PracticeDefinition.class));

        assertThat(files.read(practice.slug(), files.write(document)).document())
                .isEqualTo(document);
    }

    @Test
    void shouldNameAMissingKey() {
        var folder = bundled();
        folder.put(PracticeFiles.PRACTICE, file(folder, PracticeFiles.PRACTICE).replaceFirst("\nname: [^\n]*", ""));

        assertThat(problems(folder))
                .extracting(PracticeFileProblemDTO::code, PracticeFileProblemDTO::message)
                .containsExactly(tuple("KEY_MISSING", "Add the key name."));
    }

    @Test
    void shouldPlaceAnInvalidKindOnItsLineInsteadOfFailingTheRequest() {
        var folder = bundled();
        folder.put(
                PracticeFiles.PRACTICE,
                file(folder, PracticeFiles.PRACTICE).replaceFirst("\nkind: [^\n]*", "\nkind: Pull Request"));

        assertThat(problems(folder))
                .extracting(PracticeFileProblemDTO::code, PracticeFileProblemDTO::line)
                .containsExactly(tuple("FIELD_INVALID", 4));
    }

    @Test
    void shouldExplainAValueOfTheWrongShapeWithoutAClassName() {
        var folder = bundled();
        var text = file(folder, PracticeFiles.PRACTICE);
        int start = text.indexOf("\nreads:");
        int end = text.indexOf("\nreviewedBy:");
        folder.put(PracticeFiles.PRACTICE, text.substring(0, start) + "\nreads: 3" + text.substring(end));

        assertThat(problems(folder)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("FIELD_INVALID");
            assertThat(problem.message()).doesNotContain("de.tum");
        });
    }

    @Test
    void shouldRefuseADuplicateKeyAndAnAlias() {
        var duplicate = bundled();
        duplicate.put(
                PracticeFiles.PRACTICE,
                file(duplicate, PracticeFiles.PRACTICE).replaceFirst("\nname: ", "\nname: First\nname: "));
        assertThat(problems(duplicate)).extracting(PracticeFileProblemDTO::code).containsExactly("YAML_INVALID");

        var alias = bundled();
        alias.put(
                PracticeFiles.PRACTICE,
                file(alias, PracticeFiles.PRACTICE).replaceFirst("\nname: [^\n]*", "\nname: *anchor"));
        assertThat(problems(alias)).extracting(PracticeFileProblemDTO::code).containsExactly("YAML_INVALID");
    }

    @Test
    void shouldRefuseDeepNestingAsYamlInsteadOfFailingTheRequest() {
        var folder = bundled();
        folder.put(
                PracticeFiles.PRACTICE,
                file(folder, PracticeFiles.PRACTICE).replaceFirst("\nname: [^\n]*", "\nname: " + "[".repeat(600)));

        assertThat(problems(folder)).extracting(PracticeFileProblemDTO::code).containsExactly("YAML_INVALID");
    }

    @Test
    void shouldPlaceAGuideProblemOnTheGuideAndRefuseAnUnusedFigure() {
        var missing = bundled();
        missing.put(PracticeFiles.GUIDE, file(missing, PracticeFiles.GUIDE) + "\n\n![A step](figures/not-there.svg)\n");
        assertThat(problems(missing))
                .extracting(PracticeFileProblemDTO::file, PracticeFileProblemDTO::code)
                .containsExactly(tuple(PracticeFiles.GUIDE, "FILE_INVALID"));

        var unused = bundled();
        unused.put("figures/spare.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        assertThat(problems(unused))
                .extracting(PracticeFileProblemDTO::file, PracticeFileProblemDTO::code)
                .containsExactly(tuple("figures/spare.svg", "FIGURE_UNUSED"));
    }

    @Test
    void shouldPlaceAnUnknownKeyOnItsLine() {
        var folder = bundled();
        folder.put(
                PracticeFiles.PRACTICE,
                file(folder, PracticeFiles.PRACTICE).replaceFirst("\nname: ", "\nseverity: high\nname: "));

        assertThat(problems(folder))
                .containsExactly(new PracticeFileProblemDTO(
                        PracticeFiles.PRACTICE, 3, 1, "UNKNOWN_KEY", "Remove the key severity."));
    }

    @Test
    void shouldRefuseANewerFormat() {
        var folder = bundled();
        folder.put(
                PracticeFiles.PRACTICE,
                file(folder, PracticeFiles.PRACTICE).replace(PracticeFiles.FORMAT, "hephaestus.practice/2"));

        assertThat(problems(folder))
                .extracting(PracticeFileProblemDTO::code, PracticeFileProblemDTO::line)
                .containsExactly(tuple("FORMAT_UNSUPPORTED", 2));
    }

    @Test
    void shouldRefuseFrontMatterThatIsNotYaml() {
        var folder = bundled();
        folder.put(PracticeFiles.PRACTICE, "---\nname: [unclosed\n---\nCriteria.\n");

        assertThat(problems(folder)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("YAML_INVALID");
            assertThat(problem.line()).isNotNull();
        });
    }

    @Test
    void shouldRefuseAFolderWithoutFrontMatter() {
        assertThat(problems(Map.of(PracticeFiles.PRACTICE, "Criteria only.\n")))
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly("FRONT_MATTER_MISSING");
    }

    @Test
    void shouldRefuseAnUnknownFileAndAnInvalidFolderName() {
        var folder = bundled();
        folder.put("notes.txt", "draft");

        assertThat(files.read("Not A Slug", folder).problems())
                .extracting(PracticeFileProblemDTO::file, PracticeFileProblemDTO::code)
                .containsExactlyInAnyOrder(
                        tuple(PracticeFiles.PRACTICE, "SLUG_INVALID"), tuple("notes.txt", "UNKNOWN_FILE"));
    }

    @Test
    void shouldRefuseADescriptionWithoutItsVisual() {
        var folder = bundled();
        folder.remove(PracticeFiles.VISUAL);

        assertThat(problems(folder))
                .extracting(PracticeFileProblemDTO::file, PracticeFileProblemDTO::code)
                .containsExactly(tuple(PracticeFiles.PRACTICE, "VISUAL_MISSING"));
    }

    @Test
    void shouldReportTheValidatorsReasonForADefinitionThatTheWebAppWouldRefuse() {
        var folder = bundled();
        folder.put(
                PracticeFiles.PRACTICE,
                file(folder, PracticeFiles.PRACTICE).replaceFirst("\nname: [^\n]*", "\nname: ab"));

        assertThat(problems(folder)).extracting(PracticeFileProblemDTO::code).containsExactly("DEFINITION_INVALID");
    }

    @Test
    void shouldPlaceAnInvalidFigureOnTheFiguresOwnFile() {
        var folder = bundled();
        folder.put(PracticeFiles.GUIDE, file(folder, PracticeFiles.GUIDE) + "\n\n![A step](figures/step.svg)\n");
        folder.put("figures/step.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");

        assertThat(problems(folder))
                .extracting(PracticeFileProblemDTO::file, PracticeFileProblemDTO::code)
                .containsExactly(tuple("figures/step.svg", "FILE_INVALID"));
    }

    private Map<String, String> bundled() {
        var practice = catalog.catalog().practices().getFirst();
        return new HashMap<>(files.write(practice.definition()));
    }

    private static String file(Map<String, String> folder, String path) {
        return Objects.requireNonNull(folder.get(path), path);
    }

    private List<PracticeFileProblemDTO> problems(Map<String, String> folder) {
        var read = files.read("a-practice", folder);
        assertThat(read.document()).isNull();
        return read.problems();
    }
}
