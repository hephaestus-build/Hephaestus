package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves or reopens one thread on the work ({@link ActionKind#RESOLVE_FEEDBACK_THREAD}). Each attempt reads the
 * thread back before it changes it, so an attempt whose write may have started is safe to repeat.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackThreadResolutionAdapter implements ActionAdapter {
    private final FeedbackThreadResolutionService service;
    private final ObjectMapper mapper;

    @Override
    public ActionKind kind() {
        return ActionKind.RESOLVE_FEEDBACK_THREAD;
    }

    @Override
    public ActionVariants variants() {
        return ActionVariants.one(FeedbackThreadResolver.Payload.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        return service.execute(attempt, mapper.treeToValue(attempt.payload(), FeedbackThreadResolver.Payload.class));
    }

    /** The key is {@code feedback-thread:<resolution>:<generation>:<direction>}; an ended row keeps no payload. */
    @Override
    public void ended(ActionEnded ended) {
        String key = ended.idempotencyKey();
        int resolution = key.indexOf(':') + 1;
        int generation = resolution + 37;
        service.ended(
                Objects.requireNonNull(ended.workspaceId()),
                new FeedbackThreadResolver.Payload(
                        UUID.fromString(key.substring(resolution, generation - 1)),
                        Integer.parseInt(key.substring(generation, key.indexOf(':', generation)))),
                ended.state());
    }
}
