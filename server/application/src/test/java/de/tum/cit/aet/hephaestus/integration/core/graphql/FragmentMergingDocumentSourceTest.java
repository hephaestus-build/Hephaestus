package de.tum.cit.aet.hephaestus.integration.core.graphql;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Unit tests for {@link FragmentMergingDocumentSource}.
 * <p>
 * Verifies that shared GraphQL fragment definitions are appended selectively:
 * only fragments that are actually referenced (transitively via {@code ...FragmentName}
 * spreads) are included. This satisfies GraphQL spec §5.5.1.4 which requires that
 * all defined fragments must be used.
 *
 * @see FragmentMergingDocumentSource
 */
class FragmentMergingDocumentSourceTest extends BaseUnitTest {

    private static Resource byteResource(String content, String description) {
        return new ByteArrayResource(content.getBytes(UTF_8)) {
            @Override
            public String getDescription() {
                return description;
            }
        };
    }

    @Nested
    class GetDocumentTests {

        @Test
        void shouldAppendOnlyReferencedFragments() {
            // Arrange — two fragments, only one is referenced by GetOrganizationProjects
            String fragments = """
                fragment ActorFields on Actor { login }
                fragment UnusedFragment on SomeType { field1 }
                """;
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(byteResource(fragments, "test-fragments")));

            // Act — GetOrganizationProjects uses ...ActorFields but not ...UnusedFragment
            String document = source.getDocument("GetOrganizationProjects").block();

            assertThat(document).isNotNull();
            assertThat(document).contains("query GetOrganizationProjects");
            assertThat(document).contains("fragment ActorFields on Actor");
            assertThat(document).doesNotContain("UnusedFragment");
        }

        @Test
        void shouldNotAppendFragmentsWhenNoneReferenced() {
            // Arrange — fragments that are NOT used by GetRepository
            String fragments = """
                fragment ProjectV2BaseFields on ProjectV2 { id title }
                fragment ProjectV2OwnerFields on ProjectV2Owner { login }
                """;
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(byteResource(fragments, "test-fragments")));

            // Act — GetRepository does NOT reference any of these fragments
            String document = source.getDocument("GetRepository").block();

            // Assert — the document should be the raw operation without any appended fragments
            assertThat(document).isNotNull();
            assertThat(document).contains("query GetRepository");
            assertThat(document).doesNotContain("fragment ProjectV2BaseFields");
            assertThat(document).doesNotContain("fragment ProjectV2OwnerFields");
        }

        @Test
        void shouldResolveTransitiveFragmentDependencies() {
            // Arrange — operations are loaded from a classpath directory, so the transitive case runs on the
            // real GetProjectItems document: it uses ...ProjectV2ItemFields, which uses ...ActorFieldsCompact
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(new ClassPathResource("graphql/github/fragments/ProjectFragments.graphql")));

            // Act — GetProjectItems uses ...ProjectV2ItemFields which uses ...ActorFieldsCompact
            String document = source.getDocument("GetProjectItems").block();

            // Assert — both the direct and transitive dependencies are included
            assertThat(document).isNotNull();
            assertThat(document).contains("fragment ProjectV2ItemFields on ProjectV2Item");
            assertThat(document).contains("fragment ActorFieldsCompact on Actor");
            assertThat(document).contains("fragment ProjectV2ItemContentFields on ProjectV2ItemContent");
            assertThat(document).contains("fragment RequestedReviewerFields on RequestedReviewer");
        }

        @Test
        void shouldThrowWhenDocumentNotFound() {
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(byteResource("fragment F on T { f }", "test-fragment")));

            // Act & Assert — ResourceDocumentSource throws IllegalStateException for missing documents
            assertThatThrownBy(() -> source.getDocument("NonExistentDocument").block())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NonExistentDocument");
        }

        @Test
        @DisplayName("should work with actual ProjectFragments.graphql and include correct subset")
        void shouldWorkWithActualFragmentFileSelectively() {
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(new ClassPathResource("graphql/github/fragments/ProjectFragments.graphql")));

            // Act — GetOrganizationProjects uses ActorFields, ProjectV2BaseFields, ProjectV2OwnerFields
            String document = source.getDocument("GetOrganizationProjects").block();

            // Assert — referenced fragments are present
            assertThat(document).isNotNull();
            assertThat(document).contains("query GetOrganizationProjects");
            assertThat(document).contains("fragment ProjectV2BaseFields on ProjectV2");
            assertThat(document).contains("fragment ProjectV2OwnerFields on ProjectV2Owner");
            assertThat(document).contains("fragment ActorFields on Actor");

            // Assert — unreferenced fragments are NOT present
            assertThat(document).doesNotContain("fragment FieldValueFields on ProjectV2ItemFieldValue");
            assertThat(document).doesNotContain("fragment ProjectV2FieldConfigFields on ProjectV2FieldConfiguration");
            assertThat(document).doesNotContain("fragment ProjectV2StatusUpdateFields on ProjectV2StatusUpdate");
        }

        @Test
        @DisplayName(
                "should resolve FieldValueFieldsCostOptimized from shared fragment pool with transitive RequestedReviewerFields")
        void shouldResolveFieldValueFieldsCostOptimizedFromSharedPool() {
            // Arrange — use the real ProjectFragments.graphql (which now contains FieldValueFieldsCostOptimized)
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(new ClassPathResource("graphql/github/fragments/ProjectFragments.graphql")));

            // Act — GetProjectItems uses ...FieldValueFieldsCostOptimized (now resolved from shared pool)
            String document = source.getDocument("GetProjectItems").block();

            // Assert — FieldValueFieldsCostOptimized is resolved from the shared fragment pool
            assertThat(document).isNotNull();
            assertThat(document).contains("query GetProjectItems");
            assertThat(document).contains("fragment FieldValueFieldsCostOptimized on ProjectV2ItemFieldValue");

            // Assert — transitive dependency: FieldValueFieldsCostOptimized references ...RequestedReviewerFields
            assertThat(document).contains("fragment RequestedReviewerFields on RequestedReviewer");

            // Assert — FieldValueFieldsCostOptimized is NOT defined inline in GetProjectItems.graphql anymore
            // It should appear exactly once (from the shared pool), not twice
            int firstIndex = document.indexOf("fragment FieldValueFieldsCostOptimized");
            int secondIndex = document.indexOf("fragment FieldValueFieldsCostOptimized", firstIndex + 1);
            assertThat(secondIndex)
                    .as("FieldValueFieldsCostOptimized should appear exactly once")
                    .isEqualTo(-1);
        }

        @Test
        void shouldResolveFieldValueFieldsCostOptimizedForEmbeddedQueries() {
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(new ClassPathResource("graphql/github/fragments/ProjectFragments.graphql")));

            // Act — embedded queries now use ...FieldValueFieldsCostOptimized spread
            String issueItems = source.getDocument("GetIssueProjectItems").block();
            String prItems = source.getDocument("GetPullRequestProjectItems").block();

            // Assert — both resolve the shared fragment with all 11 field value types
            for (String doc : List.of(issueItems, prItems)) {
                assertThat(doc).isNotNull();
                assertThat(doc).contains("fragment FieldValueFieldsCostOptimized on ProjectV2ItemFieldValue");
                assertThat(doc).contains("fragment RequestedReviewerFields on RequestedReviewer");
                // Verify coverage of complex field value types (beyond the original 5 inline types)
                assertThat(doc).contains("ProjectV2ItemFieldLabelValue");
                assertThat(doc).contains("ProjectV2ItemFieldMilestoneValue");
                assertThat(doc).contains("ProjectV2ItemFieldUserValue");
                assertThat(doc).contains("ProjectV2ItemFieldRepositoryValue");
                assertThat(doc).contains("ProjectV2ItemFieldPullRequestValue");
                assertThat(doc).contains("ProjectV2ItemFieldReviewerValue");
            }
        }

        @Test
        void shouldNotContaminateNonProjectQueries() {
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(new ClassPathResource("graphql/github/fragments/ProjectFragments.graphql")));

            // Act — GetRepository, GetRepositoryLabels, GetRepositoryCollaborators don't use any fragments
            String getRepo = source.getDocument("GetRepository").block();
            String getLabels = source.getDocument("GetRepositoryLabels").block();
            String getCollaborators =
                    source.getDocument("GetRepositoryCollaborators").block();

            // Assert — no fragment definitions should be appended
            for (String doc : List.of(getRepo, getLabels, getCollaborators)) {
                assertThat(doc).doesNotContain("fragment ActorFields");
                assertThat(doc).doesNotContain("fragment ProjectV2");
                assertThat(doc).doesNotContain("fragment FieldValueFields");
                assertThat(doc).doesNotContain("fragment RequestedReviewerFields");
            }
        }
    }

    @Nested
    class MultipleFragmentFileTests {

        /**
         * A fragment file's last definition runs to end-of-file, so parsing the concatenation of several
         * files staples the next file's header comment onto it. If that header happens to contain
         * {@code ...SomethingElse} — an ordinary way to write a comment about a fragment — the spread
         * scanner reads it as a real reference and drags an unrelated fragment into every document that
         * uses the neighbour. GitHub then rejects the whole query for the unused fragment (§5.5.1.4), so
         * the symptom is every project/issue sync failing at once.
         */
        @Test
        void aCommentInOneFileCannotDragInAFragmentFromAnother() {
            Resource first = byteResource("fragment ActorFields on Actor { login }\n", "first");
            Resource second = byteResource("""
                # Spread this with ...LonelyFragment where a commit is selected.
                fragment LonelyFragment on Commit { oid }
                """, "second");
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(first, second));

            String document = source.getDocument("GetOrganizationProjects").block();

            assertThat(document).isNotNull();
            assertThat(document).contains("fragment ActorFields on Actor");
            assertThat(document)
                    .as(
                            "GetOrganizationProjects never spreads LonelyFragment; only the other file's comment mentions it")
                    .doesNotContain("LonelyFragment");
        }
    }

    @Nested
    class ParseFragmentsTests {

        @Test
        void shouldParseMultipleFragments() {
            String raw = """
                fragment Frag1 on Type1 { field1 }
                fragment Frag2 on Type2 { field2 }
                fragment Frag3 on Type3 { field3 nested { subfield } }
                """;

            Map<String, String> fragments = FragmentMergingDocumentSource.parseFragments(raw);

            assertThat(fragments).hasSize(3);
            assertThat(fragments).containsKeys("Frag1", "Frag2", "Frag3");
            assertThat(fragments.get("Frag1")).startsWith("fragment Frag1 on Type1");
            assertThat(fragments.get("Frag3")).contains("nested { subfield }");
        }

        @Test
        void shouldReturnEmptyForBlankContent() {
            assertThat(FragmentMergingDocumentSource.parseFragments("")).isEmpty();
            assertThat(FragmentMergingDocumentSource.parseFragments("   ")).isEmpty();
            assertThat(FragmentMergingDocumentSource.parseFragments(null)).isEmpty();
        }

        @Test
        void shouldHandleComplexFragments() {
            // Arrange — fragment that references another fragment
            String raw = """
                fragment Outer on Type1 {
                    field1
                    nested {
                        ...Inner
                    }
                }
                fragment Inner on Type2 {
                    field2
                }
                """;

            Map<String, String> fragments = FragmentMergingDocumentSource.parseFragments(raw);

            assertThat(fragments).hasSize(2);
            assertThat(fragments.get("Outer")).contains("...Inner");
            assertThat(fragments.get("Inner")).contains("field2");
        }
    }

    @Nested
    class ConstructorTests {

        @Test
        void shouldThrowWhenFragmentResourceMissing() {
            Resource missingResource = new ClassPathResource("nonexistent/fragment.graphql");

            assertThatThrownBy(() -> new FragmentMergingDocumentSource(
                            List.of(new ClassPathResource("graphql/github/operations/")),
                            List.of(".graphql"),
                            List.of(missingResource)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to load GraphQL fragment resource");
        }

        @Test
        void shouldAcceptEmptyFragmentList() {
            // Arrange & Act
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")), List.of(".graphql"), List.of());

            // Assert — document should load without appended fragments
            String document = source.getDocument("GetOrganizationProjects").block();
            assertThat(document).isNotNull();
            assertThat(document).contains("query GetOrganizationProjects");
            // No fragments should be appended since none were provided
            assertThat(document).doesNotContain("fragment ProjectV2BaseFields");
        }
    }
}
