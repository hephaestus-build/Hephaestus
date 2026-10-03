package de.tum.cit.aet.hephaestus.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Checks a hand-built GraphQL response stub against the vendor's checked-in schema <em>and</em> the operation
 * document that would have produced it.
 *
 * <p>{@code GraphQlOperationDocumentValidationTest} closes the request side: a field we ask for really exists.
 * This closes the response side. A test that mocks the reply invents the vendor's half of the conversation, so
 * it can assert that production correctly handles a payload the vendor is incapable of sending — which is how a
 * GitLab approval mutation stayed green for months while failing in production every single time.
 *
 * <p>What it flags:
 *
 * <ul>
 *   <li>a key the document never selects at that path (fragment spreads and inline fragments are followed, so
 *       a field only reachable through {@code ... on Type} counts as selected);</li>
 *   <li>a scalar stubbed as the wrong Java type — {@code Int} wants an integral number, {@code Float} any
 *       number, {@code String}/{@code ID} a {@code String}, {@code Boolean} a {@code Boolean}, an enum a
 *       {@code String};</li>
 *   <li>a LIST field stubbed as something other than a {@code List}, or an object field stubbed as something
 *       other than a {@code Map}.</li>
 * </ul>
 *
 * <p>What it deliberately tolerates:
 *
 * <ul>
 *   <li><b>Absent keys.</b> A stub is allowed to be a subset of the selection set; only extra or wrongly typed
 *       keys are a lie about the vendor.</li>
 *   <li><b>{@code null} for a non-null field.</b> Existing fixtures deliberately null out {@code String!} and
 *       {@code DiscussionID!} values to exercise defensive production branches, and those fixtures are correct
 *       about everything else. Flagging them would have forced either a weakened scalar check or a blanket
 *       exclusion; dropping this one sub-check keeps the other three sharp.</li>
 *   <li><b>Custom scalars</b> ({@code Time}, {@code NoteID}, {@code GlobalID}, …), whose wire form this
 *       validator has no schema-level way to know.</li>
 *   <li><b>{@code __typename}</b>, which every vendor answers whether or not the document asks.</li>
 * </ul>
 */
public final class GraphQlResponseStubValidator {

    public enum Vendor {
        GITHUB(
                "graphql/github/schema.github.graphql",
                "graphql/github/operations/",
                List.of(
                        GitHubGraphQlFragments.PROJECT_FRAGMENTS_RESOURCE,
                        GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE)),
        GITLAB(
                "graphql/gitlab/schema.gitlab.graphql",
                "graphql/gitlab/operations/",
                List.of("graphql/gitlab/fragments/GitLabUserFields.graphql"));

        private final String schemaLocation;
        private final String operationsLocation;

        @SuppressWarnings("ImmutableEnumChecker") // every constant passes an unmodifiable List.of
        private final List<String> fragmentLocations;

        Vendor(String schemaLocation, String operationsLocation, List<String> fragmentLocations) {
            this.schemaLocation = schemaLocation;
            this.operationsLocation = operationsLocation;
            this.fragmentLocations = fragmentLocations;
        }

        private List<Resource> fragmentResources() {
            return fragmentLocations.stream()
                    .map(location -> (Resource) new ClassPathResource(location))
                    .toList();
        }
    }

    private static final String TYPENAME = "__typename";

    private static final Map<Vendor, GraphQLSchema> SCHEMAS = new ConcurrentHashMap<>();
    private static final Map<Vendor, FragmentMergingDocumentSource> DOCUMENT_SOURCES = new ConcurrentHashMap<>();
    private static final Map<Vendor, Map<String, Document>> DOCUMENTS = new ConcurrentHashMap<>();

    private GraphQlResponseStubValidator() {}

    /**
     * Fails unless {@code stub} — the {@code Map} / {@code List} of maps a test hands to a mocked
     * {@code ClientGraphQlResponse} — is something {@code documentName} could actually have elicited from the
     * vendor at {@code fieldPath} (a dotted response path from the operation root, e.g.
     * {@code "project.mergeRequests.nodes"}).
     */
    public static void assertVendorCouldReturn(
            Vendor vendor, String documentName, String fieldPath, @Nullable Object stub) {
        GraphQLSchema schema = schema(vendor);
        Document document = document(vendor, documentName);
        OperationDefinition operation =
                document.getDefinitionsOfType(OperationDefinition.class).getFirst();
        Map<String, FragmentDefinition> fragments = document.getDefinitionsOfType(FragmentDefinition.class).stream()
                .collect(Collectors.toMap(FragmentDefinition::getName, Function.identity()));

        Walk walk = new Walk(schema, fragments, documentName);
        Selected target = walk.descend(operationRoot(schema, operation), operation.getSelectionSet(), fieldPath);
        walk.validate(fieldPath, target.definition().getType(), target.selectionSet(), stub);

        assertThat(walk.problems)
                .as("this %s stub could never arrive from %s at '%s'", documentName, vendor, fieldPath)
                .isEmpty();
    }

    private record Selected(GraphQLFieldDefinition definition, SelectionSet selectionSet) {}

    private static final class Walk {

        private final GraphQLSchema schema;
        private final Map<String, FragmentDefinition> fragments;
        private final String documentName;
        private final List<String> problems = new ArrayList<>();

        private Walk(GraphQLSchema schema, Map<String, FragmentDefinition> fragments, String documentName) {
            this.schema = schema;
            this.fragments = fragments;
            this.documentName = documentName;
        }

        /** Resolves a dotted response path to the field definition and selection set it lands on. */
        private Selected descend(GraphQLType rootType, SelectionSet rootSelection, String fieldPath) {
            GraphQLType type = rootType;
            SelectionSet selectionSet = rootSelection;
            GraphQLFieldDefinition definition = null;
            StringBuilder walked = new StringBuilder();
            for (String segment : fieldPath.split("\\.", -1)) {
                Selected next = selectable(type, selectionSet).get(segment);
                if (next == null) {
                    throw new IllegalArgumentException(
                            "%s does not select '%s'%s, so no stub for '%s' can be validated against it"
                                    .formatted(
                                            documentName,
                                            segment,
                                            walked.isEmpty() ? "" : " under '" + walked + "'",
                                            fieldPath));
                }
                definition = next.definition();
                type = definition.getType();
                selectionSet = next.selectionSet();
                walked.append(walked.isEmpty() ? "" : ".").append(segment);
            }
            if (definition == null) {
                throw new IllegalArgumentException("fieldPath must not be empty");
            }
            return new Selected(definition, selectionSet);
        }

        private void validate(String path, GraphQLType declared, SelectionSet selectionSet, @Nullable Object value) {
            if (value == null) {
                return;
            }
            GraphQLType type = GraphQLTypeUtil.unwrapNonNull(declared);
            if (type instanceof GraphQLList list) {
                if (value instanceof List<?> elements) {
                    for (int i = 0; i < elements.size(); i++) {
                        validate(path + "[" + i + "]", list.getWrappedType(), selectionSet, elements.get(i));
                    }
                } else {
                    problems.add(mismatch(path, declared, value, "a List"));
                }
                return;
            }
            if (type instanceof GraphQLScalarType scalar) {
                validateScalar(path, scalar, declared, value);
                return;
            }
            if (type instanceof GraphQLEnumType) {
                if (!(value instanceof String)) {
                    problems.add(mismatch(path, declared, value, "a String"));
                }
                return;
            }
            if (!(value instanceof Map<?, ?> fields)) {
                problems.add(mismatch(path, declared, value, "a Map"));
                return;
            }
            Map<String, Selected> selectable = selectable(type, selectionSet);
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (TYPENAME.equals(key)) {
                    continue;
                }
                Selected field = selectable.get(key);
                if (field == null) {
                    problems.add("%s.%s is not selected by %s, so the vendor would never return it"
                            .formatted(path, key, documentName));
                    continue;
                }
                validate(path + "." + key, field.definition().getType(), field.selectionSet(), entry.getValue());
            }
        }

        private void validateScalar(String path, GraphQLScalarType scalar, GraphQLType declared, Object value) {
            switch (scalar.getName()) {
                case "Int" ->
                    require(
                            path,
                            declared,
                            value,
                            value instanceof Integer || value instanceof Long,
                            "an Integer or Long");
                case "Float" -> require(path, declared, value, value instanceof Number, "a Number");
                case "String", "ID" -> require(path, declared, value, value instanceof String, "a String");
                case "Boolean" -> require(path, declared, value, value instanceof Boolean, "a Boolean");
                default -> {
                    // Custom scalars (Time, NoteID, GlobalID, …) declare no wire form this validator can read.
                }
            }
        }

        private void require(String path, GraphQLType declared, Object value, boolean satisfied, String expected) {
            if (!satisfied) {
                problems.add(mismatch(path, declared, value, expected));
            }
        }

        /** Flattens a selection set — following fragment spreads and inline fragments — to response key to field. */
        private Map<String, Selected> selectable(GraphQLType parentType, SelectionSet selectionSet) {
            Map<String, Selected> selectable = new LinkedHashMap<>();
            collect(parentType, selectionSet, selectable);
            return selectable;
        }

        private void collect(
                @Nullable GraphQLType parentType, @Nullable SelectionSet selectionSet, Map<String, Selected> into) {
            if (parentType == null || selectionSet == null) {
                return;
            }
            GraphQLType type = GraphQLTypeUtil.unwrapAll(parentType);
            for (Selection<?> selection : selectionSet.getSelections()) {
                if (selection instanceof Field field) {
                    collectField(type, field, into);
                } else if (selection instanceof InlineFragment inlineFragment) {
                    GraphQLType condition = inlineFragment.getTypeCondition() == null
                            ? type
                            : schema.getType(inlineFragment.getTypeCondition().getName());
                    collect(condition, inlineFragment.getSelectionSet(), into);
                } else if (selection instanceof FragmentSpread spread) {
                    FragmentDefinition fragment = fragments.get(spread.getName());
                    if (fragment != null) {
                        collect(
                                schema.getType(fragment.getTypeCondition().getName()),
                                fragment.getSelectionSet(),
                                into);
                    }
                }
            }
        }

        private static void collectField(GraphQLType parentType, Field field, Map<String, Selected> into) {
            if (!(parentType instanceof GraphQLFieldsContainer container)) {
                return;
            }
            GraphQLFieldDefinition definition = container.getFieldDefinition(field.getName());
            if (definition != null) {
                String responseKey = field.getAlias() == null ? field.getName() : field.getAlias();
                into.putIfAbsent(responseKey, new Selected(definition, field.getSelectionSet()));
            }
        }

        private static String mismatch(String path, GraphQLType declared, Object value, String expected) {
            return "%s is declared %s and needs %s, but the stub has %s (%s)"
                    .formatted(
                            path,
                            GraphQLTypeUtil.simplePrint(declared),
                            expected,
                            value.getClass().getSimpleName(),
                            value);
        }
    }

    private static GraphQLObjectType operationRoot(GraphQLSchema schema, OperationDefinition operation) {
        if (operation.getOperation() == OperationDefinition.Operation.MUTATION) {
            return schema.getMutationType();
        }
        if (operation.getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
            return schema.getSubscriptionType();
        }
        return schema.getQueryType();
    }

    private static GraphQLSchema schema(Vendor vendor) {
        // Vendor schemas are megabytes of SDL; parse each at most once per JVM.
        return SCHEMAS.computeIfAbsent(vendor, key -> {
            TypeDefinitionRegistry registry = new SchemaParser().parse(read(new ClassPathResource(key.schemaLocation)));
            return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        });
    }

    private static Document document(Vendor vendor, String documentName) {
        return DOCUMENTS
                .computeIfAbsent(vendor, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(documentName, name -> {
                    String text = documentSource(vendor).getDocument(name).block();
                    if (text == null) {
                        throw new IllegalArgumentException(
                                "No %s operation document named '%s'".formatted(vendor, name));
                    }
                    return new Parser().parseDocument(text);
                });
    }

    private static FragmentMergingDocumentSource documentSource(Vendor vendor) {
        return DOCUMENT_SOURCES.computeIfAbsent(
                vendor,
                key -> new FragmentMergingDocumentSource(
                        Stream.concat(
                                        Stream.of((Resource) new ClassPathResource(key.operationsLocation)),
                                        key.fragmentResources().stream())
                                .toList(),
                        List.of(".graphql", ".gql"),
                        key.fragmentResources()));
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
