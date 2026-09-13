package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Injects the practice registry, criteria, and precompute scripts into a job's workspace under
 * {@code inputs/practices/} and {@code work/precompute/}. Shared by every {@link de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler}
 * regardless of artifact — the catalog is per-job (workspace-active practices), not provider-shaped,
 * so it does not live behind the {@code ContentSource} SPI.
 *
 * <p>Filters by {@link ArtifactKind}: a PR job injects only PR-focus practices, an issue job only
 * issue-focus practices — so a diff-anchored practice never reaches an issue (and vice-versa).
 */
class PracticeCatalogInjector {

    /** Job-metadata key naming the signal that occasioned the review. */
    static final String SIGNAL_METADATA_KEY = "signal";

    private static final Logger log = LoggerFactory.getLogger(PracticeCatalogInjector.class);

    private final JsonMapper objectMapper;
    private final PracticeRepository practiceRepository;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;

    PracticeCatalogInjector(
            JsonMapper objectMapper,
            PracticeRepository practiceRepository,
            WorkspaceReviewDefaultsProvider workspaceDefaults) {
        this.objectMapper = objectMapper;
        this.practiceRepository = practiceRepository;
        this.workspaceDefaults = workspaceDefaults;
    }

    /**
     * Resolve {@code slug -> whyItMatters} for the {@code focus}-scoped active practices of a workspace,
     * surfaced verbatim as the "Why this matters" line on critiques. Deliberately NOT written into the model
     * workspace — only {@code getCriteria()} reaches the agent — so the principle stays server-controlled and
     * cannot be fabricated or drift in model prose. Practices with a blank principle are omitted.
     */
    Map<String, String> whyBySlug(Workspace workspace, ArtifactKind focus) {
        return reviewedPractices(workspace, focus).stream()
                .filter(p -> p.getWhyItMatters() != null && !p.getWhyItMatters().isBlank())
                .collect(Collectors.toMap(Practice::getSlug, Practice::getWhyItMatters, (a, b) -> a));
    }

    /** Stage exactly the immutable revisions which produced a composition job's observations. */
    Map<String, String> injectComposition(Map<String, byte[]> files, List<Observation> observations) {
        Map<String, PracticeRevision> revisions = new LinkedHashMap<>();
        for (Observation observation : observations) {
            PracticeRevision revision = observation.getPracticeRevision();
            if (revision == null || revision.getId() == null) {
                throw new JobPreparationException(
                        "Observation has no reproducible practice revision: " + observation.getId());
            }
            PracticeRevision prior = revisions.putIfAbsent(revision.getSlug(), revision);
            if (prior != null && !Objects.equals(prior.getId(), revision.getId())) {
                throw new JobPreparationException(
                        "One composition contains multiple revisions of " + revision.getSlug());
            }
        }
        ArrayNode index = objectMapper.createArrayNode();
        StringBuilder bundle = new StringBuilder();
        Map<String, String> why = new LinkedHashMap<>();
        revisions.values().stream()
                .sorted(Comparator.comparing(PracticeRevision::getSlug))
                .forEach(revision -> {
                    ObjectNode entry = index.addObject();
                    entry.put("slug", revision.getSlug());
                    entry.put("name", revision.getName());
                    entry.put("revisionId", revision.getId());
                    String criteria = revision.getCriteria();
                    files.put(
                            SandboxLayout.PRACTICES_PREFIX + revision.getSlug() + ".md",
                            criteria.getBytes(StandardCharsets.UTF_8));
                    bundle.append("# ")
                            .append(revision.getSlug())
                            .append("\n\n")
                            .append(criteria)
                            .append("\n\n---\n\n");
                    if (revision.getWhyItMatters() != null
                            && !revision.getWhyItMatters().isBlank()) {
                        why.put(revision.getSlug(), revision.getWhyItMatters());
                    }
                });
        try {
            files.put(SandboxLayout.PRACTICES_PREFIX + "index.json", objectMapper.writeValueAsBytes(index));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize composition practice index: " + e.getMessage(), e);
        }
        files.put(
                SandboxLayout.PRACTICES_PREFIX + "all-criteria.md",
                bundle.toString().getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(why);
    }

    private static JsonNode admittedPractices(AgentJob job) {
        JsonNode snapshot = job.getEvidenceSnapshot();
        JsonNode practices = snapshot == null ? null : snapshot.path("practices");
        if (practices == null || !practices.isArray()) {
            throw new JobDeliveryException("Job has no admitted practice snapshot: jobId=" + job.getId());
        }
        return practices;
    }

    boolean isAdmitted(AgentJob job, String slug) {
        for (JsonNode practice : admittedPractices(job)) {
            if (slug.equals(practice.path("slug").asString())
                    && practice.path("revisionId").isIntegralNumber()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject the {@code focus}-scoped active practice catalog for {@code job}'s workspace into
     * {@code files}.
     *
     * @throws JobPreparationException if the job has no workspace, no matching active practices, or a
     *     slug violates the workspace ABI pattern.
     */
    void inject(Map<String, byte[]> files, AgentJob job, ArtifactKind focus) {
        inject(files, job, focus, resolveEligiblePractices(job, focus));
    }

    /**
     * The practices of one workspace and work type whose <em>effective</em> autonomy admits a review.
     *
     * <p>Resolved in the JVM rather than filtered in SQL. A practice that holds no autonomy of its own inherits
     * one, and a {@code autonomy <> 'OFF'} predicate answers UNKNOWN for it — so the SQL form silently
     * dropped exactly the practices the inheritance chain exists to serve.
     */
    private List<Practice> reviewedPractices(Workspace workspace, ArtifactKind focus) {
        // By ID, not off the entity: the job's workspace association is lazy and the job is detached on
        // some paths, so reading the settings here would make the catalogue depend on whether the caller
        // holds a session.
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(workspace.getId()).defaultAutonomy();
        return practiceRepository.findByWorkspaceIdAndArtifactKind(workspace.getId(), focus).stream()
                .filter(p -> AutonomyResolver.effectiveAutonomyOf(p, workspaceDefault)
                        .admitsReview())
                .toList();
    }

    List<Practice> resolveEligiblePractices(AgentJob job, ArtifactKind focus) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        List<Practice> practices = reviewedPractices(job.getWorkspace(), focus).stream()
                .sorted(Comparator.comparing(Practice::getSlug))
                .toList();
        SignalName signal = signalOf(job);
        if (signal != null) {
            practices = practices.stream()
                    .filter(p -> p.getBindings().stream().anyMatch(binding -> binding.matches(signal)))
                    .toList();
        }
        practices = practices.stream().filter(p -> attributable(p, signal, job)).toList();
        if (practices.isEmpty()) {
            throw new JobPreparationException("No active " + focus
                    + " practices this review can attribute to a person: workspaceId="
                    + job.getWorkspace().getId()
                    + ", jobId="
                    + job.getId());
        }
        for (Practice p : practices) {
            String slug = p.getSlug();
            if (slug == null || !SandboxLayout.PRACTICE_SLUG.matcher(slug).matches()) {
                throw new JobPreparationException(
                        "Practice slug fails ABI pattern " + SandboxLayout.PRACTICE_SLUG.pattern() + ": " + slug);
            }
        }
        return practices;
    }

    private boolean attributable(Practice practice, @Nullable SignalName signal, AgentJob job) {
        ActorRole subject = PracticeBinding.subjectRoleOf(practice.getBindings(), signal);
        JsonNode metadata = job.getMetadata();
        boolean reviewerRun = metadata != null
                && metadata.path("about_user_id").isNumber()
                && "REVIEWER".equals(metadata.path("subject_role").asString());
        if (subject == ActorRole.AUTHOR) {
            return !reviewerRun;
        }
        if (subject == ActorRole.REVIEWER && reviewerRun) {
            return true;
        }
        log.debug(
                "Practice withheld from review: no {} this job can name, slug={}, jobId={}",
                subject,
                practice.getSlug(),
                job.getId());
        return false;
    }

    void inject(Map<String, byte[]> files, AgentJob job, ArtifactKind focus, List<Practice> practices) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        Long workspaceId = job.getWorkspace().getId();

        ArrayNode index = objectMapper.createArrayNode();
        for (Practice p : practices) {
            String groupSlug = p.getGroup() != null ? p.getGroup().getSlug() : p.getSlug();
            ObjectNode entry = index.addObject();
            entry.put("slug", p.getSlug());
            entry.put("name", p.getName());
            entry.put("group", groupSlug);
            if (p.getCurrentRevision() == null || p.getCurrentRevision().getId() == null) {
                throw new JobPreparationException("Practice has no current revision: " + p.getSlug());
            }
            entry.put("revisionId", p.getCurrentRevision().getId());
            // A pointer, not a fence: what may be CITED is what the run staged (inputs/manifest.json), so
            // reading beyond this list is expected, not a violation.
            ArrayNode readsSources = entry.putArray("readsSources");
            PracticeBinding.needsFor(p.getBindings(), signalOf(job)).stream()
                    .map(need -> need.sourceKind().value())
                    .distinct()
                    .sorted()
                    .forEach(readsSources::add);
            // The sources the practice claims to have searched exhaustively — what makes an ABSENT
            // observation assertable, and what the runner requires be searched before accepting one.
            ArrayNode exhaustiveSources = entry.putArray("exhaustiveSources");
            PracticeBinding.needsFor(p.getBindings(), signalOf(job)).stream()
                    .filter(need -> need.stance() == EvidenceStance.EXHAUSTIVE)
                    .map(need -> need.sourceKind().value())
                    .distinct()
                    .sorted()
                    .forEach(exhaustiveSources::add);
        }
        try {
            files.put(SandboxLayout.PRACTICES_PREFIX + "index.json", objectMapper.writeValueAsBytes(index));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize practice index.json: " + e.getMessage(), e);
        }

        StringBuilder bundle = new StringBuilder();
        for (Practice p : practices) {
            String criteria = p.getCriteria() + renderKnownLimitations(p);
            files.put(SandboxLayout.PRACTICES_PREFIX + p.getSlug() + ".md", criteria.getBytes(StandardCharsets.UTF_8));
            bundle.append("# ")
                    .append(p.getSlug())
                    .append("\n\n")
                    .append(criteria)
                    .append("\n\n---\n\n");
        }
        files.put(
                SandboxLayout.PRACTICES_PREFIX + "all-criteria.md",
                bundle.toString().getBytes(StandardCharsets.UTF_8));

        files.put(SandboxLayout.ANALYSIS_PRACTICES_PREFIX + ".gitkeep", new byte[0]);

        int precomputeCount = 0;
        for (Practice p : practices) {
            String script = p.getPrecomputeScript();
            if (script != null && !script.isBlank()) {
                files.put(
                        SandboxLayout.PRECOMPUTE_PREFIX + "practices/" + p.getSlug() + ".ts",
                        script.getBytes(StandardCharsets.UTF_8));
                precomputeCount++;
            }
        }

        log.info(
                "Injected practice catalog: {} {} practices ({} with precompute), workspaceId={}, jobId={}",
                practices.size(),
                focus,
                precomputeCount,
                workspaceId,
                job.getId());
    }

    /** The claims this practice's evidence cannot support, appended to the criteria staged for the model. */
    private static String renderKnownLimitations(Practice p) {
        List<PracticeEvidenceLimitation> limitations =
                p.getAutomatedReviewPolicy().knownLimitations();
        if (limitations.isEmpty()) {
            return "";
        }
        // Each bullet states a limit of the evidence, not a claim to avoid. Reading them as claims
        // inverts the instruction: it would forbid the hedge and leave the overclaim it guards against.
        StringBuilder section = new StringBuilder("\n\n## What this evidence cannot show\n\n");
        section.append("Do not state or imply any conclusion that would require going beyond these limits:\n\n");
        for (PracticeEvidenceLimitation limitation : limitations) {
            section.append("- ").append(limitation.description()).append("\n");
        }
        return section.toString();
    }

    /**
     * The signal that occasioned this job, or {@code null} when nobody named one.
     *
     * <p>Null is the gate-bypass path — a review somebody asked for by hand — and it means every active
     * practice of the kind runs, reading everything any of its bindings reads. Narrowing that to one
     * binding would answer a narrower question than the one asked.
     */
    @Nullable
    static SignalName signalOf(AgentJob job) {
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            return null;
        }
        JsonNode node = metadata.get(SIGNAL_METADATA_KEY);
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString();
        return (value == null || value.isBlank()) ? null : SignalName.of(value);
    }
}
