package de.tum.cit.aet.hephaestus.mentor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The partial answers of running attempts, kept in memory only. The worker sends each new partial answer as a change to
 * the one before it, and a whole snapshot at least every {@link Stream#FULL_EVERY}, in {@link
 * MentorLedgerEvents.ProgressChanged} frames. Each process that listens to the ledger rebuilds the snapshots here.
 *
 * <p>Nothing here is stored. A process that starts, reconnects or misses a frame shows no partial answer until the next
 * whole snapshot. The answer itself is stored once, when its attempt ends ({@link MentorTurnService#end}). So a
 * growing answer is not written again and again, and its write-ahead log stays near the size of the answer.
 */
@Component
public class MentorTurnProgress {

    /** The largest snapshot kept; a larger one is dropped, and the reader shows the last one it kept. */
    static final int MAX_SNAPSHOT_CHARS = 256 * 1024;

    /** The largest update, as UTF-8 JSON; its text may escape characters. */
    static final int MAX_UPDATE_BYTES = 1024 * 1024;

    /** Raw bytes in one frame: Base64 and the frame's fields stay below PostgreSQL's 8000-byte notification limit. */
    static final int FRAME_BYTES = 4096;

    private static final int MAX_FRAMES = MAX_UPDATE_BYTES / FRAME_BYTES + 1;

    /**
     * One update of a partial answer: the previous snapshot's text with {@code text} put between its first {@code
     * prefix} and last {@code suffix} characters. A base of {@code 0} makes {@code text} the whole snapshot.
     */
    public record Update(long sequence, long baseSequence, int prefix, int suffix, String text) {}

    private record Attempt(long workspaceId, UUID turnId, int attempt) {}

    private record Snapshot(long sequence, String text) {}

    private static final class Assembly {
        private final long sequence;
        private final int count;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int next;

        private Assembly(long sequence, int count) {
            this.sequence = sequence;
            this.count = count;
        }
    }

    private final ObjectMapper mapper;

    private final Cache<Attempt, Snapshot> snapshots = Caffeine.newBuilder()
            // Longer than any attempt runs; a turn that ends removes its snapshot before (changed).
            .expireAfterWrite(Duration.ofMinutes(20))
            .maximumWeight(16L * 1024 * 1024)
            .weigher((Attempt attempt, Snapshot snapshot) ->
                    Math.max(1, snapshot.text().length() * 2))
            .build();

    /** The streams of the attempts whose partial answers this process sends. */
    private final Cache<Attempt, Stream> streams =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).build();

    private final Cache<Attempt, Assembly> assemblies = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .maximumSize(64)
            .build();

    public MentorTurnProgress(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The newest partial answer of the turn's running attempt, or {@code null} when this process has none. */
    public @Nullable JsonNode snapshot(MentorTurn turn) {
        if (turn.getStatus() != MentorTurn.Status.RUNNING) {
            return null;
        }
        Snapshot snapshot = snapshots.getIfPresent(new Attempt(turn.getWorkspaceId(), turn.getId(), turn.getAttempt()));
        if (snapshot == null) {
            return null;
        }
        // Parsed when read, so a process that only listens, such as a worker, keeps text and parses nothing.
        try {
            JsonNode json = mapper.readTree(snapshot.text());
            return json.isObject() ? json : null;
        } catch (JacksonException malformed) {
            return null;
        }
    }

    /**
     * Takes one frame. Frames of one update arrive in order on the ledger's one connection; any gap drops the update.
     *
     * @return the change that readers learn of, once the frame completes an update that changed the snapshot
     */
    Optional<MentorLedgerEvents.TurnChanged> accept(MentorLedgerEvents.ProgressChanged frame) {
        // A notification is parsed without validation, so any field may be missing.
        if (frame.data() == null
                || frame.turnId() == null
                || frame.conversationId() == null
                || frame.surface() == null
                || frame.count() < 1
                || frame.count() > MAX_FRAMES
                || frame.index() < 0
                || frame.index() >= frame.count()) {
            return Optional.empty();
        }
        Attempt attempt = new Attempt(frame.workspaceId(), frame.turnId(), frame.attempt());
        if (frame.index() == 0) {
            assemblies.put(attempt, new Assembly(frame.sequence(), frame.count()));
        }
        Assembly assembly = assemblies.getIfPresent(attempt);
        if (assembly == null
                || assembly.sequence != frame.sequence()
                || assembly.count != frame.count()
                || assembly.next != frame.index()) {
            assemblies.invalidate(attempt);
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(frame.data());
        } catch (IllegalArgumentException malformed) {
            assemblies.invalidate(attempt);
            return Optional.empty();
        }
        if (bytes.length > FRAME_BYTES || assembly.bytes.size() + bytes.length > MAX_UPDATE_BYTES) {
            assemblies.invalidate(attempt);
            return Optional.empty();
        }
        assembly.bytes.writeBytes(bytes);
        if (++assembly.next < assembly.count) {
            return Optional.empty();
        }
        assemblies.invalidate(attempt);
        return apply(attempt, assembly.bytes.toByteArray())
                ? Optional.of(new MentorLedgerEvents.TurnChanged(
                        frame.workspaceId(),
                        frame.conversationId(),
                        frame.turnId(),
                        MentorTurn.Status.RUNNING,
                        frame.surface(),
                        frame.attempt()))
                : Optional.empty();
    }

    private boolean apply(Attempt attempt, byte[] json) {
        try {
            Update update = mapper.readValue(json, Update.class);
            Snapshot prior = snapshots.getIfPresent(attempt);
            if (update.text() == null || update.prefix() < 0 || update.suffix() < 0) {
                return false;
            }
            String text;
            // A whole snapshot always replaces the kept one: one connection receives frames in commit order, so it is
            // the newest whatever its sender's clock says. A change applies only to the snapshot it was made from.
            if (update.baseSequence() == 0) {
                if (update.prefix() != 0 || update.suffix() != 0) {
                    return false;
                }
                text = update.text();
            } else {
                if (prior == null
                        || prior.sequence() != update.baseSequence()
                        || (long) update.prefix() + update.suffix()
                                > prior.text().length()) {
                    return false;
                }
                text = prior.text().substring(0, update.prefix())
                        + update.text()
                        + prior.text().substring(prior.text().length() - update.suffix());
            }
            if (text.length() > MAX_SNAPSHOT_CHARS) {
                return false;
            }
            snapshots.put(attempt, new Snapshot(update.sequence(), text));
            return true;
        } catch (JacksonException | IllegalArgumentException malformed) {
            // A malformed update costs only progress, which the next whole snapshot replaces.
            return false;
        }
    }

    /** Forgets the turn's partial answers once it no longer runs. */
    void changed(MentorLedgerEvents.TurnChanged change) {
        if (change.status() == MentorTurn.Status.RUNNING) {
            return;
        }
        snapshots.asMap().keySet().removeIf(key -> key.turnId().equals(change.turnId()));
        streams.asMap().keySet().removeIf(key -> key.turnId().equals(change.turnId()));
        assemblies.asMap().keySet().removeIf(key -> key.turnId().equals(change.turnId()));
    }

    /** Frames were missed: a change waits for the next whole snapshot; the kept snapshots stay until then. */
    void resync() {
        assemblies.invalidateAll();
    }

    /**
     * The update that sends {@code text} as the attempt's newest partial answer. The caller sends one attempt's partial
     * answers one at a time.
     */
    Optional<Update> next(long workspaceId, UUID turnId, int attempt, String text, Instant now) {
        if (text.length() > MAX_SNAPSHOT_CHARS) {
            // No reader keeps it, so it is not sent; the answer itself is stored at the end.
            return Optional.empty();
        }
        return Optional.of(streams.get(new Attempt(workspaceId, turnId, attempt), ignored -> new Stream())
                .next(text, now));
    }

    /** The frames that carry one update. */
    List<MentorLedgerEvents.ProgressChanged> frames(
            long workspaceId,
            UUID conversationId,
            UUID turnId,
            MentorTurn.Surface surface,
            int attempt,
            Update update) {
        byte[] json = mapper.writeValueAsBytes(update);
        int count = Math.max(1, (json.length + FRAME_BYTES - 1) / FRAME_BYTES);
        List<MentorLedgerEvents.ProgressChanged> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int from = index * FRAME_BYTES;
            int to = Math.min(json.length, from + FRAME_BYTES);
            frames.add(new MentorLedgerEvents.ProgressChanged(
                    workspaceId,
                    conversationId,
                    turnId,
                    surface,
                    attempt,
                    update.sequence(),
                    index,
                    count,
                    Base64.getEncoder().encodeToString(Arrays.copyOfRange(json, from, to))));
        }
        return frames;
    }

    /**
     * The updates of one attempt's partial answers, as the process that receives them sends them. Each update changes
     * only what differs from the one before, and a whole snapshot follows at least every {@link #FULL_EVERY}, so a
     * reader that missed a frame catches up. Not thread-safe: one attempt's partial answers are sent one at a time.
     * A partial answer whose transaction rolls back still advances the stream, so readers skip updates until the next
     * whole snapshot.
     */
    static final class Stream {

        /** How long a reader that missed a frame waits at most for the next whole snapshot. */
        static final Duration FULL_EVERY = Duration.ofSeconds(2);

        private long sequence;
        private @Nullable String text;
        private @Nullable Instant fullAt;

        /** The update from the previous snapshot to {@code next}. */
        Update next(String next, Instant now) {
            // Sequences grow with time, so a change never names the base of another process's stream by chance.
            long nextSequence = Math.max(sequence + 1, now.toEpochMilli());
            String previous = text;
            Update update;
            if (previous == null || fullAt == null || !now.isBefore(fullAt.plus(FULL_EVERY))) {
                update = new Update(nextSequence, 0, 0, 0, next);
                fullAt = now;
            } else {
                int prefix = commonPrefix(previous, next);
                int suffix = commonSuffix(previous, next, prefix);
                update = new Update(
                        nextSequence, sequence, prefix, suffix, next.substring(prefix, next.length() - suffix));
            }
            sequence = nextSequence;
            text = next;
            return update;
        }

        private static int commonPrefix(String a, String b) {
            int max = Math.min(a.length(), b.length());
            int prefix = 0;
            while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
                prefix++;
            }
            // Never between the two halves of a surrogate pair: the changed text is sent as UTF-8.
            if (prefix > 0 && Character.isHighSurrogate(a.charAt(prefix - 1))) {
                prefix--;
            }
            return prefix;
        }

        private static int commonSuffix(String a, String b, int prefix) {
            int max = Math.min(a.length(), b.length()) - prefix;
            int suffix = 0;
            while (suffix < max && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
                suffix++;
            }
            if (suffix > 0 && Character.isLowSurrogate(b.charAt(b.length() - suffix))) {
                suffix--;
            }
            return suffix;
        }
    }
}
