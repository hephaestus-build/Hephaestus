package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The host imports its modules with relative specifiers, so Node resolves them from the staged workspace: a module
 * missing there fails every Heph turn with {@code ERR_MODULE_NOT_FOUND}, and no build or boot step notices.
 */
@Tag("unit")
class HephHostSpecTest {

    private static final Pattern RELATIVE_IMPORT = Pattern.compile("from \"\\./([^\"]+)\"");

    @Test
    void shouldStageEveryModuleTheHostImportsWhenItBuildsTheWorkspace() {
        Map<String, byte[]> files = HephHostSpec.stagedFiles();
        Set<String> reached = new LinkedHashSet<>();
        collectRelativeImports(files, HephHostSpec.HOST_SCRIPT, reached);

        assertThat(reached).isNotEmpty();
        assertThat(files).containsKeys(reached.toArray(String[]::new));
        assertThat(files).containsKey(SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH);
    }

    @Test
    void shouldRunTheHostUnderThePermissionModelWithoutChildProcessesWhenItStarts() {
        HostSandbox.HostSpec spec = HephHostSpec.spec();

        String command = String.join(" ", spec.command());
        assertThat(command)
                .contains("--permission")
                .endsWith(SandboxLayout.WORKSPACE_ROOT + "/" + HephHostSpec.HOST_SCRIPT)
                .doesNotContain("--allow-child-process")
                .doesNotContain("--allow-worker");
        assertThat(spec.environment()).doesNotContainKeys("LLM_PROXY_TOKEN", "LLM_PROXY_URL", "SANDBOX_RUNTIME_URL");
    }

    private static void collectRelativeImports(Map<String, byte[]> files, String script, Set<String> reached) {
        byte[] source = Objects.requireNonNull(files.get(script), script + " is not staged");
        Matcher matcher = RELATIVE_IMPORT.matcher(new String(source, StandardCharsets.UTF_8));
        while (matcher.find()) {
            String imported = matcher.group(1);
            if (reached.add(imported)) {
                collectRelativeImports(files, imported, reached);
            }
        }
    }
}
