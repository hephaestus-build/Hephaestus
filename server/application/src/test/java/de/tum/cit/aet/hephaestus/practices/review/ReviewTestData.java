package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

public final class ReviewTestData {
    private ReviewTestData() {}

    public static Practice practice(long workspaceId, long practiceId, String slug) {
        return practice(workspaceId, practiceId, slug, DefinitionTestFixtures.definition());
    }

    public static Practice practice(long workspaceId, long practiceId, String slug, PracticeDefinition definition) {
        var workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setWorkspaceSlug("test-workspace");
        var practice = new Practice();
        practice.setId(practiceId);
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setKind(new ItemKind("scm.pull_request"));
        var version = new PracticeVersion(practice, 1, new JsonMapper().valueToTree(definition), null);
        ReflectionTestUtils.setField(version, "id", practiceId + 1000);
        ReflectionTestUtils.setField(version, "name", definition.name());
        ReflectionTestUtils.setField(version, "reviewDigest", "v6:" + "a".repeat(64));
        practice.setCurrentVersion(version);
        return practice;
    }

    public static ResultView view(long itemId, UUID reviewId, Instant at, ItemKind kind, ResultOutcome outcome) {
        var practice = practice(1, 1, "test-practice");
        var version = practice.getCurrentVersion();
        var review = Review.builder()
                .id(reviewId)
                .workspaceId(1)
                .itemId(itemId)
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.EVENT)
                .eventId(UUID.randomUUID())
                .status(ReviewStatus.COMPLETE)
                .itemVersion("version-1")
                .orderedAt(at)
                .createdAt(at)
                .completedAt(at)
                .build();
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(reviewId)
                .practiceId(1)
                .practiceVersionId(version.getId())
                .itemId(itemId)
                .itemVersion("version-1")
                .developerId(7L)
                .outcome(outcome)
                .severity(outcome == ResultOutcome.NOT_MET ? Severity.MAJOR : null)
                .recordedAt(at)
                .build();
        var item = new ItemRefDTO(
                Long.toString(itemId),
                kind.value(),
                null,
                null,
                "Work " + itemId,
                "Work " + itemId,
                null,
                null,
                null,
                null);
        return new ResultView(result, practice, version, review, item, Currentness.CURRENT);
    }
}
