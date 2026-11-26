package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.conversation.LegacyDigestCutoffs;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class AgentRunTiming {

    record Reading(@Nullable Instant replayNow, String legacyDigestCutoffs) {}

    private final Clock clock;
    private final LegacyDigestCutoffs legacy;
    private final WorkspaceQueryService workspaces;

    AgentRunTiming(Clock clock, LegacyDigestCutoffs legacy, WorkspaceQueryService workspaces) {
        this.clock = clock;
        this.legacy = legacy;
        this.workspaces = workspaces;
    }

    @Nullable
    Instant replayNow() {
        return ControlledClock.replayInstant(clock);
    }

    Reading read(boolean includeDigests) {
        String cutoffs = includeDigests ? legacy.read(workspaces.findIds(), List.of(), true) : "{}";
        return new Reading(replayNow(), cutoffs);
    }

    Reading readSubjects(boolean includeDigests, Collection<Long> workspaceIds, Collection<String> subjects) {
        if (!includeDigests) return read(false);
        var conversations = new ArrayList<UUID>();
        for (String subject : subjects) {
            if (!subject.startsWith("conversation:")) continue;
            try {
                conversations.add(UUID.fromString(subject.substring("conversation:".length())));
            } catch (IllegalArgumentException ignored) {
                // A subject without a conversation ID has no digest stimulus.
            }
        }
        return new Reading(replayNow(), legacy.read(workspaceIds, conversations, false));
    }

    Reading refresh(Reading reading) {
        return new Reading(replayNow(), reading.legacyDigestCutoffs());
    }
}
