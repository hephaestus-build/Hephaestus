package de.tum.cit.aet.hephaestus.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * A malformed operation document fails at the vendor's API with a GraphQL error — the same outcome every caller
 * here treats as an unknown/skip result — so a typo can ship silently and never surface as a test failure.
 *
 * <p>This only proves each document is valid against the checked-in schema; a schema refresh is what catches
 * vendor-side deprecation.
 */
class GraphQlOperationDocumentValidationTest extends BaseUnitTest {

    private record Vendor(String name, String schema, String operations, List<String> fragments) {
        List<Resource> fragmentResources() {
            return fragments.stream()
                    .map(location -> (Resource) new ClassPathResource(location))
                    .toList();
        }
    }

    private static final List<Vendor> VENDORS = List.of(
            new Vendor(
                    "github",
                    "graphql/github/schema.github.graphql",
                    "graphql/github/operations/",
                    List.of(
                            GitHubGraphQlFragments.ACTOR_FRAGMENTS_RESOURCE,
                            GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE)),
            new Vendor(
                    "gitlab",
                    "graphql/gitlab/schema.gitlab.graphql",
                    "graphql/gitlab/operations/",
                    List.of("graphql/gitlab/fragments/GitLabUserFields.graphql")));

    static Stream<Arguments> operationDocuments() {
        List<Arguments> arguments = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            GraphQLSchema schema = loadSchema(vendor.schema());
            FragmentMergingDocumentSource documentSource = new FragmentMergingDocumentSource(
                    Stream.concat(
                                    Stream.of((Resource) new ClassPathResource(vendor.operations())),
                                    vendor.fragmentResources().stream())
                            .toList(),
                    List.of(".graphql", ".gql"),
                    vendor.fragmentResources());
            for (String name : operationNames(vendor.operations())) {
                arguments.add(Arguments.of(vendor.name() + "/" + name, schema, documentSource, name));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationDocuments")
    void operationDocumentIsValidAgainstTheVendorSchema(
            String label, GraphQLSchema schema, FragmentMergingDocumentSource documentSource, String documentName) {
        String documentText = documentSource.getDocument(documentName).block();
        assertThat(documentText).as("document %s could not be loaded", label).isNotNull();

        ParseAndValidateResult result = ParseAndValidate.parseAndValidate(
                schema, ExecutionInput.newExecutionInput(documentText).build());

        assertThat(result.getSyntaxException())
                .as("%s failed to parse: %s", label, result.getSyntaxException())
                .isNull();
        List<GraphQLError> errors = result.getValidationErrors().stream()
                .filter(error -> !isAcceptedByTheVendor(error))
                .map(GraphQLError.class::cast)
                .toList();
        assertThat(errors)
                .as("%s is invalid against the checked-in schema: %s", label, describe(errors))
                .isEmpty();
    }

    /**
     * GitHub's server does not enforce GraphQL's SameResponseShape rule (§5.3.2): {@code RequestedReviewerFields}
     * selects {@code name} on both {@code User} ({@code String}) and {@code Team} ({@code String!}) inside one
     * union selection, and the affected documents run fine against api.github.com. graphql-java does enforce it,
     * so this carve-out is scoped to that exact conflict — any other {@code FieldsConflict} still fails the build.
     *
     * <p>The scoping reads the two field paths and never the prose around them: graphql-java localises validation
     * messages, so matching on the English wording passed on an English JVM and failed on any other — the build
     * went red on a German workstation while CI stayed green. The paths are part of the query, not of the
     * translation, so they say the same thing in every locale.
     */
    private static boolean isAcceptedByTheVendor(ValidationError error) {
        if (error.getValidationErrorType() != ValidationErrorType.FieldsConflict) {
            return false;
        }
        String message = error.getMessage();
        return message.contains("/reviewers/nodes/name'") || message.contains("/requestedReviewer/name'");
    }

    @Test
    void everyVendorContributesDocumentsSoTheScanIsNotVacuous() {
        for (Vendor vendor : VENDORS) {
            assertThat(operationNames(vendor.operations()))
                    .as("no operation documents found for %s", vendor.name())
                    .isNotEmpty();
        }
    }

    private static String describe(List<? extends GraphQLError> errors) {
        return errors.stream().map(GraphQLError::getMessage).toList().toString();
    }

    private static GraphQLSchema loadSchema(String schemaLocation) {
        // An unexecutable schema needs no data fetchers or scalar implementations — validation only reads the
        // type system.
        TypeDefinitionRegistry registry = new SchemaParser().parse(read(new ClassPathResource(schemaLocation)));
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
    }

    private static List<String> operationNames(String operationsLocation) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + operationsLocation + "*.graphql");
            return Stream.of(resources)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .map(filename -> filename.substring(0, filename.lastIndexOf('.')))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
