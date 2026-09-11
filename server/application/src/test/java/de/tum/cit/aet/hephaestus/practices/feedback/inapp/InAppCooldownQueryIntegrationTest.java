package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lastInAppSurfacedAt} — the cooldown that stops one habit being restated on every pull
 * request. It is a {@code MAX()} over an implicit join, and every first-ever call for a workspace hits
 * its empty case, so what a NULL aggregate maps to is the behaviour the whole lane starts from: an empty
 * Optional means "never said", and anything else here would take the lane down through the listener's
 * catch-all.
 */
@Transactional
class InAppCooldownQueryIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PRACTICE_SLUG = "ships-tests-with-changes";

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private User recipient;

    @BeforeEach
    void setUp() {
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("in-app-cooldown"));
        otherWorkspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("in-app-cooldown-other"));
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(9_149_600L, "cooldown-recipient", provider));
    }

    @Test
    @DisplayName("nothing said yet is an empty answer, not a failure")
    void answersEmptyWhenNothingWasEverSurfaced() {
        assertThat(feedbackRepository.lastInAppSurfacedAt(workspace.getId(), recipient.getId(), PRACTICE_SLUG))
                .isEmpty();
    }

    @Test
    @DisplayName("answers with the newest message about this habit, and only from this workspace")
    void answersTheNewestWithinTheWorkspace() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        surfaced(workspace, older);
        surfaced(workspace, newer);
        // The same person, the same practice slug, a different tenant: a message said over there must not
        // silence this workspace's lane.
        surfaced(otherWorkspace, Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(feedbackRepository.lastInAppSurfacedAt(workspace.getId(), recipient.getId(), PRACTICE_SLUG))
                .contains(newer);
    }

    /** One IN_APP unit about {@link #PRACTICE_SLUG}, written for {@link #recipient} in this workspace. */
    private void surfaced(Workspace ws, Instant createdAt) {
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ws.getId(), PRACTICE_SLUG)
                .orElseGet(() -> {
                    Practice created = new Practice();
                    created.setWorkspace(ws);
                    created.setSlug(PRACTICE_SLUG);
                    created.setName("Ships Tests With Changes");
                    created.setCriteria("Criteria");
                    created.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
                    created.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
                    return practiceRepository.saveAndFlush(created);
                });

        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job = agentJobRepository.save(job);

        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "occ-" + observationId,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                "scm.pull_request",
                42L,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                null,
                "reasoning",
                null,
                createdAt,
                "LIVE");

        Feedback unit = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(ws.getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_APP)
                .position(7000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body("### A habit\n\nWhat keeps happening.\n\n**Try next:** Something.")
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .build());
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
    }
}
