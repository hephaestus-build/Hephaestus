package de.tum.cit.aet.hephaestus.practices.scan;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.StateBasis;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@TestComponent
@RequiredArgsConstructor
public class ScanReportTestData {
    private final ReviewIntegrationData data;
    private final ItemRepository items;
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository members;
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ScanReportService reports;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public record Scenario(ReviewIntegrationData.Scenario owner, Item repository, Item file) {}

    @Transactional
    public Scenario scenario() {
        var original = DefinitionTestFixtures.definition();
        var definition = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                null,
                new ScanDefinition(
                        new ItemKind("scm.file"),
                        List.of("**/*"),
                        List.of(),
                        List.of("**/*"),
                        new PracticeReads(
                                "3.0.0",
                                List.of(new PracticeReadSource(
                                        new SourceKind("scm.repository.tree"), ReadNeed.COMPLETE)))),
                false,
                original.guidance());
        var owner = data.scenario(definition);
        var repository = new Item();
        repository.setWorkspaceId(owner.workspace().getId());
        repository.setKind(new ItemKind("scm.repository"));
        repository.setSourceId(owner.item().getSourceId());
        repository.setCreatedAt(Instant.now().minusSeconds(7200));
        items.saveAndFlush(repository);
        var file = new Item();
        file.setWorkspaceId(owner.workspace().getId());
        file.setKind(new ItemKind("scm.file"));
        file.setParentId(repository.getId());
        file.setPath("src/Main.java");
        file.setCreatedAt(Instant.now().minusSeconds(7200));
        items.saveAndFlush(file);
        return new Scenario(owner, repository, file);
    }

    @Transactional
    public Review scan(Scenario scenario, ReviewStatus status, Instant at) {
        var key = UUID.randomUUID();
        var review = reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(scenario.owner().workspace().getId())
                .itemId(scenario.repository().getId())
                .kind(ReviewKind.SCAN)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(scenario.owner().account().getId())
                .status(status)
                .itemVersion("a".repeat(40))
                .budgetUsd(BigDecimal.ONE)
                .createdAt(at)
                .orderedAt(at)
                .completedAt(status == ReviewStatus.COMPLETE ? at : null)
                .requestKey(key)
                .requestDigest(ProblemComparison.sha256(key.toString()))
                .checks(json.createObjectNode()
                        .put("ref", "main")
                        .put("reuse", true)
                        .put("startedAt", at.toString()))
                .inventoryReceipt(json.createObjectNode()
                        .put("complete", true)
                        .put("files", 1)
                        .put("previousFiles", 0)
                        .put("wouldRemove", 0)
                        .put("removalGuard", "NOT_NEEDED"))
                .build());
        var slots = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, null),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of())));
        var digest = jdbc.sql("SELECT review_model_digest(CAST(:models AS jsonb))")
                .param("models", json.writeValueAsString(slots))
                .query(String.class)
                .single();
        assignments.saveAndFlush(ReviewPractice.builder()
                .reviewId(review.getId())
                .workspaceId(review.getWorkspaceId())
                .practiceId(scenario.owner().practice().getId())
                .practiceVersionId(
                        scenario.owner().practice().getCurrentVersion().getId())
                .status(status == ReviewStatus.COMPLETE ? ReviewPracticeStatus.DONE : ReviewPracticeStatus.READY)
                .itemsListed(1)
                .listingComplete(true)
                .method(new ReviewMethod(1, digest, slots))
                .build());
        members.saveAndFlush(ReviewItem.builder()
                .workspaceId(review.getWorkspaceId())
                .reviewId(review.getId())
                .practiceId(scenario.owner().practice().getId())
                .itemId(scenario.file().getId())
                .itemVersion(version())
                .detail(json.createObjectNode().putNull("developerId"))
                .build());
        return review;
    }

    public static String version() {
        return "scm.file:v1:" + "b".repeat(40) + ":" + "c".repeat(64);
    }

    @Transactional
    public Result result(Scenario scenario, Review review) {
        var source = new SourceKind("scm.repository.tree");
        var quote = "unsafe();";
        var hash = ProblemComparison.sha256(quote);
        var citation = new EvidenceCitation(
                source,
                "inputs/sources/tree",
                "src/Main.java",
                CitationSide.NEW,
                review.getItemVersion(),
                1,
                1,
                quote,
                new CitationProof(CitationVerificationStatus.VERIFIED, hash, hash));
        var result = results.saveAndFlush(Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(review.getWorkspaceId())
                .reviewId(review.getId())
                .practiceId(scenario.owner().practice().getId())
                .practiceVersionId(
                        scenario.owner().practice().getCurrentVersion().getId())
                .itemId(scenario.file().getId())
                .itemVersion(version())
                .outcome(ResultOutcome.NOT_MET)
                .severity(Severity.MAJOR)
                .summary("Two proved problems")
                .explanation("The captured file has two problems.")
                .evidence(json.valueToTree(new ResultEvidence(List.of(citation), null, null, List.of())))
                .coverage(json.valueToTree(new HostCoverage(
                        true,
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(new ReadReceipt(
                                source,
                                "inputs/sources/tree",
                                "src/Main.java",
                                version(),
                                ReadReceiptStatus.COMPLETE,
                                hash,
                                1,
                                1,
                                null)))))
                .recordedAt(review.getCreatedAt())
                .build());
        for (int position = 0; position < 2; position++)
            problems.save(Problem.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(review.getWorkspaceId())
                    .resultId(result.getId())
                    .position(position)
                    .key("problem-" + position)
                    .severity(position == 0 ? Severity.MAJOR : Severity.MINOR)
                    .summary("Problem " + position)
                    .explanation("The captured file proves this problem.")
                    .citations(json.valueToTree(List.of(citation)))
                    .basis(json.valueToTree(new StateBasis("The file has this problem.")))
                    .build());
        var member = members.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        review.getWorkspaceId(), review.getId(), result.getPracticeId(), result.getItemId())
                .orElseThrow();
        var job = data.job(result);
        member.setState(ReviewItemState.JUDGED);
        member.setResultId(result.getId());
        member.setJobId(job);
        member.setDetail(json.createObjectNode()
                .putNull("developerId")
                .set("custody", json.valueToTree(new HistoryCustody(true, false, List.of(), List.of(), List.of(job)))));
        members.saveAndFlush(member);
        reports.freeze(review.getWorkspaceId(), review.getId());
        return result;
    }

    @Transactional
    public void terminal(Review review, ReviewStatus status, @Nullable ReviewStopReason reason) {
        var stored = reviews.findByIdAndWorkspaceId(review.getId(), review.getWorkspaceId())
                .orElseThrow();
        stored.setStatus(status);
        stored.setStopReason(reason);
        stored.setCompletedAt(Instant.now());
        reports.freeze(review.getWorkspaceId(), review.getId());
    }
}
