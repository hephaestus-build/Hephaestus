package de.tum.cit.aet.hephaestus.practices.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.consent.ConsentService;
import de.tum.cit.aet.hephaestus.core.auth.consent.ResearchConsentTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchDisclosureAudit;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.research.adapter.ResearchDisclosureWorkspacePurge;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.groups.Tuple;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The release, removal and closure paths against the real schema and SQL: tenancy, consent identity,
 * legacy history, sentinel text and the protected mapping's lifecycle.
 */
class ResearchDisclosureIntegrationTest extends BaseIntegrationTest {

    private static final List<String> SENTINELS = List.of(
            "SENTINEL_RESULT_SUMMARY",
            "SENTINEL_RESULT_EXPLANATION",
            "SENTINEL_EVIDENCE_QUOTE",
            "ghp_SENTINELSECRETTOKEN",
            "SENTINEL_PROBLEM_SUMMARY",
            "SENTINEL_PROBLEM_EXPLANATION",
            "SENTINEL_NEXT_STEP",
            "SENTINEL_FEEDBACK_BODY",
            "SENTINEL_RESPONSE_COMMENT",
            "SENTINEL_DECISION_NOTE",
            "SENTINEL_MARK_REASON",
            "SENTINEL_NONCONSENTING_SUMMARY",
            "https://sentinel.example",
            "Recorded result",
            "The captured change supports this result.",
            "A recorded problem",
            "The captured change introduces the problem.",
            "Adjust the changed branch.",
            "changed();");

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ResearchDatasetRepository datasetRows;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ResearchDisclosureRepository disclosures;

    @Autowired
    ResearchDisclosureSubjectRepository subjects;

    @Autowired
    ResearchDisclosureAudit audit;

    @Autowired
    JsonMapper mapper;

    @Autowired
    ReviewRuntimeQuery runtime;

    @Autowired
    PracticeVersionService versions;

    ConsentService consent;
    ResearchDatasetSource source;
    ResearchDisclosureRecorder recorder;
    Scenario course;
    Instant at;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // Built by hand over the real ledger so the test shares the cached context; the transactions
        // the proxies would open are opened below with the same isolation.
        consent = ResearchConsentTestSupport.forOrganization(context, "AET");
        source = new ResearchDatasetSource(datasetRows, consent, runtime);
        recorder = new ResearchDisclosureRecorder(disclosures, subjects, consent, audit);
        course = data.scenario();
        at = Instant.now().minus(2, ChronoUnit.DAYS);
    }

    private <T> T write(Supplier<T> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private ResearchSnapshot snapshot(long workspaceId) {
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(template.execute(
                status -> source.read(workspaceId, at.minus(1, ChronoUnit.DAYS), at.plus(1, ChronoUnit.DAYS))));
    }

    private static final class Released {
        private final ResearchDisclosure disclosure;
        private final byte[] bytes;
        private final Map<Long, String> keys;

        private Released(ResearchDisclosure disclosure, byte[] bytes, Map<Long, String> keys) {
            this.disclosure = disclosure;
            this.bytes = bytes;
            this.keys = keys;
        }

        ResearchDisclosure disclosure() {
            return disclosure;
        }

        byte[] bytes() {
            return bytes;
        }

        Map<Long, String> keys() {
            return keys;
        }
    }

    private Released release(long workspaceId, Instant useUntil) {
        ResearchSnapshot snapshot = snapshot(workspaceId);
        return authorize(snapshot, useUntil);
    }

    private Released authorize(ResearchSnapshot snapshot, Instant useUntil) {
        UUID id = UUID.randomUUID();
        var assembled = ResearchDatasetAssembler.assemble(
                snapshot,
                id,
                at.minus(1, ChronoUnit.DAYS),
                at.plus(1, ChronoUnit.DAYS),
                useUntil,
                Map.<String, ResearchDatasetAssembler.BundledStandard>of(),
                new ResearchDataset.StandardSource(
                        ResearchDisclosureService.CATALOG_RESOURCE, "0.0.0-development", "DEVELOPMENT", null),
                ResearchPseudonyms.fresh(new SecureRandom()));
        byte[] bytes = mapper.writeValueAsBytes(assembled.dataset());
        var disclosure = write(() -> recorder.authorize(
                new ResearchDisclosureRecorder.Release(
                        id,
                        snapshot.workspaceId(),
                        at.minus(1, ChronoUnit.DAYS),
                        at.plus(1, ChronoUnit.DAYS),
                        useUntil,
                        ResearchDisclosureService.sha256(bytes),
                        assembled.dataset().counts()),
                snapshot,
                assembled.participantKeys(),
                Objects.requireNonNull(course.account().getId()),
                Instant.now()));
        return new Released(disclosure, bytes, assembled.participantKeys());
    }

    private long decide(long accountId, boolean granted, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO consent_decision(account_id,purpose,granted,mechanism,notice_version,research_organization,occurred_at)"
                        + " VALUES (?,'RESEARCH_PARTICIPATION',?,'ACCOUNT_SETTINGS',?,'AET',?)",
                accountId,
                granted,
                ResearchConsentTestSupport.wordingVersion(),
                Timestamp.from(occurredAt));
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT max(id) FROM consent_decision WHERE account_id = ?", Long.class, accountId));
    }

    private void legacy(Result result) {
        // Only migration creates version-0 rows; the write guard must keep refusing them.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    // The practice version stays as migration retained it; only capture-contract fields go.
                    "UPDATE result SET evidence_version=0,item_version=NULL,coverage=NULL,"
                            + "evidence=CAST(? AS jsonb) WHERE workspace_id=? AND id=?",
                    "{\"citations\":[{\"quote\":\"SENTINEL_EVIDENCE_QUOTE ghp_SENTINELSECRETTOKEN\","
                            + "\"url\":\"https://sentinel.example/pr/1\"}]}",
                    result.getWorkspaceId(),
                    result.getId());
        });
    }

    private UUID feedback(Result result, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,developer_id,destination,state,body,created_at)"
                        + " VALUES (?,?,?,?,'PRACTICE_PAGE','READY',?,?)",
                id,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getDeveloperId(),
                body,
                Timestamp.from(at));
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES (?,?,?,?,?,0)",
                UUID.randomUUID(),
                result.getWorkspaceId(),
                id,
                result.getId(),
                result.getDeveloperId());
        return id;
    }

    @Test
    void shouldReleaseEveryConsentingRowOfOneWorkspaceIncludingLegacyHistoryAndNoText() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        Result migrated = data.result(course, ResultOutcome.NOT_MET, at.minusSeconds(60));
        data.end(migrated, at.minusSeconds(30));
        legacy(migrated);
        Result current = data.result(course, ResultOutcome.NOT_MET, at);
        UUID piece = feedback(current, "SENTINEL_FEEDBACK_BODY https://sentinel.example");
        jdbc.update(
                "INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,usefulness,comment)"
                        + " VALUES (?,?,?,'DISAGREE','UNHELPFUL','SENTINEL_RESPONSE_COMMENT')",
                UUID.randomUUID(),
                workspace,
                piece);
        jdbc.update(
                "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note)"
                        + " VALUES (?,?,?,'WITHDRAW',?,'SENTINEL_DECISION_NOTE')",
                UUID.randomUUID(),
                workspace,
                piece,
                participant);
        data.mark(current, ResultMarkKind.INCORRECT, participant);
        jdbc.update("UPDATE result_mark SET reason='SENTINEL_MARK_REASON' WHERE workspace_id=?", workspace);
        // A member who never agreed, in the same workspace and review.
        var outsider = data.member(workspace);
        Result outsiderResult = data.result(
                forPractice(outsider),
                ResultOutcome.NOT_MET,
                at.plusSeconds(120),
                at.plusSeconds(120),
                Objects.requireNonNull(current.getItemVersion()));
        reuse(current, outsiderResult);
        // A consenting person in another workspace.
        Scenario other = data.scenario();
        decide(Objects.requireNonNull(other.account().getId()), true, at.minus(10, ChronoUnit.DAYS));
        data.result(other, ResultOutcome.NOT_MET, at);

        Released released = release(workspace, Instant.now().plus(365, ChronoUnit.DAYS));
        String json = new String(released.bytes(), StandardCharsets.UTF_8);

        for (String sentinel : SENTINELS) assertThat(json).doesNotContain(sentinel);
        for (Object raw : List.of(current.getId(), migrated.getId(), piece, participant, outsider.getId(), workspace)) {
            assertThat(json).as("raw identifier %s", raw).doesNotContain("\"" + raw + "\"");
        }
        assertThat(json).doesNotContain(course.workspace().getWorkspaceSlug());
        var dataset = mapper.readValue(released.bytes(), ResearchDataset.class);
        long owned = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM result WHERE workspace_id=? AND developer_id=?",
                Long.class,
                workspace,
                participant));
        long ownedProblems = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM problem p JOIN result r ON r.id=p.result_id AND r.workspace_id=p.workspace_id"
                        + " WHERE r.workspace_id=? AND r.developer_id=?",
                Long.class,
                workspace,
                participant));
        assertThat(dataset.results()).as("no row loss, legacy included").hasSize((int) owned);
        assertThat(dataset.problems()).hasSize((int) ownedProblems);
        assertThat(dataset.results())
                .filteredOn(r -> r.evidenceVersion() == 0)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.proof()).isEqualTo("LEGACY_CAPTURE_CONTRACT_NOT_ESTABLISHED");
                    assertThat(r.practiceVersion())
                            .as("the retained version marker survives")
                            .isNotNull();
                    assertThat(r.itemVersionRecorded()).isFalse();
                });
        assertThat(dataset.reviews())
                .as("usage of a review that also judged a non-participant is never released")
                .allSatisfy(r -> {
                    if (!r.subjectScope().equals("ALL_SUBJECTS_PARTICIPATING")) {
                        assertThat(r.usage().state()).isEqualTo("UNAVAILABLE");
                        assertThat(r.usage().costUsd()).isNull();
                    }
                });
        assertThat(dataset.participants()).hasSize(1);
        assertThat(dataset.feedback()).singleElement().satisfies(f -> {
            assertThat(f.basis()).hasSize(1);
            assertThat(f.decisions())
                    .extracting(ResearchDataset.Decision::action)
                    .containsExactly("WITHDRAW");
            assertThat(f.responses())
                    .singleElement()
                    .satisfies(r -> assertThat(r.resolution()).isEqualTo("DISAGREE"));
        });

        var header = disclosures.findById(released.disclosure().getId()).orElseThrow();
        assertThat(header.getContentSha256()).isEqualTo(ResearchDisclosureService.sha256(released.bytes()));
        assertThat(header.getResultCount()).isEqualTo((int) owned);
        assertThat(subjects.findForDisclosure(workspace, header.getId()))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getAccountId()).isEqualTo(participant);
                    assertThat(s.getParticipantKey()).isEqualTo(released.keys().get(participant));
                    assertThat(s.getGrantDecisionId()).isNotNull();
                });
        assertThat(releaseAuditCount(header.getId())).isOne();
        String details = jdbc.queryForObject(
                "SELECT details FROM auth_event WHERE event_type='RESEARCH_DATASET_RELEASED'"
                        + " AND CAST(details AS jsonb)->>'disclosureId'=?",
                String.class,
                header.getId().toString());
        assertThat(details)
                .contains(header.getId().toString())
                .doesNotContain(released.keys().get(participant));
    }

    @Test
    void shouldWithholdWholeReviewUsageWhenTheSameReviewJudgedANonParticipant() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        Result own = data.result(course, ResultOutcome.NOT_MET, at);
        var outsider = data.member(workspace);
        Result other = data.result(
                forPractice(outsider),
                ResultOutcome.NOT_MET,
                at.plusSeconds(30),
                at.plusSeconds(30),
                Objects.requireNonNull(own.getItemVersion()));
        reuse(own, other);

        ResearchSnapshot snapshot = snapshot(workspace);

        assertThat(snapshot.reviewSubjects())
                .filteredOn(row -> row.reviewId().equals(own.getReviewId()))
                .singleElement()
                .satisfies(row -> assertThat(row.developers()).contains(participant, outsider.getId()));
        assertThat(snapshot.usage())
                .as("the accounting owner is not even asked")
                .doesNotContainKey(own.getReviewId());
        var dataset = mapper.readValue(
                authorize(snapshot, Instant.now().plus(30, ChronoUnit.DAYS)).bytes(), ResearchDataset.class);
        assertThat(dataset.reviews()).singleElement().satisfies(review -> {
            assertThat(review.subjectScope()).isEqualTo("INCLUDES_NON_PARTICIPANT");
            assertThat(review.usage().state()).isEqualTo("UNAVAILABLE");
            assertThat(review.usage().unavailableReason()).isEqualTo("INCLUDES_NON_PARTICIPANT");
            assertThat(review.practices())
                    .allSatisfy(run -> assertThat(run.assignments()).isNull());
        });
        assertThat(dataset.results()).hasSize(1);
    }

    @Test
    void shouldRollBackTheReleaseAuditWithTheDisclosureAndProtectedMapping() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        ResearchSnapshot snapshot = snapshot(workspace);

        Released rolledBack = Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            Released released = authorize(snapshot, Instant.now().plus(30, ChronoUnit.DAYS));
            disclosures.flush();
            assertThat(releaseAuditCount(released.disclosure().getId())).isOne();
            status.setRollbackOnly();
            return released;
        }));

        UUID datasetId = rolledBack.disclosure().getId();
        assertThat(disclosures.findById(datasetId)).isEmpty();
        assertThat(subjects.findForDisclosure(workspace, datasetId)).isEmpty();
        assertThat(releaseAuditCount(datasetId)).isZero();
    }

    private long releaseAuditCount(UUID datasetId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type='RESEARCH_DATASET_RELEASED'"
                        + " AND CAST(details AS jsonb)->>'disclosureId'=?",
                Long.class,
                datasetId.toString()));
    }

    private Scenario forPractice(Account developer) {
        var practice = versions.create(new PracticeVersionService.NewPractice(
                course.workspace(),
                "research-" + UUID.randomUUID(),
                course.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                developer.getId()));
        return new Scenario(course.workspace(), developer, practice, course.item());
    }

    private void copyPracticeAssignment(Result own, Long practiceId, Long versionId) {
        jdbc.update(
                "INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,items_listed,listing_complete,capture_decision,method)"
                        + " SELECT review_id,workspace_id,?,?,status,items_listed,listing_complete,capture_decision,method"
                        + " FROM review_practice WHERE workspace_id=? AND review_id=? AND practice_id=?",
                practiceId,
                versionId,
                own.getWorkspaceId(),
                own.getReviewId(),
                own.getPracticeId());
    }

    private void reuse(Result own, Result prior) {
        copyPracticeAssignment(own, prior.getPracticeId(), Objects.requireNonNull(prior.getPracticeVersionId()));
        jdbc.update(
                "INSERT INTO review_item(workspace_id,review_id,practice_id,item_id,item_version,state,result_id,detail)"
                        + " VALUES (?,?,?,?,?,'REUSED',?,jsonb_build_object('developerId',CAST(? AS bigint)))",
                own.getWorkspaceId(),
                own.getReviewId(),
                prior.getPracticeId(),
                prior.getItemId(),
                prior.getItemVersion(),
                prior.getId(),
                prior.getDeveloperId());
    }

    private ResearchSnapshot.ReviewSubjectRow subjectsOf(ResearchSnapshot snapshot, UUID reviewId) {
        return snapshot.reviewSubjects().stream()
                .filter(row -> row.reviewId().equals(reviewId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldBindAndRecheckAParticipantWhoseSupportForASharedReviewLiesOutsideTheWindow() {
        long workspace = Objects.requireNonNull(course.workspace().getId());
        long a = Objects.requireNonNull(course.account().getId());
        var second = data.member(workspace);
        long b = Objects.requireNonNull(second.getId());
        decide(a, true, at.minus(10, ChronoUnit.DAYS));
        decide(b, true, at.minus(10, ChronoUnit.DAYS));
        Result own = data.result(course, ResultOutcome.NOT_MET, at);
        Result earlier = data.result(
                forPractice(second),
                ResultOutcome.MET,
                at.minus(30, ChronoUnit.DAYS),
                at.minus(30, ChronoUnit.DAYS),
                Objects.requireNonNull(own.getItemVersion()));
        // B's earlier result supports a reused assignment; its immutable facts stay on the original review.
        reuse(own, earlier);

        ResearchSnapshot stale = snapshot(workspace);
        assertThat(subjectsOf(stale, own.getReviewId()).developers()).containsExactlyInAnyOrder(a, b);
        // B withdraws and says yes again before the final authorisation: B's grant is not the one read.
        decide(b, false, Instant.now());
        decide(b, true, Instant.now().plusSeconds(1));
        assertThatThrownBy(() -> authorize(stale, Instant.now().plus(30, ChronoUnit.DAYS)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nothing was released");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM research_disclosure WHERE workspace_id=?", Long.class, workspace))
                .isZero();

        Released released = release(workspace, Instant.now().plus(365, ChronoUnit.DAYS));
        String bKey = Objects.requireNonNull(released.keys().get(b));
        var dataset = mapper.readValue(released.bytes(), ResearchDataset.class);
        assertThat(dataset.results())
                .as("B has no row of their own in the window")
                .extracting(ResearchDataset.Result::participant)
                .doesNotContain(bKey);
        assertThat(dataset.reviews())
                .filteredOn(r -> r.subjectParticipants().contains(bKey))
                .singleElement()
                .satisfies(r -> assertThat(r.subjectScope()).isEqualTo("ALL_SUBJECTS_PARTICIPATING"));
        assertThat(subjects.findForDisclosure(workspace, released.disclosure().getId()))
                .extracting(ResearchDisclosureSubject::getAccountId)
                .containsExactlyInAnyOrder(a, b);

        decide(b, false, Instant.now().plusSeconds(2));
        assertThat(write(() -> recorder.detectRemovals(Instant.now())))
                .singleElement()
                .satisfies(open -> assertThat(open.removals()).singleElement().satisfies(owed -> {
                    assertThat(owed.participantKey()).isEqualTo(bKey);
                    assertThat(owed.reason()).isEqualTo(ResearchDisclosureSubject.RemovalReason.CONSENT_WITHDRAWN);
                }));
    }

    @Test
    void shouldTakeTheSubjectOfAnUnjudgedAssignmentOnlyFromItsRecordedDeveloper() {
        long workspace = Objects.requireNonNull(course.workspace().getId());
        long a = Objects.requireNonNull(course.account().getId());
        var second = data.member(workspace);
        long b = Objects.requireNonNull(second.getId());
        decide(a, true, at.minus(10, ChronoUnit.DAYS));
        decide(b, true, at.minus(10, ChronoUnit.DAYS));
        Result own = data.result(course, ResultOutcome.NOT_MET, at);
        Scenario unjudged = forPractice(second);
        UUID shared = own.getReviewId();
        // B's assignment has never produced a result; its captured developer is the only attribution.
        copyPracticeAssignment(
                own,
                unjudged.practice().getId(),
                Objects.requireNonNull(unjudged.practice().getCurrentVersion().getId()));
        jdbc.update(
                "INSERT INTO review_item(workspace_id,review_id,practice_id,item_id,item_version,state,detail)"
                        + " VALUES (?,?,?,?,?,'LISTED',jsonb_build_object('developerId',CAST(? AS bigint)))",
                workspace,
                shared,
                unjudged.practice().getId(),
                course.item().getId(),
                own.getItemVersion(),
                b);

        var recorded = subjectsOf(snapshot(workspace), shared);
        assertThat(recorded.unattributedAssignments()).isZero();
        assertThat(recorded.developers()).containsExactlyInAnyOrder(a, b);
        Released released = release(workspace, Instant.now().plus(365, ChronoUnit.DAYS));
        assertThat(released.keys()).containsKeys(a, b);

        // Without a usable recorded developer the assignment is unknown; nobody is guessed in.
        jdbc.update(
                "UPDATE review_item SET detail=CAST('{\"developerId\":null}' AS jsonb) WHERE workspace_id=? AND review_id=? AND practice_id=?",
                workspace,
                shared,
                unjudged.practice().getId());
        var unknown = snapshot(workspace);
        assertThat(subjectsOf(unknown, shared).unattributedAssignments()).isEqualTo(1);
        assertThat(ReviewSubjectScope.of(
                        subjectsOf(unknown, shared), unknown.grants().keySet()))
                .isEqualTo(ReviewSubjectScope.ATTRIBUTION_UNKNOWN);
        assertThat(unknown.usage()).doesNotContainKey(shared);
    }

    @Test
    void shouldRefuseReleaseWhenTheGrantChangedAfterTheSnapshotEvenIfGivenAgain() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        ResearchSnapshot snapshot = snapshot(workspace);

        decide(participant, false, Instant.now());
        decide(participant, true, Instant.now().plusSeconds(1));

        assertThatThrownBy(() -> authorize(snapshot, Instant.now().plus(30, ChronoUnit.DAYS)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nothing was released");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM research_disclosure WHERE workspace_id=?", Long.class, workspace))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM research_disclosure_subject WHERE workspace_id=?", Long.class, workspace))
                .isZero();
    }

    @Test
    void shouldOweRemovalAfterWithdrawalAndRegrantAndKeepTheKeyThroughErasure() {
        long workspace = Objects.requireNonNull(course.workspace().getId());
        long withdrawing = Objects.requireNonNull(course.account().getId());
        decide(withdrawing, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        var erasedAccount = data.member(workspace);
        long erased = Objects.requireNonNull(erasedAccount.getId());
        decide(erased, true, at.minus(10, ChronoUnit.DAYS));
        data.result(
                new Scenario(course.workspace(), erasedAccount, course.practice(), course.item()),
                ResultOutcome.MET,
                at);
        Released released = release(workspace, Instant.now().plus(365, ChronoUnit.DAYS));
        UUID dataset = released.disclosure().getId();

        decide(withdrawing, false, Instant.now());
        decide(withdrawing, true, Instant.now().plusSeconds(1));
        jdbc.update("UPDATE consent_decision SET account_id=NULL WHERE account_id=?", erased);
        jdbc.update("UPDATE account SET status='DELETED' WHERE id=?", erased);
        write(() -> {
            new ResearchDisclosureAccountErasureAdapter(subjects, disclosures).eraseAccount(erased);
            return true;
        });

        var manifest = write(() -> recorder.detectRemovals(Instant.now()));
        assertThat(manifest).singleElement().satisfies(open -> {
            assertThat(open.datasetId()).isEqualTo(dataset);
            assertThat(open.useExpired()).isFalse();
            assertThat(open.removals())
                    .extracting(
                            ResearchDisclosureRecorder.Obligation::participantKey,
                            ResearchDisclosureRecorder.Obligation::reason)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple(
                                    released.keys().get(withdrawing),
                                    ResearchDisclosureSubject.RemovalReason.CONSENT_WITHDRAWN),
                            Tuple.tuple(
                                    released.keys().get(erased),
                                    ResearchDisclosureSubject.RemovalReason.ACCOUNT_ERASED));
        });

        String withdrawnKey = Objects.requireNonNull(released.keys().get(withdrawing));
        write(() -> recorder.recordRemoved(dataset, List.of(withdrawnKey), withdrawing, Instant.now()));
        assertThat(subjects.findForDisclosure(workspace, dataset))
                .filteredOn(s -> s.getParticipantKey().equals(withdrawnKey))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getAccountId()).isNull();
                    assertThat(s.getGrantDecisionId()).isNull();
                    assertThat(s.getRemovedAt()).isNotNull();
                });
        assertThatThrownBy(() ->
                        write(() -> recorder.recordRemoved(dataset, List.of(withdrawnKey), withdrawing, Instant.now())))
                .isInstanceOf(ResponseStatusException.class);
        var purge = new ResearchDisclosureWorkspacePurge(disclosures);
        assertThatThrownBy(() -> purge.verifyQuiescent(workspace)).isInstanceOf(WorkspacePurgeBlockedException.class);

        write(() -> {
            recorder.close(dataset, ResearchDisclosure.Closure.DESTROYED, withdrawing, Instant.now());
            return true;
        });
        assertThat(subjects.findForDisclosure(workspace, dataset)).isEmpty();
        assertThat(disclosures.findById(dataset).orElseThrow().getClosure())
                .isEqualTo(ResearchDisclosure.Closure.DESTROYED);
        purge.verifyQuiescent(workspace);
        assertThat(write(() -> recorder.detectRemovals(Instant.now()))).isEmpty();
    }

    @Test
    void shouldKeepAnErasureThatCommitsDuringTheRemovalCheckAndNeverLinkTheAccountAgain() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        Released released = release(workspace, Instant.now().plus(30, ChronoUnit.DAYS));
        UUID dataset = released.disclosure().getId();
        String key = Objects.requireNonNull(released.keys().get(participant));
        // The check reads a withdrawal; the account is erased before the check writes.
        decide(participant, false, Instant.now());
        AtomicReference<String> erased = new AtomicReference<>();
        var ledger = new CommitAfterStandingRead(consent, () -> {
            committed(() -> erase(participant));
            erased.set(subjectRow(dataset));
        });
        var checking = new ResearchDisclosureRecorder(disclosures, subjects, ledger, audit);

        var manifest = write(() -> checking.detectRemovals(Instant.now()));

        assertThat(erased.get()).as("the erasure committed while the check ran").isNotNull();
        assertThat(subjectRow(dataset))
                .as("the check writes nothing over the erasure")
                .isEqualTo(erased.get());
        assertThat(subjects.findForDisclosure(workspace, dataset))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getAccountId()).isNull();
                    assertThat(s.getRemovalReason()).isEqualTo(ResearchDisclosureSubject.RemovalReason.ACCOUNT_ERASED);
                    assertThat(s.getRemovedAt()).isNull();
                });
        assertThat(manifest)
                .singleElement()
                .satisfies(open -> assertThat(open.removals())
                        .extracting(
                                ResearchDisclosureRecorder.Obligation::participantKey,
                                ResearchDisclosureRecorder.Obligation::reason)
                        .containsExactly(Tuple.tuple(key, ResearchDisclosureSubject.RemovalReason.ACCOUNT_ERASED)));
    }

    @Test
    void shouldKeepAReceiptThatCommitsDuringTheRemovalCheckWithItsFirstReasonAndNoLink() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        Released released = release(workspace, Instant.now().plus(30, ChronoUnit.DAYS));
        UUID dataset = released.disclosure().getId();
        String key = Objects.requireNonNull(released.keys().get(participant));
        decide(participant, false, Instant.now());
        Instant owedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant removedAt = owedAt.plusSeconds(1);
        // Before this check writes, another check records the withdrawal and the operator records the key as removed.
        AtomicReference<String> receipted = new AtomicReference<>();
        var ledger = new CommitAfterStandingRead(consent, () -> {
            committed(() -> recorder.detectRemovals(owedAt));
            committed(() -> recorder.recordRemoved(dataset, List.of(key), participant, removedAt));
            receipted.set(subjectRow(dataset));
        });
        var checking = new ResearchDisclosureRecorder(disclosures, subjects, ledger, audit);

        var manifest = write(() -> checking.detectRemovals(removedAt.plusSeconds(1)));

        assertThat(receipted.get())
                .as("the receipt committed while the check ran")
                .isNotNull();
        assertThat(subjects.findForDisclosure(workspace, dataset))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getAccountId()).isNull();
                    assertThat(s.getGrantDecisionId()).isNull();
                    assertThat(s.getRemovalReason())
                            .isEqualTo(ResearchDisclosureSubject.RemovalReason.CONSENT_WITHDRAWN);
                    assertThat(s.getRemovalRequiredAt())
                            .as("the first date stays")
                            .isEqualTo(owedAt);
                    assertThat(s.getRemovedAt()).isEqualTo(removedAt);
                });
        assertThat(subjectRow(dataset))
                .as("the check restores neither the link nor an open removal")
                .isEqualTo(receipted.get());
        assertThat(manifest).singleElement().satisfies(open -> {
            assertThat(open.datasetId()).isEqualTo(dataset);
            assertThat(open.removals()).isEmpty();
        });
    }

    /** One account erasure, as the existing erasure path leaves the ledger, the account and the mapping. */
    private void erase(long accountId) {
        jdbc.update("UPDATE consent_decision SET account_id=NULL WHERE account_id=?", accountId);
        jdbc.update("UPDATE account SET status='DELETED' WHERE id=?", accountId);
        new ResearchDisclosureAccountErasureAdapter(subjects, disclosures).eraseAccount(accountId);
    }

    /** Commits on its own connection, as a concurrent request would, while a caller's transaction stays open. */
    private void committed(Runnable work) {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> work.run());
    }

    private String subjectRow(UUID dataset) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT to_jsonb(s)::text FROM research_disclosure_subject s WHERE s.disclosure_id=?",
                String.class,
                dataset));
    }

    /**
     * The real consent ledger. Right after the first standing read, so between the removal check's read of the
     * subject rows and its write, one concurrent writer runs and commits.
     */
    private static final class CommitAfterStandingRead implements ResearchParticipationQuery {
        private final ResearchParticipationQuery ledger;
        private @Nullable Runnable concurrent;

        private CommitAfterStandingRead(ResearchParticipationQuery ledger, Runnable concurrent) {
            this.ledger = ledger;
            this.concurrent = concurrent;
        }

        @Override
        public Map<Long, GrantStanding> grantStandings(Map<Long, Long> grantDecisionIds) {
            Map<Long, GrantStanding> standings = ledger.grantStandings(grantDecisionIds);
            Runnable writer = concurrent;
            concurrent = null;
            if (writer != null) writer.run();
            return standings;
        }

        @Override
        public Optional<String> researchOrganization() {
            return ledger.researchOrganization();
        }

        @Override
        public boolean participates(long accountId) {
            return ledger.participates(accountId);
        }

        @Override
        public Optional<ResearchQuestion> currentQuestion() {
            return ledger.currentQuestion();
        }

        @Override
        public Map<Long, Long> currentGrants(Collection<Long> accountIds) {
            return ledger.currentGrants(accountIds);
        }

        @Override
        public Map<Long, Long> lockCurrentGrants(Collection<Long> accountIds) {
            return ledger.lockCurrentGrants(accountIds);
        }
    }

    @Test
    void shouldListExpiredUseForClosureWithoutDroppingTheMapping() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        Instant useUntil = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(30, ChronoUnit.DAYS);
        UUID dataset = release(workspace, useUntil).disclosure().getId();

        assertThat(write(() -> recorder.detectRemovals(useUntil.minusSeconds(1))))
                .singleElement()
                .satisfies(open -> {
                    assertThat(open.datasetId()).isEqualTo(dataset);
                    assertThat(open.useExpired()).isFalse();
                    assertThat(open.removals()).isEmpty();
                });
        assertThat(write(() -> recorder.detectRemovals(useUntil)))
                .singleElement()
                .satisfies(open -> {
                    assertThat(open.useExpired()).isTrue();
                    assertThat(open.removals()).isEmpty();
                });
        assertThat(subjects.findForDisclosure(workspace, dataset)).hasSize(1);
    }

    @Test
    void shouldListAnOrdinaryOpenDisclosureUntilItIsClosedEarly() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        Instant useUntil = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(30, ChronoUnit.DAYS);
        UUID dataset = release(workspace, useUntil).disclosure().getId();

        assertThat(write(() -> recorder.detectRemovals(Instant.now())))
                .singleElement()
                .satisfies(open -> {
                    assertThat(open.datasetId()).isEqualTo(dataset);
                    assertThat(open.workspaceId()).isEqualTo(workspace);
                    assertThat(open.useUntil()).isEqualTo(useUntil);
                    assertThat(open.useExpired()).isFalse();
                    assertThat(open.removals()).isEmpty();
                });
        assertThat(subjects.findForDisclosure(workspace, dataset))
                .singleElement()
                .satisfies(subject -> {
                    assertThat(subject.getRemovalRequiredAt()).isNull();
                    assertThat(subject.getRemovedAt()).isNull();
                });

        write(() -> {
            recorder.close(dataset, ResearchDisclosure.Closure.DESTROYED, participant, Instant.now());
            return true;
        });
        assertThat(write(() -> recorder.detectRemovals(Instant.now()))).isEmpty();
        assertThat(disclosures.findById(dataset).orElseThrow().getClosure())
                .isEqualTo(ResearchDisclosure.Closure.DESTROYED);
        assertThat(subjects.findForDisclosure(workspace, dataset)).isEmpty();
    }

    @Test
    void shouldKeepRemovalAndClosureAvailableAfterTheResearchProgramIsRemoved() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        var released = release(workspace, Instant.now().plus(30, ChronoUnit.DAYS));
        UUID dataset = released.disclosure().getId();
        String key = Objects.requireNonNull(released.keys().get(participant));
        var noProgram = ResearchConsentTestSupport.forOrganization(context, "");
        assertThat(noProgram.currentQuestion()).isEmpty();
        var remaining = new ResearchDisclosureRecorder(disclosures, subjects, noProgram, audit);

        assertThat(write(() -> remaining.detectRemovals(Instant.now())))
                .singleElement()
                .satisfies(open -> assertThat(open.removals())
                        .extracting(
                                ResearchDisclosureRecorder.Obligation::participantKey,
                                ResearchDisclosureRecorder.Obligation::reason)
                        .containsExactly(Tuple.tuple(key, ResearchDisclosureSubject.RemovalReason.QUESTION_CHANGED)));
        assertThat(write(() -> remaining.recordRemoved(dataset, List.of(key), participant, Instant.now())))
                .isOne();
        assertThat(write(() -> remaining.detectRemovals(Instant.now())))
                .singleElement()
                .satisfies(open -> {
                    assertThat(open.datasetId()).isEqualTo(dataset);
                    assertThat(open.removals()).isEmpty();
                });
        write(() -> {
            remaining.close(dataset, ResearchDisclosure.Closure.ANONYMIZED, participant, Instant.now());
            return true;
        });
        assertThat(write(() -> remaining.detectRemovals(Instant.now()))).isEmpty();
        assertThat(subjects.findForDisclosure(workspace, dataset)).isEmpty();
    }

    @Test
    void shouldRejectASubjectWhoseWorkspaceDiffersFromItsDisclosure() {
        long participant = Objects.requireNonNull(course.account().getId());
        long workspace = Objects.requireNonNull(course.workspace().getId());
        long grant = decide(participant, true, at.minus(10, ChronoUnit.DAYS));
        data.result(course, ResultOutcome.NOT_MET, at);
        UUID dataset = release(workspace, Instant.now().plus(30, ChronoUnit.DAYS))
                .disclosure()
                .getId();
        long otherWorkspace = Objects.requireNonNull(data.scenario().workspace().getId());

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO research_disclosure_subject(id,disclosure_id,workspace_id,account_id,participant_key,grant_decision_id)"
                                + " VALUES (?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        dataset,
                        otherWorkspace,
                        participant,
                        "p_" + "a".repeat(26),
                        grant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
