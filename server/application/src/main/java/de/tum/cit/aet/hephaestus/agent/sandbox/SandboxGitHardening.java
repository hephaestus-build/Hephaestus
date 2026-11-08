package de.tum.cit.aet.hephaestus.agent.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Git settings of every sandbox that runs Git on a checkout it did not make: a repository can carry a hostile
 * {@code .git/config} in with it.
 */
public final class SandboxGitHardening {

    /**
     * Each entry pairs a git config key that can execute code with a value that neutralises it: an empty string disables
     * the feature, {@code /nonexistent} points it at a path that cannot exist, and {@code cat} is the canonical no-op
     * pager.
     *
     * @see <a href="https://git-scm.com/docs/git-config#_environment">git-config environment</a>
     */
    public static final List<Map.Entry<String, String>> CONFIGS = List.of(
            Map.entry("core.hooksPath", "/nonexistent"),
            Map.entry("core.fsmonitor", "false"),
            Map.entry("core.sshCommand", ""),
            Map.entry("core.askPass", ""),
            Map.entry("core.editor", ""),
            Map.entry("core.pager", "cat"),
            Map.entry("core.gitProxy", ""),
            Map.entry("sequence.editor", ""),
            Map.entry("credential.helper", ""),
            Map.entry("diff.external", ""),
            Map.entry("protocol.ext.allow", "never"));

    private SandboxGitHardening() {}

    /** The settings as Git's environment: no prompt, no system attributes, and {@link #CONFIGS}. */
    public static Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (int index = 0; index < CONFIGS.size(); index++) {
            env.put("GIT_CONFIG_KEY_" + index, CONFIGS.get(index).getKey());
            env.put("GIT_CONFIG_VALUE_" + index, CONFIGS.get(index).getValue());
        }
        env.put("GIT_CONFIG_COUNT", String.valueOf(CONFIGS.size()));
        // Git would prompt interactively and hang the call.
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        return env;
    }
}
