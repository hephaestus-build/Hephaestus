package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins, for each false-lapse or false-credit pattern the criteria decide, the sentence that places the
 * work on the right side of the line. Patterns a precompute script caused are pinned by its spec instead.
 */
class CatalogFalseLapseLineTest extends BaseUnitTest {

    private static final Map<String, String> CRITERIA = new HashMap<>();

    @BeforeAll
    static void loadCatalogue() throws IOException {
        try (InputStream in = CatalogFalseLapseLineTest.class
                .getClassLoader()
                .getResourceAsStream("practices/default-catalog.json")) {
            assertThat(in).isNotNull();
            JsonNode catalogue = new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (JsonNode group : catalogue.path("groups")) {
                for (JsonNode practice : group.path("practices")) {
                    CRITERIA.put(
                            practice.path("slug").asText(),
                            practice.path("criteria").asText());
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            states-how-to-verify-the-change | a look-alike example | Looks like a lapse but is not:
            states-how-to-verify-the-change | unreferenced scaffolding is never the main effect | Scaffolding beside a material effect is never the main effect.
            states-how-to-verify-the-change | a sentence naming where to look is enough | do not demand a literal value or a numbered step list
            states-how-to-verify-the-change | a rename's check has an expected result | a project that generates and builds with the new configuration is its expected result
            states-how-to-verify-the-change | a declared prerequisite is part of a good check | a declared prerequisite is part of a good check
            states-how-to-verify-the-change | a stale issue item or a slip is not misleading | is not misleading while the rest of the check reaches the effect
            states-how-to-verify-the-change | a preview's own controls are an action | A preview is live
            commits-are-atomic-and-cohesive | a look-alike example | Looks like a lapse but is not:
            commits-are-atomic-and-cohesive | clauses are not concerns | joined clauses are punctuation, not a count of concerns
            commits-are-atomic-and-cohesive | a feature's supporting files are one concern | the supporting files of the work it does name never are
            commits-are-atomic-and-cohesive | a chore in its own commit is no tangle | a chore or a revert in its own commit is the partition working
            commits-are-atomic-and-cohesive | a vague broad commit is uncertain | not a tangle by breadth alone
            validates-inputs-and-edge-cases-at-the-boundary | a look-alike example | Looks like a lapse but is not:
            validates-inputs-and-edge-cases-at-the-boundary | a guard may sit in traceable code | in the hunk or in code you can trace to
            validates-inputs-and-edge-cases-at-the-boundary | an optional the type forces is handled | a Swift optional the type forces the author to unwrap
            validates-inputs-and-edge-cases-at-the-boundary | a propagating try handles a failure | a `try` that propagates out of a throwing function
            validates-inputs-and-edge-cases-at-the-boundary | unchanged code is never charged | a site in unchanged code is never charged
            validates-inputs-and-edge-cases-at-the-boundary | typed in-app data has no subject | So is a change whose values are all typed in-app data
            avoids-unsafe-panics-and-chosen-crashes | a look-alike example | Looks like a lapse but is not:
            avoids-unsafe-panics-and-chosen-crashes | preview and test code is no runtime path | Code that runs only in an Xcode `#Preview`, a preview-only sample or in tests is not an ordinary runtime path
            avoids-unsafe-panics-and-chosen-crashes | shipped data is no runtime input | data the author ships
            avoids-unsafe-panics-and-chosen-crashes | implicit traps are the input practice's | An implicit trap — a subscript, a slice, an integer division, a trapping numeric conversion — is not on the list
            engaging-with-inline-review-comments | a look-alike example | Looks like a lapse but is not:
            engaging-with-inline-review-comments | advice posted with the approval asks nothing | nor when it is advice the reviewer attached to their own approval
            engaging-with-inline-review-comments | the reviewer's settling note closes the loop | the reviewer's own later note saying the point was taken up
            engaging-with-inline-review-comments | a note after the hand-off is uncertain | the only substantive notes were posted after the work merged or closed
            merges-only-after-approval | a bot's approval is no person's | An approval marked `bot` never makes this cell.
            merges-only-after-approval | a bot's request for changes does not stand | its request for changes does not stand against another person's approval
            merges-only-after-approval | a bot's decision still makes the occasion | a decision marked `bot` is still a review decision for this gate
            merges-only-after-approval | automation comes from the marker, not the login | Whether an account is automated is the captured `bot` marker, never the login's shape
            ships-a-preview-with-each-new-view | a host's preview covers what it renders | shown through a previewed host's body is covered by that preview
            ships-a-preview-with-each-new-view | an effect owner needs no preview scaffolding | it needs no new initializer, injection or architecture just so a preview can construct it
            ships-a-preview-with-each-new-view | a wrapper's own chrome still needs a preview | is its own presentation, and a preview must show it
            ships-a-preview-with-each-new-view | a host's coverage is traced, not assumed | Never assume a host renders a view you did not trace
            ships-a-preview-with-each-new-view | a live fetch is no sample data | a live fetch inside a preview is not sample data
            changes-dependencies-deliberately | a manifest's name is no occasion | The changed lines decide, not the file's name.
            changes-dependencies-deliberately | internal target wiring is no dependency | declares no external dependency and is NOT_APPLICABLE, even inside a dependency manifest
            changes-dependencies-deliberately | a lockfile refresh is still an occasion | A resolution change on its own, such as a lockfile refresh, is an occasion.
            issue-points-to-relevant-context | the source location does not decide relevance | A link outside the wiki counts on the same terms as a wiki link.
            records-significant-decisions-with-rationale | an ADR in the same change records why | An ADR or design document can record the rationale in the same change. Read its prose.
            records-significant-decisions-with-rationale | dependency edits have one owner | Dependency edits belong only to `changes-dependencies-deliberately`.
            records-significant-decisions-with-rationale | independent architecture stays in scope | A separate architectural decision in the same change remains in scope here.
            changes-dependencies-deliberately | a package name supplies no reason | A package name or an edit label is not a reason
            handles-errors-instead-of-swallowing-them | a fixed valid URL drops no runtime failure | A valid fixed URL has no runtime-dependent parse failure
            handles-errors-instead-of-swallowing-them | runtime input and transport can fail | Do not extend this exception to a URL from runtime input, or to a network request that uses the URL.
            handles-errors-instead-of-swallowing-them | a guarantee comes from a type or contract | never from the guard itself or a name; where the sources leave it open, the case stays possible.
            handles-errors-instead-of-swallowing-them | recorded failures are not swallowed twice | A later check on an empty value does not swallow a failure already recorded by its handler.
            keeps-views-free-of-networking-and-persistence | framework view storage belongs in views | Core Data's `@FetchRequest`, and SwiftUI's `@AppStorage` and `@SceneStorage`
            declares-permissions-truthfully-at-point-of-use | the root view can present the feature | A root view can be that feature.
            uses-structured-concurrency-safely | error handling has its own practice | Swallowed errors in async code belong to `handles-errors-instead-of-swallowing-them`
            uses-adaptive-colors-for-every-appearance | an image scrim is content | a shadow or a translucent scrim over an image
            leaves-the-code-clean-with-intent-revealing-comments | print diagnostics have one owner | Do not report the same print here as residue.
            logs-through-the-platform-logger | credential exposure has one owner | Do not report the same credential-bearing diagnostic here
            avoids-insecure-defaults-and-over-broad-permissions | usage descriptions have one owner | A missing or placeholder usage description in `Info.plist` belongs to `declares-permissions-truthfully-at-point-of-use`
            handles-errors-instead-of-swallowing-them | print choice is not an error swallow | A printed failure with diagnostic context is recorded, not swallowed.
            """)
    void theCriteriaDrawTheLineWhereACarefulReviewerWould(String slug, String pattern, String sentence) {
        assertThat(CRITERIA.get(slug)).as(pattern).contains(sentence);
    }

    @Test
    void aDifferentLoginIsNotTakenForAnotherPerson() {
        assertThat(CRITERIA.get("merges-only-after-approval")).doesNotContain("different logins are different people");
    }
}
