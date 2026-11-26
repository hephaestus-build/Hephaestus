package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.conversation.LegacyDigestCutoffs;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class AgentRunTiming {

    record Reading(@Nullable Instant replayNow, String legacyDigestCutoffs) {}

    private final Clock clock;
    private final LegacyDigestCutoffs legacy;

    AgentRunTiming(Clock clock, LegacyDigestCutoffs legacy) {
        this.clock = clock;
        this.legacy = legacy;
    }

    @Nullable
    Instant replayNow() {
        return ControlledClock.replayInstant(clock);
    }

    Reading read(boolean includeDigests) {
        String cutoffs = includeDigests ? legacy.read() : "{}";
        return new Reading(replayNow(), cutoffs);
    }
}
