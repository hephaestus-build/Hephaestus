package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout.REPO_MOUNT_RELATIVE;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticePreconditionClause;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * A complete body is admitted only when every contributor passes public evidence and delivery gates.
 * Supporting observations and all public bodies must address one person; removing support cannot repair prose.
 */
final class ComposedReviewAdmission {

    private static final Logger log = LoggerFactory.getLogger(ComposedReviewAdmission.class);

    private ComposedReviewAdmission() {}

    record Admission(
            @Nullable DeliveryContent content,
            boolean needsApproval,
            @Nullable Long recipient) {}

    /**
     * @param recorded every observation the review recorded, stamped with its persisted identities
     * @param subjects the person each recorded observation is about, by observation id
     * @param automatic the observations admitted to the work without approval
     * @param awaitingApproval the observations admitted to the work only once a reviewer approves
     */
    static Admission admit(
            ComposedReview review,
            ArtifactKind artifact,
            List<ValidatedObservation> recorded,
            Map<UUID, Long> subjects,
            List<ValidatedObservation> automatic,
            List<ValidatedObservation> awaitingApproval) {
        Map<String, ValidatedObservation> byId = new HashMap<>();
        for (ValidatedObservation observation : recorded) {
            UUID id = observation.observationId();
            if (id != null) byId.put(id.toString(), observation);
        }
        Set<String> automaticIds = idsOf(automatic);
        Set<String> approvalIds = idsOf(awaitingApproval);
        Set<Long> recipients = new HashSet<>();
        boolean needsApproval = false;

        String summary = null;
        List<String> summaryContributors = List.of();
        ComposedReview.Summary composedSummary = review.summary();
        if (composedSummary != null) {
            Part part = PullRequestCommentPoster.speaksApproval(composedSummary.body())
                    ? null
                    : partOf(composedSummary.basedOn(), byId, subjects, automaticIds, approvalIds);
            if (part != null) {
                summary = composedSummary.body();
                summaryContributors = keysOf(part.support());
                recipients.add(part.recipient());
                needsApproval = part.awaitsApproval();
            } else {
                log.info("The summary speaks approval or rests on an observation that may not go out; it stays unsaid");
            }
        }

        List<DiffNote> notes = new ArrayList<>();
        for (ComposedReview.InlineNote note : review.inline()) {
            if (!ArtifactKinds.hasInlineLane(artifact)) {
                log.warn("A line note was composed for work without lines; it stays unsaid");
                continue;
            }
            Part part = PullRequestCommentPoster.speaksApproval(note.body())
                    ? null
                    : partOf(note.basedOn(), byId, subjects, automaticIds, approvalIds);
            if (part == null
                    || part.support().stream()
                            .anyMatch(observation ->
                                    observation.deliveryBehavior().summaryOnly())) {
                log.info(
                        "A line note violates placement policy, speaks approval or rests on an observation that may not go out; it stays unsaid");
                continue;
            }
            ValidatedObservation anchored = byId.get(note.anchor().observationId());
            if (anchored == null
                    || !note.basedOn().contains(note.anchor().observationId())
                    || !verifiedAnchor(anchored, note.anchor())) {
                log.warn("A line note's citation is not a verified line of this change; the note stays unsaid");
                continue;
            }
            notes.add(new DiffNote(
                    repoRelative(note.anchor().path()),
                    note.anchor().startLine(),
                    note.anchor().endLine(),
                    note.body(),
                    deliveryKey(anchored, note.anchor().citationIndex()),
                    keysOf(part.support())));
            recipients.add(part.recipient());
            needsApproval |= part.awaitsApproval();
        }

        if (recipients.size() > 1) {
            log.warn("The parts of a composed review are about different people; nothing of it reaches the work");
            summary = null;
            summaryContributors = List.of();
            notes.clear();
            recipients.clear();
            needsApproval = false;
        }

        List<WithheldObservation> withheld = new ArrayList<>();
        Set<String> withheldKeys = new HashSet<>();
        for (ComposedReview.Withheld decision : review.withheld()) {
            List<ValidatedObservation> support = resolve(decision.basedOn(), byId);
            if (support.isEmpty()
                    || support.size() != decision.basedOn().size()
                    || support.stream()
                            .anyMatch(observation -> observation.outcome() != Outcome.NOT_MET
                                    || observation.occurrenceKey() == null
                                    || observation.occurrenceKey().isBlank()
                                    || !PublicReviewEligibility.admits(observation.evidence()))) {
                continue;
            }
            for (ValidatedObservation observation : support) {
                String key = observation.occurrenceKey();
                if (observation.outcome() == Outcome.NOT_MET && key != null && withheldKeys.add(key)) {
                    withheld.add(new WithheldObservation(key, FeedbackSuppressionReason.COMPOSER_WITHHELD));
                }
            }
        }

        DeliveryContent content = summary == null && notes.isEmpty() && withheld.isEmpty()
                ? null
                : new DeliveryContent(summary, List.copyOf(notes), List.copyOf(withheld), summaryContributors);
        return new Admission(
                content,
                needsApproval,
                recipients.isEmpty() ? null : recipients.iterator().next());
    }

    /** One admitted part: the observations it rests on, the person they are about, and whether it waits. */
    private record Part(List<ValidatedObservation> support, Long recipient, boolean awaitsApproval) {}

    /**
     * A part rests on what it names, or it does not go out. An observation that decides nothing — not applicable,
     * undetermined — cannot carry a claim about the work, and one drawn from the person's history is not a fact about
     * it ({@link PublicReviewEligibility}), so a part naming either is refused like one the gates held.
     */
    private static @Nullable Part partOf(
            List<String> basedOn,
            Map<String, ValidatedObservation> byId,
            Map<UUID, Long> subjects,
            Set<String> automaticIds,
            Set<String> approvalIds) {
        if (basedOn.isEmpty()) return null;
        List<ValidatedObservation> support = new ArrayList<>(basedOn.size());
        Set<Long> about = new HashSet<>();
        boolean approval = false;
        for (String id : basedOn) {
            ValidatedObservation observation = byId.get(id);
            if (observation == null
                    || observation.occurrenceKey() == null
                    || observation.occurrenceKey().isBlank()
                    || !observation.outcome().isDecided()
                    || !PublicReviewEligibility.admits(observation.evidence())) return null;
            Long subject = subjects.get(UUID.fromString(id));
            if (subject == null) return null;
            about.add(subject);
            if (!automaticIds.contains(id)) {
                if (!approvalIds.contains(id)) return null;
                approval = true;
            }
            support.add(observation);
        }
        if (support.isEmpty() || about.size() != 1) {
            if (about.size() > 1) log.warn("A composed part rests on observations about different people");
            return null;
        }
        return new Part(List.copyOf(support), about.iterator().next(), approval);
    }

    /** Only a citation the admission verified at its exact diff location may carry a note onto that line. */
    private static boolean verifiedAnchor(ValidatedObservation observation, ComposedReview.ResolvedAnchor anchor) {
        JsonNode evidence = observation.evidence();
        JsonNode citations = evidence == null ? null : evidence.get("citations");
        if (citations == null
                || !citations.isArray()
                || anchor.citationIndex() < 0
                || anchor.citationIndex() >= citations.size()) {
            return false;
        }
        JsonNode citation = citations.get(anchor.citationIndex());
        JsonNode verification = citation.path("verification");
        Integer endLine = anchor.endLine();
        return citation.isObject()
                && "VERIFIED".equals(verification.path("status").asString())
                && "EXACT_LOCATION".equals(verification.path("scope").asString())
                && PracticePreconditionClause.DIFF_SOURCE
                        .value()
                        .equals(citation.path("sourceKind").asString())
                && "NEW".equals(citation.path("side").asString())
                && repoRelative(anchor.path())
                        .equals(repoRelative(citation.path("path").asString("")))
                && anchor.startLine() == citation.path("startLine").asInt()
                && (endLine == null || endLine == citation.path("endLine").asInt(anchor.startLine()));
    }

    /** One note per citation, so the key names the citation as well as the observation. */
    private static @Nullable String deliveryKey(ValidatedObservation anchored, int citationIndex) {
        String key = anchored.occurrenceKey();
        return key == null ? null : "observation:" + key + ":" + citationIndex;
    }

    private static List<ValidatedObservation> resolve(List<String> basedOn, Map<String, ValidatedObservation> byId) {
        return basedOn.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static List<String> keysOf(List<ValidatedObservation> observations) {
        return observations.stream()
                .map(ValidatedObservation::occurrenceKey)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static Set<String> idsOf(List<ValidatedObservation> observations) {
        Set<String> ids = new HashSet<>();
        for (ValidatedObservation observation : observations) {
            UUID id = observation.observationId();
            if (id != null) ids.add(id.toString());
        }
        return ids;
    }

    private static String repoRelative(String path) {
        return path.startsWith(REPO_MOUNT_RELATIVE) ? path.substring(REPO_MOUNT_RELATIVE.length()) : path;
    }
}
