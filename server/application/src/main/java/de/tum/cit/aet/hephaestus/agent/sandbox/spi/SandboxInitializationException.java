package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import de.tum.cit.aet.hephaestus.observability.CredentialMaskingCustomizer;
import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The sandbox's initializer did not prepare the workspace: it exited with an error, or did not end by the work
 * deadline. No model call ran, so the attempt is the infrastructure's failure and may run again within the job's retry
 * budget.
 *
 * <p>The message carries the exit code only. The transcript tail is for the attempt's failure record: bounded, and
 * with the known credential formats masked. The initializer prints the workspace download and its extraction, which
 * can name repository paths, never the model's output.
 */
public class SandboxInitializationException extends SandboxInfrastructureException {

    @Serial
    private static final long serialVersionUID = 1L;

    static final int TAIL_LINES = 20;
    static final int TAIL_CHARACTERS = 2048;

    private final @Nullable Integer exitCode;
    private final boolean timedOut;
    private final String transcriptTail;

    public SandboxInitializationException(@Nullable Integer exitCode, boolean timedOut, @Nullable String transcript) {
        super(
                timedOut
                        ? "Sandbox workspace initialization did not end by the work deadline"
                        : "Sandbox workspace initialization failed: exit " + (exitCode == null ? "unknown" : exitCode));
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.transcriptTail = tail(transcript);
    }

    public @Nullable Integer exitCode() {
        return exitCode;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public String transcriptTail() {
        return transcriptTail;
    }

    /** The last lines of the transcript, masked before they are cut so that no cut leaves part of a credential. */
    static String tail(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "";
        }
        List<String> lines = transcript
                .lines()
                .map(CredentialMaskingCustomizer::maskCredentials)
                .toList();
        String last = String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
        return last.length() <= TAIL_CHARACTERS ? last : last.substring(last.length() - TAIL_CHARACTERS);
    }
}
