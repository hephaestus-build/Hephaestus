// pi-agent-sandbox.ts — the sandbox trust posture every Pi session in this image runs under.
// Shared by the practice runner (pi-runner.ts) and the Heph host (heph-host.ts): both execute the
// same vendored SDK in the same image at the same working directory, so each trust and discovery
// option must be set the same way in both.

import {
	type BashToolOptions,
	createBashToolDefinition,
	type SettingsManagerCreateOptions,
} from "@earendil-works/pi-coding-agent";

/**
 * What a review's shell commands may see of the runner's environment: the search path, the home and scratch
 * directories, the locale, and the git hardening that the sandbox sets (`GIT_CONFIG_*`, `GIT_ATTR_NOSYSTEM`, ...).
 * Nothing else: the runner's own variables stay with the runner.
 */
const SHELL_VARIABLES = new Set([
	"PATH",
	"HOME",
	"XDG_CONFIG_HOME",
	"TMPDIR",
	"LANG",
	"TZ",
	"TERM",
]);

function shellVariable(name: string): boolean {
	return SHELL_VARIABLES.has(name) || name.startsWith("LC_") || name.startsWith("GIT_");
}

/** The environment of one shell command: only the allowed variables of `env`. */
export function shellEnvironment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
	return Object.fromEntries(Object.entries(env).filter(([name]) => shellVariable(name)));
}

/** How a review's shell tool runs each command: with a minimal environment and no session metadata. */
export const REVIEW_SHELL_OPTIONS = {
	exposeSessionEnvironment: false,
	spawnHook: (context) => ({ ...context, env: shellEnvironment(context.env) }),
} satisfies BashToolOptions;

/** A review's shell tool. */
export function reviewShellTool(cwd: string): ReturnType<typeof createBashToolDefinition> {
	return createBashToolDefinition(cwd, REVIEW_SHELL_OPTIONS);
}

/**
 * Untrusted on purpose: a trusted project makes the SDK look for `.agents/skills` in every ancestor
 * of the working directory, up to `/`, and the sandbox lets Node read only /workspace and the SDK
 * itself (PiRunnerProfile), so that walk dies on the first ancestor with a permission error before
 * the model is ever called. Nothing project-local is wanted here anyway: every resource a session
 * uses is injected by its runner, never discovered from a checkout.
 */
export const SANDBOX_SETTINGS_MANAGER_OPTIONS: SettingsManagerCreateOptions = {
	projectTrusted: false,
};

/**
 * The SDK's own context-file discovery climbs from the working directory to `/` looking for
 * AGENTS.md and dies on the first ancestor the sandbox's read allowlist forbids; and the SDK's
 * discovery is not the channel a session's instructions should arrive on — each runner hands its
 * own instructions over by name instead.
 */
export const SANDBOX_RESOURCE_LOADER_OPTIONS = {
	noContextFiles: true,
};
