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
        String cutoffs = includeDigests ? legacy.read(workspaceIds(), List.of(), true) : "{}";
        return new Reading(replayNow(), cutoffs);
    }

    List<Long> workspaceIds() {
        return workspaces.findIds();
    }

    Reading readSubjects(boolean includeDigests, Collection<Long> workspaceIds, Collection<String> subjects) {
        if (!includeDigests) return read(false);
        var conversations = new ArrayList<UUID>();
        for (String subject : subjects) {
            UUID id = conversationId(subject);
            if (id != null) conversations.add(id);
        }
        return new Reading(replayNow(), legacy.read(workspaceIds, conversations, false));
    }

    static @Nullable UUID conversationId(String subject) {
        if (!subject.startsWith("conversation:")) return null;
        try {
            return UUID.fromString(subject.substring("conversation:".length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    Reading refresh(Reading reading) {
        return new Reading(replayNow(), reading.legacyDigestCutoffs());
    }
}
