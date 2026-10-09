package de.tum.cit.aet.hephaestus.agent.gateway;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Binds a review attempt's gateway session when it is registered, before its sandbox starts. What the listener needs to
 * know about the attempt is captured then: an upload arrives later, when the job may already belong to another attempt.
 */
@FunctionalInterface
public interface SandboxResultListener {
    /**
     * @param attempt the attempt the sandbox is launched for
     * @param image the image the sandbox is launched from
     * @return what to do with the session's first admitted upload, relative to the output root; it must not throw
     */
    Consumer<Map<String, byte[]>> bind(UUID jobId, int attempt, String image);
}
