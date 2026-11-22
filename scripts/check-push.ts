import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import {
	changedPaths,
	mergeBase,
	parseBase,
	pathFilters,
	scopesFor,
	type Scope,
} from "./check-affected.ts";
import {
	type ChangelogSnapshot,
	MASTER_CHANGELOG,
	snapshot,
	violations,
} from "./check-changelog-immutability.ts";
import { CHANGELOG_DIRECTORY, NEXT_CHANGELOG_DIRECTORY } from "./lib/changelog.ts";
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
	server: "server",
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

/**
 * Why the push checks cover what they cover, for the hook's first line: a contributor who sees every scope
 * selected can tell which file did it, or that no merge base was found.
 */
export function pushScopeReason(paths: string[] | undefined): string {
	if (paths === undefined) {
		return `all scopes (${allScopes.join(", ")}), because no merge base was found`;
	}
	const scopes = pushScopes(paths);
	if (scopes.length === allScopes.length) {
		const escalating = paths.find((file) => pushScopes([file]).length === allScopes.length);
		if (escalating !== undefined) {
			return `all scopes (${scopes.join(", ")}), because ${escalating} selects them`;
		}
	}
	return scopes.length === 0 ? "no scopes" : `scopes ${scopes.join(", ")}`;
}

export function pushTasks(paths: string[] | undefined): string[] {
	return [...new Set(pushScopes(paths).flatMap((scope) => pushStaticTasks[scope]))];
}

const nativeTest =
	/^(?:scripts\/.*\.test\.ts|docker\/agents\/.*\.test\.ts|server\/application\/src\/test\/resources\/agent\/[^/]+\.spec\.ts)$/u;

/**
 * The Node tests that run a Git hook. A test reaches a hook only through its path, Git's hook setting or the hook
 * installer. It runs the hook as a process, which no import graph shows.
 */
export function hookTests(cwd = process.cwd()): string[] {
	const scripts = path.join(cwd, "scripts");
	if (!existsSync(scripts)) {
		return [];
	}
	return readdirSync(scripts, { recursive: true })
		.map(String)
		.filter(
			(entry) => entry.endsWith(".test.ts") && !entry.split(path.sep).includes("node_modules"),
		)
		.map((entry) => path.join("scripts", entry).split(path.sep).join("/"))
		.filter((file) =>
			/\.vite-hooks|core\.hooksPath|enable-hooks/u.test(readFileSync(path.join(cwd, file), "utf8")),
		)
		.toSorted();
}

/**
 * The Node tests that a push runs: changed tests, existing source companions, the CI contract suites for a workflow
 * change, and the hook tests for a hook change. The complete Node suites remain in CI.
 */
export function focusedTests(paths: string[], cwd = process.cwd()): string[] {
	const tests = new Set<string>();
	if (paths.some((file) => file.startsWith(".vite-hooks/"))) {
		for (const hookTest of hookTests(cwd)) {
			tests.add(hookTest);
		}
	}
	// A suite split by topic is `scripts/<family>.test.ts` and `scripts/<family>-<topic>.test.ts`.
	const family = (name: string) => {
		const directory = path.join(cwd, "scripts");
		if (!existsSync(directory)) {
			return [];
		}
		return readdirSync(directory)
			.filter(
				(entry) =>
					entry === `${name}.test.ts` ||
					(entry.startsWith(`${name}-`) && entry.endsWith(".test.ts")),
			)
			.map((entry) => `scripts/${entry}`);
	};
	for (const file of paths) {
		for (const candidate of [file, file.replace(/\.ts$/u, ".test.ts")]) {
			if (nativeTest.test(candidate) && existsSync(path.join(cwd, candidate))) {
				tests.add(candidate);
			}
		}
		// The helpers that a split suite shares run every file of that suite.
		const support = /^scripts\/(?<name>[^/]+)-support\.ts$/u.exec(file)?.groups?.name;
		if (support !== undefined) {
			for (const member of family(support)) {
				tests.add(member);
			}
		}
		if (file.startsWith(".github/") || file === "vite.config.ts") {
			for (const owner of ["ci-contract", "ci-windows-selection", "ci-image-set"]) {
				for (const member of family(owner)) {
					tests.add(member);
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
		if (!/\.[jt]sx?$/u.test(file)) {
			continue;
		}
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

const frontendTrees = (paths: string[] | undefined) =>
	(["webapp", "extension"] as const).filter((tree) => pushScopes(paths).includes(tree));

const treeFiles = (tree: "webapp" | "extension", paths: string[]) =>
	paths.filter((file) => file.startsWith(`${tree}/`));

/** Whether a changed frontend file can reach tests that the hook did not run. */
export function skipsImportedTests(paths: string[] | undefined, cwd = process.cwd()): boolean {
	return (
		paths !== undefined &&
		frontendTrees(paths).some((tree) => {
			const files = treeFiles(tree, paths);
			const tests = new Set(colocatedTests(files, cwd));
			return files.length === 0 || files.some((file) => !tests.has(file));
		})
	);
}

/**
 * The frontend tests that the hook runs: each changed test file, and the colocated test of each changed
 * source file. Never the whole unit project: a test helper, a mock, a setup file or a shared input reaches
 * every test, and the whole webapp project is too slow for a hook on one worker. The type check and lint of
 * the tree catch a broken import there; PR CI runs the complete unit projects.
 */
export function frontendCommands(paths: string[] | undefined, cwd = process.cwd()): string[][] {
	return frontendTrees(paths).flatMap((tree) => {
		// The extension's unit project holds only src/**/*.test.ts; another file would match no test.
		const tests = (paths === undefined ? [] : colocatedTests(treeFiles(tree, paths), cwd)).filter(
			(file) => tree === "webapp" || /^extension\/src\/.+\.test\.ts$/u.test(file),
		);
		// Without a merge base the hook cannot choose, so it runs the whole unit project.
		if (paths !== undefined && tests.length === 0) {
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

function run(program: string, args: string[]): number {
	const result = spawnSync(program, args, { stdio: "inherit", env: pushEnvironment() });
	if (result.error !== undefined) {
		throw result.error;
	}
	return result.status ?? 1;
}

/**
 * What the changelog guard in ci-security-scan.yml would refuse: a change to a released change set, or a new raw
 * SQL or custom change set without a rollback. A push that touches no changelog reads nothing.
 */
export function changelogProblems(
	paths: readonly string[],
	base: string,
	read: (revision: string) => ChangelogSnapshot = snapshot,
): string[] {
	if (
		!paths.some((file) => file.startsWith(`${CHANGELOG_DIRECTORY}/`) || file === MASTER_CHANGELOG)
	) {
		return [];
	}
	return violations(read(base), read("HEAD"));
}

/** The base revision, and whether a failed check lets the next one run. */
export function parsePushArguments(args: readonly string[]): { base: string; keepGoing: boolean } {
	const keepGoing = args.includes("--keep-going");
	try {
		return { base: parseBase(args.filter((arg) => arg !== "--keep-going")), keepGoing };
	} catch {
		throw new Error("Usage: vp run check:push [--base <revision>] [--keep-going]");
	}
}

/**
 * Where each failed check goes. The hook stops at the first one. With `--keep-going`, every
 * selected check runs, and the end lists each failure and exits with the first one's status.
 */
export function pushFailures(
	keepGoing: boolean,
	exit: (status: number) => never = (status) => process.exit(status),
) {
	const failures: { check: string; status: number }[] = [];
	return {
		record(check: string, status: number): void {
			if (status === 0) {
				return;
			}
			if (!keepGoing) {
				exit(status);
			}
			console.error(`${check} failed (exit ${status}); --keep-going lists it at the end.`);
			failures.push({ check, status });
		},
		finish(): void {
			const [first] = failures;
			if (first === undefined) {
				return;
			}
			console.error(
				[
					`${failures.length} push ${failures.length === 1 ? "check" : "checks"} failed:`,
					...failures.map(({ check, status }) => `- ${check} (exit ${status})`),
				].join("\n"),
			);
			exit(first.status);
		},
	};
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
	const { base, keepGoing } = parsePushArguments(process.argv.slice(2));
	const failures = pushFailures(keepGoing);
	let paths: string[] | undefined;
	let shared: string | undefined;
	let since = "";
	try {
		shared = mergeBase(base);
		paths = changedPaths(base);
		since = ` since ${shared.slice(0, 10)}, the merge base with ${base}`;
	} catch {
		console.log(
			`No usable merge base with ${base}; selecting all scoped checks and frontend unit projects without fetching.`,
		);
	}
	console.log(
		`Push checks for ${paths === undefined ? "every changed path" : `${paths.length} changed ${paths.length === 1 ? "path" : "paths"}`}${since}: ${pushScopeReason(paths)}.`,
	);
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
			failures.record("migration order", 1);
		}
	}
	// Milliseconds, and otherwise CI's. Against the merge base, not the tip of the base, so that a branch behind
	// main is not blamed for main's newer migrations.
	if (paths !== undefined && shared !== undefined) {
		const problems = changelogProblems(paths, shared);
		if (problems.length > 0) {
			console.error(
				[
					...problems,
					`These compare against the merge base with ${base}; if it is stale, fetch it. A manual \`vp run check:push --base <branch>\` checks against the branch that you merge into.`,
				].join("\n"),
			);
			failures.record("changelog immutability", 1);
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
		failures.record("release-note rules", 1);
	}
	const tasks = pushTasks(paths);
	if (tasks.length === 0) {
		failures.finish();
		console.log("No changes detected; no push checks ran.");
		return;
	}
	const cli = repositoryCli();
	console.log(`Push checks, one task worker: ${tasks.join(", ")}`);
	for (const task of tasks) {
		failures.record(task, run(process.execPath, [cli, "run", task]));
	}
	const tests = focusedTests(paths ?? []);
	if (tests.length > 0) {
		console.log(`Focused Node tests, one file worker: ${tests.join(", ")}`);
		failures.record(
			"focused Node tests",
			run(process.execPath, ["--test", "--test-concurrency=1", ...tests]),
		);
	}
	for (const command of frontendCommands(paths)) {
		console.log(`Frontend unit selection: ${command.join(" ")}`);
		failures.record(command.join(" "), await runUnlessStalled(process.execPath, [cli, ...command]));
	}
	failures.finish();
	if (skipsImportedTests(paths)) {
		console.log(
			"Tests that reach the changed frontend files through imports, setup files or shared inputs did not run here; PR CI runs the complete unit projects.",
		);
	}
	console.log(
		"Push checks passed. Complete frontend unit projects and Node suites, Java unit and integration tests, Storybook, browser suites and other builds did not run; CI runs them.",
	);
}

if (import.meta.main) {
	await main();
}
