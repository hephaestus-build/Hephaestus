package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class PracticeScriptInputs {
    private final ObjectMapper mapper;

    public PracticeScriptInputs(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, byte[]> stage(
            ReviewSnapshot review,
            ReviewAssignmentSnapshot assignment,
            JsonNode item,
            Instant startedAt,
            @Nullable JsonNode previous) {
        if (!review.id().equals(assignment.id().reviewId())
                || assignment.item().workspaceId() != review.workspaceId()) {
            throw new IllegalArgumentException("The assignment does not belong to this review");
        }
        var root = mapper.createObjectNode();
        root.put("contractVersion", SandboxLayout.PRACTICE_CONTRACT_VERSION)
                .put("kind", assignment.item().kind().value());
        root.set("item", item.deepCopy());
        var definition = assignment.practice().definition();
        if (review.kind() == ReviewKind.SCAN || definition.about() == null) root.putNull("about");
        else {
            Long developer = assignment.developerId();
            if (developer == null)
                throw new IllegalArgumentException("A person-attributed script requires a pinned developer");
            root.putObject("about").put("role", definition.about().name()).put("developerId", developer.toString());
        }
        root.putObject("practice")
                .put("id", Long.toString(assignment.practice().practiceId()))
                .put("versionId", Long.toString(assignment.practice().practiceVersionId()))
                .put("reviewDigest", assignment.practice().reviewDigest())
                .put("slug", assignment.practiceSlug())
                .put("criteria", definition.criteria());
        var scope = root.putObject("scope");
        scope.put("kind", review.kind().name())
                .put("version", assignment.version().value());
        var reference = scope.putObject("item")
                .put("id", Long.toString(assignment.item().itemId()))
                .put("kind", assignment.item().kind().value());
        if (assignment.item().parentId() != null)
            reference.put("parentId", assignment.item().parentId().toString());
        if (assignment.item().path() != null)
            reference.put("path", assignment.item().path());
        if (review.kind() == ReviewKind.ITEM && assignment.version().baseVersion() != null)
            scope.put("baseVersion", assignment.version().baseVersion());
        root.putObject("review")
                .put("id", review.id().toString())
                // A trial runs as a requested review, so a script never behaves differently on trial.
                .put("origin", (review.origin() == ReviewOrigin.TRIAL ? ReviewOrigin.REQUEST : review.origin()).name())
                .put("startedAt", startedAt.toString());
        root.set("previous", previous == null ? mapper.nullNode() : previous.deepCopy());
        return Map.of(
                SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + assignment.practiceSlug() + ".inputs.json",
                mapper.writeValueAsBytes(root));
    }
}
