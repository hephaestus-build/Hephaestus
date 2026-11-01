package de.tum.cit.aet.hephaestus.integration.core.graphql;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
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
            // Arrange — two fragments, only one is referenced by GetCommitMetadata
            String fragments = """
                fragment CommitEnrichmentFields on Commit { oid }
                fragment UnusedFragment on SomeType { field1 }
                """;
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(byteResource(fragments, "test-fragments")));

            // Act — GetCommitMetadata uses ...CommitEnrichmentFields but not ...UnusedFragment
            String document = source.getDocument("GetCommitMetadata").block();

            assertThat(document).isNotNull();
            assertThat(document).contains("query GetCommitMetadata");
            assertThat(document).contains("fragment CommitEnrichmentFields on Commit");
            assertThat(document).doesNotContain("UnusedFragment");
        }

        @Test
        void shouldNotAppendFragmentsWhenNoneReferenced() {
            // Arrange — fragments that are NOT used by GetRepository
            String fragments = """
                fragment UnusedFields on Repository { id title }
                fragment UnusedOwnerFields on RepositoryOwner { login }
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
            assertThat(document).doesNotContain("fragment UnusedFields");
            assertThat(document).doesNotContain("fragment UnusedOwnerFields");
        }

        @Test
        void shouldResolveTransitiveFragmentDependencies() {
            String fragments = """
                fragment CommitEnrichmentFields on Commit { author { ...AuthorFields } }
                fragment AuthorFields on GitActor { name }
                """;
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(byteResource(fragments, "test-fragments")));

            String document = source.getDocument("GetCommitMetadata").block();

            assertThat(document).contains("fragment CommitEnrichmentFields on Commit");
            assertThat(document).contains("fragment AuthorFields on GitActor");
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

        /**
         * A fragment file's last definition runs to end-of-file, so parsing the concatenation of several
         * files staples the next file's header comment onto it. If that header happens to contain
         * {@code ...SomethingElse} — an ordinary way to write a comment about a fragment — the spread
         * scanner reads it as a real reference and drags an unrelated fragment into every document that
         * uses the neighbour. GitHub then rejects the whole query for the unused fragment (§5.5.1.4), so
         * the symptom is commit metadata sync failing at once.
         */
        @Test
        void aCommentInOneFileCannotDragInAFragmentFromAnother() {
            Resource first = byteResource("fragment CommitEnrichmentFields on Commit { oid }\n", "first");
            Resource second = byteResource("""
                # Spread this with ...LonelyFragment where a commit is selected.
                fragment LonelyFragment on Commit { oid }
                """, "second");
            FragmentMergingDocumentSource source = new FragmentMergingDocumentSource(
                    List.of(new ClassPathResource("graphql/github/operations/")),
                    List.of(".graphql"),
                    List.of(first, second));

            String document = source.getDocument("GetCommitMetadata").block();

            assertThat(document).isNotNull();
            assertThat(document).contains("fragment CommitEnrichmentFields on Commit");
            assertThat(document)
                    .as("GetCommitMetadata never spreads LonelyFragment; only the other file's comment mentions it")
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
            String document = source.getDocument("GetCommitMetadata").block();
            assertThat(document).isNotNull();
            assertThat(document).contains("query GetCommitMetadata");
            // No fragments should be appended since none were provided
            assertThat(document).doesNotContain("fragment CommitEnrichmentFields");
        }
    }
}
