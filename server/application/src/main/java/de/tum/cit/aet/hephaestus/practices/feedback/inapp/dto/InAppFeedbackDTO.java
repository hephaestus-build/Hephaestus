package de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One card on a developer's own practice pages: a process-level message about a habit in their work, what
 * it is evidenced by, and one thing to try next.
 *
 * <p>Only ever returned to the person it is about. The endpoint takes no user parameter, and the
 * operator surfaces are closed to the body.
 *
 * <p>{@code criteria} is deliberately absent, as it is on the reflective read model: the criteria text
 * is instruction for the detector, and a developer reading it would be reading the rubric they were
 * measured with rather than the practice they are learning. Only the developer framing travels —
 * {@code whyItMatters} and {@code whatGoodLooksLike}.
 *
 * <p>No counts either. "Three of your last five" is evidence for a claim about a strategy and belongs
 * inside the message the composer wrote; a number on the card would be a score, which this surface is
 * not. A card that says how many pieces of work it rests on counts {@link #evidence} itself.
 *
 * <p>The work resolves the feedback, not the developer: {@link #cleanWork} are the pieces of work in a row
 * that have come back clean on the practice since it was prepared, {@link #cleanNeeded} of them resolve it,
 * and once the run is complete {@link #resolvedByWorkAt} says when. Marking it addressed is the developer's
 * own, second way to resolve it: {@link #resolvedByDeveloperAt} says when their answer resolved it, and
 * {@link #response} carries the answer itself — the same answer the response endpoint returns, so a page of
 * cards does not fetch it once per card, and no reader re-derives which answers resolve.
 *
 * <p>A card whose practice was changed after it was prepared is closed rather than resolved:
 * {@link #practiceChangedAt} says when, and nothing the work or the developer does reopens it. A closed
 * card, resolved or not, leaves the page once {@code InAppFeedbackService#CLOSED_CARD_STAYS} has passed.
 */
@Schema(description = "A process-level message on the developer's own practice pages")
public record InAppFeedbackDTO(
        @NonNull UUID id,

        @NonNull @Schema(description = "Short headline naming the habit, never the person")
        String headline,

        @NonNull @Schema(description = "The message, as Markdown, without the headline and the next step")
        String body,

        @Schema(description = "The habit to try next, on its own; null for feedback prepared without one") @Nullable
        String nextStep,

        @NonNull @Schema(description = "Practice this habit belongs to")
        String practiceSlug,

        @NonNull String practiceName,

        @Schema(description = "Group the practice sits in; null when the practice has none") @Nullable
        String groupSlug,

        @Schema(description = "Group display name; null when the practice has none") @Nullable
        String groupName,

        @Schema(description = "Why this practice matters, in the developer's framing") @Nullable
        String whyItMatters,

        @Schema(description = "What good looks like, in the developer's framing") @Nullable
        String whatGoodLooksLike,

        @NonNull @Schema(description = "The pieces of work the habit was observed on, newest first")
        List<InAppEvidenceDTO> evidence,

        @NonNull @Schema(description = "When the message was composed")
        Instant preparedAt,

        @Schema(description = "When this developer first opened it; null until they have") @Nullable
        Instant readAt,

        @NonNull @Schema(description = "Clean pieces of work in a row on the practice that resolve this feedback")
        Integer cleanNeeded,

        @NonNull
        @Schema(
                description = "The pieces of work in a row that came back clean on the practice since the"
                        + " feedback was prepared, each with the date it was reviewed, oldest first, at most"
                        + " cleanNeeded of them; a problem empties it, and once resolved these are exactly the"
                        + " pieces that resolved it")
        List<InAppCleanWorkDTO> cleanWork,

        @Nullable
        @Schema(
                description = "When the work resolved it: the review of the piece of work that completed the"
                        + " clean run; null while the work has not")
        Instant resolvedByWorkAt,

        @Nullable
        @Schema(
                description = "When the developer's own answer resolved it: they marked it addressed or not"
                        + " applicable; null while they have not answered or their answer disputes it")
        Instant resolvedByDeveloperAt,

        @Nullable
        @Schema(
                description = "When the practice's review rules changed after this was prepared, which closes"
                        + " it without a resolution: the evidence was measured by rules the practice no longer"
                        + " has; null while the rules are the ones it was measured by")
        Instant practiceChangedAt,

        @Nullable @Schema(description = "The developer's current response to this feedback; null while they have none")
        FeedbackResponseDTO response) {}
