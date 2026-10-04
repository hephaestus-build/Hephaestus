package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.AnsweredPractice;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The practices of a pending push or edit review that a completed review already answered on exactly the code this
 * capture staged, so they are not asked again.
 *
 * <p>Only a practice whose every declared evidence requirement is the pull request's change or the repository tree can
 * be answered: those two sources carry immutable identities ({@code base:head} and {@code commit:tree}) that survive
 * admission, while the description, metadata, comments, linked work and documents have no retained identity that
 * proves them unchanged. The proof is the producing run's own record — its status, the source states and
 * generated-path policy it staged, and the revision each observation was made under — never the catalogue now.
 * Anything unknown leaves the practice to be asked.
 */
@Service
@RequiredArgsConstructor
public class AnsweredPractices {

    private static final Set<String> REVISED_WORK =
            Set.of(ScmSignals.PULL_REQUEST_SYNCHRONIZED.value(), ScmSignals.PULL_REQUEST_EDITED.value());
    private static final Set<SourceKind> CODE = Set.of(PullRequestContentSource.DIFF, RepositoryTreeContentSource.KIND);

    private final ObservationRepository observations;
    private final ObservationVisibilityPolicy visibility;
    private final AgentJobRepository jobs;
    private final ObjectMapper mapper;

    /**
     * The practices among {@code ready} whose current word about the author comes from the latest run of that claim,
     * is authorized for automated review, was made under the revision this review pins, and was produced by a
     * completed run whose staged change, tree and generated-path policy equal this capture's. Rechecked practices are
     * never answered here: they were admitted because the author's revision may have answered them.
     *
     * @param generatedPaths the generated-path policy this capture stages, or {@code null} when it stages none
     */
    @Transactional(readOnly = true)
    public List<AnsweredPractice> answered(
            AgentJob job, List<Practice> ready, JobFolderIndex manifest, byte @Nullable [] generatedPaths) {
        JsonNode metadata = job.getMetadata();
        if (generatedPaths == null
                || metadata == null
                || job.getJobType() != AgentJobType.PULL_REQUEST_REVIEW
                || job.getPracticeTriggerMode() != TriggerMode.AUTO
                || !REVISED_WORK.contains(metadata.path(PracticeCatalogInjector.SIGNAL_METADATA_KEY)
                        .asString(""))
                || "REVIEWER".equals(metadata.path("subject_role").asString(""))
                || !metadata.path(PracticeCatalogInjector.AUTHOR_ID_METADATA_KEY)
                        .isIntegralNumber()
                || !metadata.path("pull_request_id").isIntegralNumber()) {
            return List.of();
        }
        Set<String> rechecked = new HashSet<>();
        metadata.path(AgentJob.RECHECKED_PRACTICES_METADATA_KEY).forEach(slug -> rechecked.add(slug.asString()));
        List<Practice> candidates = ready.stream()
                .filter(practice -> !rechecked.contains(practice.getSlug()) && codeOnly(practice))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        long workspaceId = job.getWorkspace().getId();
        List<Observation> latest = LatestRun.perClaim(observations.findStandingForWork(
                workspaceId,
                ArtifactKinds.PULL_REQUEST,
                metadata.path("pull_request_id").asLong(),
                metadata.path(PracticeCatalogInjector.AUTHOR_ID_METADATA_KEY).asLong()));
        if (latest.isEmpty()) {
            return List.of();
        }
        Set<UUID> permitted = visibility.permitsAll(workspaceId, latest, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
        Map<UUID, AgentJobRepository.CapturedReviewedWorkRow> runs = jobs
                .findCapturedReviewedWork(
                        workspaceId,
                        latest.stream().map(Observation::getAgentJobId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(AgentJobRepository.CapturedReviewedWorkRow::getId, Function.identity()));
        Map<Long, List<Observation>> claims = latest.stream()
                .collect(Collectors.groupingBy(
                        observation -> observation.getPractice().getId()));
        JsonNode current = mapper.valueToTree(manifest);
        JsonNode policy = mapper.readTree(generatedPaths);
        List<AnsweredPractice> answered = new ArrayList<>();
        for (Practice practice : candidates) {
            List<Observation> claim = claims.getOrDefault(practice.getId(), List.of());
            @Nullable Long pinned = revisionId(practice.getCurrentRevision());
            if (claim.isEmpty() || pinned == null) {
                continue;
            }
            boolean currentWord = claim.stream()
                    .allMatch(observation -> permitted.contains(observation.getId())
                            && Objects.equals(pinned, revisionId(observation.getPracticeRevision())));
            UUID producedBy = claim.getFirst().getAgentJobId();
            var run = runs.get(producedBy);
            if (currentWord && run != null && stagedTheSameCode(run, current, policy, practice)) {
                answered.add(new AnsweredPractice(practice.getSlug(), pinned, producedBy));
            }
        }
        return List.copyOf(answered);
    }

    private static @Nullable Long revisionId(@Nullable PracticeRevision revision) {
        return revision == null ? null : revision.getId();
    }

    private static boolean codeOnly(Practice practice) {
        List<PracticeEvidenceRequirement> needs = practice.getEvidenceRequirements();
        return practice.getSubject() == ActorRole.AUTHOR
                && !needs.isEmpty()
                && needs.stream().allMatch(need -> CODE.contains(need.sourceKind()));
    }

    private boolean stagedTheSameCode(
            AgentJobRepository.CapturedReviewedWorkRow run, JsonNode current, JsonNode policy, Practice practice) {
        String manifest = run.getManifest();
        String generatedPaths = run.getGeneratedPaths();
        if (run.getStatus() != AgentJobStatus.COMPLETED || manifest == null || generatedPaths == null) {
            return false;
        }
        try {
            JsonNode retained = mapper.readTree(manifest);
            return retained.path("contractVersion").equals(current.path("contractVersion"))
                    && mapper.readTree(generatedPaths).equals(policy)
                    && practice.getEvidenceRequirements().stream()
                            .allMatch(need -> sameSource(retained, current, need.sourceKind()));
        } catch (JacksonException e) {
            // An unreadable record proves nothing about what the run staged.
            return false;
        }
    }

    /** Both captures read the source in full availability under the same immutable identity and limits. */
    private static boolean sameSource(JsonNode retained, JsonNode current, SourceKind kind) {
        JsonNode then = stateOf(retained, kind);
        JsonNode now = stateOf(current, kind);
        JsonNode identity = now.path("facts").path("immutableIdentity");
        return "AVAILABLE".equals(now.path("availability").asString(""))
                && identity.isString()
                && identity.equals(then.path("facts").path("immutableIdentity"))
                && then.path("availability").equals(now.path("availability"))
                && then.path("content").equals(now.path("content"))
                && then.path("completeness").equals(now.path("completeness"))
                && (then.path("limitations").equals(now.path("limitations"))
                        || (then.path("limitations").isEmpty()
                                && now.path("limitations").isEmpty()));
    }

    private static JsonNode stateOf(JsonNode manifest, SourceKind kind) {
        for (JsonNode source : manifest.path("sources")) {
            if (kind.value().equals(source.path("kind").asString(""))) {
                return source.path("state");
            }
        }
        return MissingNode.getInstance();
    }
}
