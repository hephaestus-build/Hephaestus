package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import java.util.Locale;

/**
 * Whose model call takes a permit of a {@link LlmCallPool}. A conversation turn may use the pool's interactive reserve; a
 * review's call, also its practice scripts' and its composition's, leaves it free. The review classes follow the
 * review's {@link ReviewOrigin}. Product feedback uses only idle capacity after all other waiting classes.
 */
public enum LlmCallClass {
    TURN,
    REQUEST,
    EVENT,
    SCHEDULE,
    BACKFILL,
    COMPOSITION,
    PRODUCT_FEEDBACK;

    public static LlmCallClass of(ReviewOrigin origin) {
        return switch (origin) {
            case REQUEST -> REQUEST;
            case EVENT -> EVENT;
            case SCHEDULE -> SCHEDULE;
            case BACKFILL -> BACKFILL;
        };
    }

    public boolean interactive() {
        return this == TURN;
    }

    /** The {@code class} label of the pool metrics. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
