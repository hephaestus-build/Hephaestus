package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultFixtures;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

public abstract class AbstractFeedbackIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    protected FeedbackRepository feedback;

    @Autowired
    protected FeedbackCopyRepository copies;

    @Autowired
    protected FeedbackBasisRepository basis;

    @Autowired
    protected FeedbackSendRepository sends;

    @Autowired
    protected FeedbackResponseRepository responses;

    @Autowired
    protected FeedbackDecisionRepository decisions;

    @Autowired
    protected ResultRepository results;

    @Autowired
    protected ProblemRepository problems;

    @Autowired
    protected JsonMapper mapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    protected Workspace workspace;
    protected long developerId, itemId;
    protected UUID reviewId;
    protected static final Instant AT = Instant.parse("2026-10-14T00:00:00Z");

    @BeforeEach
    void createFeedbackContext() {
        var owner = persistUser("feedback-owner");
        workspace = createWorkspace("feedback-tests", "Feedback tests", "feedback-org", AccountType.ORG, owner);
        developerId = Objects.requireNonNull(ensureAdminMembership(workspace).getAccountId());
        itemId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES"
                        + " (?,'scm.pull_request',42,?) RETURNING id",
                Long.class,
                workspace.getId(),
                Timestamp.from(AT)));
        reviewId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at)"
                        + " VALUES (?,?,?,'ITEM','v1','REQUEST','RUNNING',?,?)",
                reviewId,
                workspace.getId(),
                itemId,
                Timestamp.from(AT),
                Timestamp.from(AT));
    }

    protected Feedback piece(FeedbackDestination destination, FeedbackState state, @Nullable Instant deliveredAt) {
        return feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(reviewId)
                .itemId(itemId)
                .developerId(developerId)
                .destination(destination)
                .state(state)
                .body("Add a focused regression test.")
                .createdAt(AT)
                .deliveredAt(deliveredAt)
                .build());
    }

    protected FeedbackCopy summary(Feedback parent, FeedbackCopyState state) {
        UUID id = FeedbackIds.copy(parent.getId(), FeedbackCopyKind.SUMMARY, 0);
        boolean fenced = state == FeedbackCopyState.WRITE_STARTED
                || state == FeedbackCopyState.POSTED
                || state == FeedbackCopyState.RETRACTED;
        boolean shown = state == FeedbackCopyState.POSTED || state == FeedbackCopyState.RETRACTED;
        return copies.saveAndFlush(FeedbackCopy.builder()
                .id(id)
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .kind(FeedbackCopyKind.SUMMARY)
                .position(0)
                .state(state)
                .writeKey(id.toString())
                .writeMarker(FeedbackIds.writeMarker(id))
                .writeStartedAt(fenced ? AT : null)
                .externalRef(shown ? "copy-" + id : null)
                .postedAt(shown ? AT : null)
                .createdAt(AT)
                .build());
    }

    protected Result result() {
        return result(ResultOutcome.MET, null, null);
    }

    protected Result negativeResult() {
        return result(ResultOutcome.NOT_MET, null, null);
    }

    protected Result resultWithStateFilter(String dimension, String value) {
        return result(ResultOutcome.MET, dimension, value);
    }

    private Result result(ResultOutcome outcome, @Nullable String dimension, @Nullable String value) {
        var slots = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, null),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of())));
        String digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT review_model_digest(?::jsonb)", String.class, mapper.writeValueAsString(slots)));
        String method = mapper.writeValueAsString(new ReviewMethod(1, digest, slots));
        Long practiceId = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('practice','id'))", Long.class);
        Long versionId =
                jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('practice_version','id'))", Long.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO"
                            + " practice(id,workspace_id,slug,kind,current_version_id,feedback_mode)"
                            + " VALUES (?, ?, ?, 'scm.pull_request', ?, 'AUTOMATIC')",
                    practiceId,
                    workspace.getId(),
                    "test-" + practiceId,
                    versionId);
            jdbc.update(
                    "INSERT INTO"
                            + " practice_version(id,workspace_id,practice_id,number,definition)"
                            + " VALUES (?,?,?,1,?::jsonb)",
                    versionId,
                    workspace.getId(),
                    practiceId,
                    """
                    {"name":"Regression tests","criteria":"Cover failures.","about":"AUTHOR",
                     "reviewOn":{"events":["scm.pull_request.updated"],"state":{}},"appliesWhen":null,
                     "reads":{"contract":"3.0.0","sources":[{"source":"scm.pull-request.core","need":"COMPLETE"}]},
                     "reviewedBy":{"by":"HEPHAESTUS","humanReason":null,"limits":[]},
                     "script":null,"scan":null,"lineNotes":false,
                     "guidance":{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}}
                    """.replace(
                                    "\"state\":{}",
                                    dimension == null || value == null
                                            ? "\"state\":{}"
                                            : "\"state\":{" + mapper.writeValueAsString(dimension) + ":["
                                                    + mapper.writeValueAsString(value) + "]}"));
            jdbc.update(
                    "INSERT INTO"
                            + " review_practice(review_id,practice_id,workspace_id,practice_version_id,status,method)"
                            + " VALUES (?,?,?,?,'READY',?::jsonb)",
                    reviewId,
                    practiceId,
                    workspace.getId(),
                    versionId,
                    method);
            jdbc.update(
                    "INSERT INTO"
                            + " review_item(review_id,practice_id,item_id,workspace_id,item_version,state)"
                            + " VALUES (?,?,?,?,'v1','LISTED')",
                    reviewId,
                    practiceId,
                    itemId,
                    workspace.getId());
        });
        var evidence = AdmittedResultFixtures.evidence("scm.pull-request.core");
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            var row = results.saveAndFlush(Result.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(workspace.getId())
                    .reviewId(reviewId)
                    .practiceId(Objects.requireNonNull(practiceId))
                    .practiceVersionId(versionId)
                    .itemId(itemId)
                    .itemVersion("v1")
                    .developerId(developerId)
                    .outcome(outcome)
                    .severity(outcome == ResultOutcome.NOT_MET ? Severity.MAJOR : null)
                    .summary(
                            outcome == ResultOutcome.NOT_MET
                                    ? "Cover the failure" + " case."
                                    : "The tests cover the" + " failure" + " case.")
                    .explanation("The recorded test coverage" + " supports this result.")
                    .coverage(JsonNodeFactory.instance
                            .objectNode()
                            .put("source", "claimed")
                            .put("complete", true)
                            .put("selected", 1)
                            .put("checked", 1)
                            .put("total", 1))
                    .evidence(evidence)
                    .evidenceVersion((short) 1)
                    .recordedAt(AT)
                    .build());
            if (outcome == ResultOutcome.NOT_MET) {
                problems.saveAndFlush(Problem.builder()
                        .id(UUID.randomUUID())
                        .workspaceId(workspace.getId())
                        .resultId(row.getId())
                        .position(0)
                        .severity(Severity.MAJOR)
                        .summary("Cover the failure case.")
                        .explanation("The captured event requires" + " regression coverage.")
                        .citations(evidence.path("citations"))
                        .basis(JsonNodeFactory.instance
                                .objectNode()
                                .put("type", "duty")
                                .put("criterionQuote", "Cover failures.")
                                .put("event", 0)
                                .put("explanation", "The captured event" + " requires" + " regression" + " coverage."))
                        .build());
            }
            return row;
        }));
    }

    protected void bind(Feedback parent, Result result) {
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(parent.getId(), result.getId(), null))
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .resultId(result.getId())
                .developerId(developerId)
                .ordinal(0)
                .build());
    }
}
