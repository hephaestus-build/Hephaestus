package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Heph host runs: the host script, the modules it imports and Heph's system prompt, all from this JAR and
 * never from the image. The host runs no tool that executes anything.
 */
public final class HephHostSpec {

    static final String HOST_SCRIPT = "heph-host.ts";

    /** Imported by the host with a relative specifier, so each is staged beside it. */
    static final List<String> SIDECARS = List.of(
            "heph-host-protocol.ts",
            "pi-agent-sandbox.ts",
            "pi-diagnostics.ts",
            "pi-diagnostics.json",
            "pi-error-text.ts",
            "pi-text.ts",
            SandboxLayout.PROVIDER_HELPER_FILENAME);

    /** Node's heap, below the host's limit so that its code, buffers and stacks fit beside it. */
    static final long HEAP_MIB = SandboxLayout.HEPH_HOST_MEMORY_BYTES * 3 / 4 / (1024 * 1024);

    /**
     * Many sessions share one process: a larger heap than a practice runner's, and no review workspace. The runtime
     * bounds only the workspace's initialization; the host runs until the worker removes it.
     */
    public static final ResourceLimits LIMITS =
            new ResourceLimits(2L * 1024 * 1024 * 1024, 1.0, 256, Duration.ofMinutes(5));

    private static final String WRITABLE = SandboxLayout.PI_AGENT_DIR + " " + PiRunnerProfile.AGENT_TMPDIR;

    private HephHostSpec() {}

    static HostSandbox.HostSpec spec() {
        return new HostSandbox.HostSpec(stagedFiles(), environment(), command(), LIMITS);
    }

    static Map<String, byte[]> stagedFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(HOST_SCRIPT, PiRuntimeFactory.loadClasspathResource(HOST_SCRIPT));
        for (String sidecar : SIDECARS) {
            files.put(sidecar, PiRuntimeFactory.loadClasspathResource(sidecar));
        }
        String system = new String(PiRuntimeFactory.loadClasspathResource("mentor/system.md"), StandardCharsets.UTF_8);
        files.put(
                SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH,
                (system + "\n\n" + FeedbackWritingGuide.text()).getBytes(StandardCharsets.UTF_8));
        files.put(SandboxLayout.NODE_PACKAGE_JSON_FILENAME, "{\"type\":\"module\"}\n".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static Map<String, String> environment() {
        return Map.of(
                "HOME",
                "/home/agent",
                "XDG_CONFIG_HOME",
                "/home/agent/.local/config",
                "TMPDIR",
                PiRunnerProfile.AGENT_TMPDIR,
                "PI_CODING_AGENT_DIR",
                SandboxLayout.PI_AGENT_DIR);
    }

    /**
     * Node under the permission model: it reads the workspace, the SDK, the shared practice-script toolkit and the
     * empty home, and writes only Pi's agent directory and its temporary directory, which exist before Node starts
     * because a grant is resolved then. It may not start a child process. The provider helper imports the toolkit
     * through the same workspace link the practice runners use ({@link PiRunnerProfile#PRACTICE_SCRIPT_LIBRARY_SETUP}).
     */
    static List<String> command() {
        return List.of(
                "sh",
                "-c",
                "mkdir -p " + WRITABLE
                        + " /home/agent/.local/config && "
                        + String.join(" && ", PiRunnerProfile.PRACTICE_SCRIPT_LIBRARY_SETUP)
                        + " && exec node --max-old-space-size=" + HEAP_MIB + " --permission"
                        + " --allow-fs-read=" + SandboxLayout.WORKSPACE_ROOT
                        + " --allow-fs-read=/opt/pi-sdk --allow-fs-read=/home/agent"
                        + " --allow-fs-read=" + SandboxLayout.PRACTICE_SCRIPT_IMAGE_ROOT
                        + " --allow-fs-write=" + SandboxLayout.PI_AGENT_DIR
                        + " --allow-fs-write=" + PiRunnerProfile.AGENT_TMPDIR
                        + " " + SandboxLayout.WORKSPACE_ROOT + "/" + HOST_SCRIPT);
    }
}
