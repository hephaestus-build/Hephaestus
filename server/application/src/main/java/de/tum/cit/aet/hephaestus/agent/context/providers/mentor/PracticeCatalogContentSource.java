package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidanceRules;
import de.tum.cit.aet.hephaestus.practices.PracticeGuide;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVisual;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomyPolicy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/practice_catalog.json} for {@link HephTurnRequest}.
 *
 * <p>Lists workspace practices with criteria and developer guidance so the mentor agent can talk about specific
 * coding standards that apply to the user's contributions, in the words the practice panel uses. Independent of
 * {@code developerId} — every member of a workspace sees the same practice catalog, so the cache key is just
 * the workspace.
 *
 * <p><b>Freshness is TTL-bounded only.</b> The {@code mentor_practice_context} cache has no
 * event-driven invalidation: there is no Practice-change domain event to hang an eviction off. An
 * admin edit to a practice (criteria text, activate/deactivate, rename, group reassignment) is
 * picked up only after the entry expires (MENTOR_CONTEXT_TTL, see {@code CacheConfig}). This is an
 * accepted limit — the catalog is low-churn admin data and a few minutes of staleness in mentor
 * chat is harmless — not an oversight; wire a Practice-change event into the invalidator if that
 * window ever needs to close.
 */
@Component
@RequiredArgsConstructor
public class PracticeCatalogContentSource implements ContentSource {

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "practice_catalog.json";

    private static final String CACHE_NAME = "mentor_practice_context";

    /** Practices listed in full; the rest are named in {@code omittedPractices}. */
    private static final int MAX_PRACTICES = 20;

    /** The catalog's own bound, well below the fetch cap, so it is never cut inside a practice. */
    private static final int MAX_CATALOG_CHARS = 64_000;

    private final PracticeRepository practiceRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof HephTurnRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /** Tx-on-contribute / not-on-buildPayload AOP convention documented at {@link MentorContextKeys}. */
    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        HephTurnRequest req = (HephTurnRequest) request;
        Long key = req.workspaceId();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        // Atomic compute-if-absent closes the get/build/put race on invalidation events.
        ObjectNode payload = (cache != null)
                ? cache.get(key, () -> buildPayload(req.workspaceId()))
                : buildPayload(req.workspaceId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize practice catalog context", e);
        }
    }

    /** Pure function of (workspaceId). Callers cache through {@link CacheManager}. */
    public ObjectNode buildPayload(Long workspaceId) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        // Resolve effective autonomy through the practice, group and workspace. OFF practices
        // are excluded; HUMAN_APPROVAL allows the on-demand conversation channel.
        WorkspaceReviewDefaults defaults = WorkspaceReviewDefaults.of(workspace);
        List<Practice> practices = practiceRepository.findByWorkspaceId(workspaceId).stream()
                .filter(p -> PracticeAutonomyPolicy.delivers(
                        ObservationOrigin.LIVE,
                        AutonomyResolver.effectiveAutonomyOf(p, defaults.defaultAutonomy()),
                        FeedbackChannel.IN_CHAT))
                .toList();

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("workspace").put("slug", workspace.getWorkspaceSlug());
        root.put("totalPractices", practices.size());

        // By group, then by name: the catalog's own order, never a ranking.
        List<Practice> ordered = practices.stream()
                .sorted(Comparator.comparing((Practice p) -> groupName(p), Comparator.nullsLast(String::compareTo))
                        .thenComparing(Practice::getName)
                        .thenComparing(Practice::getSlug))
                .toList();
        ArrayNode arr = root.putArray("practices");
        for (Practice practice : ordered.subList(0, Math.min(ordered.size(), MAX_PRACTICES))) {
            ObjectNode node = arr.addObject();
            node.put("slug", practice.getSlug());
            node.put("displayName", practice.getName());
            node.put("group", groupName(practice));
            node.put("criteria", practice.getCriteria());
            node.put("whyItMatters", practice.getWhyItMatters());
            node.put("whatGoodLooksLike", practice.getWhatGoodLooksLike());
            PracticeVisual visual = practice.getVisual();
            node.put("visualDescription", visual == null ? null : visual.alt());
            PracticeGuide guide = practice.getGuide();
            node.put("guide", guide == null ? null : PracticeGuidanceRules.describeFigures(guide.markdown()));
        }
        // Whole practices first: a cut inside one would read as a complete criterion. The bound measures the whole
        // file, with its list of what it left out.
        ArrayNode omitted = root.putArray("omittedPractices");
        ordered.subList(arr.size(), ordered.size()).forEach(practice -> omitted.add(practice.getSlug()));
        root.put("truncated", !omitted.isEmpty());
        while (arr.size() > 1 && !fits(root)) {
            omitted.insert(0, arr.remove(arr.size() - 1).path("slug").asString());
            root.put("truncated", true);
        }
        if (!arr.isEmpty() && !fits(root)) {
            shorten(root, (ObjectNode) arr.get(0));
            root.put("truncated", true);
        }
        return root;
    }

    /** The texts of a practice that may be shortened, each marked when it is. */
    private static final List<String> SHORTENABLE =
            List.of("criteria", "whyItMatters", "whatGoodLooksLike", "guide", "visualDescription");

    /** Fits the one practice left by halving its longest text until the file fits, naming each text it shortened. */
    private void shorten(ObjectNode root, ObjectNode practice) {
        ArrayNode shortened = practice.putArray("shortenedFields");
        while (!fits(root)) {
            Optional<String> found = SHORTENABLE.stream()
                    .filter(field -> practice.path(field).isString())
                    .max(Comparator.comparingInt(
                            field -> practice.path(field).asString().length()));
            if (found.isEmpty() || practice.path(found.get()).asString().isEmpty()) {
                return;
            }
            String longest = found.get();
            String text = practice.path(longest).asString();
            int keep = text.length() / 2;
            int end = keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1)) ? keep - 1 : keep;
            practice.put(longest, text.substring(0, end));
            if (!shortened.valueStream().anyMatch(name -> name.asString().equals(longest))) {
                shortened.add(longest);
            }
        }
    }

    private boolean fits(ObjectNode root) {
        return objectMapper.writeValueAsString(root).length() <= MAX_CATALOG_CHARS;
    }

    private static @Nullable String groupName(Practice practice) {
        PracticeGroup group = practice.getGroup();
        return group == null ? null : group.getName();
    }
}
