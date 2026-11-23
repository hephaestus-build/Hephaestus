// pi-agent-sandbox.ts — the sandbox trust posture every Pi session in this image runs under. The session factory
// (pi-session.ts) applies its trust and discovery options to every session; the tool environment serves the tools of
// the practice runner (pi-runner.ts) and the conversation turn (turn.ts).

import { realpathSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import path from "node:path";

import type { BashSpawnHook, SettingsManagerCreateOptions } from "@earendil-works/pi-coding-agent";

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
 * No discovery: a session holds only the resources its run names. The SDK's own context-file discovery climbs from the
 * working directory to `/` looking for AGENTS.md and dies on the first ancestor the sandbox's read allowlist forbids;
 * and discovery is not the channel a session's instructions should arrive on — each run hands its own over by name.
 */
export const SANDBOX_RESOURCE_LOADER_OPTIONS = {
	noContextFiles: true,
	noExtensions: true,
	noSkills: true,
	noPromptTemplates: true,
	noThemes: true,
};

/**
 * The variables a command started for a tool may see: the shell's basics, and the Git settings that the server
 * (`SandboxGitHardening`) and the image set to harden Git. A prefix such as `GIT_` would also pass a credential that a
 * caller names `GIT_TOKEN`.
 */
const TOOL_VARIABLES = new Set([
	"PATH",
	"HOME",
	"LANG",
	"TMPDIR",
	"GIT_CONFIG_COUNT",
	"GIT_TERMINAL_PROMPT",
	"GIT_ATTR_NOSYSTEM",
	"GIT_PAGER",
	"GIT_OPTIONAL_LOCKS",
]);
const GIT_CONFIG_ENTRY = /^GIT_CONFIG_(?:KEY|VALUE)_\d+$/u;

/** What a command started for a tool sees of this process's environment: none of the runtime's credentials. */
export function toolEnvironment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
	return Object.fromEntries(
		Object.entries(env).filter(([name]) => TOOL_VARIABLES.has(name) || GIT_CONFIG_ENTRY.test(name)),
	);
}

/** Gives Pi's `bash` the tool environment in place of the whole process environment that it passes by default. */
export const TOOL_SPAWN_HOOK: BashSpawnHook = (context) => ({
	...context,
	env: toolEnvironment(context.env),
});

/** A signed JSON Web Token: a JSON header and payload, base64url-encoded, and a signature. */
const SIGNED_TOKEN = /eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/gu;
export const REDACTED_CREDENTIAL = "[credential]";

/**
 * The text with each credential-shaped string replaced: what leaves a sandbox to be stored carries no signed token, even
 * one that a tool found. The sandbox holds no credential of its own, so this stays a second line of defence.
 */
export function redactCredentials(text: string): string {
	return text.replaceAll(SIGNED_TOKEN, REDACTED_CREDENTIAL);
}

/** The path with its links resolved; for a path that does not exist yet, its nearest existing ancestor's. */
function resolvedPath(target: string): string {
	try {
		return realpathSync(target);
	} catch {
		const parent = path.dirname(target);
		return parent === target ? target : path.join(resolvedPath(parent), path.basename(target));
	}
}

/**
 * Why a file tool may not open `target`, or undefined when it may. The file tools open the workspace `root` and the
 * files in which Pi's bash keeps a long output; a link out of the root counts where it leads. `target` is the path the
 * tool received, relative to `cwd`.
 */
export function outsideWorkspace(target: string, cwd: string, root: string): string | undefined {
	// Pi's tools strip a leading @ and expand ~, so the check reads the path the tool will open.
	const given = target.replace(/^@/u, "");
	const expanded =
		given === "~" || given.startsWith("~/") ? path.join(homedir(), given.slice(1)) : given;
	const opened = resolvedPath(path.resolve(cwd, expanded));
	const workspace = resolvedPath(root);
	const relative = path.relative(workspace, opened);
	if (relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative))) {
		return undefined;
	}
	if (
		path.dirname(opened) === resolvedPath(tmpdir()) &&
		path.basename(opened).startsWith("pi-bash-")
	) {
		return undefined;
	}
	return `${given} is outside ${root}. The file tools open only files in ${root}.`;
}
