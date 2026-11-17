package de.tum.cit.aet.hephaestus.agent.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Stages only the pinned definitions and scripts assigned to this job. */
@Component
public class PracticeCatalogInjector {
    private final ObjectMapper mapper;

    public PracticeCatalogInjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void inject(
            Map<String, byte[]> files,
            List<ReviewAssignmentSnapshot> assignments,
            ReviewKind scope,
            Map<ReviewAssignmentId, List<String>> contextPaths) {
        var index = new ArrayList<PracticeIndexEntry>();
        for (var assignment : assignments) {
            var practice = assignment.practice();
            var definition = practice.definition();
            var paths = scope == ReviewKind.SCAN
                    ? Objects.requireNonNull(contextPaths.get(assignment.id()), "Missing frozen scan context grant")
                    : null;
            var reads = scope == ReviewKind.SCAN
                    ? Objects.requireNonNull(definition.scan()).reads()
                    : definition.reads();
            index.add(new PracticeIndexEntry(
                    assignment.practiceSlug(),
                    definition.name(),
                    Long.toString(practice.practiceId()),
                    Long.toString(practice.practiceVersionId()),
                    paths,
                    reads.sources().stream().map(read -> read.source().value()).toList(),
                    reads.sources().stream()
                            .filter(read -> read.need() == ReadNeed.COMPLETE)
                            .map(read -> read.source().value())
                            .toList(),
                    definition.reviewedBy().limits().stream()
                            .map(limit -> limit.description())
                            .toList(),
                    definition.guidance().whyItMatters()));
            files.put(
                    SandboxLayout.PRACTICES_PREFIX + assignment.practiceSlug() + ".md",
                    definition.criteria().getBytes(StandardCharsets.UTF_8));
            String script = definition.script();
            if (script != null)
                files.put(
                        SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + assignment.practiceSlug() + ".ts",
                        script.getBytes(StandardCharsets.UTF_8));
        }
        files.put(SandboxLayout.PRACTICES_PREFIX + "index.json", mapper.writeValueAsBytes(index));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PracticeIndexEntry(
            String slug,
            String name,
            String practiceId,
            String versionId,
            @Nullable List<String> contextPaths,
            List<String> readsSources,
            List<String> exhaustiveSources,
            List<String> knownLimitations,
            @Nullable String whyItMatters) {}
}
