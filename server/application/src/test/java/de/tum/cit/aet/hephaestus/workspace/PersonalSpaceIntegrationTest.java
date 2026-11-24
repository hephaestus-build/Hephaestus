package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.McpWorkspaceDirectory;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspacePersonDataCatalog;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextResolver;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database keeps a personal space's kind, owner and one member, and only the request filter's lookup finds it
 * (KR-06).
 */
class PersonalSpaceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private LlmUsageEventRepository usage;

    @Autowired
    private WorkspaceSummaryQuery summaries;

    @Autowired
    private WorkspaceContextResolver resolver;

    @Autowired
    private McpWorkspaceDirectory mcpDirectory;

    @Autowired
    private WorkspacePersonDataCatalog personData;

    @Autowired
    private PersonDataService personDataService;

    @Autowired
    private WorkspaceMemberships workspaceMemberships;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private long ownerId;
    private long spaceId;
    private String slug;

    @BeforeEach
    void space() {
        ownerId = Objects.requireNonNull(persistAccount("Personal owner").getId());
        spaceId = PersonalSpaceFixtures.insert(jdbc, ownerId);
        slug = Objects.requireNonNull(
                jdbc.queryForObject("SELECT slug FROM workspace WHERE id = ?", String.class, spaceId));
    }

    @Test
    void shouldRefuseASecondMemberAndAnAdminOrOwnerRole() {
        long otherId = Objects.requireNonNull(persistAccount("Someone else").getId());

        assertRefused(
                () -> jdbc.update(
                        "INSERT INTO membership (workspace_id, account_id, role) VALUES (?, ?, 'MEMBER')",
                        spaceId,
                        otherId),
                "a personal space has one member");
        for (String role : new String[] {"ADMIN", "OWNER"}) {
            assertRefused(
                    () -> jdbc.update(
                            "UPDATE membership SET role = ? WHERE workspace_id = ? AND account_id = ?",
                            role,
                            spaceId,
                            ownerId),
                    "a personal space has one member");
        }
    }

    @Test
    void shouldRefuseMovingAnotherMembershipIntoThePersonalSpace() {
        long otherId = Objects.requireNonNull(persistAccount("Team member").getId());
        long teamId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("team-of-the-member"))
                .getId();
        jdbc.update("INSERT INTO membership (workspace_id, account_id, role) VALUES (?, ?, 'MEMBER')", teamId, otherId);

        assertRefused(
                () -> jdbc.update(
                        "UPDATE membership SET workspace_id = ? WHERE workspace_id = ? AND account_id = ?",
                        spaceId,
                        teamId,
                        otherId),
                "a personal space has one member");
        // The owner's own row may not become another account's either.
        assertRefused(
                () -> jdbc.update(
                        "UPDATE membership SET account_id = ? WHERE workspace_id = ? AND account_id = ?",
                        otherId,
                        spaceId,
                        ownerId),
                "a personal space has one member");
    }

    @Test
    void shouldRefuseAChangedOwnerOrKind() {
        long otherId = Objects.requireNonNull(persistAccount("Someone else").getId());
        long teamId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("team-stays-team"))
                .getId();

        assertRefused(
                () -> jdbc.update("UPDATE workspace SET personal_account_id = ? WHERE id = ?", otherId, spaceId),
                "keeps its kind and its personal owner");
        // A valid personal slug, owner and privacy in the same update, so only the kind trigger can refuse it.
        assertRefused(
                () -> jdbc.update(
                        "UPDATE workspace SET kind = 'PERSONAL', personal_account_id = ?, slug = ?,"
                                + " public_activity_enabled = false WHERE id = ?",
                        otherId,
                        PersonalSpaceFixtures.slug(),
                        teamId),
                "keeps its kind and its personal owner");
    }

    @Test
    void shouldRefuseAPersonalSlugForATeamAndPublicActivityForAPersonalSpace() {
        assertRefused(
                () -> jdbc.update(
                        "INSERT INTO workspace (slug, display_name, account_login, account_type, status)"
                                + " VALUES (?, 'Team', 'team-login', 'ORG', 'ACTIVE')",
                        PersonalSpaceFixtures.slug()),
                "chk_workspace_slug_assignable");
        assertRefused(
                () -> jdbc.update("UPDATE workspace SET public_activity_enabled = true WHERE id = ?", spaceId),
                "ck_workspace_personal_private");
    }

    @Test
    void shouldMissAPersonalSpaceInEveryTeamLookupAndFindItOnlyInTheRequestFilters() {
        assertThat(workspaces.findAnyKindByWorkspaceSlug(slug)).isPresent();

        assertThat(workspaces.findByWorkspaceSlug(slug)).isEmpty();
        assertThat(mcpDirectory.findActiveBySlug(slug)).as("MCP").isEmpty();
        assertThat(workspaces.findActiveIdBySlug(slug)).as("user view").isEmpty();
        Optional<Workspace> locked =
                Objects.requireNonNull(transactions.execute(status -> workspaces.findByWorkspaceSlugForUpdate(slug)));
        assertThat(locked).as("instance budget write").isEmpty();
        // With the CHECK that keeps a personal space private in place: the CHECK itself is tested above.
        assertThat(workspaces.findPublicActivityWorkspace(slug)).isEmpty();
        assertThat(workspaces.findAllByAccountLoginIgnoreCase(slug))
                .as("provider routing")
                .isEmpty();
        assertThat(workspaces.findByAccountLoginIgnoreCase(slug)).isEmpty();
        assertThat(workspaces.findAllByKind(WorkspaceKind.TEAM))
                .as("startup activation")
                .noneMatch(workspace -> workspace.getId() == spaceId);
        assertThat(usage.aggregateByWorkspace(Instant.EPOCH, Instant.now().plusSeconds(60)))
                .as("instance LLM usage report")
                .noneMatch(row -> row.getWorkspaceId() == spaceId);
        assertThat(summaries.findTeamIds(Set.of(spaceId))).as("model sharing").isEmpty();
        assertThatThrownBy(() -> resolver.requireTeamWorkspace(spaceId))
                .as("research release")
                .isInstanceOf(EntityNotFoundException.class);
    }

    /** An instance admin's preview and export of an account name its team workspaces, never its personal space. */
    @Test
    void shouldLeaveThePersonalSpaceOutOfAnAccountsSelectedPersonData() {
        long teamId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("team-of-the-subject"))
                .getId();
        jdbc.update("INSERT INTO membership (workspace_id, account_id, role) VALUES (?, ?, 'MEMBER')", teamId, ownerId);
        var memberships = personData.contributors().stream()
                .filter(store -> store.store().equals("membership"))
                .findFirst()
                .orElseThrow();
        var subject = new PersonScope(ownerId, List.of(), List.of());

        assertThat(memberships.export(memberships.select(subject), subject))
                .singleElement()
                .satisfies(row -> assertThat(row.get("workspace_id").asLong()).isEqualTo(teamId));
        // Other modules' person-data stores read the same team workspaces through the workspace module.
        assertThat(workspaceMemberships.teamWorkspaceIds(ownerId)).containsExactly(teamId);
        // The personal space store names the space by its slug and creation time, and nothing else of it.
        var spaces = personData.contributors().stream()
                .filter(store -> store.store().equals("workspace"))
                .findFirst()
                .orElseThrow();
        var selected = spaces.select(subject);
        assertThat(selected.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.columns()).containsEntry("id", Long.toString(spaceId)));
        assertThat(spaces.export(selected, subject)).singleElement().satisfies(row -> {
            assertThat(row.get("slug").asString()).isEqualTo(slug);
            assertThat(row.propertyNames()).containsExactlyInAnyOrder("slug", "created_at");
        });
    }

    /** The erasure of the account disposes of the personal space and its membership. */
    @Test
    void shouldDisposeOfThePersonalSpaceWhenTheAccountIsErased() {
        personDataService.eraseForAccount(ownerId);

        assertThat(workspaces.findById(spaceId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership WHERE workspace_id = ?", Long.class, spaceId))
                .isZero();
    }

    private static void assertRefused(Runnable write, String reason) {
        assertThatThrownBy(write::run).isInstanceOf(DataAccessException.class).hasMessageContaining(reason);
    }
}
