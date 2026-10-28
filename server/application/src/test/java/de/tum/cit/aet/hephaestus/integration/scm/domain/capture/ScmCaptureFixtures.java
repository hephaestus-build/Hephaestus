package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ScmCaptureFixtures {
    private ScmCaptureFixtures() {}

    /**
     * Every bundled practice by slug, as the catalogue ships it. {@code BundledPracticeReadRequirementsTest}
     * runs the definition validator, so this load does not repeat it.
     */
    public static Map<String, CatalogPracticeDocument> bundledPractices() {
        return new BundledPracticeCatalogLoader(JsonMapper.builder().build(), mock(PracticeDefinitionValidator.class))
                .catalog().practices().stream()
                        .collect(Collectors.toMap(practice -> practice.slug(), practice -> practice.definition()));
    }

    public static ScmCaptureContext context(JsonNode fixture, long workspaceId, boolean issue) {
        long source = fixture.path(issue ? "issue_id" : "pull_request_id").asLong(1);
        long repository = fixture.path("repository_id").asLong(1);
        Long review = fixture.path("review_id").isIntegralNumber()
                ? fixture.path("review_id").asLong()
                : null;
        if (!fixture.path("subject_role").asString("AUTHOR").equals("REVIEWER")) review = null;
        Long reviewer = fixture.path("about_user_id").isIntegralNumber()
                ? fixture.path("about_user_id").asLong()
                : null;
        String head = fixture.has("commit_sha") && fixture.path("commit_sha").isNull()
                ? null
                : fixture.path("commit_sha").asString("a".repeat(40));
        String base = fixture.path("base_ref_oid").asString("b".repeat(40));
        var work = new ScmCapturedWork(
                source,
                repository,
                fixture.path("pr_number").asInt(1),
                fixture.path("pr_url").asString("https://scm.example/owner/repo/pull/1"),
                fixture.path("repository_full_name").asString("owner/repo"),
                fixture.path("title").asString("Work"),
                fixture.path("body").isString() ? fixture.path("body").asString() : null,
                head,
                base,
                fixture.path("source_branch").asString("feature"),
                fixture.path("target_branch").asString("main"),
                review,
                reviewer);
        var kind = new ItemKind(issue ? "scm.issue" : review == null ? "scm.pull_request" : "scm.pull_request_review");
        var item = new ItemRef(
                workspaceId,
                review == null ? source : source + 1,
                kind,
                review == null ? source : review,
                review == null ? null : source,
                null);
        var version = new ItemVersion(fixture.path("itemVersion").asString("fixture"), null);
        var integration = new IntegrationRef(IntegrationKind.GITHUB, workspaceId, null);
        var snapshot = ItemFixtures.snapshot(item, Map.of())
                .version(version)
                .integration(integration)
                .label(ItemFixtures.label()
                        .itemId(item.itemId())
                        .title("Work")
                        .noun("Pull request")
                        .number(1)
                        .container("owner/repo")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
        var request = new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(), "3.0.0", 3);
        return new ScmCaptureContext(snapshot, request, work, new ScmCapturePreparation(), Instant.now());
    }

    public static ScmCaptureContext withVersion(ScmCaptureContext context, ItemVersion version) {
        var previous = context.pinned();
        return new ScmCaptureContext(
                ItemFixtures.snapshot(previous.item(), previous.state())
                        .version(version)
                        .integration(previous.integration())
                        .roles(previous.roles())
                        .label(previous.label())
                        .build(),
                context.request(),
                context.work(),
                context.preparation(),
                context.capturedAt());
    }
}
