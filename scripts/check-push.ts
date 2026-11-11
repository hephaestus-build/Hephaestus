import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { changedPaths, parseBase, pathFilters, scopesFor, type Scope } from "./check-affected.ts";
import { NEXT_CHANGELOG_DIRECTORY } from "./lib/changelog.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { repositoryCli } from "./lib/process.ts";
import { PUSH_CHECK_ENV, pushStaticTasks } from "./lib/push-gates.ts";
import { localReleaseNoteProblems } from "./release-note-rules.ts";
import { orderProblem, readQueue } from "./reorder-migrations.ts";

const allScopes = Object.keys(pushStaticTasks).filter(
	(scope): scope is keyof typeof pushStaticTasks => Object.hasOwn(pushStaticTasks, scope),
);
const instructionPath = /^(?:\.agents|\.claude)\/|(?:^|\/)(?:AGENTS|CLAUDE)\.md$/u;

// The CI path filters that select a tree's jobs, and the push scope that runs that tree's checks.
const ciTrees = {
	"application-server": "server",
	docs: "docs",
	extension: "extension",
	webapp: "webapp",
} as const;

/** The CI path filters, or undefined when `cicd.yml` cannot be read and every scope must run. */
function ciFilters(): Record<string, string[]> | undefined {
	try {
		const workflow = path.join(import.meta.dirname, "..", ".github", "workflows", "cicd.yml");
		return pathFilters(readFileSync(workflow, "utf8"));
	} catch {
		return undefined;
	}
}

/**
 * Repository tooling, plus each tree whose CI path filter names the file: what pull request CI runs
 * for it. Without the filters, every scope.
 */
export function toolingScopes(
	file: string,
	filters: Record<string, string[]> | undefined,
): Exclude<Scope, "full">[] | undefined {
	if (filters === undefined) {
		return undefined;
	}
	const trees = Object.entries(ciTrees).flatMap(([filter, scope]) =>
		(filters[filter] ?? []).some(
			(glob) => !glob.startsWith("!") && path.posix.matchesGlob(file, glob),
		)
			? [scope]
			: [],
	);
	return ["agents", ...trees];
}

export function pushScopes(paths: string[] | undefined): Exclude<Scope, "full">[] {
	const selected = new Set<Exclude<Scope, "full">>();
	if (paths === undefined) {
		return allScopes;
	}
	const filters = ciFilters();
	for (const file of paths) {
		if (file.startsWith("scripts/") && !/^scripts\/ci-[^/]+\.ts$/u.test(file)) {
			const scopes = toolingScopes(file, filters);
			if (scopes === undefined) {
				return allScopes;
			}
			for (const scope of scopes) {
				selected.add(scope);
			}
			continue;
		}
		if (
			file.startsWith(".github/") ||
			/^scripts\/ci-[^/]+\.ts$/u.test(file) ||
			instructionPath.test(file)
		) {
			selected.add("agents");
			selected.add("docs");
			continue;
		}
		const scopes = scopesFor([file]);
		if (scopes.includes("full")) {
			return allScopes;
		}
		for (const scope of scopes) {
			if (scope !== "full") {
				selected.add(scope);
			}
		}
	}
	return [...selected].toSorted();
}

export function pushTasks(paths: string[] | undefined): string[] {
	const scopes = pushScopes(paths);
	const tasks = new Set(scopes.flatMap((scope) => pushStaticTasks[scope]));
	if (
		scopes.length === allScopes.length ||
		paths?.some((file) => instructionPath.test(file)) === true
	) {
		tasks.add("gate:instructions");
	}
	return [...tasks];
}

const nativeTest =
	/^(?:scripts\/.*\.test\.ts|docker\/agents\/.*\.test\.ts|server\/application\/src\/test\/resources\/agent\/[^/]+\.spec\.ts)$/u;

/** Only explicit changed tests and existing source companions; full Node suites remain required. */
export function focusedTests(paths: string[], cwd = process.cwd()): string[] {
	const tests = new Set<string>();
	for (const file of paths) {
		for (const candidate of [file, file.replace(/\.ts$/u, ".test.ts")]) {
			if (nativeTest.test(candidate) && existsSync(path.join(cwd, candidate))) {
				tests.add(candidate);
			}
		}
		if (file.startsWith(".github/") || file === "vite.config.ts") {
			for (const owner of ["ci-contract", "ci-windows-selection", "ci-image-set"]) {
				const candidate = `scripts/${owner}.test.ts`;
				if (existsSync(path.join(cwd, candidate))) {
					tests.add(candidate);
				}
			}
		}
	}
	return [...tests].toSorted();
}

/** The changed test files of a tree, and the existing colocated tests of its changed sources. */
export function colocatedTests(files: readonly string[], cwd = process.cwd()): string[] {
	const tests = new Set<string>();
	for (const file of files) {
		const candidates = /\.test\.[jt]sx?$/u.test(file)
			? [file]
			: [".test.ts", ".test.tsx"].map((suffix) => file.replace(/\.[jt]sx?$/u, suffix));
		for (const candidate of candidates) {
			if (existsSync(path.join(cwd, candidate))) {
				tests.add(candidate);
			}
		}
	}
	return [...tests].toSorted();
}

/**
 * For source files, the changed and colocated tests. Their import graph reaches most route tests
 * through the router, which took minutes on one worker; CI runs the complete unit project.
 * Resources, deleted files, test helpers and shared inputs need the whole unit project here too.
 */
/** Whether only source files of `tree` changed, so its changed and colocated tests suffice here. */
function sourceOnly(tree: "webapp" | "extension", paths: string[], cwd: string): boolean {
	const files = paths.filter((file) => file.startsWith(`${tree}/`));
	return (
		files.length > 0 &&
		files.every(
			(file) =>
				file.startsWith(`${tree}/src/`) &&
				// Test helpers and the shared network mocks reach many tests, as a resource does.
				!/^[^/]+\/src\/(?:test|mocks)\//u.test(file) &&
				/\.[jt]sx?$/u.test(file) &&
				existsSync(path.join(cwd, file)),
		) &&
		paths.every(
			(file) =>
				!scopesFor([file]).includes("full") &&
				(file.startsWith(`${tree}/`) || !scopesFor([file]).includes(tree)),
		)
	);
}

const frontendTrees = (paths: string[] | undefined) =>
	(["webapp", "extension"] as const).filter((tree) => pushScopes(paths).includes(tree));

/** Whether some tests that reach a changed frontend file only through its imports did not run. */
export function skipsImportedTests(paths: string[] | undefined, cwd = process.cwd()): boolean {
	return paths !== undefined && frontendTrees(paths).some((tree) => sourceOnly(tree, paths, cwd));
}

export function frontendCommands(paths: string[] | undefined, cwd = process.cwd()): string[][] {
	return frontendTrees(paths).flatMap((tree) => {
		const selective = paths !== undefined && sourceOnly(tree, paths, cwd);
		const tests = selective
			? colocatedTests(
					paths.filter((file) => file.startsWith(`${tree}/`)),
					cwd,
				)
			: [];
		if (selective && tests.length === 0) {
			return [];
		}
		return [
			[
				"-C",
				tree,
				"test",
				"--run",
				"--maxWorkers=1",
				...(tree === "extension" ? ["--project", "unit"] : []),
				...tests.map((file) => file.slice(tree.length + 1)),
			],
		];
	});
}

const pushEnvironment = (): NodeJS.ProcessEnv => ({
	...environmentWithoutGitRepository(),
	VP_RUN_CONCURRENCY_LIMIT: "1",
	[PUSH_CHECK_ENV]: "true",
});

function run(program: string, args: string[]): void {
	const result = spawnSync(program, args, { stdio: "inherit", env: pushEnvironment() });
	if (result.error !== undefined) {
		throw result.error;
	}
	if (result.status !== 0) {
		process.exit(result.status ?? 1);
	}
}

/**
 * How long a frontend unit run may print nothing before the hook stops it. The slowest test file
 * took 30 seconds on one worker of a heavily loaded host, and a run prints as each file finishes.
 */
export const STALL_LIMIT_MS = 5 * 60_000;

/**
 * Runs a frontend unit project, and fails it loudly when it prints nothing for `stallLimitMs`:
 * Vitest has hung there with no worker left, and a hook that never ends teaches `--no-verify`.
 * Before it stops the run, each Node process of it writes a diagnostic report of what it waits on.
 */
export async function runUnlessStalled(
	program: string,
	args: string[],
	stallLimitMs = STALL_LIMIT_MS,
): Promise<number> {
	const posix = process.platform !== "win32";
	const reports = mkdtempSync(path.join(tmpdir(), "check-push-stall-"));
	// Windows has no report signal.
	const reportOptions = posix
		? ` --report-on-signal --report-signal=SIGUSR2 --report-directory="${reports}"`
		: "";
	const environment = pushEnvironment();
	const child = spawn(program, args, {
		detached: posix,
		stdio: ["ignore", "pipe", "pipe"],
		env: {
			...environment,
			NODE_OPTIONS: `${environment.NODE_OPTIONS ?? ""}${reportOptions}`.trim(),
			...(process.stdout.isTTY ? { FORCE_COLOR: "1" } : {}),
		},
	});
	// The whole tree: its own process group on POSIX, `taskkill /T` on Windows.
	const signal = (name: NodeJS.Signals) => {
		if (child.pid === undefined) {
			return;
		}
		if (!posix) {
			spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
			return;
		}
		try {
			process.kill(-child.pid, name);
		} catch {
			// Every process of the group already ended.
		}
	};
	// Its own process group does not receive the terminal's Ctrl-C, so pass it on.
	const onInterrupt = () => signal("SIGINT");
	const onTerminate = () => signal("SIGTERM");
	const onHangup = () => signal("SIGHUP");
	process.on("SIGINT", onInterrupt).on("SIGTERM", onTerminate).on("SIGHUP", onHangup);
	let lastOutput = Date.now();
	child.stdout.on("data", (chunk: Buffer) => {
		lastOutput = Date.now();
		process.stdout.write(chunk);
	});
	child.stderr.on("data", (chunk: Buffer) => {
		lastOutput = Date.now();
		process.stderr.write(chunk);
	});
	// Time for each process to write its report before the whole run is killed.
	const reportGraceMs = 5000;
	const state = { stalledAt: 0 };
	const stopped = Promise.withResolvers<undefined>();
	let force: ReturnType<typeof globalThis.setTimeout> | undefined;
	const watch = setInterval(
		() => {
			if (state.stalledAt !== 0 || Date.now() - lastOutput < stallLimitMs) {
				return;
			}
			state.stalledAt = Date.now();
			signal("SIGUSR2");
			force = globalThis.setTimeout(() => {
				signal("SIGKILL");
				// A descendant can leave the process group while retaining its inherited pipes.
				child.stdout.destroy();
				child.stderr.destroy();
				stopped.resolve(undefined);
			}, reportGraceMs);
		},
		Math.min(10_000, stallLimitMs / 4),
	);
	try {
		// Inherited pipes stay open until workers end, even when their leader exits first.
		await once(child, "close");
		if (state.stalledAt !== 0) {
			await stopped.promise;
		}
	} finally {
		clearInterval(watch);
		clearTimeout(force);
		process.off("SIGINT", onInterrupt).off("SIGTERM", onTerminate).off("SIGHUP", onHangup);
	}
	if (state.stalledAt !== 0) {
		const where = posix
			? `\nDiagnostic reports of its Node processes, with what each waited on: ${reports}`
			: "";
		console.error(
			`\nThe frontend unit run printed nothing for ${Math.round(stallLimitMs / 1000)} s, so the push check stopped it and failed.${where}\nRetry the push. If it stalls again, keep the reports and tell the maintainers; do not skip the hook.`,
		);
		return 1;
	}
	rmSync(reports, { recursive: true, force: true });
	return child.exitCode ?? 1;
}

async function main(): Promise<void> {
	const base = parseBase(process.argv.slice(2));
	let paths: string[] | undefined;
	try {
		paths = changedPaths(base);
	} catch {
		console.log(
			`No usable merge base with ${base}; selecting all scoped checks and frontend unit projects without fetching.`,
		);
	}
	// First, because it takes milliseconds and otherwise fails only in the merge queue.
	if (paths?.some((file) => file.startsWith(`${NEXT_CHANGELOG_DIRECTORY}/`)) === true) {
		let queued: string[] = [];
		try {
			queued = readQueue(process.cwd());
		} catch (error) {
			console.log(
				`The merge queue was not read, so the migration order was checked against ${base} only: ${error instanceof Error ? error.message : String(error)}`,
			);
		}
		const problem = orderProblem(base, process.cwd(), queued);
		if (problem !== undefined) {
			console.error(problem);
			process.exit(1);
		}
	}
	// Also milliseconds, and otherwise CI's: a change to shipped code needs a changeset even when the
	// push changes no .changeset file, which is when gate:changesets does not run.
	const releaseNotes = localReleaseNoteProblems(process.cwd(), base);
	if (releaseNotes.length > 0) {
		console.error(
			[
				...releaseNotes,
				`These compare against the merge base with ${base}; if it is stale, run \`git fetch origin main\` and push again.`,
			].join("\n"),
		);
		process.exit(1);
	}
	const tasks = pushTasks(paths);
	if (tasks.length === 0) {
		console.log("No changes detected; no push checks ran.");
		return;
	}
	const cli = repositoryCli();
	console.log(`Push checks, one task worker: ${tasks.join(", ")}`);
	for (const task of tasks) {
		run(process.execPath, [cli, "run", task]);
	}
	const tests = focusedTests(paths ?? []);
	if (tests.length > 0) {
		console.log(`Focused Node tests, one file worker: ${tests.join(", ")}`);
		run(process.execPath, ["--test", "--test-concurrency=1", ...tests]);
	}
	for (const command of frontendCommands(paths)) {
		console.log(`Frontend unit selection: ${command.join(" ")}`);
		const status = await runUnlessStalled(process.execPath, [cli, ...command]);
		if (status !== 0) {
			process.exit(status);
		}
	}
	if (skipsImportedTests(paths)) {
		console.log(
			"Tests that reach the changed frontend files only through their imports did not run here; CI runs the complete unit project.",
		);
	}
	console.log(
		"Push checks passed. Complete Node suites, Java unit and integration tests, browser suites and builds did not run; CI runs them.",
	);
}

if (import.meta.main) {
	await main();
}
