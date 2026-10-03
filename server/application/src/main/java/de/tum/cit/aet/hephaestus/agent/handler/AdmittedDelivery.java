package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** What a review delivers on the reviewed work once its observations have passed the delivery gates. */
sealed interface AdmittedDelivery {

    /** The review did not reach every practice and found no problem, so it may not claim an all-clear. */
    record Withheld(@Nullable DeliveryContent content) implements AdmittedDelivery {}

    /** The note was written from an observation that awaits approval. */
    record Proposed(DeliveryContent content) implements AdmittedDelivery {}

    /** Posted without approval; {@code content} is null when nothing is left to post. */
    record Automatic(@Nullable DeliveryContent content, Set<String> contributingPracticeSlugs)
            implements AdmittedDelivery {}

    /** What the note is written from besides the observations it reports. */
    record Composition(
            ArtifactKind artifact,
            Map<String, String> whyBySlug,
            List<ComposedFeedbackUnit> units,
            @Nullable String lead,
            Set<String> recurringSlugs) {

        @Nullable
        DeliveryContent compose(List<ValidatedObservation> observations, @Nullable String withLead) {
            return DeliveryComposer.composeAdmitted(observations, artifact, whyBySlug, units, withLead, recurringSlugs);
        }
    }

    /**
     * Decides what the review places on the reviewed work, checking in this order:
     *
     * <ol>
     *   <li>{@link Withheld} when the review did not reach every practice and no admitted observation is a
     *       problem: the all-clear would speak for practices nobody evaluated, so nothing is placed on the
     *       work and only the composer's withheld decisions are kept.
     *   <li>{@link Proposed} when an observation awaits approval and the note, written with the lead, was
     *       written from at least one such observation.
     *   <li>{@link Automatic} otherwise: the note without the lead, posted without approval, with the
     *       practices it was written from; its content is null when no observation was admitted.
     * </ol>
     *
     * @param observations every observation the review recorded, in delivery order
     * @param proposals    the observations awaiting approval
     * @param automatic    the observations admitted without approval
     */
    static AdmittedDelivery decide(
            @Nullable JsonNode jobOutput,
            List<ValidatedObservation> observations,
            List<ValidatedObservation> proposals,
            List<ValidatedObservation> automatic,
            Composition composition) {
        // Both surfaces render an all-clear when no problem survives the gates, so the coverage question is
        // asked once, over the union.
        List<ValidatedObservation> composable =
                Stream.concat(proposals.stream(), automatic.stream()).toList();
        Set<String> included =
                composable.stream().map(ValidatedObservation::occurrenceKey).collect(Collectors.toUnmodifiableSet());
        List<ValidatedObservation> reviewPackage = observations.stream()
                .filter(observation -> included.contains(observation.occurrenceKey()))
                .toList();
        // The lead is unattributed prose that may speak for any practice, so automatic content goes without it.
        DeliveryContent content = composition.compose(reviewPackage, null);
        if (ReviewCoverage.withholdsAllClear(jobOutput, composable)) {
            return new Withheld(content == null ? null : content.withoutNote());
        }
        if (!proposals.isEmpty()) {
            DeliveryContent proposal = composition.compose(reviewPackage, composition.lead());
            // Only an observation the note was written from puts it under approval; a not-applicable result of
            // an approval-gated practice beside it does not.
            if (proposal != null && proposal.writtenFromAny(proposals)) {
                return new Proposed(proposal);
            }
        }
        return new Automatic(content, content == null ? Set.of() : content.contributingPracticeSlugs(reviewPackage));
    }
}
