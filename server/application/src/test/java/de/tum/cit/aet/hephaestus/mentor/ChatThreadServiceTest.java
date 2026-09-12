package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.ObjectMapper;

/**
 * Service-layer unit tests. Pin the workspace + owner scoping the controllers rely on for
 * 404-vs-leak semantics.
 */
class ChatThreadServiceTest extends BaseUnitTest {

    @Mock
    private ChatThreadRepository chatThreadRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UserRepository userRepository;

    private ChatThreadService service;

    private static final Long WORKSPACE_ID = 42L;
    private static final Long OWNER_USER_ID = 7L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChatThreadService(chatThreadRepository, chatMessageRepository, userRepository, objectMapper);
    }

    @Test
    void getOwnedThread_missingThreadThrows() {
        UUID threadId = UUID.randomUUID();
        when(userRepository.getCurrentUserElseThrow()).thenReturn(stubUser(OWNER_USER_ID));
        when(chatThreadRepository.findByIdAndWorkspaceIdAndUserId(threadId, WORKSPACE_ID, OWNER_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownByEnityNotFound(() -> service.getOwnedThread(WORKSPACE_ID, threadId));
    }

    @Test
    void getOwnedThread_returnsForOwner() {
        UUID threadId = UUID.randomUUID();
        User owner = stubUser(OWNER_USER_ID);
        ChatThread thread = stubThread(threadId, owner);
        when(userRepository.getCurrentUserElseThrow()).thenReturn(owner);
        when(chatThreadRepository.findByIdAndWorkspaceIdAndUserId(threadId, WORKSPACE_ID, OWNER_USER_ID))
                .thenReturn(Optional.of(thread));

        ChatThread resolved = service.getOwnedThread(WORKSPACE_ID, threadId);

        assertThat(resolved).isSameAs(thread);
    }

    private static User stubUser(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private ChatThread stubThread(UUID id, User owner) {
        ChatThread thread = new ChatThread();
        thread.setId(id);
        thread.setUser(owner);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        thread.setWorkspace(workspace);
        return thread;
    }

    private static void assertThatThrownByEnityNotFound(Runnable runnable) {
        Assertions.assertThatThrownBy(runnable::run).isInstanceOf(EntityNotFoundException.class);
    }
}
