package de.tum.cit.aet.hephaestus.integration.core.signal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The identity of one occurrence of a signal — what makes "the same thing happened again" decidable.
 *
 * <p>Together with the workspace, artifact and signal name this is the ledger's unique key, so a
 * revision that is too coarse loses reviews and one that is too fine repeats them. Which derivation
 * is right is a property of the signal, not of the artifact: see {@link RevisionScheme}. Every factory
 * prefixes its scheme so two schemes can never collide on the same string.
 */
public record SignalRevision(String value) {
    /** Fits {@code artifact_signal.revision}. */
    static final int MAX_LENGTH = 128;

    /**
     * {@code ':'} is excluded because revisions are carried inside colon-delimited agent-job
     * idempotency keys, and whitespace because these values appear verbatim in logs and metrics.
     */
    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9_.~-]+");

    /** Truncated digest: wide enough to be collision-free here, short enough to read in a trace. */
    private static final int DIGEST_HEX_LENGTH = 32;

    private static final byte ABSENT_PART = 1;
    private static final byte PRESENT_PART = 2;
    private static final byte PART_SEPARATOR = 0;

    public SignalRevision {
        Objects.requireNonNull(value, "signal revision must not be null");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("signal revision exceeds " + MAX_LENGTH + " characters: " + value);
        }
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "signal revision must contain only [A-Za-z0-9_.~-] — no colon, no whitespace — got: " + value);
        }
    }

    /** For signals whose subject is the code: a new commit is a new occurrence. */
    public static SignalRevision ofHeadCommit(String commitSha) {
        Objects.requireNonNull(commitSha, "commitSha must not be null");
        if (commitSha.isBlank()) {
            throw new IllegalArgumentException("commitSha must not be blank");
        }
        return new SignalRevision(RevisionScheme.HEAD_COMMIT.prefix() + commitSha);
    }

    /**
     * For signals whose subject is authored prose: the occurrence changes when — and only when — the
     * text does. Each part is framed so that neither field boundaries nor absence can be forged:
     * {@code ("ab", "c")} differs from {@code ("a", "bc")}, and an empty description differs from one
     * that was never written.
     */
    public static SignalRevision ofContentDigest(@Nullable String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
        for (String part : parts) {
            if (part == null) {
                digest.update(ABSENT_PART);
            } else {
                digest.update(PRESENT_PART);
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            digest.update(PART_SEPARATOR);
        }
        String hex = HexFormat.of().formatHex(digest.digest()).substring(0, DIGEST_HEX_LENGTH);
        return new SignalRevision(RevisionScheme.CONTENT_DIGEST.prefix() + hex);
    }

    /** For signals that can only happen once: the artifact reached a state it cannot leave. */
    public static SignalRevision ofTerminalState(String terminalState) {
        Objects.requireNonNull(terminalState, "terminalState must not be null");
        return new SignalRevision(RevisionScheme.TERMINAL_STATE.prefix() + terminalState);
    }

    /** For an explicit request to review: the ask itself is the occurrence. */
    public static SignalRevision ofRunId(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return new SignalRevision(RevisionScheme.RUN_ID.prefix() + runId);
    }

    public static SignalRevision ofEventId(long eventId) {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        return new SignalRevision(RevisionScheme.EVENT_ID.prefix() + eventId);
    }

    /**
     * The event {@link #ofEventId} encoded here, or empty when another scheme minted this revision or
     * a persisted value predates a grammar the constructor no longer rejects.
     */
    public Optional<Long> eventId() {
        if (scheme().orElse(null) != RevisionScheme.EVENT_ID) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(
                    value.substring(RevisionScheme.EVENT_ID.prefix().length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The scheme that produced this revision, read back off its prefix. */
    public Optional<RevisionScheme> scheme() {
        for (RevisionScheme scheme : RevisionScheme.values()) {
            if (value.startsWith(scheme.prefix())) {
                return Optional.of(scheme);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
