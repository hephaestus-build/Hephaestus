package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.activity.ActivityAutomation;
import de.tum.cit.aet.hephaestus.activity.ActivityAutomationRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityActionDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO.ReviewerState;
import de.tum.cit.aet.hephaestus.activity.overview.dto.TeamRefDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer.ReviewState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermissionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The Activity read model against real rows: what counts, whose activity a scope covers, and what is open for
 * one member. Every range but the default one is pinned so a slow run cannot move an event across a boundary.
 */
@WithMentorUser
class ActivityControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2026-02-01T00:00:00Z";
    private static final Instant DAY = Instant.parse("2026-01-10T12:00:00Z");

    @Autowired
    private IdentityLinkRepository publicIdentities;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private ActivityAutomationRepository automationRepository;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private TeamRepositoryPermissionRepository teamRepositoryPermissionRepository;

    @Autowired
    private WorkspaceTeamSettingsService teamSettingsService;

    private final AtomicLong nativeIds = new AtomicLong(770_000);

    private Workspace workspace;
    private Repository monitored;
    private Repository unmonitored;

    /** Logs in as {@code z-ada} but is named Ada, so ordering by name and by login disagree. */
    private User ada;

    /** Logs in as {@code a-zoe} but is named Zoe. */
    private User zoe;

    @BeforeEach
    void seedWorkspace() {
        User caller = persistUser("mentor");
        User owner = persistUser("activity-owner");
        workspace = createWorkspace("activity-workspace", "Activity", "activity-org", AccountType.ORG, owner);
        ensureWorkspaceMembership(workspace, caller, WorkspaceRole.MEMBER);
        ada = member("z-ada", "Ada");
        zoe = member("a-zoe", "Zoe");
        monitored = repository("activity-org/widgets", true);
        unmonitored = repository("activity-org/elsewhere", false);
    }

    @Autowired
    private ActivityPeopleService peopleService;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private ActivityAutomationService automationService;

    @Autowired
    private PublicActivityObjectionService publicObjections;

    @Nested
    class PublicActivity {
        @BeforeEach
        void publish() {
            workspace.setPublicActivityEnabled(true);
            workspaceRepository.save(workspace);
            monitored.setVisibility(Repository.Visibility.PUBLIC);
            monitored.setPrivate(false);
            repositoryRepository.save(monitored);
            jdbc.update(
                    "INSERT INTO instance_settings (id, silent_mode_engaged, version, public_activity_allowed) VALUES (1,true,0,true) ON CONFLICT (id) DO UPDATE SET public_activity_allowed=true");
        }

        @Test
        void shouldReturnTheSameErrorForUnknownOffAndInactiveWorkspaces() {
            var unknown = error("unknown");
            workspace.setPublicActivityEnabled(false);
            workspaceRepository.save(workspace);
            assertThat(error(workspace.getWorkspaceSlug())).isEqualTo(unknown);
            workspace.setPublicActivityEnabled(true);
            workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
            workspaceRepository.save(workspace);
            assertThat(error(workspace.getWorkspaceSlug())).isEqualTo(unknown);
        }

        @Test
        void shouldIncludeOutsideHumansButNotPrivateRepositoriesCommentsAutomationOrOtherWorkspaces() {
            var outside = persistUser("public-outside");
            record(outside, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1, DAY);
            record(ada, ActivityEventType.COMMENT_CREATED, ActivityTargetType.ISSUE_COMMENT, -2, DAY);
            var privateRepo = repository("activity-org/private", true);
            privateRepo.setVisibility(Repository.Visibility.PRIVATE);
            privateRepo.setPrivate(true);
            repositoryRepository.save(privateRepo);
            record(zoe, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, -3, DAY, privateRepo);
            var other =
                    createWorkspace("other-public", "Other", "other-org", AccountType.ORG, persistUser("other-owner"));
            var sharedMonitor = new RepositoryToMonitor();
            sharedMonitor.setWorkspace(other);
            sharedMonitor.setNameWithOwner(monitored.getNameWithOwner());
            repositoryToMonitorRepository.save(sharedMonitor);
            UUID otherEvent = UUID.randomUUID();
            activityEventRepository.insertIfAbsent(
                    otherEvent,
                    "other-" + otherEvent,
                    "ISSUE_CREATED",
                    DAY,
                    zoe.getId(),
                    other.getId(),
                    monitored.getId(),
                    "issue",
                    -10L);
            var bot = persistUser("public-bot");
            bot.setType(User.Type.BOT);
            userRepository.save(bot);
            record(bot, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -11, DAY);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -12, DAY);
            automationService.classify(workspace.getId(), ada.getId(), true);
            var result = page();
            assertThat(result.people()).extracting(p -> p.login()).containsExactly(outside.getLogin());
            assertThat(result.people().getFirst().counts().contributions()).isEqualTo(1);
            assertThat(result.repositories()).extracting(r -> r.key()).containsExactly(monitored.getNameWithOwner());
            assertThat(result.from()).isEqualTo(DAY);
            monitored.setPrivate(true);
            repositoryRepository.save(monitored);
            assertThat(page().people()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(IdentityProviderType.class)
        void shouldPublishOnlyGitHubAndGitLabActivity(IdentityProviderType type) {
            var provider =
                    gitProviderRepository.save(new IdentityProvider(type, "https://public-" + type + ".example"));
            monitored.setProvider(provider);
            repositoryRepository.save(monitored);
            ada.setProvider(provider);
            userRepository.save(ada);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1, DAY);
            var result = page();
            boolean scm = type == IdentityProviderType.GITHUB || type == IdentityProviderType.GITLAB;
            assertThat(result.people()).hasSize(scm ? 1 : 0);
            assertThat(result.repositories()).hasSize(scm ? 1 : 0);
            assertThat(result.coverage().totalRepositories()).isEqualTo(scm ? 1 : 0);
        }

        @Test
        void shouldApplyAccountOptOutAndWorkspaceHideToRowsAndTotals() {
            TestUserFactory.ensureAccountForUser(accountRepository, publicIdentities, ada);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1, DAY);
            record(zoe, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -2, DAY);
            jdbc.update(
                    "UPDATE account SET public_activity_visible=false WHERE id IN (SELECT account_id FROM identity_link WHERE provider_id=? AND subject=?)",
                    ada.getProvider().getId(),
                    ada.getNativeId().toString());
            workspaceMembershipService.updateMemberVisibility(workspace.getId(), zoe.getId(), true);
            assertThat(page().people()).isEmpty();
            jdbc.update(
                    "UPDATE account SET public_activity_visible=true WHERE id IN (SELECT account_id FROM identity_link WHERE provider_id=? AND subject=?)",
                    ada.getProvider().getId(),
                    ada.getNativeId().toString());
            assertThat(page().people()).extracting(p -> p.login()).containsExactly(ada.getLogin());
        }

        @Test
        void shouldComputeFirstContributionCoverageAndPeopleHelpedWithinThePublicScope() {
            TestUserFactory.ensureAccountForUser(accountRepository, publicIdentities, ada);
            var privateRepo = repository("activity-org/earlier", true);
            privateRepo.setVisibility(Repository.Visibility.PRIVATE);
            privateRepo.setPrivate(true);
            repositoryRepository.save(privateRepo);
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -1,
                    DAY.minus(Duration.ofDays(365)),
                    privateRepo);
            var pull = pullRequest(ada, monitored, pr -> pr);
            record(ada, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, pull.getId(), DAY);
            var review = review(pull, zoe, PullRequestReview.State.APPROVED);
            record(zoe, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, review.getId(), DAY);
            var before = page();
            assertThat(before.people())
                    .filteredOn(p -> p.id().equals(ada.getId()))
                    .extracting(p -> p.firstContributionAt())
                    .containsExactly(DAY);
            assertThat(before.coverage().totalRepositories()).isEqualTo(1);
            jdbc.update(
                    "UPDATE account SET public_activity_visible=false WHERE id IN (SELECT account_id FROM identity_link WHERE provider_id=? AND subject=?)",
                    ada.getProvider().getId(),
                    ada.getNativeId().toString());
            var after = page();
            assertThat(after.people()).extracting(p -> p.login()).containsExactly(zoe.getLogin());
            assertThat(after.people().getFirst().counts().peopleHelped()).isZero();
        }

        @Test
        void shouldExcludeSuppressedPeopleEvenWhenTheirLedgerRowsRemain() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1, DAY);
            jdbc.update(
                    "INSERT INTO person_suppression (id,provider_id,subject,team_key) VALUES (?,?,?,'')",
                    UUID.randomUUID(),
                    ada.getProvider().getId(),
                    ada.getNativeId().toString());
            assertThat(page().people()).isEmpty();
            jdbc.update(
                    "UPDATE workspace_membership SET hidden=true WHERE workspace_id=? AND user_id=?",
                    workspace.getId(),
                    ada.getId());
            assertThat(publicObjections.hiddenPeople(workspace.getId())).isZero();
        }

        @Test
        void shouldKeepObjectionsReversibleWorkspaceScopedAndUnattributedToTheContributor() {
            var outside = persistUser("objecting-outside");
            record(outside, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1, DAY);
            publicObjections.hide(workspace.getId(), outside.getId(), true);
            ensureAdminMembership(workspace);
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/config-audit?entityType=WORKSPACE_VISIBILITY&changedKey=contributorHidden",
                            workspace.getWorkspaceSlug())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].entityId")
                    .isEqualTo(workspace.getId().toString())
                    .jsonPath("$.content[0].oldValue")
                    .isEqualTo("{\"contributorHidden\":false}")
                    .jsonPath("$.content[0].newValue")
                    .isEqualTo("{\"contributorHidden\":true}");
            assertThat(page().people()).isEmpty();
            assertThat(publicObjections.hiddenPeople(workspace.getId())).isEqualTo(1);
            var other = createWorkspace(
                    "objection-other", "Other", "other-objection-org", AccountType.ORG, persistUser("objection-owner"));
            assertThat(publicObjections.hiddenPeople(other.getId())).isZero();
            assertThatThrownBy(() -> publicObjections.hide(other.getId(), outside.getId(), true))
                    .isInstanceOf(EntityNotFoundException.class);
            publicObjections.hide(workspace.getId(), outside.getId(), false);
            assertThat(page().people()).extracting(p -> p.login()).containsExactly(outside.getLogin());
            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/activity/people/{id}/public-visibility?hidden=true",
                            workspace.getWorkspaceSlug(),
                            outside.getId())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/activity/public-hidden-count", workspace.getWorkspaceSlug())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.hiddenPeople")
                    .isEqualTo(1)
                    .jsonPath("$.length()")
                    .isEqualTo(1);
            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/activity/people/{id}/public-visibility?hidden=true",
                            workspace.getWorkspaceSlug(),
                            outside.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/activity/public-hidden-count", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        void shouldApplyTheAccountChoiceWithoutMarkingOtherWorkspaceOnboardingSeen() {
            var second = createWorkspace(
                    "public-second", "Second", "public-second-org", AccountType.ORG, persistUser("second-owner"));
            second.setPublicActivityEnabled(true);
            workspaceRepository.save(second);
            webTestClient
                    .get()
                    .uri("/user/public-activity/workspaces/public-second/onboarding")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.seen")
                    .isEqualTo(false)
                    .jsonPath("$.visible")
                    .isEqualTo(true);
            webTestClient
                    .put()
                    .uri("/user/public-activity/workspaces/public-second/onboarding")
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("visible", false))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.seen")
                    .isEqualTo(true)
                    .jsonPath("$.visible")
                    .isEqualTo(false);
            webTestClient
                    .get()
                    .uri("/user/public-activity/workspaces/{slug}/onboarding", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.seen")
                    .isEqualTo(false)
                    .jsonPath("$.visible")
                    .isEqualTo(false);
            webTestClient
                    .put()
                    .uri("/user/public-activity")
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("visible", true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.visible")
                    .isEqualTo(true);
            webTestClient
                    .get()
                    .uri("/user/public-activity/workspaces/public-second/onboarding")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.seen")
                    .isEqualTo(true)
                    .jsonPath("$.visible")
                    .isEqualTo(true);
            persistUser("testuser");
            webTestClient
                    .get()
                    .uri("/user/public-activity/workspaces/public-second/onboarding")
                    .headers(headers -> headers.setBearerAuth("testuser"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.seen")
                    .isEqualTo(false);
            webTestClient
                    .put()
                    .uri("/user/public-activity")
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of())
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/user/public-activity")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        void shouldReservePublicationChangesForAdministratorsAndApplyBothSwitchesImmediately() {
            ensureAdminMembership(workspace);
            webTestClient
                    .put()
                    .uri("/admin/settings/public-activity")
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("allowed", false))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/public-activity", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("publicActivityEnabled", false, "allowSearchEngines", false))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/public-activity", workspace.getWorkspaceSlug())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .bodyValue(Map.of("publicActivityEnabled", true, "allowSearchEngines", true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/public/workspaces/{slug}/activity", workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .doesNotExist("X-Robots-Tag")
                    .expectBody(Void.class);
            webTestClient
                    .put()
                    .uri("/admin/settings/public-activity")
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .bodyValue(Map.of("allowed", false))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            error(workspace.getWorkspaceSlug());
            webTestClient
                    .put()
                    .uri("/admin/settings/public-activity")
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .bodyValue(Map.of("allowed", true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/public-activity", workspace.getWorkspaceSlug())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .bodyValue(Map.of("publicActivityEnabled", false, "allowSearchEngines", false))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            error(workspace.getWorkspaceSlug());
        }

        @Test
        void shouldCacheOnlyAnonymousSuccessAndDefaultToNoIndex() {
            webTestClient
                    .get()
                    .uri("/public/workspaces/{slug}/activity", workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "max-age=60, public")
                    .expectHeader()
                    .doesNotExist("Set-Cookie")
                    .expectHeader()
                    .valueEquals("X-Robots-Tag", "noindex")
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/public/workspaces/{slug}/activity", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody(Void.class);
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/activity/people", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .value("Cache-Control", value -> assertThat(value).doesNotContain("public"))
                    .expectBody(Void.class);
            jdbc.update("UPDATE instance_settings SET public_activity_allowed=false WHERE id=1");
            error(workspace.getWorkspaceSlug());
        }

        private String error(String slug) {
            return Objects.requireNonNull(webTestClient
                    .get()
                    .uri("/public/workspaces/{slug}/activity", slug)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .value("Cache-Control", value -> assertThat(value).doesNotContain("public"))
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());
        }

        private PublicActivityDTO page() {
            return Objects.requireNonNull(webTestClient
                    .get()
                    .uri("/public/workspaces/{slug}/activity?range=all&to=" + TO, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PublicActivityDTO.class)
                    .returnResult()
                    .getResponseBody());
        }
    }

    @Nested
    class People {
        @Test
        void shouldKeepTheStatementBudgetWhenTheContributorListGrows() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            var range = new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO));
            var before = reads.measure(() -> peopleService.people(workspace.getId(), range, null, Set.of()));
            List<Long> added = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                User outside = persistUser("scale-contributor-" + i);
                added.add(outside.getId());
                record(outside, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1000L - i, DAY, monitored);
            }
            var after = reads.measure(() -> peopleService.people(workspace.getId(), range, null, Set.of()));
            assertThat(after.value().people()).extracting(p -> p.person().id()).containsAll(added);
            assertThat(after.cost()).isEqualTo(before.cost());
            assertThat(after.cost().statements()).isEqualTo(5);
            assertThat(after.cost().entities()).isZero();
        }

        @Test
        void shouldExcludeHiddenPeopleAndCountEachCommentOnceWhenItsEventsRepeat() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            record(zoe, ActivityEventType.COMMENT_CREATED, ActivityTargetType.ISSUE_COMMENT, -2L, DAY, monitored);
            record(
                    zoe,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -2L,
                    DAY.plusSeconds(60),
                    monitored);
            workspaceMembershipService.updateMemberVisibility(workspace.getId(), ada.getId(), true);
            var result = peopleService.people(
                    workspace.getId(),
                    new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                    null,
                    Set.of());
            assertThat(result.people()).extracting(p -> p.person().id()).doesNotContain(ada.getId());
            assertThat(result.people().stream()
                            .filter(p -> p.person().id().equals(zoe.getId()))
                            .findFirst()
                            .orElseThrow()
                            .counts()
                            .comments())
                    .isEqualTo(1);
        }

        @Test
        void shouldMoveMachineUsersInBothDirectionsWithoutGrantingMembership() {
            User machine = persistUser("machine-contributor");
            record(machine, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            var range = new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO));
            automationService.classify(workspace.getId(), machine.getId(), true);
            var classified = peopleService.people(workspace.getId(), range, null, Set.of());
            assertThat(classified.people()).extracting(p -> p.person().id()).doesNotContain(machine.getId());
            assertThat(classified.automation()).extracting(p -> p.person().id()).contains(machine.getId());
            assertThat(jdbc.queryForList(
                            "SELECT entity_type FROM config_audit_event WHERE workspace_id=? AND entity_id=?",
                            String.class,
                            workspace.getId(),
                            machine.getId().toString()))
                    .contains("ACTIVITY_AUTOMATION");
            automationService.classify(workspace.getId(), machine.getId(), false);
            assertThat(peopleService
                            .people(workspace.getId(), range, null, Set.of())
                            .people())
                    .extracting(p -> p.person().id())
                    .contains(machine.getId());
            assertThat(peopleService
                            .people(workspace.getId(), range, null, Set.of())
                            .people())
                    .extracting(p -> p.person().id())
                    .contains(machine.getId());
        }

        @ParameterizedTest
        @EnumSource(
                value = PersonDataRequest.State.class,
                names = {"ERASING", "FAILED"})
        void shouldRejectNewAutomationAssociationsAfterErasureAdmissionAndDuringRetry(PersonDataRequest.State state) {
            User machine = persistUser("erasure-machine");
            ensureWorkspaceMembership(workspace, machine, WorkspaceRole.MEMBER);
            long administrator = Objects.requireNonNull(
                    persistInstanceAdmin("Erasure administrator").getId());
            var preview = personData.preview(
                    administrator,
                    null,
                    List.of(new PersonIdentity(
                            Objects.requireNonNull(machine.getProvider().getId()),
                            machine.getNativeId().toString(),
                            null)));
            UUID requestId = preview.request().getId();
            personData.requestErasure(requestId, administrator, true);
            jdbc.update("UPDATE person_data_request SET state=? WHERE id=?", state.name(), requestId);

            assertThatThrownBy(() -> automationService.classify(workspace.getId(), machine.getId(), true))
                    .isInstanceOf(EntityNotFoundException.class);
            var classification = new ActivityAutomation.Id(workspace.getId(), machine.getId());
            assertThat(automationRepository.existsById(classification)).isFalse();
            if (state == PersonDataRequest.State.FAILED) {
                personData.requestErasure(requestId, administrator, true);
            }
            personData.run(requestId);
            assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
            assertThat(automationRepository.existsById(classification)).isFalse();
        }

        @Test
        void shouldRejectAutomationChangesWhenCallerIsOnlyAMember() {
            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/activity/people/{userId}/automation?treatAsAutomation=true",
                            workspace.getWorkspaceSlug(),
                            ada.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        void shouldKeepOtherWorkspaceEventsOutOfPeopleAndRejectUnknownRepositoryKeys() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            var range = new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO));
            var other = createWorkspace("other-activity", "Other activity", "other-org", AccountType.ORG, zoe);
            assertThat(peopleService
                            .people(other.getId(), range, null, Set.of())
                            .people())
                    .isEmpty();
            assertThatThrownBy(() ->
                            peopleService.people(other.getId(), range, null, Set.of(monitored.getNameWithOwner())))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void shouldCountDistinctWorkAcrossReviewsAndWeeksWhenContributorReviewsRepeatedly() {
            PullRequest pr = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(pr, ada, PullRequestReview.State.APPROVED);
            PullRequestReview changes = review(pr, ada, PullRequestReview.State.CHANGES_REQUESTED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY, monitored);
            record(
                    ada,
                    ActivityEventType.REVIEW_CHANGES_REQUESTED,
                    ActivityTargetType.REVIEW,
                    changes.getId(),
                    DAY.plus(Duration.ofDays(7)),
                    monitored);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    pr.getId(),
                    DAY,
                    monitored);
            var measured = reads.measure(() -> peopleService.people(
                    workspace.getId(),
                    new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                    null,
                    Set.of()));
            var person = measured.value().people().stream()
                    .filter(p -> p.person().id().equals(ada.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(person.counts().contributions()).isEqualTo(2);
            assertThat(person.counts().pullRequestsReviewed()).isEqualTo(1);
            assertThat(person.counts().peopleHelped()).isEqualTo(1);
            assertThat(person.counts().activeWeeks()).isEqualTo(2);
            assertThat(person.weeks()).hasSize(2);
            assertThat(measured.cost().statements()).isEqualTo(5);
            assertThat(measured.cost().entities()).isZero();
        }

        @Test
        void shouldSeparateBotsAndIncludeOutsideContributorsWhenMembershipIsNotRequired() {
            User outside = persistUser("outside-contributor");
            User bot = persistUser("machine-account");
            bot.setType(User.Type.BOT);
            bot = userRepository.save(bot);
            record(outside, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, monitored);
            record(bot, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -2L, DAY, monitored);
            var range = new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO));
            var people = peopleService.people(workspace.getId(), range, null, Set.of());
            assertThat(people.people())
                    .extracting(p -> p.person().id())
                    .contains(outside.getId())
                    .doesNotContain(bot.getId());
            assertThat(people.automation()).extracting(p -> p.person().id()).contains(bot.getId());
            assertThat(peopleService
                            .people(workspace.getId(), range, null, Set.of())
                            .people())
                    .extracting(p -> p.person().id())
                    .contains(outside.getId());
        }

        @Test
        void shouldExcludeOwnReviewsAndUnmonitoredRepositoriesWhenCountingPeople() {
            PullRequest pr = pullRequest(ada, monitored, work -> work);
            PullRequestReview own = review(pr, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, own.getId(), DAY, monitored);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY, unmonitored);
            var people = peopleService.people(
                    workspace.getId(),
                    new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                    null,
                    Set.of());
            assertThat(people.people()).extracting(p -> p.person().id()).doesNotContain(ada.getId());
        }
    }

    @Test
    void shouldReadFullHistoryWhenAllTimeIsSelected() {
        Instant old = DAY.minus(Duration.ofDays(3650));
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -908L, old, monitored);
        var response = Objects.requireNonNull(status("/people", uri -> uri.queryParam("range", "all"))
                .isOk()
                .expectBody(ActivityPeopleDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(response.from()).isEqualTo(old);
        assertThat(response.people())
                .filteredOn(person -> person.person().id().equals(ada.getId()))
                .singleElement()
                .satisfies(person -> assertThat(person.counts().contributions()).isEqualTo(1));
    }

    @Test
    void shouldCountReviewsWithoutAnAuthorInBothPeopleAndWork() {
        var pull = pullRequest(zoe, monitored, work -> work);
        var review = review(pull, ada, PullRequestReview.State.APPROVED);
        jdbc.update("UPDATE issue SET author_id = NULL WHERE id = ?", pull.getId());
        record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, review.getId(), DAY);
        var person = members(uri -> uri).stream()
                .filter(row -> row.person().id().equals(ada.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(person.counts().pullRequestsReviewed()).isEqualTo(1);
        assertThat(person.counts().peopleHelped()).isZero();
        assertThat(person.firstContributionAt()).isEqualTo(DAY);
        assertThat(work(uri -> uri).content())
                .singleElement()
                .satisfies(row ->
                        assertThat(row.actions()).contains(new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1)));
    }

    @Test
    void shouldReadOwnActivityWhenTheMemberIsHidden() {
        var caller = userRepository
                .findByLoginAndProviderId(
                        "mentor", Objects.requireNonNull(ensureGitHubProvider().getId()))
                .orElseThrow();
        var issue = issue(monitored, Issue.State.OPEN, caller);
        record(caller, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, issue.getId(), DAY);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), caller.getId(), true);
        assertThat(members(uri -> uri)).extracting(row -> row.person().id()).doesNotContain(caller.getId());
        var detail = Objects.requireNonNull(get("/people/" + caller.getId(), uri -> uri)
                .isOk()
                .expectBody(ActivityPersonDetailDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(detail.counts().contributions()).isEqualTo(1);
        get("/people/" + caller.getId() + "/work", uri -> uri)
                .isOk()
                .expectBody(ActivityWorkPageDTO.class)
                .value(page -> assertThat(page.content()).hasSize(1));
    }

    @Test
    void shouldHideAnotherMembersWorkOnEveryPersonReadPath() {
        var issue = issue(monitored, Issue.State.OPEN, ada);
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, issue.getId(), DAY);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), ada.getId(), true);
        get("/people/" + ada.getId(), uri -> uri).isNotFound().expectBody(Void.class);
        get("/people/" + ada.getId() + "/work", uri -> uri).isNotFound().expectBody(Void.class);
        get("/work", uri -> uri.queryParam("login", ada.getLogin()))
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldReturnZeroCountsForOwnActivityBeforeTheFirstContribution() {
        var caller = userRepository
                .findByLoginAndProviderId(
                        "mentor", Objects.requireNonNull(ensureGitHubProvider().getId()))
                .orElseThrow();
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), caller.getId(), true);
        get("/people/" + caller.getId(), uri -> uri)
                .isOk()
                .expectBody(ActivityPersonDetailDTO.class)
                .value(detail -> {
                    assertThat(detail.counts().contributions()).isZero();
                    assertThat(detail.weeks()).isEmpty();
                });
    }

    @Test
    void shouldDenyEveryAnonymousActivityReadEvenWhenTheWorkspacePublicActivityEnabled() {
        workspace.setPublicActivityEnabled(true);
        workspaceRepository.save(workspace);
        for (String path : List.of(
                "/people",
                "/people/" + ada.getId(),
                "/people/" + ada.getId() + "/work",
                "/work",
                "/members/" + ada.getLogin() + "/open-work")) {
            webTestClient
                    .get()
                    .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/activity" + path)
                    .exchange()
                    .expectStatus()
                    .value(status -> assertThat(status).isIn(401, 403))
                    .expectBody(Void.class);
        }
    }

    @Test
    void shouldScopeFirstContributionsCoverageAndAllTimeToSelectedRepositories() {
        var other = repository("activity-org/private-history", true);
        Instant old = Instant.parse(FROM).minus(Duration.ofDays(1500));
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -991L, old, other);
        record(
                zoe,
                ActivityEventType.ISSUE_CREATED,
                ActivityTargetType.ISSUE,
                -992L,
                old.plus(Duration.ofDays(1000)),
                monitored);
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -993L, DAY, monitored);
        jdbc.update(
                "UPDATE repository_to_monitor SET issue_backfill_high_water_mark=0, pull_request_backfill_high_water_mark=0 WHERE workspace_id=?",
                workspace.getId());
        var selected = peopleService.people(
                workspace.getId(),
                new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                null,
                Set.of(monitored.getNameWithOwner()));
        assertThat(selected.highlights().firstContributors())
                .contains(ada.getId())
                .doesNotContain(zoe.getId());
        assertThat(selected.coverage().since()).isEqualTo(old.plus(Duration.ofDays(1000)));
        assertThat(selected.coverage().completeRepositories()).isEqualTo(1);
        assertThat(selected.coverage().totalRepositories()).isEqualTo(1);
        var all = peopleService.people(
                workspace.getId(),
                new ActivityPeopleRangeParams("all", null, null),
                null,
                Set.of(monitored.getNameWithOwner()));
        assertThat(all.from()).isEqualTo(selected.coverage().since());
        assertThat(all.highlights().firstContributors()).isEmpty();
        jdbc.update(
                "UPDATE repository_to_monitor SET issue_backfill_high_water_mark=NULL WHERE workspace_id=?",
                workspace.getId());
        var incomplete = peopleService.people(
                workspace.getId(),
                new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                null,
                Set.of(monitored.getNameWithOwner()));
        assertThat(incomplete.highlights().firstContributors()).isEmpty();
    }

    @Nested
    class PersonDetail {
        @Test
        void shouldCountWeeksTypesAndRepositoriesWhenAContributorIsRead() {
            PullRequest pull = pullRequest(zoe, monitored, work -> work);
            PullRequestReview first = review(pull, ada, PullRequestReview.State.APPROVED);
            PullRequestReview second = review(pull, ada, PullRequestReview.State.CHANGES_REQUESTED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, first.getId(), DAY, monitored);
            record(
                    ada,
                    ActivityEventType.REVIEW_CHANGES_REQUESTED,
                    ActivityTargetType.REVIEW,
                    second.getId(),
                    DAY.plus(Duration.ofDays(7)),
                    monitored);
            Repository other = repository("activity-org/gadgets", true);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -902L, DAY, other);
            var detail = Objects.requireNonNull(get("/people/" + ada.getId(), uri -> uri)
                    .isOk()
                    .expectBody(ActivityPersonDetailDTO.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(detail.person().id()).isEqualTo(ada.getId());
            assertThat(detail.counts().contributions()).isEqualTo(2);
            assertThat(detail.counts().pullRequestsReviewed()).isEqualTo(1);
            assertThat(detail.counts().peopleHelped()).isEqualTo(1);
            assertThat(detail.weeks()).hasSize(2);
            assertThat(detail.breakdown().approvals()).isEqualTo(1);
            assertThat(detail.breakdown().changeRequests()).isEqualTo(1);
            assertThat(detail.repositories())
                    .extracting(row -> row.repository().id())
                    .containsExactlyInAnyOrder(monitored.getId(), other.getId());
            var filtered = Objects.requireNonNull(
                    get("/people/" + ada.getId(), uri -> uri.queryParam("repo", other.getNameWithOwner()))
                            .isOk()
                            .expectBody(ActivityPersonDetailDTO.class)
                            .returnResult()
                            .getResponseBody());
            assertThat(filtered.counts().contributions()).isEqualTo(1);
            assertThat(filtered.repositories())
                    .singleElement()
                    .satisfies(row -> assertThat(row.counts().issuesOpened()).isEqualTo(1));
        }

        @Test
        void shouldReturnZeroWhenAVisibleContributorHasNoActivityInTheRange() {
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -903L,
                    Instant.parse(FROM).minusSeconds(1),
                    monitored);
            var detail = Objects.requireNonNull(get("/people/" + ada.getId(), uri -> uri)
                    .isOk()
                    .expectBody(ActivityPersonDetailDTO.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(detail.counts().contributions()).isZero();
            assertThat(detail.weeks()).isEmpty();
            assertThat(detail.repositories()).isEmpty();
        }

        @Test
        void shouldReadDetailsAndWorkForOutsideContributorsAndProviderBots() {
            User outside = persistUser("detail-contributor");
            User bot = persistUser("detail-provider-bot");
            bot.setType(User.Type.BOT);
            bot = userRepository.save(bot);
            for (User contributor : List.of(outside, bot)) {
                PullRequest pull = pullRequest(contributor, monitored, work -> work);
                record(
                        contributor,
                        ActivityEventType.PULL_REQUEST_OPENED,
                        ActivityTargetType.PULL_REQUEST,
                        pull.getId(),
                        DAY,
                        monitored);
                PullRequest disconnected = pullRequest(contributor, unmonitored, work -> work);
                record(
                        contributor,
                        ActivityEventType.PULL_REQUEST_OPENED,
                        ActivityTargetType.PULL_REQUEST,
                        disconnected.getId(),
                        DAY.plusSeconds(60),
                        unmonitored);
                String path = "/people/" + contributor.getId();
                var detail = Objects.requireNonNull(get(path, uri -> uri)
                        .isOk()
                        .expectBody(ActivityPersonDetailDTO.class)
                        .returnResult()
                        .getResponseBody());
                assertThat(detail.person().id()).isEqualTo(contributor.getId());
                assertThat(detail.counts().contributions()).isEqualTo(1);
                assertThat(detail.automation()).isEqualTo(contributor.getType() == User.Type.BOT);
                var page = Objects.requireNonNull(get(path + "/work", uri -> uri)
                        .isOk()
                        .expectBody(ActivityWorkPageDTO.class)
                        .returnResult()
                        .getResponseBody());
                assertThat(page.content()).singleElement().satisfies(work -> {
                    assertThat(work.id()).isEqualTo("work:" + pull.getId());
                    assertThat(work.actions())
                            .containsExactly(new ActivityActionDTO(ActivityKind.PULL_REQUEST_OPENED, 1));
                });
                assertThat(page.nextCursor()).isNull();
                assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(
                                workspace.getId(), contributor.getId()))
                        .isEmpty();
            }
        }

        @Test
        void shouldRejectHiddenAndUnrelatedPeopleWhenDetailsOrWorkAreRead() {
            User outsider = persistUser("detail-outsider");
            for (String suffix : List.of("", "/work")) {
                get("/people/" + outsider.getId() + suffix, uri -> uri)
                        .isNotFound()
                        .expectBody(Void.class);
            }
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -904L, DAY, monitored);
            var membership = workspaceMembershipRepository
                    .findByWorkspace_IdAndUser_Id(workspace.getId(), ada.getId())
                    .orElseThrow();
            membership.setHidden(true);
            workspaceMembershipRepository.save(membership);
            for (String suffix : List.of("", "/work")) {
                get("/people/" + ada.getId() + suffix, uri -> uri).isNotFound().expectBody(Void.class);
            }
        }

        @Test
        void shouldWalkWorkOnceWithAllActionsAndKeepRepositoryScopeWhenPagesChange() {
            PullRequest pull = pullRequest(zoe, monitored, work -> work);
            PullRequestReview first = review(pull, ada, PullRequestReview.State.APPROVED);
            PullRequestReview second = review(pull, ada, PullRequestReview.State.CHANGES_REQUESTED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, first.getId(), DAY, monitored);
            record(
                    ada,
                    ActivityEventType.REVIEW_CHANGES_REQUESTED,
                    ActivityTargetType.REVIEW,
                    second.getId(),
                    DAY.plusSeconds(120),
                    monitored);
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -905L,
                    DAY.plusSeconds(60),
                    monitored);
            Repository other = repository("activity-org/other", true);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -906L, DAY.plusSeconds(180), other);
            String path = "/people/" + ada.getId() + "/work";
            var page = Objects.requireNonNull(get(
                            path,
                            uri -> uri.queryParam("repo", monitored.getNameWithOwner())
                                    .queryParam("size", 1))
                    .isOk()
                    .expectBody(ActivityWorkPageDTO.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(page.content()).singleElement().satisfies(work -> {
                assertThat(work.id()).isEqualTo("work:" + pull.getId());
                assertThat(work.actions())
                        .containsExactlyInAnyOrder(
                                new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1),
                                new ActivityActionDTO(ActivityKind.REVIEW_CHANGES_REQUESTED, 1));
            });
            assertThat(page.nextCursor()).isNotNull();
            var next = Objects.requireNonNull(get(
                            path,
                            uri -> uri.queryParam("repo", monitored.getNameWithOwner())
                                    .queryParam("size", 1)
                                    .queryParam("cursor", Objects.requireNonNull(page.nextCursor())))
                    .isOk()
                    .expectBody(ActivityWorkPageDTO.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(next.content())
                    .singleElement()
                    .satisfies(work -> assertThat(work.id()).isEqualTo("work:-905"));
            assertThat(next.nextCursor()).isNull();
            get(path, uri -> uri.queryParam("cursor", "invalid")).isBadRequest().expectBody(Void.class);
        }
    }

    @Nested
    class Work {

        @Test
        void shouldListCommentsAndAnApprovalOnOnePullRequestAsOneEntry() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(zoe, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, zoesWork.getId(), DAY);
            for (int i = 1; i <= 3; i++) {
                record(
                        ada,
                        ActivityEventType.COMMENT_CREATED,
                        ActivityTargetType.ISSUE_COMMENT,
                        comment(zoesWork, ada).getId(),
                        DAY.plusSeconds(60L * i));
            }
            record(
                    ada,
                    ActivityEventType.REVIEW_APPROVED,
                    ActivityTargetType.REVIEW,
                    approval.getId(),
                    DAY.plusSeconds(600));

            ActivityWorkDTO adas = entry(work(uri -> uri.queryParam("login", ada.getLogin())), zoesWork);
            ActivityWorkDTO everyones = entry(work(uri -> uri), zoesWork);

            assertThat(adas.actions())
                    .as("in the order of kinds")
                    .containsExactly(
                            new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1),
                            new ActivityActionDTO(ActivityKind.COMMENTED, 3));
            assertThat(adas.lastOccurredAt()).isEqualTo(DAY.plusSeconds(600));
            assertThat(adas.people()).extracting(person -> person.id()).containsExactly(ada.getId());
            assertThat(Objects.requireNonNull(adas.work()).type()).isEqualTo(WorkItemDTO.WorkItemType.PULL_REQUEST);
            assertThat(everyones.actions())
                    .containsExactly(
                            new ActivityActionDTO(ActivityKind.PULL_REQUEST_OPENED, 1),
                            new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1),
                            new ActivityActionDTO(ActivityKind.COMMENTED, 3));
            assertThat(everyones.people())
                    .as("by name: Ada before Zoe, though a-zoe logs in before z-ada")
                    .extracting(person -> person.id())
                    .containsExactly(ada.getId(), zoe.getId());
        }

        @Test
        void shouldListActivityAsItsOwnEntryWhenItsWorkIsNotKnown() {
            PullRequest tombstoned = pullRequest(zoe, monitored, work -> {
                work.setDeletedAt(DAY);
                return work;
            });
            PullRequestReview onTombstoned = review(tombstoned, ada, PullRequestReview.State.APPROVED);
            UUID unknownReview = record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, -120L, DAY);
            UUID unknownComment = record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -120L,
                    DAY.plusSeconds(60));
            record(
                    ada,
                    ActivityEventType.REVIEW_APPROVED,
                    ActivityTargetType.REVIEW,
                    onTombstoned.getId(),
                    DAY.plusSeconds(120));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    Long.MAX_VALUE,
                    DAY.plusSeconds(180));

            List<ActivityWorkDTO> entries =
                    work(uri -> uri.queryParam("login", ada.getLogin())).content();

            assertThat(entries)
                    .extracting(ActivityWorkDTO::id)
                    .containsExactly(
                            "work:" + Long.MAX_VALUE,
                            "work:" + tombstoned.getId(),
                            "event:" + unknownComment,
                            "event:" + unknownReview);
            assertThat(entries).allSatisfy(entry -> assertThat(entry.work()).isNull());
        }

        @Test
        void shouldWalkEveryEntryOnceNewestFirstWhenNewerActivityArrivesBetweenPages() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -130L, DAY);
            // Two entries at one instant, split across a page boundary, so the id decides their order.
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -131L, DAY.plusSeconds(60));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -132L, DAY.plusSeconds(60));
            UUID newer = record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -133L,
                    DAY.plusSeconds(120));
            record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -136L, DAY.plusSeconds(150));
            record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -134L, DAY.plusSeconds(180));

            ActivityWorkPageDTO first =
                    work(uri -> uri.queryParam("login", ada.getLogin()).queryParam("size", 2));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -135L, DAY.plusSeconds(240));
            ActivityWorkPageDTO second = work(uri -> uri.queryParam("login", ada.getLogin())
                    .queryParam("size", 2)
                    .queryParam("cursor", Objects.requireNonNull(first.nextCursor())));
            ActivityWorkPageDTO third = work(uri -> uri.queryParam("login", ada.getLogin())
                    .queryParam("size", 2)
                    .queryParam("cursor", Objects.requireNonNull(second.nextCursor())));

            List<ActivityWorkDTO> walked = new ArrayList<>(first.content());
            walked.addAll(second.content());
            walked.addAll(third.content());
            assertThat(third.nextCursor()).as("the third page is the last").isNull();
            assertThat(walked)
                    .extracting(ActivityWorkDTO::id)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(
                            "work:-134", "work:-136", "event:" + newer, "work:-131", "work:-132", "work:-130")
                    .startsWith("work:-134", "work:-136", "event:" + newer)
                    .endsWith("work:-130")
                    .doesNotContain("work:-135");
            assertThat(walked)
                    .extracting(ActivityWorkDTO::lastOccurredAt)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        }

        /** No end is given, so each page would otherwise read up to its own now. */
        @Test
        void shouldListEveryEntryOnceWhenOlderWorkGetsActivityBetweenPagesOfAnOpenEndedRange() {
            Instant now = Instant.now();
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -170L,
                    now.minus(Duration.ofHours(3)));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -171L,
                    now.minus(Duration.ofHours(2)));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -172L,
                    now.minus(Duration.ofHours(1)));

            List<String> walked = new ArrayList<>();
            @Nullable String cursor = null;
            do {
                @Nullable String after = cursor;
                ActivityWorkPageDTO page = Objects.requireNonNull(status("/work", uri -> {
                            UriBuilder query =
                                    uri.queryParam("login", ada.getLogin()).queryParam("size", 1);
                            return after == null ? query : query.queryParam("cursor", after);
                        })
                        .isOk()
                        .expectBody(ActivityWorkPageDTO.class)
                        .returnResult()
                        .getResponseBody());
                page.content().forEach(entry -> walked.add(entry.id()));
                if (after == null) {
                    record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -170L, Instant.now());
                }
                cursor = page.nextCursor();
            } while (cursor != null);

            assertThat(walked).containsExactly("work:-172", "work:-171", "work:-170");
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfOneMembersWorkIsWalked() {
            seedMixedActivity(monitored);

            assertWorkAddsUpToTheSummary(uri -> uri.queryParam("login", ada.getLogin()));
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfWorkspaceWorkIsWalked() {
            seedMixedActivity(monitored);

            assertThat(assertWorkAddsUpToTheSummary(uri -> uri))
                    .as("every kind is listed, each from its own count")
                    .containsExactlyInAnyOrder(ActivityKind.values());
        }

        @Test
        void shouldListOnlyTheAskedKindsWhenAKindFilterIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY);
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(zoesWork, ada).getId(),
                    DAY.plusSeconds(30));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -140L, DAY.plusSeconds(60));

            ActivityWorkPageDTO reviews = work(uri ->
                    uri.queryParam("login", ada.getLogin()).queryParam("kinds", ActivityKind.REVIEW_APPROVED.name()));

            assertThat(reviews.content()).singleElement().satisfies(entry -> {
                assertThat(entry.actions()).containsExactly(new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1));
                assertThat(entry.lastOccurredAt()).isEqualTo(DAY);
                assertThat(Objects.requireNonNull(entry.work()).id()).isEqualTo(zoesWork.getId());
            });
        }

        @Test
        void shouldRejectTheKindWhenItIsUnknown() {
            get("/work", uri -> uri.queryParam("kinds", "NOT_A_KIND"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldRejectThePageSizeWhenItExceedsAHundred() {
            get("/work", uri -> uri.queryParam("size", 101)).isBadRequest().expectBody(Void.class);
        }

        @Test
        void shouldRejectTheCursorWhenItCannotBeRead() {
            get("/work", uri -> uri.queryParam("cursor", "not-a-cursor"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        private static ActivityWorkDTO entry(ActivityWorkPageDTO page, Issue work) {
            return page.content().stream()
                    .filter(entry -> entry.id().equals("work:" + work.getId()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    class Teams {

        private Team platform;

        /** A sub-team of {@link #platform}; Zoe is its member and it can write to the monitored repository. */
        private Team web;

        @BeforeEach
        void seedTeams() {
            Organization organization = new Organization();
            organization.setNativeId(nativeIds.incrementAndGet());
            organization.setLogin(workspace.getAccountLogin());
            organization.setHtmlUrl("https://github.com/" + workspace.getAccountLogin());
            organization.setProvider(ensureGitHubProvider());
            workspace.setOrganization(organizationRepository.save(organization));
            workspace = workspaceRepository.save(workspace);

            platform = team("platform", null);
            web = team("platform-web", platform.getId());
            teamMembershipRepository.save(new TeamMembership(web, zoe, TeamMembership.Role.MEMBER));
            teamRepositoryPermissionRepository.save(
                    new TeamRepositoryPermission(web, monitored, TeamRepositoryPermission.PermissionLevel.WRITE));
        }

        @Test
        void shouldFindTheFirstContributionInsideTheTeamScope() {
            Instant old = Instant.parse(FROM).minus(Duration.ofDays(1000));
            record(
                    zoe,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -980L,
                    old,
                    repository("acme/other-team", true));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -981L,
                    old.plusSeconds(1),
                    monitored);
            record(zoe, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -982L, DAY, monitored);
            jdbc.update(
                    "UPDATE repository_to_monitor SET issue_backfill_high_water_mark=0, pull_request_backfill_high_water_mark=0 WHERE workspace_id=?",
                    workspace.getId());
            var result = peopleService.people(
                    workspace.getId(),
                    new ActivityPeopleRangeParams("custom", Instant.parse(FROM), Instant.parse(TO)),
                    platform.getSlug(),
                    Set.of());
            assertThat(result.highlights().firstContributors()).contains(zoe.getId());
            assertThat(result.people()).singleElement().satisfies(person -> {
                assertThat(person.person().id()).isEqualTo(zoe.getId());
                assertThat(person.firstContributionAt()).isEqualTo(DAY);
            });
        }

        @Test
        void shouldCoverOnlyTheTeamAndItsSubTeamsWhenATeamIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -50L,
                    DAY.plusSeconds(60),
                    unmonitored);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -51L, DAY);

            assertThat(members(uri -> uri.queryParam("team", platform.getSlug())))
                    .extracting(member -> member.person().id())
                    .containsExactly(zoe.getId());
            assertThat(work(uri -> uri.queryParam("team", platform.getSlug())).content())
                    .as("the team's activity is in repositories the team can access")
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.actions())
                            .containsExactly(new ActivityActionDTO(ActivityKind.PULL_REQUEST_OPENED, 1)));
            TypeCounts summary = summary(uri -> uri.queryParam("team", platform.getSlug()));
            assertThat(summary.pullRequestsOpened()).isEqualTo(1);
            assertThat(summary.issuesOpened()).isZero();
        }

        @Test
        void shouldCountOnlyTheTeamsRepositoriesWhenAMemberIsReadInATeamsScope() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -52L,
                    DAY.plusSeconds(60),
                    unmonitored);

            TypeCounts summary =
                    summary(uri -> uri.queryParam("login", zoe.getLogin()).queryParam("team", platform.getSlug()));

            assertThat(summary.pullRequestsOpened()).isEqualTo(1);
            assertThat(summary.issuesOpened())
                    .as("the team cannot access the repository the issue is in")
                    .isZero();
        }

        @Test
        void shouldCountOnlyReviewsOnPullRequestsWithTheTeamsLabelWhenTheTeamFiltersByLabel() {
            Label platformLabel = label("platform", monitored);
            PullRequest labeled = pullRequest(ada, monitored, work -> {
                work.getLabels().add(platformLabel);
                return work;
            });
            PullRequest unlabeled = pullRequest(ada, monitored, work -> work);
            PullRequestReview onLabeled = review(labeled, zoe, PullRequestReview.State.COMMENTED);
            PullRequestReview onUnlabeled = review(unlabeled, zoe, PullRequestReview.State.COMMENTED);
            record(
                    zoe,
                    ActivityEventType.REVIEW_COMMENTED,
                    ActivityTargetType.REVIEW,
                    onLabeled.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.REVIEW_COMMENTED,
                    ActivityTargetType.REVIEW,
                    onUnlabeled.getId(),
                    DAY.plusSeconds(60),
                    monitored);
            assertThat(teamSettingsService.addLabelFilter(workspace, platform.getId(), platformLabel.getId()))
                    .isPresent();

            assertThat(summary(uri -> uri.queryParam("team", platform.getSlug()))
                            .commentReviews())
                    .isEqualTo(1);
        }

        @Test
        void shouldLeaveOutARepositoryWhenTheTeamHidesItsContributions() {
            Repository gadgets = repository("activity-org/gadgets", true);
            teamRepositoryPermissionRepository.save(
                    new TeamRepositoryPermission(web, gadgets, TeamRepositoryPermission.PermissionLevel.WRITE));
            PullRequest inWidgets = pullRequest(zoe, monitored, work -> work);
            PullRequest inGadgets = pullRequest(zoe, gadgets, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    inWidgets.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    inGadgets.getId(),
                    DAY.plusSeconds(60),
                    gadgets);
            assertThat(teamSettingsService.updateRepositoryVisibility(workspace, web.getId(), monitored.getId(), true))
                    .isPresent();

            assertThat(summary(uri -> uri.queryParam("team", platform.getSlug()))
                            .pullRequestsOpened())
                    .isEqualTo(1);
        }

        @Test
        void shouldNotFindATeamWhenItBelongsToAnotherWorkspace() {
            Workspace other = createWorkspace(
                    "activity-other", "Other", "other-org", AccountType.ORG, persistUser("other-owner"));
            Team elsewhere = team("elsewhere", null);
            elsewhere.setOrganization(other.getAccountLogin());
            elsewhere = teamRepository.save(elsewhere);
            String elsewhereKey = elsewhere.getSlug();

            summaryStatus(uri -> uri.queryParam("team", elsewhereKey))
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldLeaveOutAHiddenSubTeamAndEverythingBelowItWhenTheParentTeamIsRead() {
            Team webUi = team("platform-web-ui", web.getId());
            teamMembershipRepository.save(new TeamMembership(webUi, ada, TeamMembership.Role.MEMBER));
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            teamSettingsService.updateTeamVisibility(workspace, web.getId(), true);

            assertThat(members(uri -> uri.queryParam("team", platform.getSlug())))
                    .as("Zoe is only in the hidden sub-team, Ada only in the team below it")
                    .isEmpty();
            assertThat(summary(uri -> uri.queryParam("login", zoe.getLogin()).queryParam("team", platform.getSlug()))
                            .pullRequestsOpened())
                    .as("only the hidden sub-team can access the repository")
                    .isZero();
        }

        @Test
        void shouldListNothingWhenTheTeamHasNoMembers() {
            Team empty = team("platform-empty", null);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -70L, DAY);

            assertThat(members(uri -> uri.queryParam("team", empty.getSlug()))).isEmpty();
            assertThat(summary(uri -> uri.queryParam("team", empty.getSlug())))
                    .isEqualTo(new TypeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            ActivityWorkPageDTO work = work(uri -> uri.queryParam("team", empty.getSlug()));
            assertThat(work.content()).isEmpty();
            assertThat(work.nextCursor()).isNull();
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfATeamsWorkIsWalked() {
            Label platformLabel = label("platform", monitored);
            assertThat(teamSettingsService.addLabelFilter(workspace, platform.getId(), platformLabel.getId()))
                    .isPresent();
            seedMixedActivity(monitored);
            record(zoe, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -150L, DAY, unmonitored);

            assertWorkAddsUpToTheSummary(uri -> uri.queryParam("team", platform.getSlug()));
            assertWorkAddsUpToTheSummary(
                    uri -> uri.queryParam("team", platform.getSlug()).queryParam("login", zoe.getLogin()));
        }

        @Test
        void shouldNotFindATeamWhenItIsHiddenFromWorkspaceActivity() {
            teamSettingsService.updateTeamVisibility(workspace, platform.getId(), true);

            summaryStatus(uri -> uri.queryParam("team", platform.getSlug()))
                    .isNotFound()
                    .expectBody(Void.class);
        }

        private Label label(String name, Repository repository) {
            Label label = new Label();
            label.setNativeId(nativeIds.incrementAndGet());
            label.setProvider(ensureGitHubProvider());
            label.setName(name);
            label.setColor("0e8a16");
            label.setRepository(repository);
            return labelRepository.save(label);
        }

        @Test
        void shouldListAPullRequestAskingTheMembersTeamWhenTheTeamIsAskedForAReview() {
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(web).andThen(requestingTeam(platform)));

            OpenWorkDTO zoes = openWork(zoe);

            assertThat(zoes.teamReviewRequests().content())
                    .filteredOn(item -> item.id().equals(asking.getId()))
                    .singleElement()
                    .satisfies(item -> assertThat(item.requestedTeams())
                            .as("only the teams Zoe is in")
                            .containsExactly(new TeamRefDTO(web.getId(), "platform-web")));
            assertThat(zoes.reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
            assertThat(openWork(ada).teamReviewRequests().content())
                    .as("Ada is in no team the pull request asks")
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
        }

        /** A pull request asking Zoe's team, and whether Zoe's open work lists it as a team request. */
        enum TeamRequest {
            ASKED_DIRECTLY(false),
            AUTHORED(false),
            APPROVED(false),
            CHANGES_REQUESTED(false),
            DRAFT(false),
            UNMONITORED(false),
            COMMENTED(true),
            APPROVAL_DISMISSED(true);

            private final boolean listedAsTeamRequest;

            TeamRequest(boolean listedAsTeamRequest) {
                this.listedAsTeamRequest = listedAsTeamRequest;
            }
        }

        /** A comment is not a verdict, and a dismissed approval no longer stands. */
        @ParameterizedTest
        @EnumSource(TeamRequest.class)
        void shouldListATeamRequestOnlyWhenNothingElseAsksTheMemberOrAnswersIt(TeamRequest given) {
            PullRequest asking =
                    switch (given) {
                        case ASKED_DIRECTLY ->
                            pullRequest(ada, monitored, requestingTeam(web).andThen(requesting(zoe)));
                        case AUTHORED -> pullRequest(zoe, monitored, requestingTeam(web));
                        case DRAFT ->
                            pullRequest(ada, monitored, requestingTeam(web).andThen(work -> {
                                work.setDraft(true);
                                return work;
                            }));
                        case UNMONITORED -> pullRequest(ada, unmonitored, requestingTeam(web));
                        case APPROVED, CHANGES_REQUESTED, COMMENTED, APPROVAL_DISMISSED ->
                            pullRequest(ada, monitored, requestingTeam(web));
                    };
            switch (given) {
                case APPROVED -> review(asking, zoe, PullRequestReview.State.APPROVED);
                case CHANGES_REQUESTED -> review(asking, zoe, PullRequestReview.State.CHANGES_REQUESTED);
                case COMMENTED -> review(asking, zoe, PullRequestReview.State.COMMENTED);
                case APPROVAL_DISMISSED -> {
                    PullRequestReview withdrawn = review(asking, zoe, PullRequestReview.State.APPROVED);
                    withdrawn.setDismissed(true);
                    reviewRepository.save(withdrawn);
                }
                default -> {}
            }

            List<Long> teamRequests = openWork(zoe).teamReviewRequests().content().stream()
                    .map(WorkItemDTO::id)
                    .toList();

            if (given.listedAsTeamRequest) {
                assertThat(teamRequests).contains(asking.getId());
            } else {
                assertThat(teamRequests).doesNotContain(asking.getId());
            }
        }

        @Test
        void shouldListADirectRequestAmongTheReviewRequestsWhenTheTeamIsAskedToo() {
            PullRequest direct = pullRequest(ada, monitored, requestingTeam(web).andThen(requesting(zoe)));

            assertThat(openWork(zoe).reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .contains(direct.getId());
        }

        @Test
        void shouldLeaveOutATeamRequestWhenTheMemberLeavesTheTeam() {
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(web));

            teamMembershipRepository.deleteById(new TeamMembership.Id(web.getId(), zoe.getId()));

            assertThat(openWork(zoe).teamReviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
        }

        @Test
        void shouldKeepATeamRequestWhenTheTeamIsHiddenFromWorkspaceActivity() {
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(web));
            teamSettingsService.updateTeamVisibility(workspace, web.getId(), true);
            teamSettingsService.updateTeamVisibility(workspace, platform.getId(), true);

            assertThat(openWork(zoe).teamReviewRequests().content())
                    .as("hiding a team shapes workspace activity, not what Zoe is asked to do")
                    .extracting(WorkItemDTO::id)
                    .contains(asking.getId());
        }

        @Test
        void shouldLeaveOutARequestToATeamOutsideTheWorkspaceWhenTheMemberIsInIt() {
            Team outside = team("outside", null);
            outside.setOrganization("other-org");
            outside = teamRepository.save(outside);
            teamMembershipRepository.save(new TeamMembership(outside, zoe, TeamMembership.Role.MEMBER));
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(outside));

            assertThat(openWork(zoe).teamReviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
        }

        private Team team(String name, @Nullable Long parentId) {
            Team team = new Team();
            team.setNativeId(nativeIds.incrementAndGet());
            team.setName(name);
            team.setSlug(name);
            team.setOrganization(workspace.getAccountLogin());
            team.setHtmlUrl("https://github.com/orgs/activity-org/teams/" + name);
            team.setPrivacy(Team.Privacy.VISIBLE);
            team.setProvider(ensureGitHubProvider());
            team.setParentId(parentId);
            return teamRepository.save(team);
        }
    }

    @Nested
    class OpenWork {

        @Test
        void shouldListOpenReviewRequestsByOthersInMonitoredRepositoriesWhenAMemberIsAsked() {
            PullRequest requested = pullRequest(zoe, monitored, requesting(ada));
            pullRequest(zoe, monitored, requesting(ada).andThen(OpenWork::draft));
            pullRequest(zoe, monitored, requesting(ada).andThen(OpenWork::closed));
            pullRequest(zoe, unmonitored, requesting(ada));
            pullRequest(ada, monitored, requesting(ada));

            assertThat(openWork(ada).reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactly(requested.getId());
        }

        @Test
        void shouldListOwnOpenPullRequestsIncludingDraftsWhenAMemberIsAsked() {
            PullRequest open = pullRequest(ada, monitored, work -> work);
            PullRequest drafted = pullRequest(ada, monitored, OpenWork::draft);
            pullRequest(ada, monitored, OpenWork::closed);
            pullRequest(ada, unmonitored, work -> work);
            pullRequest(zoe, monitored, work -> work);

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactlyInAnyOrder(open.getId(), drafted.getId());
        }

        @Test
        void shouldListOpenAssignedIssuesButNoPullRequestsWhenAMemberIsAsked() {
            Issue assigned = issue(monitored, Issue.State.OPEN, ada);
            issue(monitored, Issue.State.CLOSED, ada);
            issue(unmonitored, Issue.State.OPEN, ada);
            issue(monitored, Issue.State.OPEN, zoe);
            pullRequest(zoe, monitored, work -> {
                work.getAssignees().add(ada);
                return work;
            });

            assertThat(openWork(ada).issues().content()).singleElement().satisfies(item -> {
                assertThat(item.id()).isEqualTo(assigned.getId());
                assertThat(item.type()).isEqualTo(WorkItemDTO.WorkItemType.ISSUE);
            });
        }

        @Test
        void shouldReportNoMoreWorkWhenAListHoldsExactlyItsLimit() {
            for (int i = 0; i < OpenWorkService.LIMIT; i++) {
                pullRequest(ada, monitored, work -> work);
            }

            var pullRequests = openWork(ada).pullRequests();

            assertThat(pullRequests.content()).hasSize(OpenWorkService.LIMIT);
            assertThat(pullRequests.hasMore()).isFalse();
        }

        @Test
        void shouldReportMoreWorkWhenAListExceedsItsLimitByOne() {
            for (int i = 0; i <= OpenWorkService.LIMIT; i++) {
                pullRequest(ada, monitored, work -> work);
            }

            var pullRequests = openWork(ada).pullRequests();

            assertThat(pullRequests.content()).hasSize(OpenWorkService.LIMIT);
            assertThat(pullRequests.hasMore()).isTrue();
        }

        @Test
        void shouldListTheMostRecentlyUpdatedWorkFirst() {
            PullRequest older = pullRequest(ada, monitored, updatedAt(DAY));
            PullRequest newest = pullRequest(ada, monitored, updatedAt(DAY.plusSeconds(7200)));
            PullRequest newer = pullRequest(ada, monitored, updatedAt(DAY.plusSeconds(3600)));

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactly(newest.getId(), newer.getId(), older.getId());
        }

        @Test
        void shouldLeaveChecksUnknownWhenTheyWereObservedForAnEarlierHead() {
            PullRequest current = pullRequest(ada, monitored, head("aaa", "aaa"));
            PullRequest stale = pullRequest(ada, monitored, head("bbb", "ccc"));

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id, WorkItemDTO::checks)
                    .containsExactlyInAnyOrder(tuple(current.getId(), CheckState.SUCCESS), tuple(stale.getId(), null));
        }

        @Test
        void shouldListReviewersByWhereTheyStandThenByNameWhenAPullRequestIsOpen() {
            User bob = member("bob", "Bob");
            User cleo = member("cleo", "Cleo");
            User dan = member("dan", "Dan");
            PullRequest open = pullRequest(ada, monitored, requesting(dan));
            review(open, cleo, PullRequestReview.State.COMMENTED);
            review(open, bob, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.CHANGES_REQUESTED, DAY.plusSeconds(60));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(zoe.getId(), ReviewerState.CHANGES_REQUESTED),
                            tuple(bob.getId(), ReviewerState.APPROVED),
                            tuple(cleo.getId(), ReviewerState.COMMENTED),
                            tuple(dan.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(openWork(dan).reviewRequests().content(), open))
                    .as("the review request lists the same reviewers")
                    .hasSize(4);
        }

        @Test
        void shouldShowTheReviewerAsRequestedWhenAReviewIsAskedAgainAfterTheyApproved() {
            PullRequest open = pullRequest(ada, monitored, requesting(zoe));
            review(open, zoe, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(zoe.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheLatestVerdictWhenTheReviewerCommentedAfterIt() {
            User bob = member("bob", "Bob");
            PullRequest open = pullRequest(ada, monitored, work -> work);
            review(open, zoe, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.COMMENTED, DAY.plusSeconds(60));
            review(open, bob, PullRequestReview.State.CHANGES_REQUESTED);
            review(open, bob, PullRequestReview.State.APPROVED, DAY.plusSeconds(60));
            review(open, bob, PullRequestReview.State.COMMENTED, DAY.plusSeconds(120));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(bob.getId(), ReviewerState.APPROVED), tuple(zoe.getId(), ReviewerState.APPROVED));
        }

        @Test
        void shouldShowTheReviewOverTheRequestWhenGitLabStatedNoReviewerState() {
            User bob = member("bob", "Bob");
            PullRequest mergeRequest = pullRequest(
                    ada, monitored, onGitLab().andThen(requesting(zoe)).andThen(requesting(bob)));
            review(mergeRequest, zoe, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mergeRequest))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(zoe.getId(), ReviewerState.APPROVED), tuple(bob.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldKeepAnUndatedCurrentGitLabApprovalWithoutInferringItFromHistoricalDates() {
            PullRequest mr = pullRequest(ada, monitored, onGitLab().andThen(requesting(zoe)));
            review(mr, zoe, PullRequestReview.State.CHANGES_REQUESTED);
            PullRequestReview approval = review(mr, zoe, PullRequestReview.State.APPROVED);
            approval.setSubmittedAt(null);
            reviewRepository.save(approval);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mr))
                    .extracting(ReviewerDTO::state)
                    .containsExactly(ReviewerState.APPROVED);

            approval.setDismissed(true);
            approval.setState(PullRequestReview.State.DISMISSED);
            reviewRepository.save(approval);
            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mr))
                    .extracting(ReviewerDTO::state)
                    .containsExactly(ReviewerState.CHANGES_REQUESTED);
        }

        @Test
        void shouldKeepAMergeRequestWithTheReadersOwnReviewWhenGitLabStatedNoReviewerState() {
            PullRequest approved = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));
            review(approved, ada, PullRequestReview.State.APPROVED);
            PullRequest commented = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));
            review(commented, ada, PullRequestReview.State.COMMENTED);
            PullRequest waiting = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));

            List<WorkItemDTO> requests = openWork(ada).reviewRequests().content();

            assertThat(requests)
                    .as("GitLab keeps a reviewer listed after they reviewed, so the request stays in the list")
                    .extracting(WorkItemDTO::id)
                    .contains(approved.getId(), commented.getId(), waiting.getId());
            assertThat(reviewersOf(requests, approved))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.APPROVED));
            assertThat(reviewersOf(requests, commented))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.COMMENTED));
            assertThat(reviewersOf(requests, waiting))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheReviewerWhereGitLabSaysTheyStandWhenGitLabStatesIt() {
            User bob = member("bob", "Bob");
            User cleo = member("cleo", "Cleo");
            User dan = member("dan", "Dan");
            PullRequest mergeRequest = pullRequest(
                    ada,
                    monitored,
                    onGitLab()
                            .andThen(requesting(zoe, ReviewState.APPROVED))
                            .andThen(requesting(bob, ReviewState.REVIEWED))
                            .andThen(requesting(cleo, ReviewState.REQUESTED_CHANGES))
                            .andThen(requesting(dan, ReviewState.REVIEW_STARTED)));
            review(mergeRequest, dan, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mergeRequest))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(cleo.getId(), ReviewerState.CHANGES_REQUESTED),
                            tuple(zoe.getId(), ReviewerState.APPROVED),
                            tuple(bob.getId(), ReviewerState.COMMENTED),
                            tuple(dan.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheReviewAsRequestedAgainWhenGitLabSetsTheReviewerBackToUnreviewed() {
            PullRequest reRequested =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.UNREVIEWED)));
            review(reRequested, ada, PullRequestReview.State.APPROVED);
            PullRequest withdrawn =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.UNAPPROVED)));
            PullRequest approved =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.APPROVED)));

            List<WorkItemDTO> requests = openWork(ada).reviewRequests().content();

            assertThat(reviewersOf(requests, reRequested))
                    .as("the approval came before GitLab asked again")
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(requests, withdrawn))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(requests, approved))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.APPROVED));
        }

        @Test
        void shouldIgnoreADismissedOrPendingReviewWhenListingReviewers() {
            User bob = member("bob", "Bob");
            PullRequest open = pullRequest(ada, monitored, work -> work);
            review(open, zoe, PullRequestReview.State.COMMENTED);
            PullRequestReview zoesApproval = review(open, zoe, PullRequestReview.State.APPROVED, DAY.plusSeconds(60));
            zoesApproval.setDismissed(true);
            reviewRepository.save(zoesApproval);
            PullRequestReview bobsApproval = review(open, bob, PullRequestReview.State.APPROVED);
            bobsApproval.setDismissed(true);
            bobsApproval.setState(PullRequestReview.State.DISMISSED);
            reviewRepository.save(bobsApproval);
            review(open, bob, PullRequestReview.State.PENDING, DAY.plusSeconds(120));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(zoe.getId(), ReviewerState.COMMENTED));
        }

        @Test
        void shouldLeaveOutTheAuthorAndBotsWhenListingReviewers() {
            User member = member("review-bot", "Review bot");
            member.setType(User.Type.BOT);
            User bot = userRepository.save(member);
            PullRequest open = pullRequest(ada, monitored, requesting(ada).andThen(requesting(bot)));
            review(open, ada, PullRequestReview.State.COMMENTED);
            review(open, bot, PullRequestReview.State.COMMENTED);
            Issue assigned = issue(monitored, Issue.State.OPEN, ada);

            OpenWorkDTO openWork = openWork(ada);

            assertThat(reviewersOf(openWork.pullRequests().content(), open)).isEmpty();
            assertThat(openWork.issues().content())
                    .filteredOn(item -> item.id().equals(assigned.getId()))
                    .singleElement()
                    .satisfies(item -> assertThat(item.reviewers()).isNull());
        }

        @Test
        void shouldReturnNotFoundWhenTheMemberIsUnknown() {
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/activity/members/{login}/open-work",
                            workspace.getWorkspaceSlug(),
                            "nobody-here")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        private static List<ReviewerDTO> reviewersOf(List<WorkItemDTO> work, PullRequest pullRequest) {
            return Objects.requireNonNull(work.stream()
                    .filter(item -> item.id().equals(pullRequest.getId()))
                    .findFirst()
                    .orElseThrow()
                    .reviewers());
        }

        private Function<PullRequest, PullRequest> onGitLab() {
            return work -> {
                work.setProvider(ensureGitLabProvider());
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> head(String observedSha, String headSha) {
            return work -> {
                work.observeHeadChecks(observedSha, CheckState.SUCCESS, true);
                work.setHeadRefOid(headSha);
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> updatedAt(Instant updatedAt) {
            return work -> {
                work.setUpdatedAt(updatedAt);
                return work;
            };
        }

        private static PullRequest draft(PullRequest work) {
            work.setDraft(true);
            return work;
        }

        private static PullRequest closed(PullRequest work) {
            work.setState(Issue.State.CLOSED);
            work.setClosedAt(DAY);
            return work;
        }
    }

    private OpenWorkDTO openWork(User member) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/activity/members/{login}/open-work",
                        workspace.getWorkspaceSlug(),
                        member.getLogin())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OpenWorkDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private static Function<PullRequest, PullRequest> requesting(User reviewer) {
        return requesting(reviewer, null);
    }

    private static Function<PullRequest, PullRequest> requesting(User reviewer, @Nullable ReviewState state) {
        return work -> {
            Map<User, @Nullable ReviewState> reviewers = new HashMap<>();
            work.getRequestedReviewers().forEach(listed -> reviewers.put(listed.getUser(), listed.getReviewState()));
            reviewers.put(reviewer, state);
            work.replaceRequestedReviewers(reviewers, DAY);
            return work;
        };
    }

    private static Function<PullRequest, PullRequest> requestingTeam(Team team) {
        return work -> {
            Set<Team> teams = new HashSet<>();
            work.getRequestedTeams().forEach(listed -> teams.add(listed.getTeam()));
            teams.add(team);
            work.replaceRequestedTeams(teams, DAY);
            return work;
        };
    }

    private TypeCounts summary(Function<UriBuilder, UriBuilder> query) {
        var raw = UriComponentsBuilder.fromPath("");
        var parameters =
                UriComponentsBuilder.fromUri(query.apply(raw).build()).build().getQueryParams();
        String login = parameters.getFirst("login");
        return sum(members(query).stream()
                .filter(p -> login == null || p.person().login().equals(login))
                .map(person -> {
                    var detail = Objects.requireNonNull(
                            get("/people/" + person.person().id(), query)
                                    .isOk()
                                    .expectBody(ActivityPersonDetailDTO.class)
                                    .returnResult()
                                    .getResponseBody());
                    var counts = detail.counts();
                    var types = detail.breakdown();
                    return new TypeCounts(
                            Math.toIntExact(counts.pullRequestsOpened()),
                            Math.toIntExact(counts.pullRequestsMerged()),
                            types.pullRequestsClosed(),
                            types.approvals(),
                            types.changeRequests(),
                            types.commentReviews(),
                            types.discussionComments(),
                            types.codeComments(),
                            Math.toIntExact(counts.issuesOpened()),
                            types.issuesClosed());
                })
                .toList());
    }

    private StatusAssertions summaryStatus(Function<UriBuilder, UriBuilder> query) {
        return get("/people", query);
    }

    private List<ActivityPersonDTO> members(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(get("/people", query)
                        .isOk()
                        .expectBody(ActivityPeopleDTO.class)
                        .returnResult()
                        .getResponseBody())
                .people();
    }

    private ActivityWorkPageDTO work(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(get("/work", query)
                .isOk()
                .expectBody(ActivityWorkPageDTO.class)
                .returnResult()
                .getResponseBody());
    }

    /**
     * Walks every page of work two entries at a time, for all kinds and for a few, and checks that the actions
     * add up to the summary of the same scope.
     */
    private Set<ActivityKind> assertWorkAddsUpToTheSummary(Function<UriBuilder, UriBuilder> scope) {
        TypeCounts summary = summary(scope);
        Map<ActivityKind, Integer> listed = listedActions(scope, List.of());
        Map<ActivityKind, Integer> reviewsAndComments =
                listedActions(scope, List.of(ActivityKind.REVIEW_APPROVED, ActivityKind.COMMENTED));

        assertThat(summary).as("the scope has activity to add up").isNotEqualTo(sum(List.of()));
        assertThat(summaryOf(listed)).isEqualTo(summary);
        assertThat(summaryOf(reviewsAndComments))
                .isEqualTo(new TypeCounts(0, 0, 0, summary.approvals(), 0, 0, summary.comments(), 0, 0, 0));
        return listed.keySet();
    }

    private Map<ActivityKind, Integer> listedActions(Function<UriBuilder, UriBuilder> scope, List<ActivityKind> kinds) {
        Map<ActivityKind, Integer> listed = new EnumMap<>(ActivityKind.class);
        List<String> ids = new ArrayList<>();
        @Nullable String cursor = null;
        do {
            @Nullable String after = cursor;
            ActivityWorkPageDTO page = work(uri -> {
                UriBuilder query = scope.apply(uri).queryParam("size", 2);
                kinds.forEach(kind -> query.queryParam("kinds", kind.name()));
                return after == null ? query : query.queryParam("cursor", after);
            });
            page.content().forEach(entry -> {
                ids.add(entry.id());
                entry.actions().forEach(action -> listed.merge(action.kind(), action.count(), Integer::sum));
            });
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).doesNotHaveDuplicates();
        return listed;
    }

    /**
     * Activity of every kind, some of it counted and some not, spread over the range and over pull requests,
     * issues and activity whose work is unknown. Recorded in {@code repository} when one is given.
     */
    private void seedMixedActivity(@Nullable Repository repository) {
        PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
        PullRequest adasWork = pullRequest(ada, monitored, work -> work);
        PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
        PullRequestReview changes = review(adasWork, zoe, PullRequestReview.State.CHANGES_REQUESTED);
        PullRequestReview ownReview = review(adasWork, ada, PullRequestReview.State.COMMENTED);
        PullRequestReview zoesComments = review(adasWork, zoe, PullRequestReview.State.COMMENTED);
        PullRequest abandoned = pullRequest(zoe, monitored, work -> work);
        record(
                zoe,
                ActivityEventType.PULL_REQUEST_CLOSED,
                ActivityTargetType.PULL_REQUEST,
                abandoned.getId(),
                DAY.plus(Duration.ofDays(2)),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_COMMENTED,
                ActivityTargetType.REVIEW,
                zoesComments.getId(),
                DAY.plusSeconds(240),
                repository);
        record(
                zoe,
                ActivityEventType.PULL_REQUEST_OPENED,
                ActivityTargetType.PULL_REQUEST,
                zoesWork.getId(),
                DAY,
                repository);
        record(
                ada,
                ActivityEventType.PULL_REQUEST_OPENED,
                ActivityTargetType.PULL_REQUEST,
                adasWork.getId(),
                DAY,
                repository);
        record(
                ada,
                ActivityEventType.PULL_REQUEST_MERGED,
                ActivityTargetType.PULL_REQUEST,
                adasWork.getId(),
                DAY.plus(Duration.ofDays(3)),
                repository);
        record(
                ada,
                ActivityEventType.REVIEW_APPROVED,
                ActivityTargetType.REVIEW,
                approval.getId(),
                DAY.plusSeconds(60),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_CHANGES_REQUESTED,
                ActivityTargetType.REVIEW,
                changes.getId(),
                DAY.plusSeconds(120),
                repository);
        record(
                ada,
                ActivityEventType.REVIEW_COMMENTED,
                ActivityTargetType.REVIEW,
                ownReview.getId(),
                DAY.plusSeconds(180),
                repository);
        for (int i = 0; i < 3; i++) {
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(zoesWork, ada).getId(),
                    DAY.plus(Duration.ofHours(i)),
                    repository);
            record(
                    zoe,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(adasWork, zoe).getId(),
                    DAY.plus(Duration.ofDays(i)),
                    repository);
        }
        record(
                ada,
                ActivityEventType.COMMENT_CREATED,
                ActivityTargetType.ISSUE_COMMENT,
                -160L,
                DAY.plus(Duration.ofDays(5)),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_COMMENT_CREATED,
                ActivityTargetType.REVIEW_COMMENT,
                -161L,
                DAY.plus(Duration.ofDays(6)),
                repository);
        record(
                zoe,
                ActivityEventType.ISSUE_CREATED,
                ActivityTargetType.ISSUE,
                -162L,
                DAY.plus(Duration.ofDays(7)),
                repository);
        record(
                zoe,
                ActivityEventType.ISSUE_CLOSED,
                ActivityTargetType.ISSUE,
                -162L,
                DAY.plus(Duration.ofDays(8)),
                repository);
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -163L, Instant.parse(TO), repository);
    }

    private record TypeCounts(
            int pullRequestsOpened,
            int pullRequestsMerged,
            int pullRequestsClosed,
            int approvals,
            int changeRequests,
            int commentReviews,
            int comments,
            int codeComments,
            int issuesOpened,
            int issuesClosed) {}

    private static TypeCounts summaryOf(Map<ActivityKind, Integer> counts) {
        return new TypeCounts(
                counts.getOrDefault(ActivityKind.PULL_REQUEST_OPENED, 0),
                counts.getOrDefault(ActivityKind.PULL_REQUEST_MERGED, 0),
                counts.getOrDefault(ActivityKind.PULL_REQUEST_CLOSED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_APPROVED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_CHANGES_REQUESTED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_COMMENTED, 0),
                counts.getOrDefault(ActivityKind.COMMENTED, 0),
                counts.getOrDefault(ActivityKind.CODE_COMMENTED, 0),
                counts.getOrDefault(ActivityKind.ISSUE_OPENED, 0),
                counts.getOrDefault(ActivityKind.ISSUE_CLOSED, 0));
    }

    private static TypeCounts sum(List<TypeCounts> summaries) {
        return summaries.stream()
                .reduce(
                        new TypeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        (a, b) -> new TypeCounts(
                                a.pullRequestsOpened() + b.pullRequestsOpened(),
                                a.pullRequestsMerged() + b.pullRequestsMerged(),
                                a.pullRequestsClosed() + b.pullRequestsClosed(),
                                a.approvals() + b.approvals(),
                                a.changeRequests() + b.changeRequests(),
                                a.commentReviews() + b.commentReviews(),
                                a.comments() + b.comments(),
                                a.codeComments() + b.codeComments(),
                                a.issuesOpened() + b.issuesOpened(),
                                a.issuesClosed() + b.issuesClosed()));
    }

    /** A read over the pinned range. */
    private StatusAssertions get(String path, Function<UriBuilder, UriBuilder> query) {
        return status(path, uri -> query.apply(uri.queryParam("from", FROM).queryParam("to", TO)));
    }

    private StatusAssertions status(String path, Function<UriBuilder, UriBuilder> query) {
        return webTestClient
                .get()
                .uri(uri -> query.apply(uri.path("/workspaces/{slug}/activity" + path))
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus();
    }

    private User member(String login, String name) {
        User user = persistUser(login);
        user.setName(name);
        user = userRepository.save(user);
        ensureWorkspaceMembership(workspace, user, WorkspaceRole.MEMBER);
        return user;
    }

    private Repository repository(String nameWithOwner, boolean monitor) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        if (monitor) {
            RepositoryToMonitor monitorRow = new RepositoryToMonitor();
            monitorRow.setWorkspace(workspace);
            monitorRow.setNameWithOwner(nameWithOwner);
            repositoryToMonitorRepository.save(monitorRow);
        }
        return repository;
    }

    private PullRequest pullRequest(User author, Repository repository, Function<PullRequest, PullRequest> shape) {
        long nativeId = nativeIds.incrementAndGet();
        PullRequest work = new PullRequest();
        work.setNativeId(nativeId);
        work.setProvider(ensureGitHubProvider());
        work.setNumber((int) (nativeId % 100_000));
        work.setTitle("Pull request " + nativeId);
        work.setState(Issue.State.OPEN);
        work.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + nativeId);
        work.setRepository(repository);
        work.setAuthor(author);
        work.setCreatedAt(DAY);
        work.setUpdatedAt(DAY);
        // Shaped once stored: a reviewer request is keyed by the pull request's id.
        return pullRequestRepository.save(shape.apply(pullRequestRepository.save(work)));
    }

    private PullRequestReview review(PullRequest pullRequest, User author, PullRequestReview.State state) {
        return review(pullRequest, author, state, DAY);
    }

    private PullRequestReview review(
            PullRequest pullRequest, User author, PullRequestReview.State state, Instant submittedAt) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(nativeIds.incrementAndGet());
        review.setProvider(ensureGitHubProvider());
        review.setState(state);
        review.setPullRequest(pullRequest);
        review.setAuthor(author);
        review.setSubmittedAt(submittedAt);
        review.setHtmlUrl(pullRequest.getHtmlUrl() + "#review-" + review.getNativeId());
        return reviewRepository.save(review);
    }

    private IssueComment comment(Issue on, User author) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeIds.incrementAndGet());
        comment.setProvider(ensureGitHubProvider());
        comment.setBody("A comment");
        comment.setHtmlUrl(on.getHtmlUrl() + "#issuecomment-" + comment.getNativeId());
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setAuthor(author);
        comment.setIssue(on);
        return issueCommentRepository.save(comment);
    }

    private Issue issue(Repository repository, Issue.State state, User assignee) {
        long nativeId = nativeIds.incrementAndGet();
        Issue issue = new Issue();
        issue.setNativeId(nativeId);
        issue.setProvider(ensureGitHubProvider());
        issue.setNumber((int) (nativeId % 100_000));
        issue.setTitle("Issue " + nativeId);
        issue.setState(state);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + nativeId);
        issue.setRepository(repository);
        issue.setAuthor(zoe);
        issue.setCreatedAt(DAY);
        issue.setUpdatedAt(DAY);
        issue.getAssignees().add(assignee);
        return issueRepository.save(issue);
    }

    private UUID record(User actor, ActivityEventType type, ActivityTargetType target, long targetId, Instant at) {
        return record(actor, type, target, targetId, at, monitored);
    }

    private UUID record(
            User actor,
            ActivityEventType type,
            ActivityTargetType target,
            long targetId,
            Instant at,
            @Nullable Repository repository) {
        UUID id = UUID.randomUUID();
        activityEventRepository.insertIfAbsent(
                id,
                "activity-" + id,
                type.name(),
                at,
                actor.getId(),
                workspace.getId(),
                repository == null ? null : repository.getId(),
                target.getValue(),
                targetId);
        return id;
    }
}
