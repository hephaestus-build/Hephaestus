package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentProperties;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MentorChatControllerTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MentorAgentProperties TEST_PROPERTIES = new MentorAgentProperties(100_000);

    @Mock
    MentorChatService mentorChatService;

    private MentorChatController controller;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new MentorChatController(mentorChatService, MAPPER, TEST_PROPERTIES);
        response = new MockHttpServletResponse();
    }

    @Test
    void setsProtocolResponseHeader() {
        controller.chat(stubContext(), validBody(UUID.randomUUID(), "hi"), response);
        assertThat(response.getHeader(UIMessageChunk.RESPONSE_HEADER)).isEqualTo(UIMessageChunk.PROTOCOL_VERSION);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void dispatchesToService() {
        UUID threadId = UUID.randomUUID();
        controller.chat(stubContext(), validBody(threadId, "hello mentor"), response);
        ArgumentCaptor<MentorTurnRequest> req = ArgumentCaptor.forClass(MentorTurnRequest.class);
        verify(mentorChatService).start(req.capture(), any());
        assertThat(req.getValue().threadId()).isEqualTo(threadId);
        assertThat(req.getValue().userMessage()).isEqualTo("hello mentor");
    }

    @Test
    void propagatesClientMessageId() {
        UUID clientMessageId = UUID.randomUUID();
        MentorChatRequestBody body = body(UUID.randomUUID(), clientMessageId.toString(), "hello");
        controller.chat(stubContext(), body, response);
        ArgumentCaptor<MentorTurnRequest> req = ArgumentCaptor.forClass(MentorTurnRequest.class);
        verify(mentorChatService).start(req.capture(), any());
        assertThat(req.getValue().clientUserMessageId()).isEqualTo(clientMessageId);
    }

    @Test
    void ignoresNonUuidClientMessageId() {
        MentorChatRequestBody body = body(UUID.randomUUID(), "not-a-uuid", "hello");
        controller.chat(stubContext(), body, response);
        ArgumentCaptor<MentorTurnRequest> req = ArgumentCaptor.forClass(MentorTurnRequest.class);
        verify(mentorChatService).start(req.capture(), any());
        assertThat(req.getValue().clientUserMessageId()).isNull();
    }

    @Test
    void blankUserMessage_shortCircuits() {
        SseEmitter emitter = controller.chat(stubContext(), body(UUID.randomUUID(), null, "   "), response);
        verify(mentorChatService, never()).start(any(), any());
        assertThat(emitter).isNotNull();
        assertThat(response.getHeader(UIMessageChunk.RESPONSE_HEADER)).isEqualTo(UIMessageChunk.PROTOCOL_VERSION);
    }

    @Test
    void oversizeUserMessage_shortCircuits() {
        String hugeText = "x".repeat(TEST_PROPERTIES.maxPromptChars() + 1);
        SseEmitter emitter = controller.chat(stubContext(), body(UUID.randomUUID(), null, hugeText), response);
        verify(mentorChatService, never()).start(any(), any());
        assertThat(emitter).isNotNull();
        assertThat(response.getHeader(UIMessageChunk.RESPONSE_HEADER)).isEqualTo(UIMessageChunk.PROTOCOL_VERSION);
    }

    @Test
    @DisplayName("user message exactly MAX_PROMPT_CHARS is accepted and dispatched")
    void boundarySizedUserMessage_dispatches() {
        String exactlyAtCap = "x".repeat(TEST_PROPERTIES.maxPromptChars());
        UUID threadId = UUID.randomUUID();
        controller.chat(stubContext(), body(threadId, null, exactlyAtCap), response);
        ArgumentCaptor<MentorTurnRequest> req = ArgumentCaptor.forClass(MentorTurnRequest.class);
        verify(mentorChatService).start(req.capture(), any());
        // The message must arrive whole — a cap that truncated instead of rejecting would still dispatch.
        assertThat(req.getValue().userMessage()).hasSize(TEST_PROPERTIES.maxPromptChars());
        assertThat(req.getValue().threadId()).isEqualTo(threadId);
        assertThat(response.getHeader(UIMessageChunk.RESPONSE_HEADER)).isEqualTo(UIMessageChunk.PROTOCOL_VERSION);
    }

    private static WorkspaceContext stubContext() {
        return new WorkspaceContext(1L, "test-ws", "Test", AccountType.ORG, null, false, Set.of(WorkspaceRole.MEMBER));
    }

    private static MentorChatRequestBody validBody(UUID threadId, String text) {
        return body(threadId, null, text);
    }

    private static MentorChatRequestBody body(UUID threadId, @Nullable String messageId, String text) {
        ObjectNode root = MAPPER.createObjectNode();
        if (messageId != null) {
            root.put("id", messageId);
        }
        var partsArray = root.putArray("parts");
        var part = partsArray.addObject();
        part.put("type", "text");
        part.put("text", text);
        return new MentorChatRequestBody(threadId, root);
    }
}
