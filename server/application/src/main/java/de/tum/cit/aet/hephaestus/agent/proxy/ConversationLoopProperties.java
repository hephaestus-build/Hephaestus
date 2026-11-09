package de.tum.cit.aet.hephaestus.agent.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code hephaestus.conversation-loop.*}: what a worker needs to hand turns to the conversation loop.
 *
 * @param secret The credential of the conversation loop's assignment pulls, as 64 lowercase hexadecimal characters
 *     ({@code openssl rand -hex 32}). The worker and the loop read it; no other role does. The worker profile
 *     requires it; a local worker without it hands out no turn.
 */
@ConfigurationProperties(prefix = "hephaestus.conversation-loop")
public record ConversationLoopProperties(@Nullable String secret) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    /**
     * Refuses a secret of the wrong format. The binding does not check it: a binding failure prints the value, and a
     * wrong one may still be a real key.
     */
    public void requireFormat() {
        if (configured() && !FORMAT.matcher(String.valueOf(secret)).matches()) {
            throw new IllegalStateException(
                    "hephaestus.conversation-loop.secret must be 64 lowercase hexadecimal characters");
        }
    }

    /** Whether a worker can hand out turns: it has the loop's credential. */
    public boolean configured() {
        return secret != null && !secret.isEmpty();
    }

    /** Whether {@code presented} is the loop's credential, compared in constant time. */
    public boolean admits(@Nullable String presented) {
        String expected = secret;
        if (expected == null || expected.isEmpty() || presented == null) return false;
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
