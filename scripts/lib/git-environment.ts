import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

/**
 * The variables git exports to a hook so that the commands the hook runs find the repository it is
 * acting on (githooks(5); git(1) § "The Git Repository"). Each one outranks the directory a command
 * is given, so everything a hook starts — the quality gate, and every `git` below it — addresses
 * the repository being pushed until they are removed. `.vite-hooks/pre-push` unsets exactly these.
 */
export const GIT_REPOSITORY_VARIABLES = [
	"GIT_ALTERNATE_OBJECT_DIRECTORIES",
	"GIT_COMMON_DIR",
	"GIT_DIR",
	"GIT_INDEX_FILE",
	"GIT_OBJECT_DIRECTORY",
	"GIT_PREFIX",
	"GIT_WORK_TREE",
] as const;

/**
 * The environment without them, so `git` obeys the working directory it is handed. Each name is
 * kept with an `undefined` value rather than dropped: node omits those when it spawns, and the
 * result is equally usable as an override that a caller merges over `process.env` itself, where a
 * missing key would leave the inherited variable standing.
 */
export function environmentWithoutGitRepository(): NodeJS.ProcessEnv {
	const environment: NodeJS.ProcessEnv = { ...process.env };
	for (const name of GIT_REPOSITORY_VARIABLES) {
		environment[name] = undefined;
	}
	return environment;
}

/**
 * The environment for a `git` that acts on a throwaway fixture: no inherited `GIT_*` at all, and
 * neither the machine's global nor its system configuration, whose `core.hooksPath`,
 * `commit.gpgsign` or `tag.gpgSign` would otherwise decide what the fixture does.
 */
export function environmentForGitFixture(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
	const environment: NodeJS.ProcessEnv = { ...process.env };
	for (const name of Object.keys(environment)) {
		if (name.startsWith("GIT_")) {
			environment[name] = undefined;
		}
	}
	return {
		...environment,
		GIT_CONFIG_GLOBAL: emptyGlobalConfiguration(),
		GIT_CONFIG_NOSYSTEM: "1",
		...overrides,
	};
}

let emptyConfiguration: string | undefined;

/** `/dev/null` is not readable as a config file on every platform the tests run on, so: a file. */
function emptyGlobalConfiguration(): string {
	if (emptyConfiguration === undefined) {
		const directory = mkdtempSync(path.join(tmpdir(), "git-fixture-config-"));
		process.once("exit", () => rmSync(directory, { recursive: true, force: true }));
		emptyConfiguration = path.join(directory, "gitconfig");
		writeFileSync(emptyConfiguration, "");
	}
	return emptyConfiguration;
}
