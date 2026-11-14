package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.RepositoryExcludedFromWorkspacesEvent;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator.InstallationRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositoryIdentityLookup;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ManualRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryExcludedFromWorkspacesException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.support.ResourceDocumentSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Runs an org-wide installation that covers the private repository, and reads what each workspace still holds. */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class PrivateDestinationOutOfWorkspacesIntegrationTest extends BaseIntegrationTest {
    private static final long INSTALLATION = 7714L;
    private static final long OTHER_INSTALLATION = 7715L;
    private static final String PUBLIC = "acme/product";
    private static final String PRIVATE = "acme/reports";
    private static final String NEXT_PRIVATE = "acme/reports-two";
    private static final long PUBLIC_ID = 8101L;
    private static final long PRIVATE_ID = 8102L;
    private static final long NEXT_PRIVATE_ID = 8103L;

    @MockitoBean
    private ProductFeedbackIssues issues;

    @MockitoBean
    private ProductFeedbackIssueUpdates updates;

    @Autowired
    private DestinationService destinations;

    @Autowired
    private ProductFeedbackDestinationRepository destinationRows;

    @Autowired
    private WorkspaceRepositoryMonitorService monitors;

    @Autowired
    private ManualRepositoryMonitorService manualMonitors;

    @MockitoSpyBean
    private GitHubRepositoryIdentityLookup githubIdentities;

    @Autowired
    private StubInstallationRepositoryEnumerator enumerator;

    @Autowired
    private RepositoryScopeFilter scopeFilter;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RepositoryToMonitorRepository monitorRows;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private WorkspaceSyncTargetProvider syncTargets;

    @Autowired
    private FeedbackService feedback;

    @Autowired
    private MyReportsService reports;

    @Autowired
    private FollowService follow;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private long admin;

    @BeforeEach
    void orgWideInstallationsCoverEveryRepository() {
        databaseTestUtils.cleanDatabase();
        enumerator.reset();
        if (providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .isEmpty()) {
            providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com"));
        }
        admin = newAccount("An admin");
        when(issues.repository(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            boolean isPrivate = !PUBLIC.equals(name);
            return Optional.of(new ProductFeedbackIssues.Repository(1L, "node", name, INSTALLATION, isPrivate));
        });
        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(INSTALLATION, "acme")
                        .withSlug("acme"),
                INSTALLATION);
        otherWorkspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(OTHER_INSTALLATION, "acme-two")
                        .withSlug("acme-two"),
                OTHER_INSTALLATION);
        enumerator.returns(List.of(
                repository(PUBLIC_ID, PUBLIC, false),
                repository(PRIVATE_ID, PRIVATE, true),
                repository(NEXT_PRIVATE_ID, NEXT_PRIVATE, true)));
        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, null, true);
        monitors.ensureAllInstallationRepositoriesCovered(OTHER_INSTALLATION, null, true);
    }

    private static InstallationRepository repository(long id, String name, boolean isPrivate) {
        return new InstallationRepository(id, name, name.substring(name.indexOf('/') + 1), isPrivate);
    }

    private long newAccount(String name) {
        var account = new Account(name);
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        return Objects.requireNonNull(accounts.save(account).getId());
    }

    private long number(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private long repositoryId(long nativeId) {
        return number("SELECT id FROM repository WHERE native_id = ?", nativeId);
    }

    private long monitorsOf(String name) {
        return number("SELECT COUNT(*) FROM repository_to_monitor WHERE LOWER(name_with_owner) = LOWER(?)", name);
    }

    /** What a workspace held of a repository before: an issue, its item with retained words, and Activity. */
    private long heldIn(Workspace holder, long nativeId, long issueId) {
        long repository = repositoryId(nativeId);
        jdbc.update(
                "INSERT INTO issue(id, issue_type, comments_count, is_locked, number, native_id, provider_id, repository_id,"
                        + " title, body) SELECT ?, 'ISSUE', 0, false, 1, ?, provider_id, id, 'A report', 'The words' FROM repository"
                        + " WHERE id = ? ON CONFLICT DO NOTHING",
                issueId,
                issueId,
                repository);
        long parent = number(
                "SELECT id FROM item WHERE workspace_id = ? AND kind = 'scm.repository' AND source_id = ?",
                holder.getId(),
                repository);
        long item = number(
                "INSERT INTO item(workspace_id, kind, parent_id, source_id, created_at) VALUES (?, 'scm.issue', ?, ?, now())"
                        + " RETURNING id",
                holder.getId(),
                parent,
                issueId);
        jdbc.update(
                "INSERT INTO item_event(id, workspace_id, item_id, name, occurred_at, found_by, decided_at, item_version,"
                        + " evidence, evidence_expires_at) VALUES (?, ?, ?, 'scm.issue.opened', now(), 'WEBHOOK', now(),"
                        + " 'v1', '{\"body\":\"The words\"}'::jsonb, now() + interval '1 day')",
                UUID.randomUUID(),
                holder.getId(),
                item);
        jdbc.update(
                "INSERT INTO activity_event(id, event_key, event_type, occurred_at, workspace_id, repository_id, ingested_at)"
                        + " VALUES (?, ?, 'ISSUE_CREATED', now(), ?, ?, now())",
                UUID.randomUUID(),
                "issue:" + issueId + ":" + holder.getId(),
                holder.getId(),
                repository);
        return item;
    }

    private long activityOf(Workspace holder) {
        return number("SELECT COUNT(*) FROM activity_event WHERE workspace_id = ?", holder.getId());
    }

    private void save(String privateRepository) {
        destinations.update(
                new UpdateProductFeedbackDestinationsDTO(PUBLIC, privateRepository, null, 50, false, false), admin);
    }

    @Test
    void savingThePrivateRepositoryEmptiesItFromEveryWorkspaceAndLeavesTheirOtherWork() {
        long item = heldIn(workspace, PRIVATE_ID, 91L);
        long otherItem = heldIn(otherWorkspace, PRIVATE_ID, 91L);
        long keptItem = heldIn(workspace, PUBLIC_ID, 92L);

        save(PRIVATE);

        assertThat(monitorsOf(PRIVATE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM repository WHERE native_id = ?", PRIVATE_ID))
                .isZero();
        assertThat(number("SELECT COUNT(*) FROM issue WHERE id = 91")).isZero();
        assertThat(activityOf(otherWorkspace)).isZero();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id IN (?, ?) AND removed_at IS NOT NULL", item, otherItem))
                .isEqualTo(2);
        assertThat(number(
                        "SELECT COUNT(*) FROM item_event WHERE item_id IN (?, ?) AND evidence::text LIKE '%The words%'",
                        item, otherItem))
                .isZero();

        assertThat(monitorsOf(PUBLIC)).isEqualTo(2);
        assertThat(activityOf(workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT removed_at IS NULL FROM item WHERE id = ?", Boolean.class, keptItem))
                .isTrue();
        assertThat(number(
                        "SELECT COUNT(*) FROM item_event WHERE item_id = ? AND evidence::text LIKE '%The words%'",
                        keptItem))
                .isEqualTo(1);
    }

    @Test
    void aMonitorFromBeforeTheSettingIsRemovedByTheRepairAndEveryRunAfterItChangesNothing() {
        heldIn(workspace, PRIVATE_ID, 91L);
        var destination = new ProductFeedbackDestination();
        destination.setPublicRepository(PUBLIC);
        destination.setPrivateRepository(PRIVATE);
        destinationRows.save(destination);
        assertThat(monitorsOf(PRIVATE)).isEqualTo(2);

        destinations.keepPrivateRepositoryOutOfWorkspaces();
        destinations.keepPrivateRepositoryOutOfWorkspaces();

        assertThat(monitorsOf(PRIVATE)).isZero();
        assertThat(activityOf(workspace)).isZero();
        assertThat(monitorsOf(PUBLIC)).isEqualTo(2);
    }

    /** Every number that a repair must leave alone once the repository is out. */
    private List<Long> rowCounts() {
        return List.of(
                monitorsOf(PRIVATE),
                monitorsOf(PUBLIC),
                activityOf(workspace),
                activityOf(otherWorkspace),
                number("SELECT COUNT(*) FROM repository"),
                number("SELECT COUNT(*) FROM issue"),
                number("SELECT COUNT(*) FROM item WHERE removed_at IS NULL"),
                number("SELECT COUNT(*) FROM item_event"));
    }

    @Test
    void aRepairRunAfterTheFirstChangesNoRow() {
        heldIn(workspace, PRIVATE_ID, 91L);
        heldIn(workspace, PUBLIC_ID, 92L);
        save(PRIVATE);
        var after = rowCounts();

        destinations.keepPrivateRepositoryOutOfWorkspaces();
        destinations.keepPrivateRepositoryOutOfWorkspaces();

        assertThat(rowCounts()).isEqualTo(after);
    }

    @Test
    void shouldRemoveWhatAWorkspaceKeptWhenItStoppedMonitoringBeforeTheSetting() {
        long item = heldIn(workspace, PRIVATE_ID, 91L);
        monitors.removeRepositoryMonitorForInstallation(INSTALLATION, PRIVATE, PRIVATE_ID);
        // Stopping keeps the history; only the repository item goes.
        assertThat(number(
                        "SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id = ? AND native_id = ?",
                        workspace.getId(),
                        PRIVATE_ID))
                .isZero();
        assertThat(activityOf(workspace)).isOne();
        assertThat(jdbc.queryForObject("SELECT removed_at IS NULL FROM item WHERE id = ?", Boolean.class, item))
                .isTrue();

        save(PRIVATE);

        assertThat(activityOf(workspace)).isZero();
        assertThat(jdbc.queryForObject("SELECT removed_at IS NOT NULL FROM item WHERE id = ?", Boolean.class, item))
                .isTrue();
        assertThat(number(
                        "SELECT COUNT(*) FROM item_event WHERE item_id = ? AND evidence::text LIKE '%The words%'",
                        item))
                .isZero();
        assertThat(monitorsOf(PRIVATE)).isZero();
        // The repair's scheduler lock would skip a run in the next test, so the event is published directly.
        var after = rowCounts();
        events.publishEvent(new RepositoryExcludedFromWorkspacesEvent(PRIVATE));
        assertThat(rowCounts()).isEqualTo(after);
    }

    @Test
    void aFiledReportKeepsItsFilingItsViewAndItsFollowUpWhenTheMirrorRowsGo() {
        long reporter = newAccount("A reporter");
        var destination = new ProductFeedbackDestination();
        destination.setPublicRepository(PUBLIC);
        destinationRows.save(destination);
        UUID report = feedback.add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "The list jumps", null, null, false),
                        reporter,
                        null,
                        false)
                .id();
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'FILED', route = 'PRIVATE', issue_number = 91, issue_url = ?,"
                        + " issue_node_id = 'I_91', repository = ?, repository_id = 1, repository_node_id = 'node',"
                        + " issue_open = true, followed_at = NULL WHERE feedback_id = ?",
                "https://github.com/" + PRIVATE + "/issues/91",
                PRIVATE,
                report);
        heldIn(workspace, PRIVATE_ID, 91L);
        when(updates.states(any()))
                .thenReturn(List.of(new ProductFeedbackIssues.IssueState("I_91", false, "completed", List.of(), null)));

        save(PRIVATE);
        assertThat(number("SELECT COUNT(*) FROM issue WHERE id = 91")).isZero();
        follow.followDue();

        var seen = reports.mine(reporter, false).stream()
                .filter(candidate -> candidate.id().equals(report))
                .findFirst()
                .orElseThrow();
        assertThat(seen.message()).isEqualTo("The list jumps");
        assertThat(seen.issueClosedAs()).isEqualTo("completed");
        assertThat(filings.findById(report).orElseThrow().getState()).isEqualTo(FilingState.FILED);
    }

    @Test
    void anOrgWideInstallationNeverBringsItBackAndARepositoryInAnyCaseCounts() {
        save("ACME/Reports");

        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, List.of(PRIVATE), true);

        assertThat(monitorsOf(PRIVATE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM repository WHERE native_id = ?", PRIVATE_ID))
                .isZero();
    }

    @Test
    void aMonitorThatEnumerationFindsFirstLeavesWithItsHistory() {
        heldIn(workspace, PRIVATE_ID, 91L);
        var destination = new ProductFeedbackDestination();
        destination.setPrivateRepository(PRIVATE);
        destinationRows.save(destination);

        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, null, true);

        assertThat(number(
                        "SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id = ? AND name_with_owner = ?",
                        workspace.getId(),
                        PRIVATE))
                .isZero();
        assertThat(activityOf(workspace)).isZero();
    }

    @Test
    void enumerationWithdrawsARenamedPrivateRepositoryWithItsHistory() {
        long item = heldIn(workspace, PRIVATE_ID, 91L);
        long repository = repositoryId(PRIVATE_ID);
        long parent = number("SELECT parent_id FROM item WHERE id=?", item);
        jdbc.update(
                "UPDATE repository_to_monitor SET name_with_owner = 'acme/old-reports'"
                        + " WHERE workspace_id = ? AND native_id = ?",
                workspace.getId(),
                PRIVATE_ID);
        save(PRIVATE);
        assertThat(monitorsOf("acme/old-reports")).isEqualTo(1);

        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, List.of("acme/old-reports"), true);

        assertThat(monitorsOf("acme/old-reports")).isZero();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id=? AND removed_at IS NOT NULL", parent))
                .isOne();
        assertThat(number("SELECT COUNT(*) FROM repository WHERE id=?", repository))
                .isZero();
        assertThat(activityOf(workspace)).isZero();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id = ? AND removed_at IS NOT NULL", item))
                .isEqualTo(1);
        assertThat(number(
                        "SELECT COUNT(*) FROM item_event WHERE item_id = ? AND evidence::text LIKE '%The words%'",
                        item))
                .isZero();
    }

    @Test
    void repositorySyncWithdrawsARenamedPrivateRepositoryWithItsHistory() {
        long item = heldIn(workspace, PRIVATE_ID, 91L);
        jdbc.update("UPDATE repository SET name_with_owner = 'acme/old-reports' WHERE native_id = ?", PRIVATE_ID);
        jdbc.update(
                "UPDATE repository_to_monitor SET name_with_owner = 'acme/old-reports'"
                        + " WHERE workspace_id = ? AND native_id = ?",
                workspace.getId(),
                PRIVATE_ID);
        save(PRIVATE);
        var clients = mock(GitHubGraphQlClientProvider.class);
        var webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {"data":{"repository":{"databaseId":8102,"name":"reports",
                                "nameWithOwner":"acme/reports"}}}
                                """)
                        .build()))
                .build();
        when(clients.forScope(workspace.getId()))
                .thenReturn(HttpGraphQlClient.builder(webClient)
                        .codecConfigurer(codecs -> {
                            var mapper = GitHubGraphQlTestMapper.create();
                            codecs.customCodecs().register(new JacksonJsonEncoder(mapper));
                            codecs.customCodecs().register(new JacksonJsonDecoder(mapper));
                        })
                        .documentSource(new ResourceDocumentSource(new ClassPathResource("graphql/github/operations/")))
                        .build());
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        var service = new GitHubRepositorySyncService(
                clients,
                repositories,
                organizations,
                properties,
                new GitHubExceptionClassifier(new SimpleMeterRegistry()),
                mock(GitHubGraphQlSyncCoordinator.class),
                webClient,
                transactions,
                List.of(scopeFilter),
                List.of(syncTargets));
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();

        assertThat(service.syncRepository(workspace.getId(), "acme/old-reports", provider, PRIVATE_ID))
                .isEmpty();

        assertThat(monitorsOf("acme/old-reports")).isZero();
        assertThat(monitorsOf(PRIVATE)).isZero();
        assertThat(activityOf(workspace)).isZero();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id = ? AND removed_at IS NOT NULL", item))
                .isEqualTo(1);
        assertThat(number(
                        "SELECT COUNT(*) FROM item_event WHERE item_id = ? AND evidence::text LIKE '%The words%'",
                        item))
                .isZero();
    }

    @Test
    void theWebhookFilterRefusesIt() {
        save(PRIVATE);

        assertThat(scopeFilter.isRepositoryAllowed(PRIVATE)).isFalse();
        assertThat(scopeFilter.isRepositoryAllowed(PUBLIC)).isTrue();
    }

    @Test
    void aManualAddIsRefusedInWordsThatDoNotSayWhatTheRepositoryHolds() {
        save(PRIVATE);

        assertThatThrownBy(() -> manualMonitors.add(workspace, PRIVATE))
                .isInstanceOfSatisfying(RepositoryExcludedFromWorkspacesException.class, refused -> {
                    assertThat(refused.getBody().getDetail()).contains("out of workspaces");
                    assertThat(refused.getBody().getDetail()).doesNotContainIgnoringCase("feedback");
                });
        assertThat(monitorsOf(PRIVATE)).isZero();
    }

    @Test
    void shouldRefuseAManualAddWhenTheDestinationChangesDuringItsLookup() {
        Workspace manual = workspaces.save(WorkspaceTestFixtures.activeWorkspace("manual-private"));
        long manualId = Objects.requireNonNull(manual.getId());
        var connection = new Connection(
                manual,
                IntegrationKind.GITHUB,
                "manual-private",
                new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.save(connection);
        doAnswer(call -> {
                    save(PRIVATE);
                    return Optional.of(new RepositoryIdentity(PRIVATE_ID, PRIVATE));
                })
                .when(githubIdentities)
                .resolve(manualId, PRIVATE);

        assertThatThrownBy(() -> manualMonitors.add(manual, PRIVATE))
                .isInstanceOf(RepositoryExcludedFromWorkspacesException.class);
        assertThat(number("SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id=?", manual.getId()))
                .isZero();
        assertThat(destinationRows
                        .findById(ProductFeedbackDestination.SINGLETON)
                        .orElseThrow()
                        .getPrivateRepository())
                .isEqualTo(PRIVATE);
    }

    @Test
    void shouldWithdrawOnlyAfterTheExclusionEventCommits() {
        long item = heldIn(workspace, PRIVATE_ID, 91L);
        var destination = new ProductFeedbackDestination();
        destination.setPublicRepository(PUBLIC);
        destination.setPrivateRepository(PRIVATE);
        destinationRows.save(destination);
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new RepositoryExcludedFromWorkspacesEvent(PRIVATE));
            assertThat(monitorsOf(PRIVATE)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(monitorsOf(PRIVATE)).isEqualTo(2);
        assertThat(activityOf(workspace)).isOne();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id=? AND removed_at IS NULL", item))
                .isOne();

        transactions.executeWithoutResult(status -> {
            events.publishEvent(new RepositoryExcludedFromWorkspacesEvent(PRIVATE));
            assertThat(monitorsOf(PRIVATE)).isEqualTo(2);
        });

        assertThat(monitorsOf(PRIVATE)).isZero();
        assertThat(activityOf(workspace)).isZero();
        assertThat(number("SELECT COUNT(*) FROM item WHERE id=? AND removed_at IS NOT NULL", item))
                .isOne();
    }

    @Test
    void aReplacedPrivateRepositoryCanBeMonitoredAgainAndTheNewOneLeaves() {
        save(PRIVATE);

        save(NEXT_PRIVATE);
        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, null, true);

        assertThat(scopeFilter.isRepositoryAllowed(PRIVATE)).isTrue();
        assertThat(monitorsOf(PRIVATE)).isEqualTo(1);
        assertThat(monitorsOf(NEXT_PRIVATE)).isZero();
    }

    @Test
    void anUninstalledGitLabConnectionDoesNotKeepAGitHubMonitor() {
        var leftover = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "https://gitlab.example.org",
                new ConnectionConfig.GitLabConfig("https://gitlab.example.org", 42L, null, Set.of(), null));
        ReflectionTestUtils.setField(leftover, "state", IntegrationState.UNINSTALLED);
        connections.save(leftover);

        save(PRIVATE);

        assertThat(number(
                        "SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id = ? AND name_with_owner = ?",
                        workspace.getId(),
                        PRIVATE))
                .isZero();
    }

    @Test
    void aStaleWithdrawalLeavesARepositoryThatIsNoLongerTheDestination() {
        save(PRIVATE);
        save(NEXT_PRIVATE);
        monitors.ensureAllInstallationRepositoriesCovered(INSTALLATION, null, true);
        assertThat(monitorsOf(PRIVATE)).isEqualTo(1);

        // A repair that read the old destination before the replacement, and runs after it.
        events.publishEvent(new RepositoryExcludedFromWorkspacesEvent(PRIVATE));

        assertThat(monitorsOf(PRIVATE)).isEqualTo(1);
    }

    @Test
    void aRenameThatResolvesToThePrivateRepositoryTakesItOut() {
        // A monitor stored under the name the repository had before a rename that no event reported.
        jdbc.update(
                "UPDATE repository_to_monitor SET name_with_owner = 'acme/old-reports' WHERE workspace_id = ?"
                        + " AND name_with_owner = ?",
                workspace.getId(),
                PRIVATE);
        save(PRIVATE);
        assertThat(monitorsOf("acme/old-reports")).isEqualTo(1);
        long provider = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow()
                .getId());

        syncTargets.reconcileSyncTargetsForRepository(provider, PRIVATE_ID, PRIVATE);

        assertThat(monitorsOf("acme/old-reports")).isZero();
        assertThat(monitorsOf(PRIVATE)).isZero();
    }

    @Test
    void aSuspendedGitLabWorkspaceKeepsItsProjectOnTheSamePath() {
        var gitLab = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace("acme"),
                "https://gitlab.example.org");
        monitorRows.save(WorkspaceTestFixtures.repositoryMonitor(gitLab, PRIVATE, 5L));
        jdbc.update("UPDATE connection SET state = 'SUSPENDED' WHERE workspace_id = ?", gitLab.getId());

        save(PRIVATE);

        assertThat(number("SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id = ?", gitLab.getId()))
                .isEqualTo(1);
    }

    @Test
    void aGitLabProjectOnTheSamePathStaysWithItsWorkspace() {
        var gitLab = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace("acme"),
                "https://gitlab.example.org");
        monitorRows.save(WorkspaceTestFixtures.repositoryMonitor(gitLab, PRIVATE, 5L));

        save(PRIVATE);

        assertThat(syncTargets.getSyncTargetsForScope(gitLab.getId())).isNotEmpty();
        assertThat(number("SELECT COUNT(*) FROM repository_to_monitor WHERE workspace_id = ?", gitLab.getId()))
                .isEqualTo(1);
        assertThat(monitorsOf(PRIVATE)).isEqualTo(1);
    }
}
