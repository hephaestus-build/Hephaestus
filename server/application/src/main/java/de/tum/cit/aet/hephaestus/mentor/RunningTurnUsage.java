package de.tum.cit.aet.hephaestus.mentor;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The tokens that a turn has used so far and the admission that freezes their price. */
public record RunningTurnUsage(
        @Nullable JsonNode admission, long inputTokens, long outputTokens, long cachedTokens, long cacheWriteTokens) {}
