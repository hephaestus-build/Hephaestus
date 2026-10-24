package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * A developer's own review list, review and result read the same withdrawal fact as their profile overview and the
 * response guard: withdrawn feedback is not offered for a response that the server refuses, and a restored piece
 * returns with its earlier response and only the comments that still carry it. Admin surfaces are not read here and
 * keep the full record.
 */
class OwnWithdrawnFeedbackIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FeedbackStore store;

    @Autowired
    IdentityRepository identities;

    @Autowired
    IdentityProviderRepository providers;

    long providerId;

    // ReviewIntegrationData records no source custody, so the review drawer would find no permitted row; permit
    // same-workspace rows as the other review-evidence tests do. The own feedback read does not depend on it.
    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

    Scenario scenario;
    Result result;
    Instant at;

    @BeforeEach
    void setUp() {
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenAnswer(i -> {
                    long ws = i.getArgument(0);
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream()
                            .filter(r -> r.getWorkspaceId() == ws)
                            .map(Result::getId)
                            .collect(Collectors.toSet());
                });
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        providerId = Objects.requireNonNull(providers
                .saveAndFlush(new IdentityProvider(
                        IdentityProviderType.GITHUB, "https://withdrawal-" + UUID.randomUUID() + ".example.test"))
                .getId());
        identify(scenario.account());
        at = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        result = data.result(scenario, ResultOutcome.NOT_MET, at);
    }

    @Test
    void shouldLeaveWithdrawnFeedbackOffEveryOwnSurfaceAndReturnItWithItsResponseAndStandingCommentsOnRestore() {
        var withdrawn = feedback(scenario, result);
        var summary = copy(scenario, withdrawn, FeedbackCopyKind.SUMMARY, 0, "summary");
        var lineNote = copy(scenario, withdrawn, FeedbackCopyKind.LINE_NOTE, 1, "line");
        var kept = feedback(scenario, result);
        copy(scenario, kept, FeedbackCopyKind.SUMMARY, 0, "kept");
        var elsewhere = data.scenario();
        var elsewhereResult = data.result(elsewhere, ResultOutcome.NOT_MET, at);
        var elsewhereFeedback = feedback(elsewhere, elsewhereResult);
        copy(elsewhere, elsewhereFeedback, FeedbackCopyKind.SUMMARY, 0, "elsewhere");

        assertOwnFeedback(scenario, result, List.of(withdrawn, kept));
        // The developer answered before the withdrawal; that answer is kept, not offered again.
        respond(scenario, withdrawn, FeedbackResolution.ADDRESSED)
                .expectStatus()
                .isOk();

        store.decide(
                scenario.workspace().getId(),
                withdrawn,
                admin(),
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "The wording was wrong.", null));
        // What the queued correction records once the provider confirms it (PostedCopyCorrector): the summary and
        // the line note each edited to the withdrawal notice.
        state(scenario, summary, "RETRACTED");
        state(scenario, lineNote, "RETRACTED");

        assertOwnFeedback(scenario, result, List.of(kept));
        respond(scenario, withdrawn, FeedbackResolution.NOT_APPLICABLE)
                .expectStatus()
                .isEqualTo(409);
        assertOwnFeedback(elsewhere, elsewhereResult, List.of(elsewhereFeedback));

        store.decide(
                scenario.workspace().getId(),
                withdrawn,
                admin(),
                new FeedbackDecisionInput(FeedbackDecisionAction.RESTORE, null, "The wording was right.", null));
        // A restore edits the summary back; the line note is not restored and keeps its withdrawal notice.
        state(scenario, summary, "POSTED");

        assertOwnFeedback(scenario, result, List.of(withdrawn, kept));
        get(scenario, "/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[?(@.id=='" + withdrawn + "')].copies[*].id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(summary.toString()))
                .jsonPath("$.feedback[?(@.id=='" + withdrawn + "')].response.resolution")
                .isEqualTo("ADDRESSED");
        respond(scenario, withdrawn, FeedbackResolution.NOT_APPLICABLE)
                .expectStatus()
                .isOk();
        get(scenario, "/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[?(@.id=='" + withdrawn + "')].response.resolution")
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void shouldNotLinkARetractedCommentOfFeedbackThatStands() {
        var piece = feedback(scenario, result);
        var summary = copy(scenario, piece, FeedbackCopyKind.SUMMARY, 0, "summary");
        var lineNote = copy(scenario, piece, FeedbackCopyKind.LINE_NOTE, 1, "line");
        // A line note edited to the withdrawal notice because its result was marked incorrect, while the piece
        // itself stands.
        state(scenario, lineNote, "RETRACTED");

        get(scenario, "/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[0].copies[*].id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(summary.toString()));
    }

    /** The same pieces, in id order, on the review list, the review and the result. */
    private void assertOwnFeedback(Scenario owner, Result ownResult, List<UUID> expected) {
        var ids = expected.stream().map(UUID::toString).sorted().toArray(String[]::new);
        get(owner, "/practice-profile/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].feedback[*].id")
                .value(found -> assertThat(found)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(ids));
        get(owner, "/practice-profile/reviews/" + ownResult.getReviewId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[*].id")
                .value(found -> assertThat(found)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(ids));
        get(owner, "/practices/results/" + ownResult.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[*].id")
                .value(found -> assertThat(found)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(ids));
    }

    private UUID feedback(Scenario owner, Result bound) {
        var id = UUID.randomUUID();
        long ws = owner.workspace().getId();
        long developer = Objects.requireNonNull(owner.account().getId());
        jdbc.sql("INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,"
                        + "created_at,delivered_at)"
                        + " VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Review this change.',"
                        + ":at,:at)")
                .param("id", id)
                .param("ws", ws)
                .param("review", bound.getReviewId())
                .param("item", bound.getItemId())
                .param("developer", developer)
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:result,:developer,0)")
                .param("id", FeedbackIds.basis(id, bound.getId(), null))
                .param("ws", ws)
                .param("feedback", id)
                .param("result", bound.getId())
                .param("developer", developer)
                .update();
        return id;
    }

    private UUID copy(Scenario owner, UUID feedback, FeedbackCopyKind kind, int position, String name) {
        var id = FeedbackIds.copy(feedback, kind, position);
        boolean note = kind == FeedbackCopyKind.LINE_NOTE;
        jdbc.sql("INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,path,start_line,end_line,"
                        + "state,write_key,write_marker,write_started_at,external_ref,external_url,posted_at,"
                        + "created_at)"
                        + " VALUES(:id,:ws,:feedback,:kind,:position,:body,:path,:line,:line,'POSTED',:key,:marker,:at,"
                        + ":ref,:url,:at,:at)")
                .param("id", id)
                .param("ws", owner.workspace().getId())
                .param("feedback", feedback)
                .param("kind", kind.name())
                .param("position", position)
                .param("body", note ? "Cover this line." : null)
                .param("path", note ? "src/App.java" : null)
                .param("line", note ? 12 : null)
                .param("key", id.toString())
                .param("marker", FeedbackIds.writeMarker(id))
                .param("at", Timestamp.from(at))
                .param("ref", "copy-" + name)
                .param("url", "https://github.com/example/project/pull/1#" + name)
                .update();
        return id;
    }

    private void state(Scenario owner, UUID copy, String state) {
        jdbc.sql("UPDATE feedback_copy SET state=:state WHERE workspace_id=:ws AND id=:id")
                .param("state", state)
                .param("ws", owner.workspace().getId())
                .param("id", copy)
                .update();
    }

    /** The decision's actor: the controller, not the store, checks that it is an admin. */
    private long admin() {
        var actor = data.member(scenario.workspace().getId());
        identify(actor);
        return Objects.requireNonNull(actor.getId());
    }

    /** Response and decision writes use the real native identity admission fence. */
    private void identify(Account account) {
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey(Objects.requireNonNull(account.getId()).toString());
        identities.saveAndFlush(identity);
    }

    private WebTestClient.ResponseSpec get(Scenario owner, String path) {
        return http.get()
                .uri("/workspaces/" + owner.workspace().getWorkspaceSlug() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(owner)))
                .exchange();
    }

    private WebTestClient.ResponseSpec respond(Scenario owner, UUID feedback, FeedbackResolution resolution) {
        return http.put()
                .uri("/workspaces/" + owner.workspace().getWorkspaceSlug() + "/practices/feedback/" + feedback
                        + "/response")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(owner)))
                .bodyValue(new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, resolution, null))
                .exchange();
    }
}
