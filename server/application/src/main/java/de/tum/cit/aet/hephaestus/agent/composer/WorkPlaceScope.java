package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a sealed review hands its work place (ADR 0121): the one place that decides what the public writer may read.
 *
 * <p>The scope keeps the review's public results and only what they need: the texts their citations name, the work's
 * own captured record, the catalog entries of their practices and the copy forms of the work. Nothing that only a
 * private or withheld candidate reached is kept: not its cited text, not its practice's entry, not its source. A
 * practice whose only result is private is "not reached" for the public writer, so a private result cannot change what
 * the work place reads. The private brief, the developer's problem history and the private composition files never
 * enter.
 *
 * <p>The scope is a copy, made once at the seal from inputs that the legacy composition could compose from, so it
 * outlives the review's sandbox and its basis. Its digest covers every byte it keeps, except the {@code publicEligible}
 * flags: a later change of a practice's feedback mode is a destination rule that the composer checks when it writes,
 * not a change of what the review found.
 */
public final class WorkPlaceScope {
    public static final int VERSION = 1;

    /** The copy deadline: a copy on the work that is not staged two hours after the seal is withheld (ADR 0122). */
    public static final Duration COPY_DEADLINE = Duration.ofHours(2);

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private WorkPlaceScope() {}

    /** A person-copy receipt that the kept bytes came from. */
    public record Copy(UUID copyId, int attempt) {}

    /**
     * What the seal hands over, read in its transaction.
     *
     * @param basis the sealed composition inputs, as the legacy composition restores them
     * @param results the admitted results as the server projects them, in its order
     * @param capturedSources the source kinds of the sealing job's own capture
     * @param assignedPractices the practices the review assigned, by slug
     * @param onWorkCopyForms the copy forms the work takes
     * @param root the receipt of the captured work record
     * @param stagedCopies the receipts of each practice's staged evidence, by practice slug
     * @param basisExpiresAt when the sealed inputs may no longer be used
     */
    @SuppressWarnings("ArrayRecordComponent") // each value is a file's retained bytes
    public record Sealed(
            Map<String, byte[]> basis,
            List<JsonNode> results,
            Set<String> capturedSources,
            List<String> assignedPractices,
            List<String> onWorkCopyForms,
            Copy root,
            Map<String, List<Copy>> stagedCopies,
            Instant sealedAt,
            Instant basisExpiresAt) {}

    /** The kept copy: the payload a handoff row stores, its digest and the time it may no longer be used. */
    public record Scoped(ObjectNode payload, String digest, Instant expiresAt) {}

    /**
     * The work place's copy of a sealed review, or empty when the work place has nothing to read: no public result is
     * Not met, or the inputs hold no capture manifest.
     */
    public static Optional<Scoped> of(Sealed sealed) {
        var selected = new ArrayList<JsonNode>();
        for (JsonNode result : sealed.results())
            if (ReviewOnWork.isTrue(result.path("publicEligible"))) selected.add(result);
        if (selected.stream().noneMatch(result -> "NOT_MET".equals(Js.string(result.path("outcome")))))
            return Optional.empty();
        byte[] indexBytes = sealed.basis().get(SandboxLayout.MANIFEST_PATH);
        if (indexBytes == null) return Optional.empty();
        JsonNode sealedIndex = MAPPER.readTree(indexBytes);
        if (!sealedIndex.isObject()) return Optional.empty();

        var practices = new LinkedHashSet<String>();
        selected.forEach(result -> practices.add(Evidence.text(result.path("practiceSlug"))));
        var cited = new LinkedHashSet<List<String>>();
        var citedSources = new TreeSet<String>();
        for (JsonNode result : selected)
            for (JsonNode citation : citations(result)) {
                cited.add(key(citation));
                citedSources.add(value(citation.path("sourceKind")));
            }

        var index = ((ObjectNode) sealedIndex).deepCopy();
        var texts = JSON.arrayNode();
        Js.listOf(sealedIndex.path("citableTexts")).stream()
                .filter(text -> cited.contains(List.of(
                        value(text.path("source")),
                        value(text.path("path")),
                        Objects.requireNonNullElse(Js.string(text.path("side")), "NEW"),
                        value(text.path("commit")),
                        value(text.path("sha256")))))
                .forEach(texts::add);
        index.set("citableTexts", texts);
        var sources = JSON.arrayNode();
        Js.listOf(sealedIndex.path("sources")).stream()
                .filter(source -> {
                    String kind = Evidence.text(source.path("kind"));
                    return sealed.capturedSources().contains(kind) || citedSources.contains(kind);
                })
                .forEach(sources::add);
        index.set("sources", sources);

        var files = new TreeMap<String, byte[]>();
        files.put(SandboxLayout.MANIFEST_PATH, MAPPER.writeValueAsBytes(index));
        var record = new LinkedHashSet<String>();
        Js.listOf(index.path("files"))
                .forEach(file -> record.add(Evidence.text(file.path("file").path("path"))));
        sources.forEach(source ->
                Js.listOf(source.path("files")).forEach(file -> record.add(Evidence.text(file.path("path")))));
        texts.forEach(text -> record.add(Evidence.text(text.path("stagedPath"))));
        for (String path : record) {
            byte[] bytes = sealed.basis().get(path);
            if (bytes != null) files.put(path, bytes);
        }
        var catalog = JSON.arrayNode();
        var indexed = new ArrayList<String>();
        byte[] practiceIndex = sealed.basis().get(ComposerFiles.PRACTICE_INDEX);
        if (practiceIndex != null)
            for (JsonNode practice : Js.listOf(MAPPER.readTree(practiceIndex))) {
                String slug = Evidence.text(practice.path("slug"));
                indexed.add(slug);
                if (practices.contains(slug)) catalog.add(practice);
            }
        files.put(ComposerFiles.PRACTICE_INDEX, MAPPER.writeValueAsBytes(catalog));
        for (String slug : practices) {
            byte[] criteria = sealed.basis().get(ComposerFiles.criteria(slug));
            if (criteria != null) files.put(ComposerFiles.criteria(slug), criteria);
        }

        var notReached = new ArrayList<String>();
        var assigned = new TreeSet<>(sealed.assignedPractices());
        assigned.removeAll(practices);
        for (String slug : indexed) if (assigned.remove(slug)) notReached.add(slug);
        notReached.addAll(assigned);

        var copies = new LinkedHashSet<Copy>();
        copies.add(sealed.root());
        practices.forEach(slug -> copies.addAll(sealed.stagedCopies().getOrDefault(slug, List.of())));

        var payload = JSON.objectNode();
        payload.put("version", VERSION);
        payload.putObject("request").set("onWorkCopyForms", strings(sealed.onWorkCopyForms()));
        var results = payload.putArray("results");
        selected.forEach(result -> results.add(AdmittedResults.normalize(result)));
        payload.set("notReached", strings(notReached));
        var receipts = payload.putArray("copies");
        copies.forEach(copy ->
                receipts.addObject().put("copyId", copy.copyId().toString()).put("attempt", copy.attempt()));
        var encoded = payload.putObject("files");
        files.forEach((path, bytes) -> encoded.put(path, Base64.getEncoder().encodeToString(bytes)));

        Instant deadline = sealed.sealedAt().plus(COPY_DEADLINE);
        Instant expiresAt = sealed.basisExpiresAt().isBefore(deadline) ? sealed.basisExpiresAt() : deadline;
        return Optional.of(new Scoped(payload, digest(payload), expiresAt));
    }

    /**
     * The handoff a kept copy holds, after its digest matches.
     *
     * @throws IllegalArgumentException when the payload does not match its digest or is not a version this reads
     */
    static WorkPlaceHandoff read(JsonNode payload, String digest) {
        if (payload.path("version").asInt(-1) != VERSION || !digest.equals(digest(payload)))
            throw new IllegalArgumentException("The work-place handoff does not match its digest");
        var files = new TreeMap<String, byte[]>();
        payload.path("files")
                .properties()
                .forEach(entry -> files.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asString())));
        var results = new ArrayList<ObjectNode>();
        Js.listOf(payload.path("results")).forEach(result -> results.add(((ObjectNode) result).deepCopy()));
        return new WorkPlaceHandoff(
                files,
                results,
                Js.listOf(payload.path("notReached")).stream()
                        .map(JsonNode::asString)
                        .toList(),
                Js.listOf(payload.path("request").path("onWorkCopyForms")).stream()
                        .map(JsonNode::asString)
                        .toList());
    }

    /** The quotes the handoff's results and their problems cite: the only quotes that may supply a link on the work. */
    static List<String> quotes(WorkPlaceHandoff handoff) {
        var quotes = new ArrayList<String>();
        for (var result : handoff.results()) {
            Js.listOf(result.path("evidence").path("citations")).forEach(citation -> add(quotes, citation));
            Js.listOf(result.path("problems"))
                    .forEach(
                            problem -> Js.listOf(problem.path("citations")).forEach(citation -> add(quotes, citation)));
        }
        return quotes;
    }

    private static void add(List<String> quotes, JsonNode citation) {
        String quote = Js.string(citation.path("quote"));
        if (quote != null) quotes.add(quote);
    }

    /** Every citation of a projected result: its evidence, its example, its open questions and its problems. */
    private static List<JsonNode> citations(JsonNode result) {
        var citations = new ArrayList<JsonNode>();
        JsonNode evidence = result.path("evidence");
        citations.addAll(Js.listOf(evidence.path("citations")));
        JsonNode example = result.path("example").path("citation");
        if (example.isObject()) citations.add(example);
        Js.listOf(evidence.path("openQuestions")).forEach(question -> {
            if (question.path("citation").isObject()) citations.add(question.path("citation"));
        });
        Js.listOf(result.path("problems")).forEach(problem -> citations.addAll(Js.listOf(problem.path("citations"))));
        return citations;
    }

    /** A citation's text as {@code StagedEvidence} matches it: source, path, side, commit and the item digest. */
    private static List<String> key(JsonNode citation) {
        return List.of(
                value(citation.path("sourceKind")),
                value(citation.path("path")),
                Objects.requireNonNullElse(Js.string(citation.path("side")), "NEW"),
                value(citation.path("commit")),
                value(citation.path("verification").path("itemSha256")));
    }

    /** A key part: a missing field and a null one are the same absent value, whichever way a writer serialized it. */
    private static String value(JsonNode field) {
        return Objects.requireNonNullElse(Js.string(field), "");
    }

    private static ArrayNode strings(List<String> values) {
        var array = JSON.arrayNode();
        values.forEach(array::add);
        return array;
    }

    /** SHA-256 over the payload with each file as its own digest and without the {@code publicEligible} flags. */
    private static String digest(JsonNode payload) {
        var material = JSON.objectNode();
        material.set("version", payload.path("version"));
        material.set("request", payload.path("request"));
        var results = material.putArray("results");
        for (JsonNode result : Js.listOf(payload.path("results"))) {
            var copy = ((ObjectNode) result).deepCopy();
            copy.remove("publicEligible");
            Js.listOf(copy.path("problems")).forEach(problem -> ((ObjectNode) problem).remove("publicEligible"));
            results.add(copy);
        }
        material.set("notReached", payload.path("notReached"));
        material.set("copies", payload.path("copies"));
        var files = material.putObject("files");
        var sorted = new TreeMap<String, String>();
        payload.path("files")
                .properties()
                .forEach(entry -> sorted.put(
                        entry.getKey(),
                        ProvenanceDigest.sha256Hex(
                                Base64.getDecoder().decode(entry.getValue().asString()))));
        sorted.forEach(files::put);
        return ProvenanceDigest.sha256Hex(material.toString().getBytes(StandardCharsets.UTF_8));
    }
}
