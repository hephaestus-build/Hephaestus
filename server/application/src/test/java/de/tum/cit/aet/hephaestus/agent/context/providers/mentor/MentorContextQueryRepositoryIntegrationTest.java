package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures.admittedMentorConfig;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnPersistence;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code findFirstUserMessagePartsByThreadIds} is native SQL, so it isn't boot-validated like JPQL — a
 * jsonb type-mapping regression could silently empty the prior-conversation context. Runs against a real
 * Postgres container to pin the {@code DISTINCT ON} and jsonb-to-text projection contracts.
 */
class MentorContextQueryRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MentorContextQueryRepository queryRepository;

    @Autowired
    private MentorTurnPersistence persistence;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private Workspace workspace;
    private User user;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mentor-context-q");
        workspace.setDisplayName("Mentor Context Query Workspace");
        workspace.setAccountLogin("mentor-context-org");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaceRepository.save(workspace);

        IdentityProvider gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));

        user = new User();
        user.setNativeId(8_001L);
        user.setLogin("context-tester");
        user.setName("Context Tester");
        user.setAvatarUrl("https://example.com/a.png");
        user.setHtmlUrl("https://gitlab.com/context-tester");
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(gitProvider);
        user = userRepository.save(user);
    }

    private ChatThread seedThreadWithUserMessage(String firstPrompt) {
        ChatThread thread =
                persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, Set.of(user.getId()), firstPrompt);
        persistence.persistInFlight(thread, firstPrompt, UUID.randomUUID(), null, admittedMentorConfig());
        return thread;
    }

    @Test
    void returnsEarliestUserMessagePartsPerThread() {
        ChatThread t1 = seedThreadWithUserMessage("how do I structure my PR description?");
        ChatThread t2 = seedThreadWithUserMessage("what makes a good test?");

        List<Object[]> rows = queryRepository.findFirstUserMessagePartsByThreadIds(
                workspace.getId(), List.of(t1.getId(), t2.getId()));

        Map<UUID, String> byThread = new HashMap<>();
        for (Object[] row : rows) {
            byThread.put((UUID) row[0], row[1] == null ? null : row[1].toString());
        }

        assertThat(byThread).containsKeys(t1.getId(), t2.getId());
        // parts is projected as jsonb-cast-to-text; the user's prompt text is embedded in it.
        assertThat(byThread.get(t1.getId())).contains("how do I structure my PR description?");
        assertThat(byThread.get(t2.getId())).contains("what makes a good test?");
    }

    @Test
    void excludesThreadsFromOtherWorkspaces() {
        ChatThread mine = seedThreadWithUserMessage("my prompt");

        Workspace other = new Workspace();
        other.setWorkspaceSlug("other-context-ws");
        other.setDisplayName("Other");
        other.setAccountLogin("other-org");
        other.setAccountType(AccountType.ORG);
        other = workspaceRepository.save(other);
        ChatThread foreign = persistence.ensureThread(
                other.getId(), UUID.randomUUID(), user, Set.of(user.getId()), "foreign prompt");
        persistence.persistInFlight(foreign, "foreign prompt", UUID.randomUUID(), null, admittedMentorConfig());

        List<Object[]> rows = queryRepository.findFirstUserMessagePartsByThreadIds(
                workspace.getId(), List.of(mine.getId(), foreign.getId()));

        assertThat(rows).hasSize(1);
        assertThat((UUID) rows.get(0)[0]).isEqualTo(mine.getId());
    }
}
