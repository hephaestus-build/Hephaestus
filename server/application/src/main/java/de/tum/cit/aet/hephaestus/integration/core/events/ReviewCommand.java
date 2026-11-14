package de.tum.cit.aet.hephaestus.integration.core.events;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The grammar of the one comment command, {@code /hephaestus review}, on every provider. A comment counts only when it
 * holds the command and nothing else, in any letter case and with surrounding white space, as the user guide says. It
 * takes no argument.
 */
public final class ReviewCommand {
    private static final String TEXT = "/hephaestus review";

    private ReviewCommand() {}

    public static boolean matches(@Nullable String comment) {
        return comment != null && comment.strip().toLowerCase(Locale.ROOT).equals(TEXT);
    }
}
