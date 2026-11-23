package de.tum.cit.aet.hephaestus.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records model calls by their canonical request hash, and replays them with no model.
 *
 * <p>A recording cassette asks its model once for each new request and keeps the reply. A replaying cassette answers
 * only recorded requests. An unrecorded request fails with its hash and canonical body, so a changed prompt shows as
 * a miss and never reaches a model.
 */
public final class Cassette implements ScriptedModel {

    /** One recorded call. The canonical request is kept so that a reviewer can read what the model saw. */
    public record Call(String request, String reply) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Call> calls;
    private final @Nullable ScriptedModel model;

    private Cassette(Map<String, Call> calls, @Nullable ScriptedModel model) {
        this.calls = calls;
        this.model = model;
    }

    /** A cassette that records the replies of {@code model}. */
    public static Cassette recording(ScriptedModel model) {
        return new Cassette(new TreeMap<>(), model);
    }

    /** A cassette that replays the calls in {@code file} and calls no model. */
    public static Cassette replaying(Path file) throws IOException {
        Map<String, Call> calls = JSON.readValue(Files.readString(file), new TypeReference<TreeMap<String, Call>>() {});
        return new Cassette(calls, null);
    }

    @Override
    public String reply(String requestBody) {
        String hash = CanonicalRequestHash.of(requestBody);
        Call recorded = calls.get(hash);
        if (recorded != null) {
            return recorded.reply();
        }
        String canonical = CanonicalRequestHash.canonical(requestBody);
        if (model == null) {
            throw new AssertionError("No recorded call for request " + hash + ": " + canonical);
        }
        String reply = model.reply(requestBody);
        calls.put(hash, new Call(canonical, reply));
        return reply;
    }

    /** Writes the recorded calls, sorted by hash, so a re-recording diffs line by line. */
    public void write(Path file) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(calls));
    }
}
