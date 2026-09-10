package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.ThreadSurface;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** No chat messages are seeded: this entity-derived schema lacks the Liquibase-only delete cascade.
 * Message cascade behavior is not covered here. */
class DefaultMentorSlackThreadServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MentorSlackThreadService mentorSlackThreadService;

    @Autowired
    private ChatThreadRepository chatThreadRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    private static final AtomicLong USER_SEQ = new AtomicLong(3_000_000L);

    private User user;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        user = userRepository.save(
                TestUserFactory.createUser(USER_SEQ.incrementAndGet(), "mentor-thread-user", provider));
    }

    @Test
    @DisplayName("purgeSlackThreads erases only the workspace's SLACK_DM threads; WEB + other workspaces survive")
    void purgeSlackThreads_erasesSlackDmOnly_keepsWebAndOtherWorkspace() {
        Workspace a = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-purge-threads-a"));
        Workspace b = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-purge-threads-b"));

        UUID aSlackDm = seedThread(a, ThreadSurface.SLACK_DM);
        UUID aWeb = seedThread(a, ThreadSurface.WEB);
        UUID bSlackDm = seedThread(b, ThreadSurface.SLACK_DM);

        int purged = mentorSlackThreadService.purgeSlackThreads(a.getId());

        assertThat(purged).isEqualTo(1);

        assertThat(chatThreadRepository.findById(aSlackDm)).isEmpty();

        assertThat(chatThreadRepository.findById(aWeb)).isPresent();

        assertThat(chatThreadRepository.findById(bSlackDm)).isPresent();
    }

    @Test
    void shouldKeepVerifiedActorWhenAnotherProviderHasTheSameLogin() {
        var provider = identityProviderRepository.save(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://mentor-identity.example.com"));
        var verifiedActor =
                userRepository.save(TestUserFactory.createUser(USER_SEQ.incrementAndGet(), user.getLogin(), provider));
        var actorId = verifiedActor.getId();
        assertNotNull(actorId);
        var workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-verified-actor"));
        var workspaceId = workspace.getId();
        assertNotNull(workspaceId);

        UUID threadId = mentorSlackThreadService.ensureSlackThread(workspaceId, null, actorId);
        assertThat(chatThreadRepository.findById(threadId))
                .get()
                .extracting(thread -> thread.getUser().getId())
                .isEqualTo(actorId);

        CurrentScmIdentityHolder.set(actorId, verifiedActor.getLogin());
        try {
            assertThat(userRepository.getCurrentUser())
                    .get()
                    .extracting(User::getId)
                    .isEqualTo(actorId);
        } finally {
            CurrentScmIdentityHolder.clear();
        }
        assertThat(CurrentScmIdentityHolder.getUserId()).isEmpty();
        assertThat(CurrentScmIdentityHolder.getLogin()).isEmpty();
    }

    @Test
    void shouldNotFallBackToLoginWhenPinnedActorNoLongerExists() {
        CurrentScmIdentityHolder.set(-1L, user.getLogin());
        try {
            assertThat(userRepository.getCurrentUser()).isEmpty();
        } finally {
            CurrentScmIdentityHolder.clear();
        }
    }

    private UUID seedThread(Workspace workspace, ThreadSurface surface) {
        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID());
        thread.setTitle(surface + " thread");
        thread.setUser(user);
        thread.setWorkspace(workspace);
        thread.setSurface(surface);
        return chatThreadRepository.save(thread).getId();
    }
}
