package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.mentor.context.ConversationContextDocuments;
import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * One attempt's context folder: its captured documents, the items that {@code load_context} loads, and the tools Job
 * that runs its commands. The loop asks the gateway for the documents on each read and puts them into the Job; the Job
 * holds no credential and reaches nothing on the network.
 */
@Service
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
public class ConversationFolder {

    /** How many items one attempt may load. */
    static final int MAX_LOADS_PER_ATTEMPT = 8;

    private final ConversationAssignments assignments;
    private final ConversationTurnContexts contexts;
    private final ConversationContextDocuments documents;
    private final ToolsJobs tools;
    private final Clock clock;

    public ConversationFolder(
            ConversationAssignments assignments,
            ConversationTurnContexts contexts,
            ConversationContextDocuments documents,
            ToolsJobs tools,
            Clock clock) {
        this.assignments = assignments;
        this.contexts = contexts;
        this.documents = documents;
        this.tools = tools;
        this.clock = clock;
    }

    /**
     * The attempt's tools Job, bound to its lease and to the context it may read now.
     *
     * @throws ResponseStatusException {@code 503} when the release's tools quota has no room: the model answers
     *     without the tools
     */
    public ToolsJobs.Endpoint acquire(TurnAttempt turn) {
        try {
            return tools.acquire(
                    turn.workspaceId(),
                    turn.turnId(),
                    turn.attempt(),
                    turn.leaseId(),
                    turn.contextGeneration(),
                    assignments.deadline(turn));
        } catch (SandboxCapacityException full) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Every tools sandbox is in use. Answer without the tools.", full);
        }
    }

    /** Deletes the attempt's Job of {@code generation}; a Job of a later generation stays. */
    public void discard(TurnAttempt turn, UUID generation) {
        tools.discard(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.leaseId(), generation);
    }

    /** The attempt ended: nothing of its folder is kept. Its Job goes when its end commits. */
    public void ended(TurnAttempt turn) {
        contexts.forget(turn);
    }

    /** The folder's documents as one tar. */
    public byte[] archive(TurnAttempt turn) {
        return documents.archive(turn, contexts.folderDocuments(turn));
    }

    public Optional<String> document(TurnAttempt turn, String path) {
        return documents.document(turn, contexts.folderDocuments(turn), path);
    }

    /** {@code load_context}, within the attempt's load budget and deadline. */
    public ConversationContextDocuments.Load load(TurnAttempt turn, String requested) {
        if (!contexts.countLoad(turn, MAX_LOADS_PER_ATTEMPT)) {
            return new ConversationContextDocuments.Load.Refused(
                    "BUDGET_EXHAUSTED", "This answer already loaded " + MAX_LOADS_PER_ATTEMPT + " items.");
        }
        Instant deadline = assignments.deadline(turn);
        return documents.load(turn, requested, () -> !clock.instant().isBefore(deadline));
    }
}
