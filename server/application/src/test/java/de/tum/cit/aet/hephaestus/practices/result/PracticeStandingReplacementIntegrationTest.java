package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The migration fixture is controlled; subsequent replacement and standing reads use their real owners. */
class PracticeStandingReplacementIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultStore store;

    @Autowired
    ResultRepository results;

    @Autowired
    PracticeStandingService standings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    RepositoryRepository repositories;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @Autowired
    UserRepository users;

    @Autowired
    IdentityRepository identities;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    IssueCommentRepository comments;

    @Autowired
    ItemIndexService items;

    @Autowired
    ItemRepository itemRows;

    ReviewIntegrationData.Scenario scenario;

    String itemVersion;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = monitoredPullRequest(data.scenario());
        itemVersion = productItems
                .pin(
                        productItems.require(
                                scenario.workspace().getId(), scenario.item().getId()),
                        null)
                .version()
                .value();
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
    }

    @ParameterizedTest
    @EnumSource(
            value = ResultOutcome.class,
            names = {"DOES_NOT_APPLY", "COULD_NOT_DETERMINE"})
    void shouldKeepHistoricalAssessmentAfterARequestedNonVerdictReallyReplacesItsClaim(ResultOutcome outcome) {
        var earlier = historicalLiveAssessment();
        var replacement = admit(outcome, Objects.requireNonNull(earlier.getItemVersion()));
        var ended = results.findById(earlier.getId()).orElseThrow();
        assertThat(ended.getEndReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(ended.getEndedAt()).isEqualTo(replacement.getRecordedAt());
        assertThat(replacement.getEndedAt()).isNull();

        var practice = snapshot();
        assertThat(practice.dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        assertThat(practice.dto().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        assertThat(practice.evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(earlier.getId());
        assertThat(practice.dto().strengths()).isEmpty();
        assertThat(practice.dto().toWorkOn()).isEmpty();
        assertThat(practice.dto().direction()).isNull();
        assertThat(practice.dto().trendSupport()).isNull();
        assertThat(practice.liveRuns()).isEmpty();
    }

    @Test
    void shouldPreferAQualifiedCurrentVerdictToTheHistoricalAssessment() {
        var earlier = historicalLiveAssessment();
        var replacement = admit(ResultOutcome.NOT_MET, Objects.requireNonNull(earlier.getItemVersion()));
        assertThat(results.findById(earlier.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.REPLACED);
        var practice = snapshot();
        assertThat(practice.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(practice.evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(replacement.getId());
        assertThat(practice.dto().toWorkOn())
                .extracting(result -> result.resultId())
                .containsExactly(replacement.getId());
    }

    @Test
    void shouldUseTheSameCurrentStandingForMentoringWhenProcessingIsAuthorized() {
        var result = admit(ResultOutcome.NOT_MET, itemVersion);
        var human = snapshot();
        var processing = processingSnapshot();

        assertThat(processing.dto()).isEqualTo(human.dto());
        assertThat(processing.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(processing.evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(result.getId());
        assertThat(processing.dto().toWorkOn())
                .extracting(row -> row.resultId())
                .containsExactly(result.getId());
    }

    @Test
    void shouldKeepANoAiDevelopersStandingHumanOnly() {
        var result = admit(ResultOutcome.MET, itemVersion);
        assertThat(processingSnapshot().dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        jdbc.update(
                "UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?",
                scenario.account().getId());

        assertThat(snapshot().dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        assertThat(snapshot().evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(result.getId());
        assertNotProcessed();
    }

    @Test
    void shouldKeepAMigratedStandingHumanOnly() {
        var earlier = historicalLiveAssessment();
        assertThat(snapshot().dto().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        assertThat(snapshot().evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(earlier.getId());
        assertNotProcessed();
    }

    @Test
    void shouldRefuseMentoringOverAnotherContributorsNoAiChoice() {
        var result = admit(ResultOutcome.MET, itemVersion);
        assertThat(processingSnapshot().dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        var pull = pulls.findById(Objects.requireNonNull(scenario.item().getSourceId()))
                .orElseThrow();
        var contributor = data.member(scenario.workspace().getId());
        var provider = Objects.requireNonNull(pull.getProvider());
        var nativeUser = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), "standing-commenter", provider));
        var identity = new Identity();
        identity.setAccount(contributor);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(nativeUser.getNativeId().toString());
        identities.saveAndFlush(identity);
        var comment = new IssueComment();
        comment.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        comment.setProvider(provider);
        comment.setBody("An owned contribution.");
        comment.setHtmlUrl(pull.getHtmlUrl() + "#issuecomment-1");
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setAuthor(nativeUser);
        comment.setIssue(pull);
        comment.setCreatedAt(Instant.now());
        comment.setUpdatedAt(Instant.now());
        comments.saveAndFlush(comment);
        jdbc.update("UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?", contributor.getId());

        assertThat(snapshot().dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        assertThat(snapshot().evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(result.getId());
        assertNotProcessed();
    }

    private void assertNotProcessed() {
        var processing = processingSnapshot();
        assertThat(processing.dto().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        assertThat(processing.dto().basis()).isNull();
        assertThat(processing.evidence()).isEmpty();
        assertThat(processing.liveRuns()).isEmpty();
        assertThat(processing.dto().strengths()).isEmpty();
        assertThat(processing.dto().toWorkOn()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ResultEndReason.class,
            names = {"REMOVED", "REPAIRED"})
    void shouldNotRestoreHistoricalAssessmentAcrossARealRetirement(ResultEndReason reason) {
        var earlier = historicalLiveAssessment();
        assertThat(store.endCurrent(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        reason,
                        Instant.now().minusSeconds(5)))
                .isEqualTo(1);
        admit(ResultOutcome.DOES_NOT_APPLY, Objects.requireNonNull(earlier.getItemVersion()));
        assertThat(results.findById(earlier.getId()).orElseThrow().getEndReason())
                .isEqualTo(reason);
        var practice = snapshot();
        assertThat(practice.dto().standing()).isEqualTo(PracticeStanding.NO_OPPORTUNITY);
        assertThat(practice.dto().basis()).isNull();
        assertThat(practice.evidence())
                .extracting(view -> view.result().getId())
                .doesNotContain(earlier.getId());
    }

    @Test
    void shouldKeepTheHistoricalAssessmentAcrossAWorkChange() {
        var earlier = historicalLiveAssessment();
        assertThat(store.endCurrent(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        ResultEndReason.WORK_CHANGED,
                        Instant.now().minusSeconds(5)))
                .isEqualTo(1);
        admit(ResultOutcome.DOES_NOT_APPLY, Objects.requireNonNull(earlier.getItemVersion()));

        var practice = snapshot();
        assertThat(practice.dto().standing()).isEqualTo(PracticeStanding.STRENGTH);
        assertThat(practice.dto().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        assertThat(practice.evidence())
                .extracting(view -> view.result().getId())
                .containsExactly(earlier.getId());
    }

    @Test
    void shouldKeepTheLiveStandingAfterAWorkChangeThatNoReviewFollowed() {
        var live = asLive(admit(ResultOutcome.NOT_MET, itemVersion));
        var before = snapshot();
        assertThat(before.liveRuns()).extracting(view -> view.result().getId()).containsExactly(live.getId());

        assertThat(store.endCurrent(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        ResultEndReason.WORK_CHANGED,
                        Instant.now()))
                .isEqualTo(1);
        assertThat(results.findById(live.getId()).orElseThrow().getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);

        var after = snapshot();
        assertThat(after.dto().standing()).isEqualTo(before.dto().standing());
        assertThat(after.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(after.liveRuns()).extracting(view -> view.result().getId()).containsExactly(live.getId());
        // An ended result proves nothing current: it is not quoted, and Heph does not read it.
        assertThat(after.dto().toWorkOn()).isEmpty();
        assertThat(processingSnapshot().liveRuns()).isEmpty();
    }

    private ReviewIntegrationData.Scenario monitoredPullRequest(ReviewIntegrationData.Scenario seed) {
        long installation = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        var connection = new Connection(
                seed.workspace(),
                IntegrationKind.GITHUB,
                Long.toString(installation),
                new ConnectionConfig.GitHubAppConfig(
                        installation, seed.workspace().getAccountLogin(), null, Set.of()));
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("standing");
        repository.setNameWithOwner(seed.workspace().getAccountLogin() + "/standing");
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(Instant.now());
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(seed.workspace(), repository.getNameWithOwner()));
        var author = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), "standing-author", provider));
        var identity = new Identity();
        identity.setAccount(seed.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(author.getNativeId().toString());
        identities.saveAndFlush(identity);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(provider);
        pull.setRepository(repository);
        pull.setNumber(1);
        pull.setTitle("Standing work");
        pull.setBody("A monitored change.");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(author);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/1");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        pull = pulls.saveAndFlush(pull);
        var parent = items.upsert(new ItemRegistration(
                seed.workspace().getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        var item = items.upsert(new ItemRegistration(
                seed.workspace().getId(), ScmItemEvents.PULL_REQUEST, pull.getId(), parent.itemId(), null));
        return new ReviewIntegrationData.Scenario(
                seed.workspace(),
                seed.account(),
                seed.practice(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), seed.workspace().getId())
                        .orElseThrow());
    }

    private Result historicalLiveAssessment() {
        var at = Instant.now().minusSeconds(60);
        var earlier = data.result(scenario, ResultOutcome.MET, at, at, itemVersion);
        // Only migration creates v0 facts. Set up its historical origin before exercising real admission;
        // no product assertion runs with the guards disabled, and no claim is resurrected or rewritten.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE result SET evidence_version=0 WHERE workspace_id=? AND id=?",
                    scenario.workspace().getId(),
                    earlier.getId());
            jdbc.update(
                    "UPDATE review SET origin='EVENT',requested_by_id=NULL WHERE workspace_id=? AND id=?",
                    scenario.workspace().getId(),
                    earlier.getReviewId());
        });
        assertThat(snapshot().dto().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        return results.findById(earlier.getId()).orElseThrow();
    }

    // Admission in this test records a requested review; a live review comes from an event.
    private Result asLive(Result result) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE review SET origin='EVENT',requested_by_id=NULL WHERE workspace_id=? AND id=?",
                    scenario.workspace().getId(),
                    result.getReviewId());
        });
        return results.findById(result.getId()).orElseThrow();
    }

    private Result admit(ResultOutcome outcome, String itemVersion) {
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId(), itemVersion);
        admissions.running(pending, 0);
        when(runtime.runtime(scenario.workspace().getId(), pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        0,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "standing-replacement")))));
        var receipt = store.admit(
                scenario.workspace().getId(),
                pending.review().getId(),
                pending.jobId(),
                0,
                List.of(admissions.candidate(pending, outcome)));
        assertThat(receipt.withheldItems()).isEmpty();
        assertThat(receipt.resultIds()).hasSize(1);
        return results.findById(receipt.resultIds().getFirst()).orElseThrow();
    }

    private PracticeStandingService.StandingSnapshot.PracticeStandingSnapshot processingSnapshot() {
        var snapshot = standings
                .getProcessingStandingSnapshots(
                        scenario.workspace().getId(),
                        Objects.requireNonNull(scenario.account().getId()),
                        List.of(Instant.now()))
                .getFirst();
        return Objects.requireNonNull(
                snapshot.practices().get(scenario.practice().getSlug()));
    }

    private PracticeStandingService.StandingSnapshot.PracticeStandingSnapshot snapshot() {
        var snapshot = standings
                .getStandingSnapshots(
                        scenario.workspace().getId(),
                        Objects.requireNonNull(scenario.account().getId()),
                        List.of(Instant.now()))
                .getFirst();
        return Objects.requireNonNull(
                snapshot.practices().get(scenario.practice().getSlug()));
    }
}
