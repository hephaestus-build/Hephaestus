package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.delete;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.safePath;
import static de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles.COPIES_EXPIRE_AT;
import static de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles.TRACE_BYTE_BUDGET;
import static de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles.TRACE_RETENTION;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceReceipts;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native session transcripts of a review attempt, which {@link JobEvidenceFiles} receives as the sandbox result
 * listener: the attempt it binds at launch, and the transcript or METADATA_ONLY record that it keeps beside the attempt
 * folder for a day.
 */
final class AttemptTranscripts {
    private static final Logger log = LoggerFactory.getLogger(AttemptTranscripts.class);

    /** Where the gateway runner puts the native session transcripts in a result. */
    private static final String TRACES = "traces/";

    static final String TRACE_SUFFIX = ".trace";
    static final String TRACE_RECORD = "record.json";

    /**
     * The collector's manifest (gateway-run.ts). It is sandbox output: a METADATA_ONLY record keeps only what
     * {@link TraceSummary} rebuilds from it.
     */
    private static final String TRACE_MANIFEST = "manifest.json";

    private static final int TRACE_FILE_BUDGET = 2 * 1024 * 1024;

    private final FabricLayout layout;
    private final ReviewJobRepository jobs;
    private final Clock clock;
    private final EvidenceReceipts receipts;

    AttemptTranscripts(FabricLayout layout, ReviewJobRepository jobs, Clock clock, EvidenceReceipts receipts) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
        this.receipts = receipts;
    }

    /**
     * What a review attempt was launched as, captured before its sandbox existed.
     *
     * @param copiesExpireAt when the occasion record the attempt's inputs copy expires; null when they copy none
     */
    private record LaunchedAttempt(
            long workspaceId,
            UUID jobId,
            int attempt,
            UUID runId,
            String image,
            @Nullable String model,
            @Nullable String promptDigest,
            @Nullable String inputsDigest,
            @Nullable Instant copiesExpireAt) {}

    /**
     * Binds the attempt the executor is launching: the job must be RUNNING as exactly this attempt. Anything else, and
     * a conversation session, which is never bound, keeps no transcript.
     */
    Consumer<Map<String, byte[]>> bind(UUID jobId, int attempt, String image) {
        var launched = jobs.findById(jobId)
                .filter(job -> job.getStatus() == ReviewJobStatus.RUNNING && job.getAttempt() == attempt)
                .map(job -> new LaunchedAttempt(
                        job.getWorkspaceId(),
                        jobId,
                        attempt,
                        job.getRunId(),
                        image,
                        job.getConfigSnapshot() == null
                                ? null
                                : job.getConfigSnapshot()
                                        .path("model")
                                        .path("upstreamModelId")
                                        .asString(null),
                        job.getCapture() == null
                                ? null
                                : job.getCapture().path("promptDigest").asString(null),
                        job.getCapture() == null
                                ? null
                                : job.getCapture().path("inputsDigest").asString(null),
                        copiesExpireAt(job.getCapture())));
        if (launched.isEmpty()) return files -> {};
        var bound = launched.get();
        return files -> keepTranscript(bound, files);
    }

    /** A stated expiry that cannot be read keeps no copy, as if it had passed. */
    private static @Nullable Instant copiesExpireAt(@Nullable JsonNode capture) {
        if (capture == null || !capture.has(COPIES_EXPIRE_AT)) return null;
        try {
            return Instant.parse(capture.path(COPIES_EXPIRE_AT).asString(""));
        } catch (DateTimeParseException unreadable) {
            return Instant.EPOCH;
        }
    }

    /**
     * Keeps the native session transcripts of an admitted upload beside the attempt folder for a day. Only while the
     * job is still owned as the attempt that was launched, only while copy admission and the local folder lease protect
     * its READY receipt in this store. A receipt that cannot index every copied dependency keeps a summary the
     * server rebuilds itself, marked METADATA_ONLY. Never throws into the upload, and never changes the job's status.
     */
    private void keepTranscript(LaunchedAttempt launched, Map<String, byte[]> files) {
        var traces = new TreeMap<String, byte[]>();
        files.forEach((name, bytes) -> {
            if (name.startsWith(TRACES) && name.length() > TRACES.length())
                traces.put(name.substring(TRACES.length()), bytes);
        });
        if (traces.isEmpty()) return;
        try {
            // A delayed upload of an earlier attempt finds the job requeued under another attempt or owner.
            var owned = jobs.findByIdAndWorkspaceId(launched.jobId(), launched.workspaceId())
                    .filter(job -> job.getAttempt() == launched.attempt()
                            && launched.runId().equals(job.getRunId()));
            if (owned.isEmpty()) return;
            Path root = directory(
                    layout.jobsRoot(), launched.workspaceId(), launched.jobId(), launched.attempt(), launched.runId());
            Path trace = root.resolveSibling(root.getFileName() + TRACE_SUFFIX);
            writeTranscript(owned.get(), () -> {
                var custody = receipts.traceCustody(launched.jobId(), launched.workspaceId(), launched.attempt());
                if (custody == EvidenceReceipts.TraceCustody.NOT_HELD || Files.exists(trace, LinkOption.NOFOLLOW_LINKS))
                    return;
                Files.createDirectories(root.getParent());
                Path staging = Files.createTempDirectory(root.getParent(), "." + trace.getFileName() + ".preparing-");
                try {
                    Instant now = clock.instant();
                    Instant expiresAt = now.plus(TRACE_RETENTION);
                    var occasionExpiry = launched.copiesExpireAt();
                    boolean occasionKept = occasionExpiry == null || occasionExpiry.isAfter(now);
                    boolean withinBudget = boundedTraces(traces);
                    boolean raw = custody == EvidenceReceipts.TraceCustody.INDEXED && withinBudget && occasionKept;
                    // The sessions' words may repeat the occasion's record, so they go when it expires. The summary
                    // that replaces them names no person and copies none of those words.
                    if (raw && occasionExpiry != null && occasionExpiry.isBefore(expiresAt)) expiresAt = occasionExpiry;
                    ObjectNode record = JsonNodeFactory.instance.objectNode();
                    record.put("jobId", launched.jobId().toString());
                    record.put("workspaceId", launched.workspaceId());
                    record.put("attempt", launched.attempt());
                    record.put("image", launched.image());
                    record.put("model", launched.model());
                    record.put("promptDigest", launched.promptDigest());
                    record.put("inputsDigest", launched.inputsDigest());
                    record.put("retention", raw ? "NATIVE_TRANSCRIPT" : "METADATA_ONLY");
                    // A copied row whose source cannot vouch for the people it names keeps every session's words out.
                    if (!raw)
                        record.put(
                                "contentUnavailable",
                                !occasionKept
                                        ? "OCCASION_EVIDENCE_UNAVAILABLE"
                                        : withinBudget ? "OWNERSHIP_INCOMPLETE" : "TRACE_OUTPUT_INVALID");
                    record.put("expiresAt", expiresAt.toString());
                    if (raw) {
                        for (var entry : traces.entrySet()) {
                            Path target = safePath(staging, entry.getKey());
                            Files.createDirectories(target.getParent());
                            Files.write(target, entry.getValue(), StandardOpenOption.CREATE_NEW);
                        }
                    } else {
                        var sessions = TraceSummary.of(traces.get(TRACE_MANIFEST));
                        if (sessions == null) record.putNull("sessions");
                        else record.set("sessions", sessions);
                        record.put("sessionScanTruncated", TraceSummary.scanTruncated(traces.get(TRACE_MANIFEST)));
                    }
                    Path recordFile = staging.resolve(TRACE_RECORD);
                    Files.writeString(recordFile, record.toString(), StandardOpenOption.CREATE_NEW);
                    // The sweep counts the retention from this timestamp, and ends it sooner when the record states an
                    // earlier expiry.
                    Files.setLastModifiedTime(recordFile, FileTime.from(now));
                    Files.move(staging, trace, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) delete(staging);
                }
            });
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not keep the review transcript of job {}: {}",
                    launched.jobId(),
                    exception.getClass().getSimpleName());
        }
    }

    /** Copy admission precedes the local folder lock, as it does during restoration and erasure. */
    private void writeTranscript(ReviewJob job, EvidenceFolderLease.Removal write) {
        receipts.withLiveAttempt(job, () -> {
            try {
                return EvidenceFolderLease.removeAsOwner(layout.root(), job.getWorkspaceId(), job.getId(), write);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    /** The sandbox cannot enlarge debug retention beyond the collector's declared limits. */
    private static boolean boundedTraces(Map<String, byte[]> traces) {
        long bytes = 0;
        if (traces.size() > 1001) return false;
        for (var entry : traces.entrySet()) {
            boolean manifest = TRACE_MANIFEST.equals(entry.getKey());
            if (!manifest && !entry.getKey().matches("[0-9]{4}\\.jsonl")) return false;
            if (entry.getValue().length > (manifest ? 1024 * 1024 : TRACE_FILE_BUDGET)) return false;
            bytes += entry.getValue().length;
            if (bytes > TRACE_BYTE_BUDGET) return false;
        }
        return true;
    }

    /**
     * The collector's manifest is sandbox output: what a METADATA_ONLY record keeps is rebuilt here from it, field by
     * field, as owned enums, booleans, bounded counts and numeric identifiers. Nothing the manifest carries is copied
     * as written.
     */
    private static final class TraceSummary {
        private static final JsonMapper MANIFESTS = new JsonMapper();
        /** The collector's own reserve for its manifest (gateway-run.ts); a larger one was not written by it. */
        private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

        private static final int MAX_SESSIONS = 1000;
        private static final long MAX_COUNT = 1_000_000;
        private static final long MAX_TOKENS = 1_000_000_000_000L;
        private static final long MAX_BYTES = TRACE_BYTE_BUDGET;
        private static final Set<String> PHASES = Set.of("practice", "public-review", "private-feedback");
        private static final Set<String> OMISSIONS = Set.of("oversize", "budget", "unreadable");
        private static final Set<String> STOP_REASONS =
                Set.of("stop", "length", "toolUse", "error", "aborted", "other");
        /** The tools a review session is given (pi-runner.ts); the collector counts any other name as "other". */
        private static final Set<String> TOOL_NAMES = Set.of(
                "read",
                "grep",
                "find",
                "ls",
                "bash",
                "codemode",
                "upsert_problem",
                "remove_problem",
                "read_problem",
                "submit_result",
                "other");
        /** The provider adapter's closed failure diagnostic (docker/agents/pi/patches), as the collector keeps it. */
        private static final Set<String> MODEL_FAILURE_KINDS = Set.of(
                "HTTP_ERROR",
                "CONNECTION_TIMEOUT",
                "CONNECTION_ERROR",
                "STREAM_INCOMPLETE",
                "FINISH_REASON_ERROR",
                "STREAM_ERROR_EVENT",
                "RESPONSE_FAILED",
                "RESPONSE_STATUS_ERROR",
                "TOOL_CALL_INCOMPLETE",
                "ABORTED",
                "UNKNOWN");

        private static final Set<String> MODEL_FAILURE_PHASES = Set.of("request", "response_body");
        private static final Set<String> MODEL_FAILURE_SOURCES = Set.of("ADAPTER", "MISSING", "INVALID");
        /** The adapter's call span totals in milliseconds (gateway-run.ts), each a bounded integer. */
        private static final List<String> MODEL_CALL_TIMING =
                List.of("elapsedMs", "failedElapsedMs", "maxElapsedMs", "responseMs");

        private static final long MAX_DURATION_MS = (1L << 53) - 1;
        /** The largest time a JavaScript Date can hold, in epoch milliseconds. */
        private static final long MAX_EPOCH_MS = 8_640_000_000_000_000L;

        private TraceSummary() {}

        /** The sessions as the server states them, or null when the manifest is absent, oversize or unreadable. */
        static @Nullable ArrayNode of(byte @Nullable [] manifest) {
            if (manifest == null || manifest.length > MAX_MANIFEST_BYTES) return null;
            JsonNode root;
            try {
                root = MANIFESTS.readTree(manifest);
            } catch (RuntimeException unreadable) {
                log.warn(
                        "Unreadable transcript manifest: {}",
                        unreadable.getClass().getSimpleName());
                return null;
            }
            if (!root.path("sessions").isArray()) return null;
            ArrayNode sessions = JsonNodeFactory.instance.arrayNode();
            for (JsonNode session : root.path("sessions")) {
                if (sessions.size() == MAX_SESSIONS) break;
                ObjectNode kept = sessions.addObject();
                kept.put("copied", session.path("file").isString());
                putBounded(kept, "bytes", session.path("bytes"), MAX_BYTES);
                putFlag(kept, "redacted", session.path("redacted"));
                putFlag(kept, "partialLineDropped", session.path("partialLineDropped"));
                putOwned(kept, "omitted", session.path("omitted"), OMISSIONS);
                JsonNode summary = session.path("summary");
                if (!summary.isObject()) continue;
                ObjectNode facts = kept.putObject("summary");
                putOwned(facts, "phase", summary.path("phase"), PHASES);
                putPracticeVersionId(facts, summary.path("practiceVersionId"));
                for (String count : List.of(
                        "entries", "assistantCalls", "toolErrors", "compactions", "unattributedModelFailures")) {
                    putBounded(facts, count, summary.path(count), MAX_COUNT);
                }
                ObjectNode usage = facts.putObject("usage");
                for (String bucket : List.of("input", "output", "cacheRead", "cacheWrite")) {
                    putBounded(usage, bucket, summary.path("usage").path(bucket), MAX_TOKENS);
                }
                putCounts(facts.putObject("stopReasons"), summary.path("stopReasons"), STOP_REASONS);
                putCounts(facts.putObject("toolCalls"), summary.path("toolCalls"), TOOL_NAMES);
                putCounts(facts.putObject("modelFailures"), summary.path("modelFailures"), MODEL_FAILURE_KINDS);
                putBounded(facts, "lastModelFailureAt", summary.path("lastModelFailureAt"), MAX_EPOCH_MS);
                putModelFailure(facts, summary.path("finalModelFailure"));
                JsonNode callTiming = summary.path("modelCallTiming");
                if (callTiming.isObject()) {
                    ObjectNode timing = facts.putObject("modelCallTiming");
                    for (String field : List.of("timedCalls", "respondedCalls")) {
                        putBounded(timing, field, callTiming.path(field), MAX_COUNT);
                    }
                    for (String field : MODEL_CALL_TIMING) {
                        putBounded(timing, field, callTiming.path(field), MAX_DURATION_MS);
                    }
                }
            }
            return sessions;
        }

        private static void putPracticeVersionId(ObjectNode facts, JsonNode value) {
            if (!value.isString()) return;
            String version = value.asString();
            if (version.matches("[1-9][0-9]{0,18}")
                    && (version.length() < 19 || version.compareTo(Long.toString(Long.MAX_VALUE)) <= 0)) {
                facts.put("practiceVersionId", version);
            }
        }

        /** Only an owned kind keeps a final failure; its phase, status (HTTP_ERROR only) and time each on their own. */
        private static void putModelFailure(ObjectNode facts, JsonNode failure) {
            JsonNode kind = failure.path("kind");
            if (!kind.isString() || !MODEL_FAILURE_KINDS.contains(kind.asString())) return;
            ObjectNode kept = facts.putObject("finalModelFailure");
            kept.put("kind", kind.asString());
            putOwned(kept, "source", failure.path("source"), MODEL_FAILURE_SOURCES);
            putOwned(kept, "phase", failure.path("phase"), MODEL_FAILURE_PHASES);
            if ("HTTP_ERROR".equals(kind.asString())) putBetween(kept, "status", failure.path("status"), 400, 599);
            putBounded(kept, "at", failure.path("at"), MAX_EPOCH_MS);
        }

        static boolean scanTruncated(byte @Nullable [] manifest) {
            if (manifest == null || manifest.length > MAX_MANIFEST_BYTES) return true;
            try {
                var root = MANIFESTS.readTree(manifest);
                var value = root.path("sessionScanTruncated");
                return !root.path("sessions").isArray()
                        || root.path("sessions").size() > MAX_SESSIONS
                        || !value.isBoolean()
                        || value.asBoolean();
            } catch (RuntimeException unreadable) {
                return true;
            }
        }

        private static void putBounded(ObjectNode target, String field, JsonNode value, long max) {
            putBetween(target, field, value, 0, max);
        }

        private static void putBetween(ObjectNode target, String field, JsonNode value, long min, long max) {
            if (value.isIntegralNumber()
                    && value.canConvertToLong()
                    && value.asLong() >= min
                    && value.asLong() <= max) {
                target.put(field, value.asLong());
            }
        }

        private static void putFlag(ObjectNode target, String field, JsonNode value) {
            if (value.isBoolean()) target.put(field, value.asBoolean());
        }

        private static void putOwned(ObjectNode target, String field, JsonNode value, Set<String> owned) {
            if (value.isString() && owned.contains(value.asString())) target.put(field, value.asString());
        }

        /** Only owned keys, each a bounded count; another key is dropped, not renamed. */
        private static void putCounts(ObjectNode target, JsonNode counts, Set<String> owned) {
            for (String key : owned) putBounded(target, key, counts.path(key), MAX_COUNT);
        }
    }
}
