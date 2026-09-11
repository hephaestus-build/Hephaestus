package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/** Access and response-shape coverage for the caller-scoped practice-group trend endpoint. */
class PracticeGroupTrendControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String URI = "/workspaces/{workspaceSlug}/practice-groups/{groupSlug}/trend";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    private Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("trend-owner");
        workspace = createWorkspace("trend-ws", "Trend WS", "trend-org", AccountType.ORG, owner);

        User developer = persistUser("testuser");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug("code-quality");
        group.setName("Code Quality");
        group = groupRepository.save(group);

        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setGroup(group);
        practice.setSlug("small-functions");
        practice.setName("Keep functions small");
        practice.setCriteria("Keep functions focused on one concern.");
        practiceRepository.save(practice);
    }

    @Test
    @WithUser
    @DisplayName("returns the caller's group and practice trends")
    void shouldReturnOwnTrend() {
        webTestClient
                .get()
                .uri(URI, workspace.getWorkspaceSlug(), "code-quality")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.group.slug")
                .isEqualTo("code-quality")
                .jsonPath("$.group.direction")
                .isEqualTo("INSUFFICIENT_EVIDENCE")
                .jsonPath("$.practices[0].slug")
                .isEqualTo("small-functions");
    }

    @Test
    @WithUser
    @DisplayName("denies a caller who is not a member of the workspace")
    void shouldDenyForeignCaller() {
        // A different workspace the caller has no membership in. The caller is the SAME signed-in user:
        // the test issuer resolves every mock token to `testuser`, so "someone else asks" cannot be
        // expressed by naming another username — only by asking about a workspace they are not in.
        User otherOwner = persistUser("other-owner");
        Workspace foreign = createWorkspace("foreign-ws", "Foreign WS", "foreign-org", AccountType.ORG, otherOwner);

        webTestClient
                .get()
                .uri(URI, foreign.getWorkspaceSlug(), "code-quality")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    @DisplayName("returns an RFC 7807 response for an unknown group")
    void shouldReturnProblemDetailForUnknownGroup() {
        webTestClient
                .get()
                .uri(URI, workspace.getWorkspaceSlug(), "unknown-group")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404);
    }
}
