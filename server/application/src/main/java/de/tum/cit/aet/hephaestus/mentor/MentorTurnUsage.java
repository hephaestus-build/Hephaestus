package de.tum.cit.aet.hephaestus.mentor;

/**
 * LLM usage of one turn attempt.
 *
 * @param reasoningTokens already inside {@code outputTokens}; reported, never priced twice
 */
public record MentorTurnUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        int calls) {
    /** No token in any priced bucket. */
    public boolean isEmpty() {
        return inputTokens <= 0 && outputTokens <= 0 && cacheReadTokens <= 0 && cacheWriteTokens <= 0;
    }
}
