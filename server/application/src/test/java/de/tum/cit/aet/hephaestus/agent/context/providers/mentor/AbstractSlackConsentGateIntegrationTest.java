package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared scaffold for the three {@code *ConsentGateIntegrationTest} classes ({@code ObservationHistory},
 * {@code PreparedConversationFeedback}, {@code DeliveredFeedback}): each proves the same fail-closed
 * {@code consent_state = 'ACTIVE'} gate over a different content source, so each needs the same workspace +
 * recipient bootstrap and the same "one monitored channel + one thread on it, at a given consent state" seed.
 */
abstract class AbstractSlackConsentGateIntegrationTest extends BaseIntegrationTest {

    static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    AgentJobRepository agentJobRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    WorkspaceMembershipRepository workspaceMembershipRepository;

    @Autowired
    IdentityProviderRepository identityProviderRepository;

    @Autowired
    SlackThreadRepository slackThreadRepository;

    @Autowired
    SlackMonitoredChannelRepository slackMonitoredChannelRepository;

    Workspace workspace = new Workspace();
    User recipient = new User();

    /** Saves the workspace + a GitHub-identity recipient user; call from each subclass's {@code @BeforeEach}. */
    void setUpWorkspaceAndRecipient(String workspaceSlug) {
        Workspace activeWorkspace = WorkspaceTestFixtures.activeWorkspace(workspaceSlug);
        activeWorkspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(activeWorkspace);
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(100L, "recipient", provider));
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(recipient);
        workspaceMembershipRepository.save(membership);
    }

    /** Seed a monitored channel at {@code consent} plus one thread on it; return the generated {@code slack_thread.id}. */
    long seedThread(String channelId, String threadTs, ConsentState consent) {
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspace.getId());
        channel.setSlackTeamId("T1");
        channel.setSlackChannelId(channelId);
        channel.setConsentState(consent);
        slackMonitoredChannelRepository.save(channel);

        SlackThread thread = new SlackThread();
        thread.setWorkspaceId(workspace.getId());
        thread.setSlackChannelId(channelId);
        thread.setSlackThreadTs(threadTs);
        return slackThreadRepository.save(thread).getId();
    }

    AgentJob newJob() {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.CONVERSATION_REVIEW);
        job.setMetadata(OM.valueToTree(Map.of("about_user_id", recipient.getId())));
        job.setPracticeRolloutRevision(workspace.getReviewSettings().getRolloutRevision());
        job.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OM.readTree("{\"manifest\":{\"contractVersion\":\"1.1.0\"}}"));
        return agentJobRepository.save(job);
    }
}
