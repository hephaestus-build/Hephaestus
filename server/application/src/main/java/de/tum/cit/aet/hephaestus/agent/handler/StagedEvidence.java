package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore.StagedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The exact cited bytes, independent of the Pod and of a later attempt's capture. */
final class StagedEvidence {
    private StagedEvidence() {}

    static ObjectNode retain(
            ObjectMapper mapper,
            JobFolderIndex manifest,
            @Nullable ResultCandidate candidate,
            Map<String, byte[]> inputs,
            UUID copyId,
            Instant expiresAt) {
        var evidence =
                mapper.createObjectNode().put("copyId", copyId.toString()).put("expiresAt", expiresAt.toString());
        var texts = new ArrayList<CitableText>();
        var files = evidence.putObject("files");
        for (var citation : citations(candidate)) {
            var matches = manifest.citableTexts().stream()
                    .filter(text -> text.source().equals(citation.sourceKind().value())
                            && text.path().equals(citation.path())
                            && text.side() == Objects.requireNonNullElse(citation.side(), CitationSide.NEW)
                            && Objects.equals(text.commit(), citation.commit())
                            && text.sha256()
                                    .equals(Objects.requireNonNull(citation.verification())
                                            .itemSha256()))
                    .toList();
            if (matches.size() != 1) throw new AdmissionConflictException();
            var text = matches.getFirst();
            byte[] bytes = inputs.get(text.stagedPath());
            if (bytes == null || !text.sha256().equals(ProvenanceDigest.sha256Hex(bytes)))
                throw new AdmissionConflictException();
            String retained = "context/staged/" + text.sha256() + ".txt";
            var saved =
                    new CitableText(text.source(), text.path(), text.side(), text.commit(), retained, text.sha256());
            if (!texts.contains(saved)) texts.add(saved);
            files.put(retained, Base64.getEncoder().encodeToString(bytes));
        }
        evidence.set("citableTexts", mapper.valueToTree(texts));
        evidence.set("sources", mapper.valueToTree(manifest.sources()));
        return evidence;
    }

    static void merge(ObjectMapper mapper, ReviewCompositionBasis basis, List<StagedPractice> staged) {
        var files = new TreeMap<String, byte[]>();
        basis.getPayload()
                .path("files")
                .properties()
                .forEach(entry -> files.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asString())));
        var manifest =
                mapper.readValue(Objects.requireNonNull(files.get(SandboxLayout.MANIFEST_PATH)), JobFolderIndex.class);
        var texts = new LinkedHashMap<String, CitableText>();
        var sources = new LinkedHashMap<String, SourceCapture>();
        manifest.sources().forEach(source -> sources.put(source.kind().value(), source));
        var practices = new LinkedHashMap<String, JsonNode>();
        byte[] index = files.get(SandboxLayout.PRACTICES_PREFIX + "index.json");
        if (index != null)
            mapper.readTree(index)
                    .forEach(practice -> practices.put(practice.path("slug").asString(), practice));

        for (var entry : staged) {
            var retained = entry.evidence();
            for (var sourceNode : retained.path("sources")) {
                var source = mapper.treeToValue(sourceNode, SourceCapture.class);
                sources.putIfAbsent(source.kind().value(), source);
            }
            var practice = retained.path("practice");
            if (practice.isObject()) practices.put(practice.path("slug").asString(), practice);

            Instant expires = Instant.parse(retained.path("expiresAt").asString());
            if (expires.isBefore(basis.getExpiresAt())) basis.setExpiresAt(expires);
            retained.path("files")
                    .properties()
                    .forEach(file -> files.put(
                            file.getKey(),
                            Base64.getDecoder().decode(file.getValue().asString())));
            for (var textNode : retained.path("citableTexts")) {
                var text = mapper.treeToValue(textNode, CitableText.class);
                byte[] bytes = files.get(text.stagedPath());
                if (bytes == null || !text.sha256().equals(ProvenanceDigest.sha256Hex(bytes)))
                    throw new AdmissionConflictException();
                texts.put(
                        mapper.writeValueAsString(List.of(
                                text.source(),
                                text.path(),
                                text.side(),
                                Objects.requireNonNullElse(text.commit(), ""),
                                text.sha256())),
                        text);
            }
        }
        // Preserve uncited placement and captured-work records; only cited text comes from the staged practices.
        var sealed = new JobFolderIndex(
                manifest.contractVersion(),
                manifest.catalogDigest(),
                manifest.itemKind(),
                manifest.capturedAt(),
                List.copyOf(sources.values()),
                manifest.refusals(),
                manifest.files(),
                List.copyOf(texts.values()));
        files.put(SandboxLayout.MANIFEST_PATH, mapper.writeValueAsBytes(sealed));
        files.put(SandboxLayout.PRACTICES_PREFIX + "index.json", mapper.writeValueAsBytes(practices.values()));
        var payload = mapper.createObjectNode();
        var copies = payload.putArray("copies");
        staged.stream()
                .map(entry -> Map.of("copyId", entry.evidence().path("copyId").asString(), "attempt", entry.attempt()))
                .distinct()
                .forEach(copy -> copies.add(mapper.valueToTree(copy)));
        var encoded = payload.putObject("files");
        files.forEach((path, bytes) -> encoded.put(path, Base64.getEncoder().encodeToString(bytes)));
        basis.setPayload(payload);
        basis.setBasisDigest(ReviewCompositionBasis.digest(files));
    }

    private static List<EvidenceCitation> citations(@Nullable ResultCandidate candidate) {
        if (candidate == null) return List.of();
        var citations = new ArrayList<>(candidate.evidence().citations());
        var example = candidate.example();
        if (example != null) citations.add(example.citation());
        candidate.evidence().openQuestions().forEach(question -> {
            var citation = question.citation();
            if (citation != null) citations.add(citation);
        });
        candidate.problems().forEach(problem -> citations.addAll(problem.citations()));
        return citations;
    }
}
