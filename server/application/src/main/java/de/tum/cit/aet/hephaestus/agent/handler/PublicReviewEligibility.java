package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.providers.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Which observations may support feedback on the reviewed work itself. Public feedback speaks only about the work's
 * author: an observation qualifies when the practice revision it was assessed under is about the author (or the author
 * as merger), it is about the mirrored author of this exact work, and its recorded provenance names only captured,
 * public sources. A reviewer observation stays private, even when the reviewer wrote the work. An unknown author,
 * revision or role qualifies nothing. The same set governs model input, the owed review and final admission; removing
 * citations cannot remove claims derived from them.
 */
@Component
public class PublicReviewEligibility {

    /** The sources that capture the reviewed work, its repository and its discussion. */
    private static final Set<String> CAPTURED_WORK = Stream.of(
                    PullRequestContentSource.CORE,
                    PullRequestContentSource.DIFF,
                    PullRequestContentSource.COMMENTS,
                    IssueContentSource.CORE,
                    IssueContentSource.COMMENTS,
                    RepositoryTreeContentSource.KIND,
                    LinkedWorkItemContentSource.KIND,
                    ReviewThreadContentSource.KIND,
                    GeneralReviewCommentContentSource.KIND)
            .map(SourceKind::value)
            .collect(Collectors.toUnmodifiableSet());

    /** The evidence branches that list what an observation consulted. */
    private static final Set<String> CONSULTING_BRANCHES = Set.of("search", "inapplicability", "undecidability");

    private final ReviewTargetQuery reviewTargets;
    private final RepositoryToMonitorRepository monitoredRepositories;

    private final ObservationRepository observations;
    private final FeedbackRepository feedback;
    private final FeedbackObservationRepository feedbackObservations;

    PublicReviewEligibility(
            ReviewTargetQuery reviewTargets,
            RepositoryToMonitorRepository monitoredRepositories,
            ObservationRepository observations,
            FeedbackRepository feedback,
            FeedbackObservationRepository feedbackObservations) {
        this.reviewTargets = reviewTargets;
        this.monitoredRepositories = monitoredRepositories;
        this.observations = observations;
        this.feedback = feedback;
        this.feedbackObservations = feedbackObservations;
    }

    /** The ids of the observations that may support what the reviewed work is told. */
    public Set<UUID> publicObservationIds(AgentJob job, Collection<Observation> observations) {
        Long author = artifactAuthor(job);
        if (author == null) return Set.of();
        return publicObservationIds(job, observations, author);
    }

    private Set<UUID> publicObservationIds(AgentJob job, Collection<Observation> observations, long author) {
        return observations.stream()
                .filter(observation -> belongsToWork(job, observation)
                        && isAboutAuthor(job, observation, author)
                        && admitsProvenance(observation.getEvidence()))
                .map(Observation::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Rechecks the exact supports of a persisted package, before it starts any new provider write. */
    public boolean permitsDelivery(
            AgentJob job, @Nullable UUID feedbackId, Collection<String> practiceSlugs, @Nullable Set<UUID> citedIds) {
        Long author = artifactAuthor(job);
        JsonNode metadata = job.getMetadata();
        if (metadata == null
                || author == null
                || job.getWorkspace() == null
                || job.getWorkspace().getId() == null) return false;
        long workspace = job.getWorkspace().getId();
        List<Observation> support;
        if (feedbackId != null) {
            var stored = feedback.findByIdAndWorkspaceId(feedbackId, workspace).orElse(null);
            if (stored == null
                    || stored.getChannel() != FeedbackChannel.IN_CONTEXT
                    || !Objects.equals(stored.getAboutUserId(), author)
                    || !Objects.equals(stored.getRecipientUserId(), author)
                    || !Objects.equals(stored.getAgentJobId(), job.getId())
                    || !Objects.equals(stored.getArtifactKind(), job.getArtifactKind())
                    || !Objects.equals(
                            stored.getArtifactId(),
                            metadata.path(
                                            ArtifactKinds.PULL_REQUEST.equals(job.getArtifactKind())
                                                    ? "pull_request_id"
                                                    : "issue_id")
                                    .asLong())) return false;
            support = feedbackObservations.findForVisibility(workspace, Set.of(feedbackId)).stream()
                    .map(FeedbackObservationRepository.FeedbackObservationVisibility::getObservation)
                    .toList();
            if (citedIds != null
                    && !citedIds.equals(support.stream().map(Observation::getId).collect(Collectors.toSet())))
                return false;
        } else if (citedIds != null) {
            if (citedIds.isEmpty()) return false;
            support = observations.findAllByIdInAndWorkspaceId(citedIds, workspace);
            if (support.size() != citedIds.size()) return false;
        } else {
            support = observations.findByAgentJobId(job.getId(), workspace).stream()
                    .filter(observation ->
                            practiceSlugs.contains(observation.getPractice().getSlug()))
                    .toList();
        }
        if (support.isEmpty()) return false;
        Set<UUID> eligible = publicObservationIds(job, support, author);
        return support.stream()
                        .allMatch(observation -> eligible.contains(observation.getId())
                                && (observation.getOutcome() == Outcome.MET
                                        || observation.getOutcome() == Outcome.NOT_MET))
                && practiceSlugs.stream()
                        .allMatch(slug -> support.stream()
                                .anyMatch(observation ->
                                        slug.equals(observation.getPractice().getSlug())));
    }

    /** Public history remains about the author of its original work, under its recorded practice revisions. */
    public boolean permitsPublicHistory(Feedback stored, Collection<Observation> support) {
        if (stored.getChannel() != FeedbackChannel.IN_CONTEXT || stored.getArtifactId() == null || support.isEmpty())
            return false;
        Optional<ReviewTargetQuery.Target> target = ArtifactKinds.PULL_REQUEST.equals(stored.getArtifactKind())
                ? reviewTargets.findPullRequest(stored.getArtifactId())
                : ArtifactKinds.ISSUE.equals(stored.getArtifactKind())
                        ? reviewTargets.findIssue(stored.getArtifactId())
                        : Optional.empty();
        var work = target.orElse(null);
        if (work == null
                || work.deleted()
                || work.authorId() == null
                || !monitoredRepositories.existsByWorkspaceIdAndNameWithOwner(
                        stored.getWorkspaceId(), work.repositoryFullName())
                || !Objects.equals(stored.getAboutUserId(), work.authorId())
                || !Objects.equals(stored.getRecipientUserId(), work.authorId())) return false;
        return support.stream().allMatch(observation -> {
            PracticeRevision revision = observation.getPracticeRevision();
            ActorRole role = revision == null ? null : revision.getSubject();
            return (observation.getOutcome() == Outcome.MET || observation.getOutcome() == Outcome.NOT_MET)
                    && (role == ActorRole.AUTHOR || role == ActorRole.MERGER)
                    && Objects.equals(observation.getAgentJobId(), stored.getAgentJobId())
                    && Objects.equals(observation.getWorkspaceId(), stored.getWorkspaceId())
                    && Objects.equals(observation.getArtifactKind(), stored.getArtifactKind())
                    && Objects.equals(observation.getArtifactId(), stored.getArtifactId())
                    && Objects.equals(observation.getAboutUserId(), work.authorId())
                    && admitsProvenance(observation.getEvidence());
        });
    }

    /**
     * The internal user id of the author of the job's own work, read once from the mirrored work. Only a job about
     * the author has one: a job that names any other subject role reaches the work with nothing.
     */
    private @Nullable Long artifactAuthor(AgentJob job) {
        JsonNode metadata = job.getMetadata();
        if (metadata == null || !isAuthorJob(metadata) || job.getWorkspace() == null) return null;
        Long workspaceId = job.getWorkspace().getId();
        Optional<ReviewTargetQuery.Target> target;
        String numberKey;
        if (ArtifactKinds.PULL_REQUEST.equals(job.getArtifactKind())
                && metadata.path("pull_request_id").isIntegralNumber()) {
            target = reviewTargets.findPullRequest(
                    metadata.path("pull_request_id").asLong());
            numberKey = "pr_number";
        } else if (ArtifactKinds.ISSUE.equals(job.getArtifactKind())
                && metadata.path("issue_id").isIntegralNumber()) {
            target = reviewTargets.findIssue(metadata.path("issue_id").asLong());
            numberKey = "issue_number";
        } else {
            return null;
        }
        return target.filter(work -> workspaceId != null
                        && PracticeFeedbackDeliveryPolicy.matchesArtifact(work, metadata, numberKey)
                        && monitoredRepositories.existsByWorkspaceIdAndNameWithOwner(
                                workspaceId, work.repositoryFullName()))
                .map(ReviewTargetQuery.Target::authorId)
                .orElse(null);
    }

    /** A job without a subject role is about the author; that is how author reviews are recorded. */
    static boolean isAuthorJob(JsonNode metadata) {
        JsonNode role = metadata.path("subject_role");
        return role.isMissingNode() || ActorRole.AUTHOR.name().equals(role.asString(""));
    }

    private static boolean belongsToWork(AgentJob job, Observation observation) {
        JsonNode metadata = job.getMetadata();
        if (metadata == null || job.getWorkspace() == null || observation.getId() == null) return false;
        String key = ArtifactKinds.PULL_REQUEST.equals(job.getArtifactKind()) ? "pull_request_id" : "issue_id";
        return Objects.equals(observation.getAgentJobId(), job.getId())
                && Objects.equals(
                        observation.getWorkspaceId(), job.getWorkspace().getId())
                && Objects.equals(observation.getArtifactKind(), job.getArtifactKind())
                && metadata.path(key).isIntegralNumber()
                && Objects.equals(
                        observation.getArtifactId(), metadata.path(key).asLong());
    }

    private static boolean isAboutAuthor(AgentJob job, Observation observation, long author) {
        PracticeRevision revision = observation.getPracticeRevision();
        ActorRole subject = revision == null ? null : revision.getSubject();
        return (subject == ActorRole.AUTHOR
                        || (subject == ActorRole.MERGER
                                && PracticeCatalogInjector.subjectNameable(ActorRole.MERGER, job.getMetadata())))
                && Long.valueOf(author).equals(observation.getAboutUserId());
    }

    /** Whether the observation's recorded provenance names only captured, public sources. */
    static boolean admitsProvenance(@Nullable JsonNode evidence) {
        if (evidence == null || !evidence.isObject()) {
            return false;
        }
        boolean namesASource = false;
        for (JsonNode citation : evidence.path("citations")) {
            if (!CAPTURED_WORK.contains(citation.path("sourceKind").asString(""))) {
                return false;
            }
            namesASource = true;
        }
        for (String branch : CONSULTING_BRANCHES) {
            for (JsonNode consulted : evidence.path(branch).path("consulted")) {
                if (!CAPTURED_WORK.contains(consulted.asString(""))) {
                    return false;
                }
                namesASource = true;
            }
        }
        return namesASource;
    }
}
