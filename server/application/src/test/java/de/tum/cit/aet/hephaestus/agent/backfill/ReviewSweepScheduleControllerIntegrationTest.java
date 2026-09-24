package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The admin front door for a recurring sweep.
 *
 * <p>Two things are worth more here than the happy path: only a workspace admin may authorise a spend
 * that repeats unattended, and the window ceiling is refused at the boundary rather than trusted from the
 * client, because a longer window is a differently-selected population, not a bigger sweep.
 */
class ReviewSweepScheduleControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String SCHEDULES = "/workspaces/{slug}/practices/sweep-schedules";

    /**
     * A numeric JWT {@code sub}, which is what {@code SecurityUtils.getCurrentAccountId()} reads —
     * {@code @WithAdminUser}'s token carries a non-numeric one, and a schedule needs an account to
     * attribute the spend to.
     */
    private static final String ADMIN_ACCOUNT_TOKEN = "mock-jwt-sub-1";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReviewSweepScheduleRepository scheduleRepository;

    private Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("sweep-owner");
        workspace = createWorkspace("sweep-ws", "Sweep WS", "sweep-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
    }

    @Test
    void refusesAnAnonymousCaller() {
        webTestClient
                .post()
                .uri(SCHEDULES, workspace.getWorkspaceSlug())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("scm.pull_request", "DAILY", 2))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    /** Membership is not authority to commit the workspace's budget every night. */
    @Test
    @WithMentorUser
    void refusesAnOrdinaryMember() {
        User member = persistUser("mentor");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri(SCHEDULES, workspace.getWorkspaceSlug())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("scm.pull_request", "DAILY", 2))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void anAdminCreatesAScheduleThatIsAlreadyDue() {
        post(body("scm.pull_request", "DAILY", 2))
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.cadence")
                .isEqualTo("DAILY")
                .jsonPath("$.lookbackDays")
                .isEqualTo(2)
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.lastRunAt")
                .doesNotExist();

        ReviewSweepSchedule saved = scheduleRepository.findAll().getFirst();
        // So an admin who has just described a sweep can watch one happen rather than take it on faith.
        assertThat(saved.getNextRunAt()).isBefore(Instant.now().plus(Duration.ofHours(1)));
    }

    /**
     * The rule the whole design rests on, enforced where a client cannot route around it: a month-long
     * window is a hindsight-selected corpus, not the unbiased population this feature is meant to sample.
     */
    @Test
    void refusesAWindowLongerThanTheCadenceAllows() {
        post(body("scm.pull_request", "DAILY", 5)).expectStatus().isBadRequest().expectBody(Void.class);

        assertThat(scheduleRepository.findAll()).isEmpty();
    }

    @Test
    void refusesAKindNoCampaignCanEnumerate() {
        post(body("chat.conversation_thread", "DAILY", 1))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void refusesASecondScheduleForTheSameKindOfWork() {
        post(body("scm.pull_request", "DAILY", 2)).expectStatus().isCreated().expectBody(Void.class);

        post(body("scm.pull_request", "WEEKLY", 7))
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    @Test
    void anAdminChangesTheTermsAndSwitchesTheSweepOff() {
        post(body("scm.pull_request", "DAILY", 2)).expectStatus().isCreated().expectBody(Void.class);
        UUID scheduleId = scheduleRepository.findAll().getFirst().getId();

        webTestClient
                .put()
                .uri(SCHEDULES + "/{id}", workspace.getWorkspaceSlug(), scheduleId)
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cadence", "WEEKLY", "lookbackDays", 7, "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.cadence")
                .isEqualTo("WEEKLY")
                .jsonPath("$.enabled")
                .isEqualTo(false);
    }

    @Test
    void anAdminStopsSweeping() {
        post(body("scm.pull_request", "DAILY", 2)).expectStatus().isCreated().expectBody(Void.class);
        UUID scheduleId = scheduleRepository.findAll().getFirst().getId();

        webTestClient
                .delete()
                .uri(SCHEDULES + "/{id}", workspace.getWorkspaceSlug(), scheduleId)
                .headers(asAdminAccount())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(scheduleRepository.findAll()).isEmpty();
    }

    @Test
    void aScheduleFromAnotherWorkspaceIsNotFound() {
        webTestClient
                .delete()
                .uri(SCHEDULES + "/{id}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                .headers(asAdminAccount())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec post(Map<String, Object> body) {
        return webTestClient
                .post()
                .uri(SCHEDULES, workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static java.util.function.Consumer<HttpHeaders> asAdminAccount() {
        return headers -> headers.setBearerAuth(ADMIN_ACCOUNT_TOKEN);
    }

    private static Map<String, Object> body(String artifactKind, String cadence, int lookbackDays) {
        return Map.of("artifactKind", artifactKind, "cadence", cadence, "lookbackDays", lookbackDays);
    }
}
