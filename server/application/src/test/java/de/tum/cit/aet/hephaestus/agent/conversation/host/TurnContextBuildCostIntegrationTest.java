package de.tum.cit.aet.hephaestus.agent.conversation.host;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.conversation.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnStarts;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Building one turn's context reads a fixed number of statements, whatever the length of the developer's history. The
 * workspace is shaped like a staging workspace: a developer with several monitored repositories, 45 authored pull
 * requests with commits and changed files, 120 file items, reviews whose results fill the result history, marks, and
 * delivered and prepared feedback. Every result carries complete custody that names the developer's identity, a
 * repository and its producing job, so each custody, source and suppression fence does its real work.
 *
 * <p>The build runs as a turn's worker runs it, with no outer transaction, at N and 2N results and pieces of feedback.
 * The work items they are about stay the same, so each work item's source is checked equally often at both sizes. A
 * build that reads once per result or per piece of feedback grows with N, so the test bounds the growth, not the count.
 */
class TurnContextBuildCostIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /**
     * The most statements by which one context build may grow when its results and feedback double. The 2N rows add a
     * second practice, whose version a part that lists it reads once. One more read per result or per piece of feedback
     * adds at least 15.
     */
    static final long GROWTH_STATEMENTS = 10;

    /** The work items that delivered feedback, and that prepared feedback, is about at both sizes. */
    private static final int DELIVERED_WORK = 15;

    private static final int PREPARED_WORK = 3;

    private static final ItemKind PULL_REQUEST = new ItemKind("scm.pull_request");

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private ResultHistoryQuery resultHistory;

    @Autowired
    private FeedbackHistoryQuery feedbackHistory;

    @Autowired
    private SqlStatementCounter statements;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private PracticeVersionService practices;

    @Autowired
    private ItemIndexService itemIndex;

    @Autowired
    private ItemRepository items;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JsonMapper json;

    private final AtomicLong nativeIds = new AtomicLong(310_000);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private IdentityProvider github;
    private User developer;
    private Account account;
    private long accountId;
    private Workspace workspace;
    private PersonCopyIdentity developerCopy;
    private final List<Practice> practiceList = new ArrayList<>();
    private final List<Repository> repositoryList = new ArrayList<>();
    private final Map<Long, Long> repositoryItems = new LinkedHashMap<>();
    private final List<Seeded> work = new ArrayList<>();
    private final List<Result> resultList = new ArrayList<>();
    private final Map<String, Result> current = new LinkedHashMap<>();
    private int files;
    private int delivered;
    private int prepared;
    private @Nullable TurnAttempt turn;

    private record Seeded(PullRequest pullRequest, Repository repository, Item item) {}

    @BeforeEach
    void seedDeveloper() {
        github = ensureGitHubProvider();
        developer = persistUser("cost-developer");
        workspace = createWorkspace("turn-cost", "Turn cost", "turn-cost-org", AccountType.ORG, developer);
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaces.saveAndFlush(workspace);
        connectActivitySource(workspace, github);
        accountId = accountId(developer);
        account = accountRepository.findById(accountId).orElseThrow();
        String subject = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT provider_user_key FROM identity WHERE account_id=? AND provider_id=?",
                String.class,
                accountId,
                github.getId()));
        developerCopy = new PersonCopyIdentity("GITHUB", github.getServerUrl(), subject, null);
        for (int index = 0; index < 3; index++)
            practiceList.add(practices.create(new PracticeVersionService.NewPractice(
                    workspace.getId(),
                    "turn-cost-" + index,
                    PULL_REQUEST,
                    DefinitionTestFixtures.definition(),
                    null,
                    0,
                    accountId)));
    }

    @Test
    void shouldNotGrowAContextBuildWithItsResultsAndFeedback() {
        // N: 45 results, 15 delivered and 3 prepared pieces. 2N is the staging shape: 90 results fill the history. Both
        // sizes have 45 pull requests: the 2N results and feedback rest on the same work under a second practice.
        grow(4, 45, 120, 45, 15, 3);
        var attempt = attempt();
        var small = build(attempt, 45, 18);
        grow(4, 45, 120, 90, 30, 6);
        var large = build(attempt, 90, 36);

        assertThat(large.statements() - small.statements())
                .as(() -> "statements one context build adds when its results and feedback double (%s, then %s);"
                                .formatted(small.statements(), large.statements())
                        + " the statements that grew:\n" + grown(small.sql(), large.sql()))
                .isLessThanOrEqualTo(GROWTH_STATEMENTS);
    }

    /** Adds rows until the workspace holds the given numbers of each. */
    private void grow(
            int repositoryCount,
            int pullRequestCount,
            int fileCount,
            int resultCount,
            int deliveredCount,
            int preparedCount) {
        while (repositoryList.size() < repositoryCount) repository();
        while (work.size() < pullRequestCount) pullRequest();
        while (files < fileCount) file();
        while (resultList.size() < resultCount) result();
        for (; delivered < deliveredCount; delivered++)
            feedback(support(delivered, DELIVERED_WORK, false), delivered % 3 == 0 ? "ON_WORK" : "PRACTICE_PAGE", true);
        for (; prepared < preparedCount; prepared++)
            feedback(support(prepared, PREPARED_WORK, true), "CONVERSATION", false);
    }

    /**
     * The result that the {@code piece}th piece of feedback is about: one of the first, or the newest, {@code works}
     * work items, under the next practice for each further round of them. More pieces thus rest on more results, not
     * on more work items.
     */
    private Result support(int piece, int works, boolean newest) {
        int index = piece % works;
        return resultList.get((piece / works) * work.size() + (newest ? work.size() - 1 - index : index));
    }

    private void repository() {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(github);
        String path = "turn-cost-org/service-" + repositoryList.size();
        repository.setName(path.substring(path.indexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(github.getServerUrl() + "/" + path);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(now.minus(Duration.ofDays(200)));
        repository.setUpdatedAt(now);
        repository.setPushedAt(now);
        repository = repositories.saveAndFlush(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var item = itemIndex.upsert(
                new ItemRegistration(workspace.getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        repositoryList.add(repository);
        repositoryItems.put(repository.getId(), item.itemId());
    }

    private void pullRequest() {
        int number = work.size() + 1;
        Repository repository = repositoryList.get(work.size() % repositoryList.size());
        Instant at = now.minus(Duration.ofDays(number % 80)).minus(Duration.ofHours(1));
        PullRequest pr = new PullRequest();
        pr.setNativeId(nativeIds.incrementAndGet());
        pr.setProvider(github);
        pr.setRepository(repository);
        pr.setNumber(number);
        pr.setTitle("Change " + number);
        pr.setBody("Implements change " + number + ".");
        pr.setState(number % 4 == 0 ? Issue.State.CLOSED : Issue.State.OPEN);
        pr.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
        pr.setAuthor(developer);
        pr.setHeadRefName("feature-" + number);
        pr.setBaseRefName("main");
        pr.setCommits(1);
        pr.setCreatedAt(at);
        pr.setUpdatedAt(at.plus(Duration.ofMinutes(30)));
        pr = pullRequests.saveAndFlush(pr);
        Commit commit = new Commit();
        commit.setSha(String.format(Locale.ROOT, "%040x", nativeIds.incrementAndGet()));
        commit.setMessage("Implement change " + number);
        commit.setHtmlUrl(repository.getHtmlUrl() + "/commit/" + commit.getSha());
        commit.setAuthoredAt(at);
        commit.setCommittedAt(at);
        commit.setRepository(repository);
        commit.setAuthor(developer);
        commit.setGitDetailsCapturedAt(at);
        commit.getAssociatedPullRequests().add(pr);
        commit.getFileChanges().add(fileChange(commit, "src/Change" + number + ".java", 40, 2));
        commit.getFileChanges().add(fileChange(commit, "README.md", 3, 0));
        commits.saveAndFlush(commit);
        var item = itemIndex.upsert(new ItemRegistration(
                workspace.getId(),
                ScmItemEvents.PULL_REQUEST,
                pr.getId(),
                repositoryItems.get(repository.getId()),
                null));
        work.add(new Seeded(
                pr,
                repository,
                items.findByIdAndWorkspaceId(item.itemId(), workspace.getId()).orElseThrow()));
    }

    private static CommitFileChange fileChange(Commit commit, String path, int additions, int deletions) {
        CommitFileChange change = new CommitFileChange();
        change.setCommit(commit);
        change.setFilename(path);
        change.setChangeType(CommitFileChange.ChangeType.MODIFIED);
        change.setAdditions(additions);
        change.setDeletions(deletions);
        change.setChanges(additions + deletions);
        return change;
    }

    private void file() {
        Repository repository = repositoryList.get(files % repositoryList.size());
        itemIndex.upsert(new ItemRegistration(
                workspace.getId(),
                ScmItemEvents.FILE,
                null,
                repositoryItems.get(repository.getId()),
                "src/main/File" + files + ".java"));
        files++;
    }

    /**
     * One review of a pull request with its result, recorded within the last 85 days: every third one not met with a
     * problem, its producing job, complete custody and, now and then, a mark.
     */
    private void result() {
        int index = resultList.size();
        var seeded = work.get(index % work.size());
        var practice = practiceList.get((index / work.size()) % practiceList.size());
        // Results arrive in time order; a newer review of the same work and practice replaces the current result.
        Instant at = now.minus(Duration.ofDays(85)).plus(Duration.ofHours(20L * index));
        var replaced = current.remove(seeded.item().getId() + ":" + practice.getId());
        if (replaced != null) data.end(replaced, at);
        var result = data.result(
                new Scenario(workspace, account, practice, seeded.item()),
                index % 3 == 0 ? ResultOutcome.NOT_MET : ResultOutcome.MET,
                at);
        UUID job = data.job(result);
        var custody = json.createObjectNode();
        custody.set(
                "custody",
                json.valueToTree(new HistoryCustody(
                        true,
                        false,
                        List.of(developerCopy),
                        List.of(Objects.requireNonNull(seeded.repository().getId())),
                        List.of(job))));
        // Only capture writes custody receipts; the guard that refuses other writers stays on.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE review_item SET job_id=?,detail=detail||CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                    job,
                    custody.toString(),
                    result.getWorkspaceId(),
                    result.getId());
        });
        if (index % 10 == 0) data.mark(result, ResultMarkKind.ACCEPTED, accountId);
        resultList.add(result);
        current.put(seeded.item().getId() + ":" + practice.getId(), result);
    }

    private void feedback(Result bound, String destination, boolean delivered) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES(?,?,?,?,?,?,?,'Keep each change focused.',?,?)",
                id,
                bound.getWorkspaceId(),
                bound.getReviewId(),
                bound.getItemId(),
                bound.getDeveloperId(),
                destination,
                delivered ? "DELIVERED" : "READY",
                Timestamp.from(bound.getRecordedAt()),
                delivered ? Timestamp.from(bound.getRecordedAt()) : null);
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,person_account_id,ordinal)"
                        + " VALUES(?,?,?,?,?,0)",
                FeedbackIds.basis(id, bound.getId(), null),
                bound.getWorkspaceId(),
                id,
                bound.getId(),
                bound.getDeveloperId());
    }

    /** The developer's started turn, asked once for the workspace. */
    private TurnAttempt attempt() {
        var known = turn;
        if (known != null) return known;
        var conversation = conversations.findOrCreateMain(workspace.getId(), accountId);
        ConversationTurn started = ConversationTurnStarts.askAndStart(
                beans, metrics, workspace.getId(), conversation.getId(), "What should I work on next?");
        known = new TurnAttempt(
                started.getId(),
                workspace.getId(),
                accountId,
                conversation.getId(),
                started.getAttempt(),
                Objects.requireNonNull(started.getRunId()));
        turn = known;
        return known;
    }

    /**
     * The statements of one context build, after a first build warms the code paths. First checks that every result
     * and piece of feedback passes its custody and source fences, so the build reads each fence for real.
     */
    private SqlStatementCounter.Measurement<ConversationTurnContexts.Context> build(
            TurnAttempt attempt, int results, int pieces) {
        var since = now.minus(Duration.ofDays(90));
        assertThat(resultHistory.forDeveloper(workspace.getId(), accountId, since, 1000))
                .hasSize(results)
                .allSatisfy(entry -> assertThat(entry.summary()).isNotNull());
        assertThat(feedbackHistory.forDeveloper(workspace.getId(), accountId, since, 1000))
                .hasSize(pieces)
                .allSatisfy(entry -> assertThat(entry.copyAuthorityComplete()).isTrue());
        ConversationTurnContexts contexts = ConversationTurnFixtures.contexts(beans);
        contexts.context(attempt);
        return statements.measure(() -> contexts.context(attempt));
    }

    /** Each statement that the larger build prepared more often, with how many more times. */
    private static String grown(List<String> small, List<String> large) {
        var before = small.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return large.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > before.getOrDefault(entry.getKey(), 0L))
                .map(entry -> "%5d more: %s"
                        .formatted(
                                entry.getValue() - before.getOrDefault(entry.getKey(), 0L),
                                entry.getKey().replaceAll("\\s+", " ")))
                .collect(Collectors.joining("\n"));
    }
}
