package de.tum.cit.aet.hephaestus.practices.curated;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidanceRules;
import de.tum.cit.aet.hephaestus.practices.PracticeGuide;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.PracticeVisual;
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
    private static final String GUIDANCE_RESOURCES = "practices/guidance/";

    private final BundledPracticeCatalog catalog;
    private final Map<String, String> holdsAsBySlug;
    private final Map<String, PracticeDefinition> withdrawnBySlug;

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
        Map<String, PracticeDefinition> withdrawn = new HashMap<>();
        for (BundledEntry<PracticeDefinition> practice : catalog.practices()) {
            if (practice.definition().automatedReviewPolicy().insufficiencyReason() != null) {
                withdrawn.put(practice.slug(), practice.definition());
            }
        }
        this.withdrawnBySlug = Map.copyOf(withdrawn);
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

    /**
     * The bundled practices whose question the evidence Hephaestus collects cannot answer, as shipped. Read
     * from the shipped file, so no instance or workspace edit can remove an entry.
     */
    public Map<String, PracticeDefinition> withdrawnFromAutomatedReview() {
        return withdrawnBySlug;
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
                        // A practice Hephaestus does not review has no phrase: nothing it recorded can support one.
                        practiceNode.has("insufficiencyReason") ? null : requiredText(practiceNode, "holdsAs")));
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
        CatalogOccasion occasion = occasion(objectMapper, node, slug);
        ArtifactKind artifactKind = occasion.signals().getFirst().artifactKind();
        String preambleKey = text(node, "preamble");
        if (preambleKey == null) {
            preambleKey = artifactKind.value();
        }
        String criteria = composeCriteria(catalog, preambleKey, requiredText(node, "criteria"));
        String whyItMatters = text(node, "whyItMatters");
        String whatGoodLooksLike = text(node, "whatGoodLooksLike");
        PracticeDefinition definition = new PracticeDefinition(
                requiredText(node, "name"),
                occasion.signals(),
                occasion.evidenceRequirements() == null
                        ? evidenceDefaults.needsFor(artifactKind)
                        : occasion.evidenceRequirements(),
                occasion.reviewWhen() == null
                        ? evidenceDefaults.reviewWhenFor(artifactKind)
                        : evidenceDefaults.normalizeReviewWhen(artifactKind, occasion.reviewWhen()),
                occasion.subject() == null ? ActorRole.AUTHOR : occasion.subject(),
                occasion.precondition(),
                criteria,
                loadPrecomputeScript(node, slug),
                policy(objectMapper, evidenceDefaults.policyFor(artifactKind), node, slug),
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                deliveryBehavior(objectMapper, node, slug),
                visual(node, slug),
                guide(node, slug));
        definitionValidator.validate(definition);
        return definition;
    }

    /**
     * Every bundled practice takes its kind's default contract, mode and limits. The one thing the authoring
     * file may add is a reason the collected evidence cannot answer the practice's question, which ships it
     * as needing human review: a question with no eligible evidence stays in the catalog as guidance
     * rather than being put to a model that could only answer it from the wrong record.
     */
    private static PracticeAutomatedReviewPolicy policy(
            JsonMapper mapper, PracticeAutomatedReviewPolicy defaults, JsonNode node, String slug) {
        JsonNode reason = node.get("insufficiencyReason");
        if (reason == null) {
            return defaults;
        }
        try {
            return defaults.withdrawnFor(mapper.treeToValue(reason, PracticeEvidenceLimitation.class));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid insufficiency reason: " + slug, exception);
        }
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

    private static CatalogOccasion occasion(JsonMapper objectMapper, JsonNode node, String slug) {
        try {
            return objectMapper.treeToValue(node, CatalogOccasion.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid bundled practice occasion: " + slug, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogOccasion(
            List<SignalName> signals,
            @Nullable List<PracticeEvidenceRequirement> evidenceRequirements,
            @Nullable Map<String, Set<String>> reviewWhen,
            @Nullable ActorRole subject,
            @Nullable PracticePrecondition precondition) {
        CatalogOccasion {
            signals = List.copyOf(signals);
            if (signals.isEmpty()) {
                throw new IllegalArgumentException("Choose at least one moment that starts a review.");
            }
            if (evidenceRequirements != null) {
                evidenceRequirements = List.copyOf(evidenceRequirements);
                if (evidenceRequirements.isEmpty()) {
                    throw new IllegalArgumentException("Declare the evidence the practice reads.");
                }
            }
        }
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

    /**
     * The visual a bundled practice opens with: an SVG file under its own guidance folder, and the words that say
     * what it shows. The file stays a file so a maintainer can preview and review it as a picture.
     */
    private static @Nullable PracticeVisual visual(JsonNode node, String slug) {
        JsonNode visual = node.get("visual");
        if (visual == null) {
            return null;
        }
        return new PracticeVisual(
                readGuidanceResource(slug, requiredText(visual, "file")), requiredText(visual, "alt"));
    }

    /**
     * The Read more guide of a bundled practice and every figure it shows. A figure is read from the path the
     * Markdown names, relative to the guide, so GitHub previews the guide with its figures.
     */
    private static @Nullable PracticeGuide guide(JsonNode node, String slug) {
        String path = text(node, "guide");
        if (path == null) {
            return null;
        }
        String markdown = readGuidanceResource(slug, path);
        Map<String, String> figures = new HashMap<>();
        for (String name : PracticeGuidanceRules.figureNames(markdown)) {
            figures.put(name, readGuidanceResource(slug, GUIDANCE_RESOURCES + slug + "/figures/" + name + ".svg"));
        }
        return new PracticeGuide(markdown, figures);
    }

    private static String readGuidanceResource(String slug, String path) {
        if (!path.startsWith(GUIDANCE_RESOURCES + slug + "/") || path.contains("..")) {
            throw new IllegalStateException(
                    "bundled guidance must live under " + GUIDANCE_RESOURCES + slug + "/: " + path);
        }
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("bundled guidance file does not exist: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read bundled guidance file: " + path, exception);
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
