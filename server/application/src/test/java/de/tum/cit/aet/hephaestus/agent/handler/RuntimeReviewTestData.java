package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

public final class RuntimeReviewTestData {
    public static final UUID REVIEW = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final ItemRef ITEM = new ItemRef(1, 2, new ItemKind("docs.document"), 20L, null, null);

    private RuntimeReviewTestData() {}

    public static ReviewAssignmentSnapshot assignment(@Nullable Long developer, @Nullable String script) {
        var method = new ReviewMethod(1, "b".repeat(64), List.of(new BoundModelSlot("review", ModelKind.CHAT, null)));
        var practice = new PinnedPractice(
                31,
                32,
                DefinitionTestFixtures.withAbout(
                        DefinitionTestFixtures.withLineNotes(
                                DefinitionTestFixtures.withReviewOn(
                                        DefinitionTestFixtures.definition(
                                                new ItemKind("docs.document"),
                                                "Clear names",
                                                "Use clear names.",
                                                script),
                                        new ReviewOn(List.of(), Map.of())),
                                true),
                        developer == null ? null : AboutRole.AUTHOR),
                "a".repeat(64),
                method);
        return new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(REVIEW, 31, 2),
                ITEM,
                new ItemVersion("docs:one", null),
                developer,
                "clear-names",
                practice);
    }

    public static ReviewSnapshot review(ReviewKind kind, ReviewAssignmentSnapshot assignment) {
        return new ReviewSnapshot(
                REVIEW, 1, ITEM, kind, assignment.version(), ReviewOrigin.REQUEST, 1, List.of(assignment.practice()));
    }

    public static ReviewJob job() {
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setReviewId(REVIEW);
        job.setWorkspaceId(1);
        job.setRunId(UUID.randomUUID());
        job.setInput(new ReviewJobInput(2, List.of(new ReviewAssignmentId(REVIEW, 31, 2))));
        job.setConfigSnapshot(new JsonMapper().createObjectNode());
        job.prePersist();
        // A load reads when the job's run is next offered; a fresh run is offered at once.
        ReflectionTestUtils.setField(job, "availableAt", job.getSubmittedAt());
        return job;
    }
}
