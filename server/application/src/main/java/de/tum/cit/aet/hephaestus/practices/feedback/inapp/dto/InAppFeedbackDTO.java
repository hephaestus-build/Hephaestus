package de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.FeedbackClosure.ClosedBy;
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
 * <p>How the card closes is {@code FeedbackClosure}'s: {@link #closedAt} and {@link #closedBy} say when and
 * what, and {@link #cleanWork} is the work's side of it ({@code WorkResolution}). {@link #response} is the
 * developer's current answer, the same one the response endpoint returns, so a page of cards does not fetch it
 * once per card. How long a closed card stays is {@code InAppFeedbackService#CLOSED_CARD_STAYS}.
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
                description = "When it stopped being open: the earliest of the work completing its clean run,"
                        + " the developer answering that it is addressed or not applicable, and the practice"
                        + " changing its review rules after it was prepared; null while it is open")
        Instant closedAt,

        @Nullable
        @Schema(
                description = "What closed it, for the moment closedAt names; on a tie the work, then the developer,"
                        + " then the practice; null while it is open")
        ClosedBy closedBy,

        @Nullable @Schema(description = "The developer's current response to this feedback; null while they have none")
        FeedbackResponseDTO response) {}
