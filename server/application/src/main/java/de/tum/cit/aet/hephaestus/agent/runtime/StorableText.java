package de.tum.cit.aet.hephaestus.agent.runtime;

import java.nio.charset.StandardCharsets;

/**
 * Text that PostgreSQL can store: a {@code text}, {@code varchar} or {@code jsonb} value refuses NUL, and UTF-8
 * has no unpaired surrogate. A runner's output can hold either, and a refused write fails the transaction that
 * holds it.
 */
public final class StorableText {

    private StorableText() {}

    /** A fresh encoder per call, because a {@code CharsetEncoder} is not thread-safe. */
    public static boolean isStorable(String text) {
        return text.indexOf('\u0000') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text);
    }
}
