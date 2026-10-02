package de.tum.cit.aet.hephaestus.agent.conversation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The settled chat thread as a reviewable artifact. Unconditional on purpose, unlike {@code SlackManifest}:
 * a descriptor is the domain's ceiling — what the kind <em>is</em> — while a manifest is one vendor's
 * contribution to it. Gating this on Slack being enabled would make the bundled conversation practices
 * unauthorable, and then unloadable, on every instance that has not connected Slack.
 *
 * <p>Its single signal has no {@code producedBy}: no ingested event says a discussion has finished —
 * {@code ConversationThreadTriggerScheduler} decides it from quiescence, depth and growth — and an empty
 * set is the honest way to say "raised from somewhere other than ingestion".
 */
@Component
public class ConversationThreadArtifactDescriptor implements ArtifactDescriptor {

    private static final List<Signal> SIGNALS = List.of(new Signal(
            ChatSignals.CONVERSATION_THREAD_SETTLED,
            "Discussion settled",
            Set.of(),
            // What is new about a settled thread is what was said in it, and a thread has no commits at
            // all — the same reason an issue's signals key on a content digest.
            RevisionScheme.CONTENT_DIGEST,
            true));

    @Override
    public ArtifactKind kind() {
        return ChatSignals.CONVERSATION_THREAD;
    }

    @Override
    public String displayName() {
        return "Conversation thread";
    }

    @Override
    public List<Signal> signals() {
        return SIGNALS;
    }

    /**
     * Every human turn is authored, and there is nobody else: a thread has no owner to assign it to and
     * no reviewer of it, so a practice about a conversation can only ever be about who wrote in it.
     */
    @Override
    public Set<ActorRole> roles() {
        return Set.of(ActorRole.AUTHOR);
    }

    /**
     * No diff, so no inline lane. Feedback about how somebody took part in a discussion reaches them in
     * the mentor conversation, not as a reply in the thread the rest of the channel is reading.
     */
    @Override
    public Set<FeedbackLane> lanes() {
        return Set.of(FeedbackLane.IN_CHAT, FeedbackLane.IN_APP);
    }

    /** A thread is one room. What was decided in another one, or in a call, is not in it. */
    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "PRIVATE_CONTEXT_NOT_OBSERVED",
                        "The captured thread does not include decisions or context shared outside the conversation.")),
                List.of(),
                Set.of(),
                Set.of());
    }

    @Override
    public boolean reviewable() {
        return true;
    }
}
