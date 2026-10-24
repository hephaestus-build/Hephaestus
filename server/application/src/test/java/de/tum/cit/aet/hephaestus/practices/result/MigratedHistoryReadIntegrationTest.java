package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class MigratedHistoryReadIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebTestClient http;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    IdentityRepository identities;

    @Autowired
    JsonMapper json;

    @Autowired
    EvidenceFolderPersonDataCatalog copies;

    @Autowired
    ResultSourceAuthorization authorization;

    @Autowired
    ResultVisibilityPolicy visibility;

    @Autowired
    ResultRepository results;

    @Autowired
    EntityManagerFactory entities;

    @MockitoBean
    ScmItemSourceAccess source;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        when(source.kinds()).thenReturn(Set.of(new ItemKind("scm.pull_request")));
        when(source.permits(any(), any())).thenReturn(true);
        // The human recorded standing asks the source's typed check; the real adapter is proven elsewhere.
        when(source.permits(any(), any(), any())).thenReturn(true);
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri("/workspaces/" + scenario.workspace().getWorkspaceSlug() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldHideSemanticProblemKeysWhenSourceUseIsRefused() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        String semanticKey = "private-authentication-credential";
        jdbc.update(
                "UPDATE problem SET key = ? WHERE workspace_id = ? AND result_id = ?",
                semanticKey,
                scenario.workspace().getId(),
                result.getId());
        var problemId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM problem WHERE workspace_id = ? AND result_id = ?",
                UUID.class,
                scenario.workspace().getId(),
                result.getId()));
        var paths = Map.of(
                "/practices/results/" + result.getId(), "$.problems[0].key",
                "/practices/reviews/results/" + result.getId(), "$.problems[0].key",
                "/practices/reviews/problems/" + problemId, "$.problem.key");
        for (var entry : paths.entrySet()) {
            get(entry.getKey())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath(entry.getValue())
                    .isEqualTo(semanticKey)
                    .jsonPath(entry.getValue().replace(".key", ".summary"))
                    .isEqualTo("A recorded problem");
        }
        when(source.permits(any(), any())).thenReturn(false);
        for (var entry : paths.entrySet()) {
            get(entry.getKey())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath(entry.getValue())
                    .value(value -> assertThat(value).isNull())
                    .jsonPath(entry.getValue().replace(".key", ".summary"))
                    .isEqualTo("Evidence hidden");
        }
    }

    @Test
    void shouldReadMigratedTextAndQuotesAsUnverifiedHistoryWithoutCustodyReceipts() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var retained = json.createObjectNode();
        retained.set("citations", Objects.requireNonNull(result.getEvidence()).path("citations"));
        migrated(result, retained.toString());
        get("/practices/reviews/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEqualTo("Recorded result")
                .jsonPath("$.currentness")
                .isEqualTo("UNVERIFIABLE")
                .jsonPath("$.evidenceHidden")
                .isEqualTo(false)
                .jsonPath("$.evidence.citations[0].quote")
                .isEqualTo("changed();");
        get("/practices/items/" + scenario.item().getId() + "/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].review.id")
                .isEqualTo(result.getReviewId().toString())
                .jsonPath("$.content[0].results[0].currentness")
                .isEqualTo("UNVERIFIABLE");
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("90000000" + scenario.account().getId());
        identities.saveAndFlush(identity);
        var feedbackId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at,checks) "
                        + "VALUES (?,?,?,?,?,'PRACTICE_PAGE','DELIVERED','Legacy feedback',now(),now(),'{}')",
                feedbackId,
                scenario.workspace().getId(),
                result.getReviewId(),
                scenario.item().getId(),
                scenario.account().getId());
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal) VALUES (?,?,?,?,?,0)",
                UUID.randomUUID(),
                scenario.workspace().getId(),
                feedbackId,
                result.getId(),
                scenario.account().getId());
        get("/practices/feedback/practice-page")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(feedbackId.toString())
                .jsonPath("$[0].unverifiedHistory")
                .isEqualTo(true);
        when(source.permits(any(), any())).thenReturn(false);
        get("/practices/reviews/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEmpty()
                .jsonPath("$.evidenceHidden")
                .isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"citations\":[]}",
                "{\"openQuestions\":[]}",
                "{\"citations\":null,\"openQuestions\":null}"
            })
    void shouldReadAnOlderEvidenceObjectWithoutInventingQuotations(String retained) {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        migrated(result, retained);
        get("/practices/reviews/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEqualTo("Recorded result")
                .jsonPath("$.evidence.citations.length()")
                .isEqualTo(0)
                .jsonPath("$.evidence.openQuestions.length()")
                .isEqualTo(0);
        when(source.permits(any(), any())).thenReturn(false);
        get("/practices/reviews/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.evidenceHidden")
                .isEqualTo(true)
                .jsonPath("$.evidence")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETE", "UNKNOWN"})
    void shouldRefuseMigratedHistoryAfterAnIdentityInAReadyCopyIsErased(String dependencies) {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        var copiedPerson = data.member(scenario.workspace().getId());
        var providerId = identity(copiedPerson);
        var job = producingJob(result);
        var payload = json.createObjectNode().put("dependencies", dependencies);
        payload.putArray("repositories");
        payload.putArray("identities")
                .addObject()
                .put("providerType", "GITHUB")
                .put("providerOrigin", "https://github.com")
                .put("subject", "history-" + copiedPerson.getId());
        receipt(job, payload.toString());
        assertThat(copies.custody(scenario.workspace().getId(), List.of(job)).complete())
                .isEqualTo(dependencies.equals("COMPLETE"));
        assertThat(authorization.permitsHistory(
                        scenario.workspace().getId(), result.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        jdbc.update(
                "INSERT INTO identity_restriction(id,provider_id,provider_user_key,team_key,kind) VALUES (?,?,?,'','ERASED')",
                UUID.randomUUID(),
                providerId,
                "history-" + copiedPerson.getId());
        var retained = copies.custody(scenario.workspace().getId(), List.of(job));
        assertThat(retained.complete()).isFalse();
        assertThat(retained.refused()).isTrue();
        assertThat(retained.copiedIdentities()).hasSize(1);
        assertThat(authorization.permitsHistory(
                        scenario.workspace().getId(), result.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
        assertThat(authorization.permits(
                        scenario.workspace().getId(), result.getId(), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
        assertThat(authorization.permitsHumanStanding(scenario.workspace().getId(), List.of(stored(result))))
                .isEmpty();
        get("/practices/reviews/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEmpty()
                .jsonPath("$.evidence")
                .isEmpty()
                .jsonPath("$.evidenceHidden")
                .isEqualTo(true);
    }

    @Test
    void shouldPermitAnUnlinkedCopiedContributorUntilADisabledLinkNamesAnInactiveAccount() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        var job = producingJob(result);
        var subject = "unlinked-" + UUID.randomUUID();
        var payload = json.createObjectNode().put("dependencies", "COMPLETE");
        payload.putArray("repositories");
        payload.putArray("identities")
                .addObject()
                .put("providerType", "GITHUB")
                .put("providerOrigin", "https://github.com")
                .put("subject", subject);
        receipt(job, payload.toString());
        var providerId = provider();
        assertThat(authorization.permitsHistory(
                        scenario.workspace().getId(), result.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();

        var closed = data.member(scenario.workspace().getId());
        jdbc.update("UPDATE account SET status='DELETING' WHERE id=?", closed.getId());
        var link = new Identity();
        link.setAccount(closed);
        link.setProviderId(providerId);
        link.setProviderUserKey(subject);
        link.setDisabledAt(Instant.now());
        identities.saveAndFlush(link);
        assertThat(authorization.permitsHistory(
                        scenario.workspace().getId(), result.getId(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
    }

    @Test
    void shouldCountAMigratedStandingOfANoAiDeveloperWhileEveryProcessingReadStillRefusesIt() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now().minusSeconds(60));
        migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        long workspaceId = scenario.workspace().getId();
        jdbc.update(
                "UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?",
                scenario.account().getId());
        var row = stored(result);

        assertThat(authorization.permitsHumanStanding(workspaceId, List.of(row)))
                .containsExactly(row.getId());
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(row), Instant.now()))
                .containsExactly(row.getId());
        // History, delivery, review and conversation keep the AI choice.
        for (var purpose : SourceUsePurpose.values()) {
            assertThat(authorization.permitsHistory(workspaceId, row.getId(), purpose))
                    .isFalse();
            assertThat(authorization.permits(workspaceId, row.getId(), purpose)).isFalse();
        }
        assertThat(visibility.permitsAt(
                        workspaceId, List.of(row), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, Instant.now()))
                .isEmpty();
        assertThat(authorization.permitsHumanStanding(workspaceId + 1, List.of(row)))
                .isEmpty();

        when(source.permits(any(), any(), any())).thenReturn(false);
        assertThat(authorization.permitsHumanStanding(workspaceId, List.of(row)))
                .isEmpty();
        when(source.permits(any(), any(), any())).thenReturn(true);
        jdbc.update(
                "UPDATE account SET status='DELETING' WHERE id=?",
                scenario.account().getId());
        assertThat(authorization.permitsHumanStanding(workspaceId, List.of(row)))
                .isEmpty();
    }

    @Test
    void shouldCountAnUnpinnedMigratedStandingButRefuseAVersionOfAnotherWorkspace() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now().minusSeconds(60));
        migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        long workspaceId = scenario.workspace().getId();
        historical(
                "UPDATE result SET practice_version_id=NULL WHERE workspace_id=? AND id=?",
                workspaceId,
                result.getId());
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(stored(result)), Instant.now()))
                .containsExactly(result.getId());

        var foreign = Objects.requireNonNull(
                data.scenario().practice().getCurrentVersion().getId());
        historical(
                "UPDATE result SET practice_version_id=? WHERE workspace_id=? AND id=?",
                foreign,
                workspaceId,
                result.getId());
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(stored(result)), Instant.now()))
                .isEmpty();

        historical(
                "UPDATE result SET practice_version_id=? WHERE workspace_id=? AND id=?",
                Objects.requireNonNull(scenario.practice().getCurrentVersion().getId()),
                workspaceId,
                result.getId());
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(stored(result)), Instant.now()))
                .containsExactly(result.getId());
        jdbc.update(
                "INSERT INTO result_mark(id,workspace_id,result_id,kind,reason,created_at)"
                        + " VALUES (?,?,?,'INCORRECT','Wrong verdict',now() - interval '1 minute')",
                UUID.randomUUID(),
                workspaceId,
                result.getId());
        assertThat(visibility.permitsStandingAt(workspaceId, List.of(stored(result)), Instant.now()))
                .isEmpty();
    }

    @Test
    void shouldAuthorizeManyResultsOfOneWorkInBoundedQueriesWithTheAnswersOfSingleReads() {
        long workspaceId = scenario.workspace().getId();
        var rows = chain(6);
        var expected = singleReads(workspaceId, rows);
        // The migrated rows count as earlier reviews; the newest keeps current proof without a capture receipt.
        assertThat(expected).hasSize(5);

        var all = statements(() -> authorization.permitsHumanStanding(workspaceId, rows));
        var two = statements(
                () -> authorization.permitsHumanStanding(workspaceId, List.of(rows.getFirst(), rows.getLast())));
        assertThat(all.permitted()).isEqualTo(expected);
        // Read row by row, the four extra results would add two assignment queries each.
        assertThat(all.statements()).isEqualTo(two.statements());

        var foreign = statements(() -> authorization.permitsHumanStanding(workspaceId + 1, rows));
        assertThat(foreign.permitted()).isEmpty();
        assertThat(foreign.statements()).isZero();

        when(source.permits(any(), any(), any())).thenReturn(false);
        assertThat(authorization.permitsHumanStanding(workspaceId, rows)).isEmpty();
        assertThat(singleReads(workspaceId, rows)).isEmpty();
    }

    @Test
    void shouldRefuseOnlyTheResultWhoseRetainedCustodyConflictsWhenReadTogether() {
        long workspaceId = scenario.workspace().getId();
        var rows = chain(2);
        var older = rows.getFirst();
        var newer = rows.getLast();
        migrated(newer, Objects.requireNonNull(newer.getEvidence()).toString());
        historical(
                "UPDATE review_item SET detail=CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                retainedCustody(true),
                workspaceId,
                older.getId());
        var both = List.of(stored(older), stored(newer));
        assertThat(authorization.permitsHumanStanding(workspaceId, both))
                .containsExactlyInAnyOrder(older.getId(), newer.getId());

        // Another review's assignment reuses the older result with a different receipt, and stops producing the newer.
        historical(
                "UPDATE review_item SET result_id=?,detail=CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                older.getId(),
                retainedCustody(false),
                workspaceId,
                newer.getId());
        var conflicted = List.of(stored(older), stored(newer));
        assertThat(authorization.permitsHumanStanding(workspaceId, conflicted)).containsExactly(newer.getId());
        assertThat(singleReads(workspaceId, conflicted)).containsExactly(newer.getId());
    }

    @ParameterizedTest
    @CsvSource({"PRACTICE_PAGE,0", "CONVERSATION,0", "ON_WORK,0", "PRACTICE_PAGE,1", "CONVERSATION,1", "ON_WORK,1"})
    void shouldKeepPrivateFeedbackOutOfOperatorProjectionsWithoutHidingItFromItsRecipient(
            FeedbackDestination destination, int evidenceVersion) {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        if (evidenceVersion == 0)
            migrated(result, Objects.requireNonNull(result.getEvidence()).toString());
        var job = producingJob(result);
        receipt(job, "{\"dependencies\":\"COMPLETE\",\"identities\":[],\"repositories\":[]}");
        identity(scenario.account());
        var feedbackId = UUID.randomUUID();
        String body = "Private feedback sentinel";
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at,checks) "
                        + "VALUES (?,?,?,?,?,?,'DELIVERED',?,now(),now(),'{}')",
                feedbackId,
                scenario.workspace().getId(),
                result.getReviewId(),
                scenario.item().getId(),
                scenario.account().getId(),
                destination.name(),
                body);
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal) VALUES (?,?,?,?,?,0)",
                UUID.randomUUID(),
                scenario.workspace().getId(),
                feedbackId,
                result.getId(),
                scenario.account().getId());
        var conversation = UUID.randomUUID();
        var message = UUID.randomUUID();
        if (destination == FeedbackDestination.CONVERSATION) {
            jdbc.update(
                    "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at) VALUES (?,?,?,now(),now())",
                    conversation,
                    scenario.workspace().getId(),
                    scenario.account().getId());
            jdbc.update(
                    "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,created_at) VALUES (?,?,?,1,'HEPH','APP',?,now())",
                    message,
                    scenario.workspace().getId(),
                    conversation,
                    body);
        }
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,path,state,posted_at,mentor_entry_id) VALUES (?,?,?,?,0,?,?,'POSTED',now(),?)",
                UUID.randomUUID(),
                scenario.workspace().getId(),
                feedbackId,
                destination == FeedbackDestination.CONVERSATION
                        ? "CHAT_TURN"
                        : destination == FeedbackDestination.ON_WORK ? "FILE_NOTE" : "SUMMARY",
                destination == FeedbackDestination.PRACTICE_PAGE ? null : body,
                destination == FeedbackDestination.ON_WORK ? "src/Main.java" : null,
                destination == FeedbackDestination.CONVERSATION ? message : null);
        var other = data.member(scenario.workspace().getId());
        jdbc.update(
                "UPDATE membership SET role='ADMIN' WHERE workspace_id=? AND account_id=?",
                scenario.workspace().getId(),
                other.getId());
        var operator = new Scenario(scenario.workspace(), other, scenario.practice(), scenario.item());
        boolean privateDestination = destination != FeedbackDestination.ON_WORK;
        var list = get(operator, "/practices/reviews/feedback?reviewId=" + result.getReviewId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(feedbackId.toString())
                .jsonPath("$.content[0].textHidden")
                .isEqualTo(privateDestination);
        if (privateDestination) list.jsonPath("$.content[0].bodyPreview").isEmpty();
        else list.jsonPath("$.content[0].bodyPreview").isEqualTo(body);
        var detail = get(operator, "/practices/reviews/feedback/" + feedbackId)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.textHidden")
                .isEqualTo(privateDestination);
        if (privateDestination) {
            detail.jsonPath("$.body")
                    .isEmpty()
                    .jsonPath("$.bodyPreview")
                    .isEmpty()
                    .jsonPath("$.copies[0].body")
                    .isEmpty()
                    .jsonPath("$.contentDigest")
                    .isEmpty();
        } else {
            detail.jsonPath("$.body")
                    .isEqualTo(body)
                    .jsonPath("$.copies[0].body")
                    .isEqualTo(body);
        }
        if (destination == FeedbackDestination.PRACTICE_PAGE) {
            get("/practices/feedback/practice-page")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].id")
                    .isEqualTo(feedbackId.toString())
                    .jsonPath("$[0].body")
                    .isEqualTo(body);
            get(operator, "/practices/feedback/practice-page")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$")
                    .isEmpty();
        } else if (destination == FeedbackDestination.CONVERSATION) {
            get("/mentor/entries")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.entries[0].id")
                    .isEqualTo(message.toString())
                    .jsonPath("$.entries[0].text")
                    .isEqualTo(body);
            get(operator, "/mentor/entries/" + message + "/thread")
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    private WebTestClient.ResponseSpec get(Scenario viewer, String path) {
        return http.get()
                .uri("/workspaces/" + viewer.workspace().getWorkspaceSlug() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(viewer)))
                .exchange();
    }

    private long identity(Account account) {
        var providerId = provider();
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey("history-" + account.getId());
        identities.saveAndFlush(identity);
        return providerId;
    }

    private long provider() {
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        return Objects.requireNonNull(provider.getId());
    }

    private UUID producingJob(Result result) {
        var job = data.job(result);
        jdbc.update(
                "UPDATE review_item SET job_id=? WHERE workspace_id=? AND result_id=?",
                job,
                scenario.workspace().getId(),
                result.getId());
        return job;
    }

    private void receipt(UUID job, String payload) {
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload) VALUES (?,?,?,?,'READY',CAST(? AS jsonb))",
                UUID.randomUUID(),
                scenario.workspace().getId(),
                job,
                UUID.randomUUID(),
                payload);
    }

    /**
     * {@code size} results of one practice on one work, each ended by the store before the next is recorded, so one
     * holds the claim. All but the newest are then carried over as earlier reviews.
     */
    private List<Result> chain(int size) {
        var start = Instant.now().minusSeconds(3600);
        List<Result> chain = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            var at = start.plusSeconds(60L * index);
            if (!chain.isEmpty()) data.end(chain.getLast(), at);
            chain.add(data.result(scenario, index % 2 == 0 ? ResultOutcome.NOT_MET : ResultOutcome.MET, at));
        }
        chain.subList(0, size - 1)
                .forEach(row ->
                        migrated(row, Objects.requireNonNull(row.getEvidence()).toString()));
        return chain.stream().map(this::stored).toList();
    }

    /** What reading each row on its own permits: the answer a read of many must match. */
    private Set<UUID> singleReads(long workspaceId, List<Result> rows) {
        return rows.stream()
                .filter(row -> authorization
                        .permitsHumanStanding(workspaceId, List.of(row))
                        .contains(row.getId()))
                .map(Result::getId)
                .collect(Collectors.toSet());
    }

    private String retainedCustody(boolean complete) {
        var detail = json.createObjectNode();
        detail.set("custody", json.valueToTree(new HistoryCustody(complete, false, List.of(), List.of(), List.of())));
        return detail.toString();
    }

    private record Counted(Set<UUID> permitted, long statements) {}

    /**
     * The statements Hibernate prepares during one read, with statistics on only for it. Statements through a
     * `JdbcTemplate`, such as the suppression checks, are not counted.
     */
    private Counted statements(Supplier<Set<UUID>> read) {
        var statistics = entities.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.clear();
        statistics.setStatisticsEnabled(true);
        try {
            var permitted = read.get();
            return new Counted(permitted, statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(enabled);
            statistics.clear();
        }
    }

    private Result stored(Result result) {
        return results.findByIdAndWorkspaceId(
                        result.getId(), scenario.workspace().getId())
                .orElseThrow();
    }

    private void historical(String update, Object... arguments) {
        // Only migration writes these historical rows; ordinary writes keep their guards.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(update, arguments);
        });
    }

    private void migrated(Result result, String evidence) {
        // Only migration creates these historical rows; the new-write guard must keep refusing them.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE result SET evidence_version=0,evidence=CAST(? AS jsonb) WHERE workspace_id=? AND id=?",
                    evidence,
                    scenario.workspace().getId(),
                    result.getId());
        });
    }
}
