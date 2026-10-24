package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyViolationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriBuilder;

/**
 * End-to-end coverage of the config audit trail: that producers actually write rows, that the rows
 * say the right thing, and that a workspace admin can never read another workspace's history.
 */
class ConfigAuditIntegrationTest extends AbstractWorkspaceIntegrationTest {

    // The URL checks resolve these names to tell a public host from a private one.
    @BeforeAll
    static void declarePublicHosts() {
        OfflineNetwork.answer("api.openai.com", OfflineNetwork.PUBLIC_ADDRESS);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConfigAuditEventRepository configAuditEventRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithAdminUser
    void practiceReviewPatchWritesExactlyOneRowNamingTheFieldThatChanged() {
        Workspace workspace = setupWorkspace("audit-a");

        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));

        List<ConfigAuditEvent> rows = configAuditEventRepository.findAll();
        assertThat(rows).hasSize(1);
        ConfigAuditEvent row = rows.getFirst();
        assertThat(row.getEntityType()).isEqualTo(ConfigAuditEntityType.PRACTICE_REVIEW_SETTINGS);
        assertThat(row.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(row.changedKeyList()).containsExactly("cooldownMinutes");
        assertThat(row.getNewValue()).contains("45");
        // Untouched fields must survive into the snapshot, or the "from what to what" is a lie.
        // Present AND null, not absent: null is "inherit the fleet default", so a serializer that
        // dropped null keys would make clearing an override indistinguishable from never setting
        // one.
        assertThat(row.getNewValue()).contains("\"cooldownMinutes\":45");
        // Through the real filter chain (JWT -> CurrentAccount -> actor): USER, not SYSTEM, because
        // a
        // signed-in admin did this. The id stays null because the test harness mints a non-numeric
        // subject; production subjects are always the account id.
        assertThat(row.getActorKind()).isEqualTo(ConfigAuditActorKind.USER);
        assertThat(row.isElevatedViaInstanceAdmin()).isFalse();
    }

    @Test
    void shouldTagBothLedgersWhenAnInstanceAdminReachesAWorkspaceTheyAreNotAMemberOf() {
        Workspace workspace = setupWorkspace("audit-elevated");
        Account admin = persistInstanceAdmin("Elevation operator");
        long adminId = persistedId(admin);

        patchPracticeReviewAs(workspace, token(adminId), Map.of("cooldownMinutes", 47));

        ConfigAuditEvent row = reviewSettingsRowFor(workspace);
        assertThat(row.getActorAccountId()).isEqualTo(adminId);
        assertThat(row.isElevatedViaInstanceAdmin()).isTrue();
        // One marker for the access window, not one per request, although the patch is two
        // requests.
        assertThat(elevationEventsFor(adminId)).singleElement().satisfies(event -> {
            assertThat(event.getWorkspaceId()).isEqualTo(workspace.getId());
            assertThat(event.isElevatedViaInstanceAdmin()).isTrue();
        });
    }

    @Test
    void shouldTagNeitherLedgerWhenTheSameChangeIsMadeByAWorkspaceMember() {
        Workspace workspace = setupWorkspace("audit-member");
        User member = persistUser("audit-member-admin");
        ensureMembership(workspace, member, Membership.WorkspaceRole.ADMIN);
        Account account = accountRepository.findById(accountId(member)).orElseThrow();
        account.setStatus(Account.Status.ACTIVE);
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accountRepository.saveAndFlush(account);
        long accountId = persistedId(account);

        patchPracticeReviewAs(workspace, token(accountId), Map.of("cooldownMinutes", 48));

        ConfigAuditEvent row = reviewSettingsRowFor(workspace);
        assertThat(row.getActorAccountId()).isEqualTo(accountId);
        assertThat(row.isElevatedViaInstanceAdmin()).isFalse();
        assertThat(elevationEventsFor(accountId))
                .as("no elevation marker for the account this test signed in as")
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void togglingAFeatureFlagIsRecorded() {
        Workspace workspace = setupWorkspace("audit-features");

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("practicesEnabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        ConfigAuditEvent row = configAuditEventRepository.findAll().stream()
                .filter(e -> e.getEntityType() == ConfigAuditEntityType.WORKSPACE_FEATURES)
                .findFirst()
                .orElseThrow();
        assertThat(row.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(row.changedKeyList()).contains("practicesEnabled");
        assertThat(row.getNewValue()).contains("\"practicesEnabled\":true");
    }

    @Test
    @WithAdminUser
    void practiceDefinitionLifecycleIsRecordedWithoutPersistingCriteria() {
        Workspace workspace = setupWorkspace("audit-practice");

        PracticeDTO practice = webTestClient
                .post()
                .uri("/workspaces/{slug}/practices", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeRequestDTO(
                        "focused-reviews",
                        "scm.pull_request",
                        DefinitionTestFixtures.authoring(definition(
                                "Focused reviews", "Initial private criteria describe retained review expectations.")),
                        null,
                        null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(PracticeDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(practice).isNotNull();

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/practices/{practiceSlug}", workspace.getWorkspaceSlug(), practice.slug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdatePracticeRequestDTO(
                        DefinitionTestFixtures.authoring(definition(
                                "Focused code reviews",
                                "Updated private criteria describe retained review expectations.")),
                        practice.currentVersion().id()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/practices/{practiceSlug}", workspace.getWorkspaceSlug(), practice.slug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        List<ConfigAuditEvent> rows = configAuditEventRepository.findAll().stream()
                .filter(row -> Objects.equals(row.getWorkspaceId(), workspace.getId()))
                .filter(row -> row.getEntityType() == ConfigAuditEntityType.PRACTICE_DEFINITION)
                .filter(row -> row.getEntityId().equals(String.valueOf(practice.id())))
                .sorted(Comparator.comparing(ConfigAuditEvent::getId))
                .toList();

        assertThat(rows)
                .extracting(ConfigAuditEvent::getAction)
                .containsExactly(ConfigAuditAction.CREATED, ConfigAuditAction.UPDATED, ConfigAuditAction.DELETED);
        assertThat(rows.get(0).getNewValue())
                .contains("\"versionId\":", "\"reviewDigest\":")
                .doesNotContain("Initial private criteria");
        assertThat(rows.get(1).changedKeyList()).containsExactlyInAnyOrder("versionId", "reviewDigest");
        assertThat(rows.get(1).getNewValue())
                .doesNotContain("Updated private criteria")
                .isNotEqualTo(rows.get(0).getNewValue());
        assertThat(rows.get(2).getNewValue()).isNull();
        assertThat(rows.get(2).getOldValue()).isEqualTo(rows.get(1).getNewValue());
    }

    @Test
    @WithAdminUser
    void anIdempotentRepeatOfTheSamePatchAddsNoSecondRow() {
        // Otherwise a settings page that re-submits its whole form buries real changes in noise.
        Workspace workspace = setupWorkspace("audit-noop");

        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));

        assertThat(configAuditEventRepository.findAll()).hasSize(1);
    }

    @Test
    @WithAdminUser
    void clearingAnOverrideBackToInheritIsRecorded() {
        // The null-versus-absent case: under NON_NULL serialization this change would vanish.
        Workspace workspace = setupWorkspace("audit-reset");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));

        patchPracticeReview(workspace, Map.of("reset", List.of("COOLDOWN_MINUTES")));

        List<ConfigAuditEvent> rows = configAuditEventRepository.findAll().stream()
                .sorted(Comparator.comparing(ConfigAuditEvent::getId))
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).changedKeyList()).containsExactly("cooldownMinutes");
        assertThat(rows.get(1).getNewValue()).contains("\"cooldownMinutes\":null");
    }

    @Test
    @WithAdminUser
    void workspaceAdminSeesOnlyTheirOwnWorkspacesHistory() {
        Workspace mine = setupWorkspace("audit-mine");
        Workspace theirs = setupWorkspace("audit-theirs");
        patchPracticeReview(mine, Map.of("cooldownMinutes", 45));
        patchPracticeReview(theirs, Map.of("cooldownMinutes", 15));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/config-audit", mine.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].workspaceId")
                .isEqualTo(mine.getId());
    }

    @Test
    @WithAdminUser
    void filteringByChangedKeyNarrowsToOneControl() {
        Workspace workspace = setupWorkspace("audit-filter");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));
        patchPracticeReview(workspace, Map.of("deliverToMerged", true));

        webTestClient
                .get()
                .uri(uri -> uri.path("/workspaces/{slug}/config-audit")
                        .queryParam("changedKey", "deliverToMerged")
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].changedKeys[0]")
                .isEqualTo("deliverToMerged");
    }

    @Test
    @WithAdminUser
    void instanceAdminSeesEveryWorkspaceAndCanNarrowToOne() {
        // First and only execution of findForAdmin: its SpEL binds and the CAST(:workspaceId AS
        // bigint)
        // null-branch are compile-clean but runtime-fragile, so nothing else proves they work.
        Workspace a = setupWorkspace("audit-inst-a");
        Workspace b = setupWorkspace("audit-inst-b");
        patchPracticeReview(a, Map.of("cooldownMinutes", 45));
        patchPracticeReview(b, Map.of("cooldownMinutes", 15));

        webTestClient
                .get()
                .uri("/admin/config-audit")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(2);

        webTestClient
                .get()
                .uri(uri -> uri.path("/admin/config-audit")
                        .queryParam("workspaceId", b.getId())
                        .build())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].workspaceId")
                .isEqualTo(b.getId());
    }

    @Test
    @WithMentorUser
    void aWorkspaceAdminIsRefusedAnotherWorkspacesTrail() {
        // Deliberately not @WithAdminUser: app_admin carries cross-workspace elevation, so that
        // user
        // reads any workspace by design. The sibling test also administers both workspaces, so it
        // survives removing the gate. Only a caller who administers neither proves the gate binds.
        Workspace theirs = createWorkspace(
                "audit-gate-theirs",
                "Other Workspace",
                "audit-gate-theirs-org",
                AccountType.ORG,
                persistUser("audit-gate-theirs-owner"));

        // 401, not 403: a non-member resolves no workspace context at all, so the request is
        // refused
        // before the admin gate is consulted. Either way the trail is not served.
        webTestClient
                .get()
                .uri("/workspaces/{slug}/config-audit", theirs.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void aNonInstanceAdminIsRefusedTheCrossWorkspaceView() {
        webTestClient
                .get()
                .uri("/admin/config-audit")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    static Stream<Arguments> filterCases() {
        String future = Instant.now().plusSeconds(60).toString();
        String past = Instant.now().minusSeconds(60).toString();
        return Stream.of(
                Arguments.of("entityType matches", "entityType", "WORKSPACE_LLM_CONNECTION", 1),
                Arguments.of("entityType matches the other kind", "entityType", "PRACTICE_REVIEW_SETTINGS", 1),
                Arguments.of("action matches", "action", "CREATED", 1),
                Arguments.of("action excludes", "action", "DELETED", 0),
                Arguments.of("actorId excludes", "actorId", "999999", 0),
                Arguments.of("from excludes the past", "from", future, 0),
                Arguments.of("from includes the past", "from", past, 2),
                Arguments.of("to excludes the present", "to", past, 0),
                Arguments.of("to includes the present", "to", future, 2));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filterCases")
    @WithAdminUser
    void eachFilterPredicateNarrowsIndependently(String name, String param, String value, int expected) {
        Workspace workspace = setupWorkspace("audit-matrix");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));
        createConnection(workspace, "primary");

        assertFilterYields(workspace, uri -> uri.queryParam(param, value), expected);
    }

    @Test
    @WithAdminUser
    void narrowingToOneResourceIsWhatMakesAPerResourceHistoryPossible() {
        Workspace workspace = setupWorkspace("audit-entity");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));

        assertFilterYields(
                workspace,
                uri -> uri.queryParam("entityType", "PRACTICE_REVIEW_SETTINGS")
                        .queryParam("entityId", String.valueOf(workspace.getId())),
                1);
        assertFilterYields(
                workspace,
                uri -> uri.queryParam("entityType", "PRACTICE_REVIEW_SETTINGS").queryParam("entityId", "999999"),
                0);
    }

    @Test
    @WithAdminUser
    void repeatingAFilterParameterWidensItRatherThanReplacingIt() {
        Workspace workspace = setupWorkspace("audit-multi");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));
        createConnection(workspace, "primary");

        assertFilterYields(
                workspace,
                uri -> uri.queryParam("entityType", "WORKSPACE_LLM_CONNECTION")
                        .queryParam("entityType", "PRACTICE_REVIEW_SETTINGS"),
                2);
        assertFilterYields(workspace, uri -> uri.queryParam("action", "CREATED").queryParam("action", "DELETED"), 1);
    }

    @Test
    @WithAdminUser
    void newestRowsComeFirstEvenWhenTwoShareAnInstant() {
        // Equal timestamps exercise the id tie-break; normal writes rarely share a microsecond.
        Workspace workspace = setupWorkspace("audit-order");
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 45));
        patchPracticeReview(workspace, Map.of("cooldownMinutes", 46));
        jdbcTemplate.update(
                "UPDATE config_audit_event SET occurred_at = ? WHERE workspace_id = ?",
                Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")),
                workspace.getId());

        webTestClient
                .get()
                .uri("/workspaces/{slug}/config-audit", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].newValue")
                .value((String value) -> MatcherAssert.assertThat(value, Matchers.containsString("46")))
                .jsonPath("$.content[1].newValue")
                .value((String value) -> MatcherAssert.assertThat(value, Matchers.containsString("45")));
    }

    @Test
    @WithAdminUser
    void entityIdWithoutEntityTypeIsRejected() {
        // Id spaces are per-type, so an unqualified id would match across types by accident.
        Workspace workspace = setupWorkspace("audit-badfilter");

        webTestClient
                .get()
                .uri(uri -> uri.path("/workspaces/{slug}/config-audit")
                        .queryParam("entityId", "1")
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    @Transactional
    void anUnscopedReadOfTheAuditTableIsCaughtByTenancyEnforcement() {
        // Pins that the table is registered workspace-scoped; isolation itself is carried by the
        // gate
        // and by findForWorkspace.
        assertThatThrownBy(() -> entityManager
                        .createNativeQuery("SELECT * FROM config_audit_event", ConfigAuditEvent.class)
                        .getResultList())
                .isInstanceOf(TenancyViolationException.class)
                .hasMessageContaining("config_audit_event");
    }

    private void assertFilterYields(Workspace workspace, UnaryOperator<UriBuilder> query, int expected) {
        webTestClient
                .get()
                .uri(uri ->
                        query.apply(uri.path("/workspaces/{slug}/config-audit")).build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(expected);
    }

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Audit Workspace", slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private void patchPracticeReview(Workspace workspace, Map<String, Object> body) {
        patchPracticeReviewAs(workspace, TestAuthUtils.getCurrentUserToken(), body);
    }

    private void patchPracticeReviewAs(Workspace workspace, String token, Map<String, Object> body) {
        String slug = workspace.getWorkspaceSlug();
        String version = webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/review-settings", slug)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getETag();
        String etag = Objects.requireNonNull(version, "the settings endpoint always answers with an ETag");

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/practices/review-settings", slug)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setIfMatch(etag);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    /** Authenticates as one specific account id — the JWT subject the native-auth filters read. */
    private static String token(long accountId) {
        return "mock-jwt-sub-" + accountId;
    }

    private ConfigAuditEvent reviewSettingsRowFor(Workspace workspace) {
        return configAuditEventRepository.findAll().stream()
                .filter(e -> workspace.getId().equals(e.getWorkspaceId())
                        && e.getEntityType() == ConfigAuditEntityType.PRACTICE_REVIEW_SETTINGS)
                .reduce((first, second) -> {
                    throw new AssertionError("the patch wrote more than one review-settings row");
                })
                .orElseThrow(() -> new AssertionError("the patch wrote no review-settings row"));
    }

    private List<AuthEvent> elevationEventsFor(long accountId) {
        return authEventRepository.findByAccountSince(accountId, Instant.EPOCH).stream()
                .filter(event -> event.getEventType() == AuthEvent.EventType.WORKSPACE_ELEVATION)
                .toList();
    }

    private static long persistedId(Account account) {
        return Objects.requireNonNull(account.getId(), "the account must be persisted");
    }

    private void createConnection(Workspace workspace, String slug) {
        webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkspaceLlmConnectionRequestDTO(
                        slug,
                        "My Provider",
                        "https://api.openai.com",
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        LlmAuthMode.BEARER,
                        "sk-workspace-secret-9999",
                        true,
                        null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(Void.class);
    }

    private static PracticeDefinition definition(String name, String criteria) {
        return DefinitionTestFixtures.withLineNotes(
                DefinitionTestFixtures.withReviewOn(
                        DefinitionTestFixtures.definition(name, criteria), new ReviewOn(List.of(), Map.of())),
                false);
    }
}
