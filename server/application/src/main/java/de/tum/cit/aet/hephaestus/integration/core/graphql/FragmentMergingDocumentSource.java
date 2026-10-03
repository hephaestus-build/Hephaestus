package de.tum.cit.aet.hephaestus.integration.core.graphql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.graphql.support.ResourceDocumentSource;
import reactor.core.publisher.Mono;

/**
 * A {@link org.springframework.graphql.support.DocumentSource DocumentSource} that
 * selectively appends shared GraphQL fragment definitions to loaded operation documents,
 * but <em>only</em> those fragments that are actually referenced.
 * <p>
 * GitHub's GraphQL API enforces
 * <a href="https://spec.graphql.org/June2018/#sec-Fragments-Must-Be-Used">
 * §5.5.1.4 Fragments Must Be Used</a> &mdash; any fragment defined but not referenced
 * causes a validation error with no {@code data} returned. A naive "append all fragments"
 * approach therefore breaks every query that doesn't use every fragment.
 * <p>
 * This implementation:
 * <ol>
 *   <li>Parses all fragment resources at construction time into a map of
 *       {@code fragmentName → fragmentText}.</li>
 *   <li>For each loaded document, scans for {@code ...FragmentName} spreads and
 *       resolves the transitive closure (fragments that reference other fragments).</li>
 *   <li>Appends only the required fragments, keeping requests minimal.</li>
 * </ol>
 * <p>
 * Fragment content is loaded and parsed once at construction time for efficiency.
 * <p>
 * Pattern from: <a href="https://github.com/spring-projects/spring-graphql/issues/964">
 * spring-graphql#964</a>
 *
 * @see ResourceDocumentSource
 */
public class FragmentMergingDocumentSource extends ResourceDocumentSource {

    // Matches "fragment FragmentName on TypeName { ... }" blocks.
    // Uses a simple brace-counting approach rather than regex for the body,
    // so we split on "fragment <Name> on" boundaries instead.
    private static final Pattern FRAGMENT_DEF_BOUNDARY = Pattern.compile("(?m)^\\s*fragment\\s+(\\w+)\\s+on\\s+");

    // Matches "...FragmentName" spreads (not inline spreads like "... on Type").
    private static final Pattern FRAGMENT_SPREAD = Pattern.compile("\\.\\.\\.(\\w+)");

    private final Map<String, String> fragmentsByName;

    /**
     * Creates a new {@code FragmentMergingDocumentSource}.
     *
     * @param locations         classpath locations to search for operation documents
     * @param extensions        file extensions to match (e.g. ".graphql", ".gql")
     * @param fragmentResources resources containing shared fragment definitions to append
     * @throws IllegalStateException if any fragment resource cannot be read
     */
    public FragmentMergingDocumentSource(
            List<Resource> locations, List<String> extensions, List<Resource> fragmentResources) {
        super(locations, extensions);
        this.fragmentsByName = parseFragmentsPerResource(fragmentResources);
    }

    @Override
    public Mono<String> getDocument(String name) {
        return super.getDocument(name).map(this::appendReferencedFragments);
    }

    /**
     * Scans {@code document} for fragment spreads and appends only the transitively
     * referenced fragments from the shared pool.
     */
    private String appendReferencedFragments(String document) {
        Set<String> needed = resolveTransitive(document);
        if (needed.isEmpty()) {
            return document;
        }

        StringBuilder sb = new StringBuilder(document);
        for (String fragmentName : needed) {
            String fragmentText = fragmentsByName.get(fragmentName);
            if (fragmentText != null) {
                sb.append('\n').append(fragmentText);
            }
        }
        return sb.toString();
    }

    /**
     * Finds all fragment names referenced (transitively) by {@code text}.
     * A fragment spread is {@code ...FragmentName}; if that fragment itself
     * references other fragments, those are included too.
     */
    private Set<String> resolveTransitive(String text) {
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> pending = findSpreads(text);
        // Only consider fragments we actually have definitions for
        pending.retainAll(fragmentsByName.keySet());

        while (!pending.isEmpty()) {
            String next = pending.iterator().next();
            pending.remove(next);
            if (resolved.add(next)) {
                String fragmentText = fragmentsByName.get(next);
                if (fragmentText != null) {
                    Set<String> transitive = findSpreads(fragmentText);
                    transitive.retainAll(fragmentsByName.keySet());
                    transitive.removeAll(resolved);
                    pending.addAll(transitive);
                }
            }
        }
        return resolved;
    }

    /**
     * Extracts all {@code ...FragmentName} references from a GraphQL document string.
     * Excludes inline fragment spreads ({@code ... on TypeName}) which the regex
     * naturally ignores since "on" is not followed by a word-boundary-starting identifier
     * in the pattern.
     */
    private static Set<String> findSpreads(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = FRAGMENT_SPREAD.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            // Exclude inline spreads: "... on TypeName" — the captured word would be "on"
            if (!"on".equals(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Parses raw GraphQL content into a map of fragment name to complete fragment text
     * (including the {@code fragment Name on Type { ... }} block).
     */
    static Map<String, String> parseFragments(@Nullable String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> fragments = new LinkedHashMap<>();
        Matcher m = FRAGMENT_DEF_BOUNDARY.matcher(rawContent);

        // Find each "fragment Name on ..." boundary
        int prevStart = -1;
        String prevName = null;

        while (m.find()) {
            if (prevName != null) {
                fragments.put(
                        prevName, rawContent.substring(prevStart, m.start()).trim());
            }
            prevStart = m.start();
            prevName = m.group(1);
        }
        // Last fragment goes to end of string
        if (prevName != null) {
            fragments.put(prevName, rawContent.substring(prevStart).trim());
        }

        return Collections.unmodifiableMap(fragments);
    }

    /**
     * Parses each resource on its own, deliberately: the last fragment in a file runs "to end of
     * string", so concatenating first would staple the next file's header comment onto it — and a
     * {@code ...Name} written inside such a comment then reads as a real spread, dragging an unrelated
     * fragment into every request that uses the neighbour. A conformant server rejects the whole query
     * for the unused fragment that results (GraphQL spec §5.5.1.4, "Fragments Must Be Used").
     */
    private static Map<String, String> parseFragmentsPerResource(List<Resource> resources) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Resource resource : resources) {
            merged.putAll(parseFragments(readResource(resource)));
        }
        return Collections.unmodifiableMap(merged);
    }

    private static String readResource(Resource resource) {
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load GraphQL fragment resource: " + resource.getDescription(), e);
        }
    }
}
