package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** What Heph is doing in a running turn, and the text it has written in the turn so far. Reasoning is never sent. */
@Schema(description = "What Heph is doing in a running turn")
public record MentorTurnPartialDTO(
        @NonNull Activity activity,

        @Schema(description = "The tool Heph runs, while the activity is TOOL") @Nullable
        String tool,

        @Schema(
                description =
                        "What Heph is doing, in the developer's words, for example \"Reading your practice feedback…\"")
        @NonNull
        String stage,

        @Schema(description = "The text Heph has written in this turn so far") @NonNull
        String text) {

    /** {@code THINKING_STAGE} in {@code heph-host.ts}. */
    static final String THINKING_STAGE = "Thinking…";

    public enum Activity {
        THINKING,
        WRITING,
        TOOL,
    }

    /** Reads the Heph host's whole snapshot: {@code activity}, {@code tool} and the turn's Pi messages so far. */
    static MentorTurnPartialDTO from(JsonNode snapshot) {
        Activity activity;
        try {
            activity = Activity.valueOf(
                    snapshot.path("activity").asString("thinking").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            activity = Activity.THINKING;
        }
        String tool = snapshot.path("tool").isString() ? snapshot.path("tool").asString() : null;
        // The Heph host names the stage; a snapshot without one is the host's own default.
        String stage =
                snapshot.path("stage").isString() ? snapshot.path("stage").asString() : THINKING_STAGE;
        return new MentorTurnPartialDTO(activity, tool, stage, MentorPiEntries.partialText(snapshot, false));
    }
}
