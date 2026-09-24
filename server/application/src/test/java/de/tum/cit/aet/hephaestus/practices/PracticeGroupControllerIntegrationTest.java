package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.audit.ConfigAuditEventRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderPracticeGroupsRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeAutonomyRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomySource;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Access-control coverage for {@link PracticeGroupController}.
 *
 * <p>Read operations are annotated {@code @SecurityRequirements} (any workspace member); the five
 * mutating operations (create / update / review-autonomy / reorder / delete) are
 * {@code @RequireAtLeastWorkspaceAdmin}. These tests assert that a plain workspace MEMBER is forbidden on
 * every mutation and permitted on reads, and that anonymous callers are rejected. Functional CRUD
 * behaviour for the bind endpoint lives on {@code PracticeCatalogControllerIntegrationTest}; the
 * review-autonomy PATCH's own behaviour is pinned here, because the group is the middle level of the
 * inheritance chain and nothing else exercises it.
 */
class PracticeGroupControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String BASE_URI = "/workspaces/{workspaceSlug}/practice-groups";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConfigAuditEventRepository configAuditEventRepository;

    private Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("group-owner");
        workspace = createWorkspace("group-ws", "Group WS", "group-org", AccountType.ORG, owner);
    }

    private PracticeGroup persistGroup(String slug, String name) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug(slug);
        group.setName(name);
        return groupRepository.save(group);
    }

    private CreatePracticeGroupRequestDTO validCreateRequest(String slug) {
        return new CreatePracticeGroupRequestDTO(slug, "Group " + slug, "Develops " + slug, null, "Package", "sky");
    }

    /** Registers the current {@code @WithMentorUser} principal as a plain workspace MEMBER. */
    private void asMember() {
        User member = persistUser("mentor");
        ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
    }

    // LIST — read, member-allowed (@SecurityRequirements)

    @Nested
    @DisplayName("GET /practice-groups")
    class ListGroups {

        @Test
        @WithAdminUser
        void shouldReturnGroupsForAdmin() {
            ensureAdminMembership(workspace);
            persistGroup("alpha", "Alpha");
            persistGroup("beta", "Beta");

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2);
        }

        @Test
        @WithMentorUser
        @DisplayName("allows a plain workspace member to list")
        void shouldAllowMemberToList() {
            asMember();
            persistGroup("member-visible", "Visible");

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    // GET SINGLE — read, member-allowed (@SecurityRequirements)

    @Nested
    @DisplayName("GET /practice-groups/{groupSlug}")
    class GetGroup {

        @Test
        @WithMentorUser
        @DisplayName("allows a plain workspace member to get a group")
        void shouldAllowMemberToGet() {
            asMember();
            persistGroup("member-get", "Member Get");

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "member-get")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.slug")
                    .isEqualTo("member-get");
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "any-slug")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    // CREATE — @RequireAtLeastWorkspaceAdmin

    @Nested
    @DisplayName("POST /practice-groups")
    class CreateGroup {

        @Test
        @WithMentorUser
        @DisplayName("forbids a plain workspace member from creating a group")
        void shouldReturn403ForNonAdmin() {
            asMember();

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("forbidden-group"))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);

            assertThat(groupRepository.existsByWorkspaceIdAndSlug(workspace.getId(), "forbidden-group"))
                    .isFalse();
        }

        @Test
        @DisplayName("rejects anonymous create (403 via CSRF gate, before auth)")
        void shouldRejectAnonymousCreate() {
            // Anonymous POST → double-submit CSRF gate (ADR 0017) rejects 403 before auth (no
            // X-XSRF-TOKEN). The create stays blocked for anonymous callers.
            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("anon-group"))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
    }

    // UPDATE — @RequireAtLeastWorkspaceAdmin

    @Nested
    @DisplayName("PATCH /practice-groups/{groupSlug}")
    class UpdateGroup {

        @Test
        @WithMentorUser
        @DisplayName("forbids a plain workspace member from updating a group")
        void shouldReturn403ForNonAdmin() {
            asMember();
            persistGroup("forbidden-update", "Original");

            var request = new UpdatePracticeGroupRequestDTO("Hacked Name", null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "forbidden-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);

            PracticeGroup persisted = groupRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), "forbidden-update")
                    .orElseThrow();
            assertThat(persisted.getName()).isEqualTo("Original");
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            var request = new UpdatePracticeGroupRequestDTO("Name", null, null, null, null, null);

            // Pass CSRF so the auth layer (not the CSRF filter) answers a cookie-style write → 401 (ADR 0017).
            String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(TestAuthUtils.withCsrf(csrf))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    // REORDER — @RequireAtLeastWorkspaceAdmin

    @Nested
    @DisplayName("PATCH /practice-groups/reorder")
    class ReorderGroups {

        @Test
        @WithAdminUser
        @DisplayName("reorders groups for an admin and persists the new display order")
        void shouldReorderForAdmin() {
            ensureAdminMembership(workspace);
            persistGroup("first", "First");
            persistGroup("second", "Second");

            var request = new ReorderPracticeGroupsRequestDTO(List.of("second", "first"));

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/reorder", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].slug")
                    .isEqualTo("second")
                    .jsonPath("$[1].slug")
                    .isEqualTo("first");

            assertThat(groupRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), "second")
                            .orElseThrow()
                            .getDisplayOrder())
                    .isZero();
            assertThat(groupRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), "first")
                            .orElseThrow()
                            .getDisplayOrder())
                    .isEqualTo(1);
        }

        @Test
        @WithMentorUser
        @DisplayName("forbids a plain workspace member from reordering groups")
        void shouldReturn403ForNonAdmin() {
            asMember();
            persistGroup("first", "First");
            persistGroup("second", "Second");

            var request = new ReorderPracticeGroupsRequestDTO(List.of("second", "first"));

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/reorder", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            var request = new ReorderPracticeGroupsRequestDTO(List.of("first", "second"));

            // Pass CSRF so the auth layer (not the CSRF filter) answers a cookie-style write → 401 (ADR 0017).
            String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
            webTestClient
                    .patch()
                    .uri(BASE_URI + "/reorder", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCsrf(csrf))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    // FUNCTIONAL — conflict / not-found / validation paths an admin can hit

    @Nested
    @DisplayName("functional admin paths (conflict / not-found / validation / tenancy)")
    class FunctionalAdminPaths {

        @Test
        @WithAdminUser
        @DisplayName("POST a duplicate slug returns 409")
        void createDuplicateSlugConflicts() {
            ensureAdminMembership(workspace);
            persistGroup("dup", "Existing");

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("dup"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("GET an unknown slug returns 404")
        void getUnknownSlugNotFound() {
            ensureAdminMembership(workspace);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "does-not-exist")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH an unknown slug returns 404")
        void patchUnknownSlugNotFound() {
            ensureAdminMembership(workspace);
            var request = new UpdatePracticeGroupRequestDTO("New Name", null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "does-not-exist")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("DELETE an unknown slug returns 404")
        void deleteUnknownSlugNotFound() {
            ensureAdminMembership(workspace);

            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "does-not-exist")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /reorder with a duplicate slug returns 400")
        void reorderDuplicateSlugIsBadRequest() {
            ensureAdminMembership(workspace);
            persistGroup("only", "Only");

            var request = new ReorderPracticeGroupsRequestDTO(List.of("only", "only"));

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/reorder", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /reorder with an unknown slug returns 404")
        void reorderUnknownSlugNotFound() {
            ensureAdminMembership(workspace);
            persistGroup("known", "Known");

            var request = new ReorderPracticeGroupsRequestDTO(List.of("known", "ghost"));

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/reorder", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("a group in another workspace is not readable via this workspace's slug (tenancy 404)")
        void crossWorkspaceGroupIsNotLeaked() {
            ensureAdminMembership(workspace);

            // Persist a group that belongs ONLY to a second workspace.
            User otherOwner = persistUser("other-owner");
            Workspace other = createWorkspace("other-ws", "Other WS", "other-org", AccountType.ORG, otherOwner);
            PracticeGroup foreign = new PracticeGroup();
            foreign.setWorkspace(other);
            foreign.setSlug("foreign-group");
            foreign.setName("Foreign");
            groupRepository.save(foreign);

            // Reading it through THIS workspace's slug must 404 — it is scoped to the other workspace.
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "foreign-group")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    // DELETE — @RequireAtLeastWorkspaceAdmin

    @Nested
    @DisplayName("DELETE /practice-groups/{groupSlug}")
    class DeleteGroup {

        @Test
        @WithMentorUser
        @DisplayName("forbids a plain workspace member from deleting a group")
        void shouldReturn403ForNonAdmin() {
            asMember();
            persistGroup("forbidden-delete", "Keep Me");

            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "forbidden-delete")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);

            assertThat(groupRepository.existsByWorkspaceIdAndSlug(workspace.getId(), "forbidden-delete"))
                    .isTrue();
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            // Pass CSRF so the auth layer (not the CSRF filter) answers a cookie-style write → 401 (ADR 0017).
            String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{groupSlug}", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(TestAuthUtils.withCsrf(csrf))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    // PATCH /{groupSlug}/autonomy — @RequireAtLeastWorkspaceAdmin

    /**
     * The middle level of the practice → group → workspace chain. A null clears the group's own answer back
     * to the workspace's, and re-sending the autonomy already in force does nothing at all — including to the
     * audit ledger.
     */
    @Nested
    @DisplayName("PATCH /practice-groups/{groupSlug}/autonomy")
    class SetGroupAutonomy {

        private PracticeGroup persistGroupAt(String slug, @Nullable PracticeAutonomy autonomy) {
            PracticeGroup group = persistGroup(slug, "Group " + slug);
            group.setAutonomy(autonomy);
            return groupRepository.save(group);
        }

        /** Gives the workspace an opinion of its own, so "inherit" has something to resolve to. */
        private void workspaceDefaultsTo(PracticeAutonomy autonomy) {
            Workspace stored = workspaceRepository.findById(workspace.getId()).orElseThrow();
            stored.getReviewSettings().applyDefaultAutonomy(autonomy.name());
            workspaceRepository.save(stored);
        }

        private @Nullable PracticeAutonomy storedTierOf(String slug) {
            return groupRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), slug)
                    .orElseThrow()
                    .getAutonomy();
        }

        /**
         * How many config-audit rows this one group has accumulated. Read through the workspace-scoped
         * query rather than a bare {@code count()}: {@code config_audit_event} is workspace-scoped, and
         * {@code WorkspaceStatementInspector} rejects a statement that reaches it without a
         * {@code workspace_id} predicate — in a test just as in production.
         */
        private long auditedChangesTo(PracticeGroup group) {
            return configAuditEventRepository
                    .findForWorkspace(
                            workspace.getId(),
                            new ConfigAuditFilter(
                                    List.of(ConfigAuditEntityType.PRACTICE_GROUP),
                                    String.valueOf(group.getId()),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            PageRequest.of(0, 50))
                    .getTotalElements();
        }

        /**
         * HUMAN_APPROVAL is included on purpose: refusing the middle rung would leave OFF/AUTOMATIC, the on/off
         * switch the autonomy chain exists to remove.
         */
        @ParameterizedTest
        @EnumSource(PracticeAutonomy.class)
        @WithAdminUser
        @DisplayName("sets the group's own autonomy and reports it as the group's decision, not an inheritance")
        void shouldSetTheGroupsOwnAutonomy(PracticeAutonomy autonomy) {
            ensureAdminMembership(workspace);
            persistGroupAt("decides", null);

            PracticeGroupDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "decides")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(autonomy))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeGroupDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().effective()).isEqualTo(autonomy);
            assertThat(result.autonomy().override()).isEqualTo(autonomy);
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.GROUP);
            assertThat(result.autonomy().inherited()).isFalse();
            assertThat(storedTierOf("decides")).isEqualTo(autonomy);
        }

        @Test
        @WithAdminUser
        @DisplayName("an explicit null clears the group's autonomy back to inheriting the workspace's")
        void shouldClearToInheritOnExplicitNull() {
            ensureAdminMembership(workspace);
            workspaceDefaultsTo(PracticeAutonomy.HUMAN_APPROVAL);
            persistGroupAt("clear-me", PracticeAutonomy.OFF);

            PracticeGroupDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "clear-me")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"autonomy\": null}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeGroupDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().override()).isNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.WORKSPACE);
            assertThat(result.autonomy().inherited()).isTrue();
            assertThat(storedTierOf("clear-me")).isNull();
        }

        /**
         * An absent key clears too. This endpoint carries one field, so "not sent" cannot mean "leave it
         * alone" without leaving no way at all to express a clear — unlike the settings PATCH, where absent
         * means no change and a clear is named in a {@code reset} set.
         */
        @Test
        @WithAdminUser
        @DisplayName("an absent autonomy key clears just as an explicit null does")
        void shouldClearToInheritWhenTheKeyIsAbsent() {
            ensureAdminMembership(workspace);
            workspaceDefaultsTo(PracticeAutonomy.OFF);
            persistGroupAt("absent-key", PracticeAutonomy.AUTOMATIC);

            PracticeGroupDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "absent-key")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeGroupDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().override()).isNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.OFF);
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.WORKSPACE);
            assertThat(storedTierOf("absent-key")).isNull();
        }

        /**
         * Re-sending the autonomy already in force short-circuits before the write. The observable is the audit
         * ledger: a config change that did not happen must not be recorded as one, or a reviewer reading
         * the ledger sees a decision nobody made.
         */
        @Test
        @WithAdminUser
        @DisplayName("re-sending the autonomy already in force records nothing in the audit ledger")
        void shouldBeIdempotentAndNotAudited() {
            ensureAdminMembership(workspace);
            PracticeGroup group = persistGroupAt("unchanged", PracticeAutonomy.HUMAN_APPROVAL);
            long auditedBefore = auditedChangesTo(group);

            PracticeGroupDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "unchanged")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.HUMAN_APPROVAL))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeGroupDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(result.autonomy().override()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(storedTierOf("unchanged")).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(auditedChangesTo(group)).isEqualTo(auditedBefore);
        }

        /** A real change, by contrast, is recorded — otherwise the assertion above would pass vacuously. */
        @Test
        @WithAdminUser
        @DisplayName("a real change IS recorded in the audit ledger")
        void shouldAuditARealChange() {
            ensureAdminMembership(workspace);
            PracticeGroup group = persistGroupAt("changes", PracticeAutonomy.HUMAN_APPROVAL);
            long auditedBefore = auditedChangesTo(group);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "changes")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            assertThat(auditedChangesTo(group)).isEqualTo(auditedBefore + 1);
        }

        @Test
        @WithAdminUser
        @DisplayName("returns 404 for an unknown group")
        void shouldReturn404ForUnknownGroup() {
            ensureAdminMembership(workspace);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "no-such-group")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        @DisplayName("forbids a plain workspace member from setting a group's autonomy")
        void shouldReturn403ForNonAdmin() {
            asMember();
            persistGroupAt("forbidden-autonomy", PracticeAutonomy.AUTOMATIC);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "forbidden-autonomy")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);

            assertThat(storedTierOf("forbidden-autonomy")).isEqualTo(PracticeAutonomy.AUTOMATIC);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{groupSlug}/autonomy", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(TestAuthUtils.withCsrf(csrf))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }
}
