package de.tum.cit.aet.hephaestus.agent.handler.composition;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One composition decision for one channel. It references observations but carries no measurement
 * verdict; the server resolves evidence and placement independently.
 *
 * @param basedOn ids of admitted observations from this run; at least one belongs to {@code practiceSlug}
 * @param body the in-app words, read verbatim. Null on the in-context lane, where the server renders
 *     evidence around {@link #nextStep}, and on the conversation lane, where {@link #notes} carries notes
 *     to the mentor
 * @param placement where an in-context note goes: on a verified diff citation or in the artifact summary
 */
public record ComposedFeedbackUnit(
        FeedbackChannel channel,
        String practiceSlug,
        List<String> basedOn,
        Action action,
        @Nullable String supersedesThreadKey,
        @Nullable WithholdReason withholdReason,
        @Nullable String title,
        @Nullable String body,
        @Nullable String nextStep,
        @Nullable ConversationBrief notes,
        @Nullable InContextPlacement placement) {
    public static final int MAX_TITLE_LENGTH = 255;

    public static final int MAX_BODY_LENGTH = 8_000;
    public static final int MAX_NEXT_STEP_LENGTH = 2_000;
    public static final int MAX_THREAD_KEY_LENGTH = 64;

    public static final int MAX_SITUATION_LENGTH = 4_000;

    public static final int MAX_EVIDENCE_LENGTH = 4_000;

    public static final int MAX_AIM_LENGTH = 2_000;

    public ComposedFeedbackUnit {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        Objects.requireNonNull(action, "action");
        basedOn = List.copyOf(basedOn);
    }

    public enum Action {
        NEW,
        /** Request replacement of prepared feedback identified by {@link #supersedesThreadKey}. */
        SUPERSEDE,
        WITHHOLD,
    }

    /** Composer withholding decisions, distinct from server-enforced delivery suppression. */
    public enum WithholdReason {
        NO_MATERIAL_CHANGE,
        ALREADY_SAID,
        BELOW_BAR,
    }

    /**
     * Mentor context, not developer-facing prose. Authorized observation evidence is staged separately.
     *
     * @param capability understanding or behaviour to support, not a prescribed question
     * @param inConversationSignal observable sign that the conversation helped
     */
    public record ConversationBrief(
            String situation,
            String capability,
            String evidenceSummary,
            String inConversationSignal,
            @Nullable String alreadySaid) {
        public ConversationBrief {
            requirePresent(situation, "situation");
            requirePresent(capability, "capability");
            requirePresent(evidenceSummary, "evidenceSummary");
            requirePresent(inConversationSignal, "inConversationSignal");
        }

        private static void requirePresent(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    /** In-context placement resolved from an observation citation, never from model-supplied coordinates. */
    public record ResolvedAnchor(
            String observationId,
            int citationIndex,
            String path,
            @Nullable String side,
            int startLine,
            @Nullable Integer endLine) {
        public ResolvedAnchor {
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(path, "path");
        }
    }

    public record InContextPlacement(
            PlacementKind kind, @Nullable ResolvedAnchor diffAnchor) {
        public InContextPlacement {
            Objects.requireNonNull(kind, "kind");
            if ((kind == PlacementKind.DIFF) != (diffAnchor != null)) {
                throw new IllegalArgumentException("DIFF placement requires an anchor; ARTIFACT placement forbids one");
            }
        }

        public enum PlacementKind {
            DIFF,
            ARTIFACT,
        }
    }

    public boolean isComplete() {
        if (action == Action.WITHHOLD) {
            return withholdReason != null;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        if (channel == FeedbackChannel.IN_CHAT) return notes != null;
        if (nextStep == null || nextStep.isBlank()) return false;
        return channel == FeedbackChannel.IN_CONTEXT
                ? body == null && placement != null
                : body != null && !body.isBlank();
    }
}
