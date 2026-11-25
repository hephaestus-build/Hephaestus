package de.tum.cit.aet.hephaestus.performance;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@TestComponent
@RequiredArgsConstructor
class SignedInApiReadCostData {
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");
    private static final int REVIEWS_PER_WORK = 6;

    private final ReviewIntegrationData reviews;
    private final AccountRepository accounts;
    private final HephaestusJwtIssuer issuer;
    private final IdentityRepository identities;
    private final IdentityProviderRepository providers;
    private final ConnectionRepository connections;
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final PracticeVersionService practiceVersions;
    private final UserRepository users;
    private final RepositoryRepository repositories;
    private final RepositoryToMonitorRepository monitors;
    private final PullRequestRepository pulls;
    private final ItemRepository items;
    private final ActivityEventRepository activity;
    private final LlmUsageEventRepository usage;
    private final ConversationService conversations;
    private final ConversationEntryService entries;
    private final CatalogService catalog;
    private final ScanReportTestData scans;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final JsonMapper json;

    Fixture seed(int workCount, int memberCount) {
        var owner = reviews.scenario(definition(true));
        var workspace = owner.workspace();
        long workspaceId = workspace.getId();
        var admin = new Account("Sweep instance admin");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        admin = accounts.saveAndFlush(admin);
        var provider = IdentityProviderFixtures.gitHub(providers);

        long ownerId = Objects.requireNonNull(owner.account().getId());
        long connectionId = connect(workspace);
        seedOtherWorkspaces(ownerId, memberCount / 4);
        var repository = repository(owner, provider);
        var parent = item(owner, ScmItemEvents.REPOSITORY, repository.getId(), null, Instant.now());
        var group = "sweep-practices";
        var groupId = jdbc.queryForObject(
                "INSERT INTO practice_group(workspace_id,slug,name) VALUES (?, ?, 'Sweep practices') RETURNING id",
                Long.class,
                workspaceId,
                group);
        jdbc.update(
                "UPDATE practice SET group_id=? WHERE workspace_id=? AND id=?",
                groupId,
                workspaceId,
                owner.practice().getId());

        List<Practice> practices = new ArrayList<>();
        practices.add(owner.practice());
        for (int index = 1; index <= memberCount / 5; index++) {
            var practice = practiceVersions.create(new PracticeVersionService.NewPractice(
                    workspaceId,
                    "sweep-practice-" + index,
                    ScmItemEvents.PULL_REQUEST,
                    definition(index % 2 == 0),
                    null,
                    index,
                    ownerId));
            jdbc.update(
                    "UPDATE practice SET group_id=? WHERE workspace_id=? AND id=?",
                    groupId,
                    workspaceId,
                    practice.getId());
            practices.add(practice);
        }

        List<Account> members = new ArrayList<>();
        members.add(owner.account());
        for (int index = 1; index < memberCount; index++) members.add(reviews.member(workspaceId));
        List<User> authors = new ArrayList<>();
        for (var member : members) authors.add(author(provider, member));

        if (memberCount > 10) {
            var gitLab = providers
                    .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                    .orElseGet(() -> providers.saveAndFlush(
                            new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));
            author(gitLab, owner.account());
        }

        var at = Instant.now().minus(21, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceSlug", workspace.getWorkspaceSlug());
        params.put("groupSlug", group);
        params.put("practiceSlug", owner.practice().getSlug());
        params.put("connectionId", Long.toString(connectionId));
        params.put("id", Long.toString(connectionId));
        params.put("accountId", Long.toString(ownerId));
        params.put("login", authors.getFirst().getLogin());
        params.put("userId", Objects.requireNonNull(authors.getFirst().getId()).toString());
        var available = catalog.effectiveCatalog();
        available.installablePractices().stream()
                .findFirst()
                .ifPresent(practice -> params.put("catalogPracticeSlug", practice.slug()));
        available.groups().stream().findFirst().ifPresent(entry -> params.put("catalogGroupSlug", entry.slug()));
        params.put(
                "entryId", seedConversation(workspaceId, ownerId, memberCount).toString());

        List<LlmUsageEvent> calls = new ArrayList<>();
        for (int index = 0; index < workCount; index++) {
            int person = index % 2 == 0 ? 0 : 1 + index / 2 % (memberCount - 1);
            var openedAt = at.plusSeconds(index * 20L * 86400 / workCount);
            var author = authors.get(person);
            var pr = pull(repository, author, index + 1, openedAt);
            var eventId = UUID.randomUUID();
            activity.insertIfAbsent(
                    eventId,
                    "sweep-" + eventId,
                    ActivityEventType.PULL_REQUEST_OPENED.name(),
                    openedAt,
                    author.getId(),
                    workspaceId,
                    repository.getId(),
                    ActivityTargetType.PULL_REQUEST.getValue(),
                    pr.getId());
            var work = item(owner, ScmItemEvents.PULL_REQUEST, pr.getId(), parent.getId(), openedAt);
            var scenario = new Scenario(workspace, members.get(person), practices.get(index % practices.size()), work);

            @Nullable Result result = null;
            for (int run = 0; run < REVIEWS_PER_WORK; run++) {
                var recorded = openedAt.plusSeconds((run + 1) * 3600L);
                if (result != null) reviews.end(result, recorded);
                ResultOutcome outcome =
                        (run + index / 2) % 3 == 0 || index == 0 ? ResultOutcome.NOT_MET : ResultOutcome.MET;
                result = reviews.resultWithDeclaredSources(scenario, outcome, recorded);
                if (run == REVIEWS_PER_WORK - 1) {
                    calls.add(modelCall(result, attachReceipt(result)));
                } else {
                    attachItemCustody(result);
                }
                if (outcome == ResultOutcome.NOT_MET && person == 0) {
                    var feedback = feedback(result, index % 11 != 0 || index == 0);
                    if (index == 0 && run == REVIEWS_PER_WORK - 1) params.put("feedbackId", feedback.toString());
                }
            }
            if (index == 0) {
                var example = Objects.requireNonNull(result);
                params.put("itemId", work.getId().toString());
                params.put("reviewId", example.getReviewId().toString());
                params.put("resultId", example.getId().toString());
                params.put("htmlUrl", pr.getHtmlUrl());
                params.put(
                        "problemId",
                        Objects.requireNonNull(jdbc.queryForObject(
                                        "SELECT id FROM problem WHERE workspace_id=? AND result_id=?",
                                        UUID.class,
                                        workspaceId,
                                        example.getId()))
                                .toString());
            }
        }
        usage.saveAllAndFlush(calls);
        var scanScenario = scans.scenario();
        var scan = scans.scan(scanScenario, ReviewStatus.COMPLETE, Instant.now().minus(1, ChronoUnit.DAYS));
        scans.result(scanScenario, scan);
        params.put("scanWorkspaceSlug", scanScenario.owner().workspace().getWorkspaceSlug());
        params.put("scanId", scan.getId().toString());
        return new Fixture(
                Map.copyOf(params),
                token(owner.account()),
                token(admin),
                token(scanScenario.owner().account()));
    }

    private static PracticeDefinition definition(boolean readsRecords) {
        var original = DefinitionTestFixtures.definition();
        var kinds = new ArrayList<>(List.of("scm.pull-request.diff", "scm.repository.tree"));
        if (readsRecords) {
            kinds.addAll(List.of("scm.pull-request.core", "scm.pull-request.checks", "scm.review-threads"));
        }
        var sources = kinds.stream()
                .map(kind -> new PracticeReadSource(new SourceKind(kind), ReadNeed.REQUIRED))
                .toList();
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                original.about(),
                new PracticeReads(original.reads().contract(), sources),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
    }

    private String token(Account account) {
        return issuer.issue(
                        Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private long connect(Workspace workspace) {
        long workspaceId = workspace.getId();
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "sweep-" + workspaceId,
                new ConnectionConfig.GitHubAppConfig(workspaceId, workspace.getAccountLogin(), null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        return Objects.requireNonNull(connections.saveAndFlush(connection).getId());
    }

    private void seedOtherWorkspaces(long ownerId, int count) {
        for (int index = 0; index < count; index++) {
            var workspace =
                    workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("read-cost-" + UUID.randomUUID()));
            var member = new Membership();
            member.setId(new Membership.Id(workspace.getId(), ownerId));
            member.setWorkspace(workspace);
            member.setRole(Membership.WorkspaceRole.MEMBER);
            memberships.saveAndFlush(member);
            connect(workspace);
        }
    }

    private UUID seedConversation(long workspaceId, long accountId, int entriesPerConversation) {
        var main = conversations.findOrCreateMain(workspaceId, accountId);
        @Nullable UUID lastParent = null;
        for (int index = 0; index < entriesPerConversation; index++) {
            var parent =
                    entries.append(workspaceId, main.getId(), ConversationLedgerFixtures.hephaestus("Answer " + index));
            var thread = conversations.openThread(workspaceId, parent.getId());
            entries.append(workspaceId, thread.getId(), ConversationLedgerFixtures.hephaestus("Follow-up " + index));
            lastParent = parent.getId();
        }
        return Objects.requireNonNull(lastParent);
    }

    private Repository repository(Scenario owner, IdentityProvider provider) {
        var repository = new Repository();
        repository.setNativeId(800_000L + owner.workspace().getId());
        repository.setProvider(provider);
        repository.setName("sweep");
        repository.setNameWithOwner(owner.workspace().getAccountLogin() + "/sweep");
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                owner.workspace(), repository.getNameWithOwner(), repository.getNativeId()));
        return repository;
    }

    private User author(IdentityProvider provider, Account member) {
        long id = Objects.requireNonNull(member.getId());
        var user = new User();
        user.setProvider(provider);
        user.setNativeId(900_000L + id);
        user.setLogin("sweep-" + id);
        user.setName("Sweep developer " + id);
        user.setAvatarUrl("https://example.test/avatar/" + id);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setHtmlUrl(provider.getServerUrl() + "/" + user.getLogin());
        user = users.saveAndFlush(user);
        var identity = new Identity();
        identity.setAccount(member);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(user.getNativeId().toString());
        identities.saveAndFlush(identity);
        return user;
    }

    private PullRequest pull(Repository repository, User author, int number, Instant at) {
        var pull = new PullRequest();
        pull.setNativeId(repository.getNativeId() * 1000 + number);
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setAuthor(author);
        pull.setNumber(number);
        pull.setTitle("Change " + number);
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
        pull.setCreatedAt(at);
        pull.setUpdatedAt(at);
        pull.setHeadRefName("change-" + number);
        pull.setBaseRefName("main");
        pull.setHeadRefOid(String.format("%040x", number + 1));
        pull.setBaseRefOid(String.format("%040x", 1));
        return pulls.saveAndFlush(pull);
    }

    private Item item(Scenario owner, ItemKind kind, long source, @Nullable Long parent, Instant at) {
        var item = new Item();
        item.setWorkspaceId(owner.workspace().getId());
        item.setKind(kind);
        item.setSourceId(source);
        item.setParentId(parent);
        item.setCreatedAt(at);
        return items.saveAndFlush(item);
    }

    private UUID attachReceipt(Result result) {
        var job = reviews.job(result);
        // Only capture may normally bind a terminal result to its producing job.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE review_item SET job_id=? WHERE workspace_id=? AND result_id=?",
                    job,
                    result.getWorkspaceId(),
                    result.getId());
            jdbc.update("""
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                VALUES (?,?,?,'READY', '{"dependencies":"COMPLETE","identities":[],"repositories":[],"copiedJobs":[]}')
                """, UUID.randomUUID(), result.getWorkspaceId(), job);
        });
        return job;
    }

    private static LlmUsageEvent modelCall(Result result, UUID job) {
        var call = new LlmUsageEvent();
        call.setId(UUID.randomUUID());
        call.setWorkspaceId(result.getWorkspaceId());
        call.setJobType("PRACTICE_REVIEW");
        call.setSourceType(UsageSourceType.REVIEW_JOB);
        call.setSourceId(job);
        call.setPricingState(UsagePricingState.PRICED);
        call.setInitialPricingState(UsagePricingState.PRICED);
        call.setCostUsd(new BigDecimal("0.02"));
        call.setOccurredAt(result.getRecordedAt());
        call.setStartedAt(result.getRecordedAt());
        return call;
    }

    private void attachItemCustody(Result result) {
        var detail = json.createObjectNode();
        detail.set("custody", json.valueToTree(new HistoryCustody(true, false, List.of(), List.of(), List.of())));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE review_item SET detail=detail||CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                    detail.toString(),
                    result.getWorkspaceId(),
                    result.getId());
        });
    }

    private UUID feedback(Result result, boolean delivered) {
        var id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,delivered_at)
                VALUES (?,?,?,?,?,'PRACTICE_PAGE',?,'Keep each change focused.',?,?)
                """,
                id,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getItemId(),
                result.getDeveloperId(),
                delivered ? "DELIVERED" : "READY",
                Timestamp.from(result.getRecordedAt()),
                delivered ? Timestamp.from(result.getRecordedAt()) : null);
        jdbc.update(
                """
                INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,person_account_id,ordinal)
                VALUES (?,?,?,?,?,0)
                """,
                FeedbackIds.basis(id, result.getId(), null),
                result.getWorkspaceId(),
                id,
                result.getId(),
                result.getDeveloperId());
        return id;
    }

    record Fixture(Map<String, String> params, String memberToken, String adminToken, String scanToken) {
        String token(String path) {
            if (path.startsWith("/admin/")) return adminToken;
            return path.contains("{scanWorkspaceSlug}") ? scanToken : memberToken;
        }

        boolean supports(String path) {
            if (!path.startsWith("/workspaces/") && path.contains("{id}")) return false;
            return VARIABLE.matcher(path).results().allMatch(match -> params.containsKey(match.group(1)));
        }

        String uri(String path) {
            return VARIABLE.matcher(path)
                    .replaceAll(match -> Matcher.quoteReplacement(Objects.requireNonNull(params.get(match.group(1)))));
        }
    }
}
