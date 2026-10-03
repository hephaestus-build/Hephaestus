package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * The commit-enrichment request is the one GitHub query this codebase assembles at runtime: the batch
 * aliases {@code commit0…commitN} are syntax that no variable can stand in for and no static document
 * can enumerate. An assembled request is outside both GraphQL guards —
 * {@code GraphQlOperationDocumentValidationTest} (schema-validity of what we ask for) and
 * {@code GraphQlResponseStubValidator} (honesty of what tests pretend GitHub answered) — so a field typo
 * in it comes back as a GraphQL error, which every caller here treats as "commit not found" and skips.
 *
 * <p>Keeping the selection set in a checked-in fragment hands the fields back to those guards. This test
 * covers what is left: the assembled envelope, parsed and validated against the same checked-in schema
 * the guards use.
 */
class CommitMetadataEnrichmentServiceQueryTest extends BaseUnitTest {

    private static final GraphQLSchema SCHEMA = loadSchema();

    @ParameterizedTest(name = "{0} commits in one batch")
    @ValueSource(ints = {1, 2, 50})
    void assembledBatchQueryIsValidAgainstTheCheckedInGitHubSchema(int commits) {
        List<String> shas = IntStream.range(0, commits)
                .mapToObj(i -> String.format("%040x", i))
                .toList();

        String query = CommitMetadataEnrichmentService.buildBatchQuery("ls1intum", "Hephaestus", shas);

        ParseAndValidateResult result = ParseAndValidate.parseAndValidate(
                SCHEMA, ExecutionInput.newExecutionInput(query).build());

        assertThat(result.getSyntaxException())
                .as("assembled query failed to parse:%n%s", query)
                .isNull();
        assertThat(result.getValidationErrors().stream()
                        .map(GraphQLError::getMessage)
                        .toList())
                .as("assembled query is invalid against the checked-in schema:%n%s", query)
                .isEmpty();
    }

    @Test
    void everyBatchedCommitGetsItsOwnAliasAndTheFragmentIsSentExactlyOnce() {
        String query =
                CommitMetadataEnrichmentService.buildBatchQuery("o", "r", List.of("a".repeat(40), "b".repeat(40)));

        assertThat(query).contains("commit0: object(oid: \"" + "a".repeat(40) + "\")");
        assertThat(query).contains("commit1: object(oid: \"" + "b".repeat(40) + "\")");
        assertThat(query.split("fragment CommitEnrichmentFields on Commit", -1).length - 1)
                .as("GraphQL rejects a duplicate fragment definition, so it must be appended once per request")
                .isEqualTo(1);
    }

    /**
     * The overflow follow-up assumes the batch already fetched exactly this many, so a page size that
     * drifts from the fragment silently re-fetches or skips authors/PRs on every commit that overflows.
     */
    @Test
    void fragmentPageSizesMatchTheConstantsTheOverflowFollowUpCountsFrom() {
        String fragment = CommitMetadataEnrichmentService.commitEnrichmentFragment();

        assertThat(fragment).contains("authors(first: " + CommitMetadataEnrichmentService.AUTHORS_PAGE_SIZE + ")");
        assertThat(fragment)
                .contains("associatedPullRequests(first: " + CommitMetadataEnrichmentService.ASSOCIATED_PRS_PAGE_SIZE
                        + ")");
    }

    private static GraphQLSchema loadSchema() {
        TypeDefinitionRegistry registry =
                new SchemaParser().parse(read(new ClassPathResource("graphql/github/schema.github.graphql")));
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
