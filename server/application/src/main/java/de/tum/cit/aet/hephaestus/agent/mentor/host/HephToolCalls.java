package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

/**
 * One log line for each tool call of a conversation turn: the tool, how it ended and how long it took, never its arguments or
 * its answer. A turn that keeps calling a tool shows as a run of these lines; a repeat of a call is its own line.
 */
final class HephToolCalls {

    private static final Logger log = LoggerFactory.getLogger(HephToolCalls.class);

    private HephToolCalls() {}

    /**
     * Runs {@code call} and logs it.
     *
     * @param outcome names how an answered call ended, for example {@code ok} or a refusal code
     */
    static <T> T logged(TurnAttempt turn, String tool, Supplier<T> call, Function<T, String> outcome) {
        long started = System.nanoTime();
        String ended = "error";
        try {
            T answer = call.get();
            ended = outcome.apply(answer);
            return answer;
        } catch (ResponseStatusException refused) {
            ended = "refused";
            throw refused;
        } finally {
            log.atInfo()
                    .addKeyValue("event.name", "heph.turn.tool_call")
                    .addKeyValue("turn.id", turn.turnId())
                    .addKeyValue("turn.attempt", turn.attempt())
                    .addKeyValue("gen_ai.tool.name", tool)
                    .addKeyValue("outcome", ended)
                    .addKeyValue(
                            "duration.ms",
                            Duration.ofNanos(System.nanoTime() - started).toMillis())
                    .log("Hephaestus tool call");
        }
    }
}
