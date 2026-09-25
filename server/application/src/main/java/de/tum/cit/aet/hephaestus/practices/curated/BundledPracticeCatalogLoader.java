package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalog.BundledEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BundledPracticeCatalogLoader {

    private static final String CATALOG_RESOURCE = "practices/default-catalog.json";

    private final BundledPracticeCatalog catalog;
    private final Map<String, String> holdsAsBySlug;

    BundledPracticeCatalogLoader(
            JsonMapper objectMapper,
            PracticeDefinitionValidator definitionValidator,
            PracticeEvidenceDefaults evidenceDefaults) {
        this.catalog = parse(objectMapper, definitionValidator, evidenceDefaults);
        Map<String, String> phrases = new HashMap<>();
        for (BundledEntry<PracticeDefinition> practice : catalog.practices()) {
            String holdsAs = practice.holdsAs();
            if (holdsAs != null) {
                phrases.put(practice.slug(), holdsAs);
            }
        }
        this.holdsAsBySlug = Map.copyOf(phrases);
    }

    BundledPracticeCatalog catalog() {
        return catalog;
    }

    /**
     * What the developer keeps doing when this bundled practice holds, as one present-tense sentence.
     *
     * <p>Keyed by the bundled slug, which a workspace copy retains as its source slug, so the phrase reaches
     * every workspace adopted from the catalog and follows a Hephaestus release rather than an adoption. Empty for a
     * practice the catalog does not ship.
     */
    public Optional<String> holdsAs(String bundledSlug) {
        return Optional.ofNullable(holdsAsBySlug.get(bundledSlug));
    }

    private static BundledPracticeCatalog parse(
            JsonMapper objectMapper,
            PracticeDefinitionValidator definitionValidator,
            PracticeEvidenceDefaults evidenceDefaults) {
        JsonNode root = readCatalog(objectMapper);
        List<BundledEntry<GroupDefinition>> groups = new ArrayList<>();
        List<BundledEntry<PracticeDefinition>> practices = new ArrayList<>();
        Set<String> groupSlugs = new HashSet<>();
        Set<String> practiceSlugs = new HashSet<>();
        JsonNode groupsNode = root.path("groups");
        if (!groupsNode.isArray()) {
            throw new IllegalStateException("default practice catalog groups must be an array");
        }
        int groupPosition = 0;
        for (JsonNode groupNode : groupsNode) {
            String groupSlug = requiredText(groupNode, "slug");
            if (!groupSlugs.add(groupSlug)) {
                throw new IllegalStateException("duplicate bundled practice group slug: " + groupSlug);
            }
            groups.add(new BundledEntry<>(
                    groupSlug,
                    new GroupDefinition(
                            requiredText(groupNode, "name"),
                            text(groupNode, "description"),
                            text(groupNode, "icon"),
                            text(groupNode, "color")),
                    groupPosition++,
                    null));

            JsonNode practicesNode = groupNode.path("practices");
            if (!practicesNode.isArray()) {
                throw new IllegalStateException("bundled practice group practices must be an array: " + groupSlug);
            }
            int practicePosition = 0;
            for (JsonNode practiceNode : practicesNode) {
                String slug = requiredText(practiceNode, "slug");
                if (!practiceSlugs.add(slug)) {
                    throw new IllegalStateException("duplicate bundled practice slug: " + slug);
                }
                practices.add(new BundledEntry<>(
                        slug,
                        definition(
                                objectMapper,
                                definitionValidator,
                                evidenceDefaults,
                                root,
                                groupSlug,
                                practiceNode,
                                slug),
                        practicePosition++,
                        requiredText(practiceNode, "holdsAs")));
            }
        }
        if (groups.isEmpty() || practices.isEmpty()) {
            throw new IllegalStateException("default practice catalog must contain groups and practices");
        }
        return new BundledPracticeCatalog(List.copyOf(groups), List.copyOf(practices));
    }

    private static PracticeDefinition definition(
            JsonMapper objectMapper,
            PracticeDefinitionValidator definitionValidator,
            PracticeEvidenceDefaults evidenceDefaults,
            JsonNode catalog,
            String groupSlug,
            JsonNode node,
            String slug) {
        List<PracticeBinding> bindings = bindings(objectMapper, evidenceDefaults, node, slug);
        ArtifactKind artifactKind = PracticeBinding.artifactKindOf(bindings);
        String preambleKey = text(node, "preamble");
        if (preambleKey == null) {
            preambleKey = artifactKind.value();
        }
        String criteria = composeCriteria(catalog, preambleKey, requiredText(node, "criteria"));
        String whyItMatters = text(node, "whyItMatters");
        String whatGoodLooksLike = text(node, "whatGoodLooksLike");
        PracticeDefinition definition = new PracticeDefinition(
                requiredText(node, "name"),
                bindings,
                criteria,
                loadPrecomputeScript(node, slug),
                // The authoring file cannot override the review frame: every bundled practice takes its
                // kind's default contract, mode and limits.
                evidenceDefaults.policyFor(artifactKind),
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                deliveryBehavior(objectMapper, node, slug));
        definitionValidator.validate(definition);
        return definition;
    }

    private static PracticeDeliveryBehavior deliveryBehavior(JsonMapper mapper, JsonNode node, String slug) {
        JsonNode value = node.get("deliveryBehavior");
        if (value == null) {
            return PracticeDeliveryBehavior.DEFAULT;
        }
        if (!value.isObject()) {
            throw new IllegalStateException("invalid delivery behavior: " + slug);
        }
        try {
            return mapper.treeToValue(value, PracticeDeliveryBehavior.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid delivery behavior: " + slug, exception);
        }
    }

    /**
     * Reads the {@code on} list.
     *
     * <p>A bare string is a binding on that signal reading the kind's default evidence; requiring each
     * practice to spell those out would only let the copies drift. An object names the evidence instead,
     * and is how a practice that must establish an <em>absence</em> declares the exhaustive capture that
     * licenses the claim.
     */
    private static List<PracticeBinding> bindings(
            JsonMapper objectMapper, PracticeEvidenceDefaults evidenceDefaults, JsonNode node, String slug) {
        JsonNode on = node.path("on");
        if (!on.isArray() || on.isEmpty()) {
            throw new IllegalStateException("bundled practice must declare a non-empty 'on' array: " + slug);
        }
        List<PracticeBinding> bindings = new ArrayList<>();
        for (JsonNode entry : on) {
            if (entry.isString()) {
                SignalName signal = SignalName.of(entry.asString());
                bindings.add(PracticeBinding.on(signal, evidenceDefaults.needsFor(signal.artifactKind())));
                continue;
            }
            if (!entry.isObject()) {
                throw new IllegalStateException("bundled practice binding must be a signal name or object: " + slug);
            }
            PracticeBinding binding;
            try {
                binding = objectMapper.treeToValue(entry, PracticeBinding.class);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("invalid bundled practice binding: " + slug, exception);
            }
            if (binding.needs().isEmpty()) {
                // Every component but `needs` is carried through: filling in the kind's default evidence
                // must not quietly reset whose conduct the occasion judges.
                binding = new PracticeBinding(
                        binding.signals(),
                        evidenceDefaults.needsFor(binding.artifactKind()),
                        binding.onDrafts(),
                        binding.subject(),
                        binding.appliesWhen());
            }
            bindings.add(binding);
        }
        return List.copyOf(bindings);
    }

    private static @Nullable String loadPrecomputeScript(JsonNode node, String slug) {
        String resourcePath = text(node, "precomputeScript");
        if (resourcePath == null) {
            return null;
        }
        var resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("bundled precompute script does not exist: " + resourcePath);
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read bundled precompute script: " + slug, exception);
        }
    }

    private static JsonNode readCatalog(JsonMapper objectMapper) {
        try (InputStream input = new ClassPathResource(CATALOG_RESOURCE).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read default practice catalog", exception);
        }
    }

    private static String composeCriteria(JsonNode catalog, String preambleKey, String criteria) {
        return requiredText(catalog.path("criteriaPreambles"), preambleKey) + "\n\n---\n\n" + criteria;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || !valueNode.isString()) {
            throw new IllegalStateException("bundled catalog field must be text: " + field);
        }
        String value = valueNode.asString();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("bundled catalog field is required: " + field);
        }
        return value;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalStateException("bundled catalog field must be text or null: " + field);
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }
}
