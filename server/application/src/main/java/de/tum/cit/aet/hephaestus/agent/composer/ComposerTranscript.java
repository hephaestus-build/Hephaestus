package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerRequests.Answers;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerRequests.Reply;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerRequests.User;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscriptPart.Kind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversation that the server allows each claim of a {@code review_compose} run to have with its model. It
 * records what the server issued (each session and its turns, each tool's answer) and what the gateway served (each
 * reply, linked to the exact conversation it answered, and each tool call). It holds two checks of one boundary:
 *
 * <ul>
 *   <li>The gateway forwards a model request of the claim only when it continues the conversation as the composer does:
 *       the session as issued, with its settings; the session's input; then each served reply to exactly the
 *       conversation before it, followed by the answers to its calls (with the session's nudge once) or, for a reply
 *       without calls, by the session's finish once. A conversation that already got its answer is not sent again;
 *       copies sent at the same time can each get one, and each is the model's own. No
 *       text that a sandbox process writes, and no reply sampled for another conversation, reaches the model.
 *   <li>A composition tool route runs a call only when the claim's model wrote it in a session of the same
 *       destination, and only once.
 * </ul>
 */
@Component
@ConditionalOnServerRole
public class ComposerTranscript {

    private static final int MAX_CALL_ID = 256;
    private static final int MAX_TOOL = 64;

    private final ComposerTranscriptPartRepository parts;
    private final AgentRuns runs;
    private final ObjectMapper mapper;
    private final ComposerRequests requests;

    public ComposerTranscript(ComposerTranscriptPartRepository parts, AgentRuns runs, ObjectMapper mapper) {
        this.parts = parts;
        this.runs = runs;
        this.mapper = mapper;
        this.requests = new ComposerRequests(mapper);
    }

    /** What the gateway does with a model request of a compose claim. */
    public sealed interface Check permits Forward, Refuse {}

    /**
     * Forward the request.
     *
     * @param conversation the digest of the conversation the request holds, which its answer's reply is linked to
     * @param destination the destination of the request's session
     */
    public record Forward(String conversation, String destination) implements Check {}

    /** @param reason a fixed reason, never content of the request */
    public record Refuse(String reason) implements Check {}

    /** Whether the run composes, so that its claim's model calls must be the composer's. */
    @Transactional(readOnly = true)
    public boolean composes(long workspaceId, UUID runId) {
        return runs.find(new AgentRunRef(runId, workspaceId))
                .filter(run -> run.getKind() == AgentRunKind.REVIEW_COMPOSE)
                .isPresent();
    }

    /** Records a session that the server gave the claim, and the turns it may send. */
    @Transactional
    public void issued(AgentRunFence fence, CompositionSessions.Session session) {
        String digest = requests.session(session);
        parts.add(
                fence.workspaceId(),
                fence.runId(),
                fence.epoch(),
                Kind.SESSION.name(),
                digest,
                session.destination().name());
        add(fence, Kind.TEXT, ComposerRequests.link(digest, "input", requests.textDigest(session.input())));
        add(fence, Kind.TEXT, ComposerRequests.link(digest, "nudge", requests.textDigest(session.nudge())));
        String finish = session.finish();
        if (finish != null) add(fence, Kind.TEXT, ComposerRequests.link(digest, "finish", requests.textDigest(finish)));
    }

    /** Records a tool's answer that the server gave the claim for one call of a session of this destination. */
    @Transactional
    public void answered(AgentRunFence fence, String destination, String callId, String answer) {
        add(fence, Kind.ANSWER, ComposerRequests.link(destination, callId, requests.textDigest(answer)));
    }

    /** Whether a model request of the claim continues its conversation as the composer does. */
    @Transactional(readOnly = true)
    public Check check(long workspaceId, UUID runId, int epoch, LlmApiProtocol protocol, byte[] body) {
        JsonNode request;
        try {
            request = mapper.readTree(body);
        } catch (JacksonException notJson) {
            return new Refuse("not JSON");
        }
        ComposerRequests.Parts read;
        switch (requests.read(protocol, request)) {
            case ComposerRequests.Refused refused -> {
                return new Refuse(refused.reason());
            }
            case ComposerRequests.Parts found -> read = found;
        }
        Map<String, String> sessions = new HashMap<>();
        Set<String> texts = new HashSet<>();
        Set<String> answers = new HashSet<>();
        Set<String> replies = new HashSet<>();
        Set<String> served = new HashSet<>();
        for (var part : parts.known(workspaceId, runId, epoch)) {
            switch (Kind.valueOf(part.getKind())) {
                case SESSION -> sessions.put(part.getDigest(), String.valueOf(part.getDestination()));
                case TEXT -> texts.add(part.getDigest());
                case ANSWER -> answers.add(part.getDigest());
                case REPLY -> replies.add(part.getDigest());
                case SERVED -> served.add(part.getDigest());
                case CALL -> {}
            }
        }
        String session = read.session();
        String destination = sessions.get(session);
        if (destination == null) return new Refuse("session");
        List<ComposerRequests.Turn> turns = read.turns();
        if (turns.isEmpty()
                || !(turns.getFirst() instanceof User input)
                || !texts.contains(ComposerRequests.link(session, "input", input.text()))) return new Refuse("input");
        String conversation = ComposerRequests.link(session, "user", input.text());
        int at = 1;
        boolean finished = false;
        boolean nudged = false;
        // An answer that the composer keeps nothing of, as an empty responses output, is followed by the finish.
        if (at < turns.size() && turns.get(at) instanceof User turn) {
            if (!texts.contains(ComposerRequests.link(session, "finish", turn.text()))) return new Refuse("text");
            conversation = ComposerRequests.link(conversation, "user", turn.text());
            finished = true;
            at++;
        }
        while (at < turns.size()) {
            if (!(turns.get(at) instanceof Reply reply)) return new Refuse("order");
            if (!replies.contains(ComposerRequests.link(conversation, reply.digest()))) return new Refuse("reply");
            conversation = ComposerRequests.link(conversation, "reply", reply.digest());
            at++;
            if (reply.callIds().isEmpty()) {
                // A reply without calls gets the session's finish, once.
                if (finished || at >= turns.size() || !(turns.get(at) instanceof User turn))
                    return new Refuse("finish");
                if (!texts.contains(ComposerRequests.link(session, "finish", turn.text()))) return new Refuse("text");
                conversation = ComposerRequests.link(conversation, "user", turn.text());
                finished = true;
                at++;
                continue;
            }
            if (at >= turns.size() || !(turns.get(at) instanceof Answers block)) return new Refuse("answers");
            var ids = block.answers().stream()
                    .map(ComposerRequests.Answer::callId)
                    .toList();
            if (!ids.equals(reply.callIds())) return new Refuse("answers");
            for (var answer : block.answers()) {
                if (!answers.contains(ComposerRequests.link(destination, answer.callId(), answer.text())))
                    return new Refuse("answer");
                conversation = ComposerRequests.link(conversation, "answer", answer.callId(), answer.text());
            }
            at++;
            // The answers to a reply's calls may carry the session's nudge, once.
            if (at < turns.size()
                    && turns.get(at) instanceof User turn
                    && texts.contains(ComposerRequests.link(session, "nudge", turn.text()))) {
                if (nudged) return new Refuse("text");
                conversation = ComposerRequests.link(conversation, "user", turn.text());
                nudged = true;
                at++;
            }
            // An answer to them that the composer keeps nothing of is followed by the finish.
            if (at < turns.size() && turns.get(at) instanceof User turn) {
                if (finished || !texts.contains(ComposerRequests.link(session, "finish", turn.text())))
                    return new Refuse("text");
                conversation = ComposerRequests.link(conversation, "user", turn.text());
                finished = true;
                at++;
            }
        }
        // The composer sends a conversation again only when it got no answer to keep.
        if (served.contains(conversation)) return new Refuse("answered");
        return new Forward(conversation, destination);
    }

    /**
     * Records the reply and tool calls of an answer that the gateway served for a forwarded request, before the answer
     * leaves. An answer that is not JSON records nothing.
     */
    @Transactional
    public void served(long workspaceId, UUID runId, int epoch, LlmApiProtocol protocol, Forward forward, byte[] body) {
        JsonNode answer;
        try {
            answer = mapper.readTree(body);
        } catch (JacksonException notJson) {
            return;
        }
        var fence = new AgentRunFence(runId, workspaceId, epoch);
        Reply reply = requests.reply(protocol, answer);
        if (reply != null) {
            add(fence, Kind.REPLY, ComposerRequests.link(forward.conversation(), reply.digest()));
            add(fence, Kind.SERVED, forward.conversation());
        }
        for (var call : requests.calls(protocol, answer)) {
            if (call.id().isBlank() || call.id().length() > MAX_CALL_ID) continue;
            if (call.tool().isBlank() || call.tool().length() > MAX_TOOL) continue;
            parts.addCall(
                    workspaceId,
                    runId,
                    epoch,
                    call.id(),
                    call.tool(),
                    callDigest(forward.destination(), call.arguments()));
        }
    }

    /**
     * Uses one tool call that the claim's model wrote in a session of this destination; false when it wrote none like
     * it that is still unused.
     */
    @Transactional
    public boolean useCall(
            AgentRunFence fence, String destination, String callId, String tool, @Nullable JsonNode arguments) {
        return parts.useCall(
                        fence.workspaceId(),
                        fence.runId(),
                        fence.epoch(),
                        callId,
                        tool,
                        callDigest(destination, arguments))
                == 1;
    }

    private String callDigest(String destination, @Nullable JsonNode arguments) {
        return ComposerRequests.link(destination, requests.digest(arguments));
    }

    private void add(AgentRunFence fence, Kind kind, String digest) {
        parts.add(fence.workspaceId(), fence.runId(), fence.epoch(), kind.name(), digest, null);
    }
}
