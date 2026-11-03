import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { changedPaths, parseBase, pathFilters, scopesFor, type Scope } from "./check-affected.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { repositoryCli } from "./lib/process.ts";
import { PUSH_CHECK_ENV, pushStaticTasks } from "./lib/push-gates.ts";

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
export function frontendCommands(paths: string[] | undefined, cwd = process.cwd()): string[][] {
	const scopes = pushScopes(paths);
	return (["webapp", "extension"] as const)
		.filter((tree) => scopes.includes(tree))
		.flatMap((tree) => {
			const files = (paths ?? []).filter((file) => file.startsWith(`${tree}/`));
			const sourceOnly =
				files.length > 0 &&
				files.every(
					(file) =>
						file.startsWith(`${tree}/src/`) &&
						// Test helpers and the shared network mocks reach many tests, as a resource does.
						!/^[^/]+\/src\/(?:test|mocks)\//u.test(file) &&
						/\.[jt]sx?$/u.test(file) &&
						existsSync(path.join(cwd, file)),
				) &&
				paths !== undefined &&
				paths.every(
					(file) =>
						!scopesFor([file]).includes("full") &&
						(file.startsWith(`${tree}/`) || !scopesFor([file]).includes(tree)),
				);
			const tests = sourceOnly ? colocatedTests(files, cwd) : [];
			if (sourceOnly && tests.length === 0) {
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

function run(program: string, args: string[]): void {
	const result = spawnSync(program, args, {
		stdio: "inherit",
		env: {
			...environmentWithoutGitRepository(),
			VP_RUN_CONCURRENCY_LIMIT: "1",
			[PUSH_CHECK_ENV]: "true",
		},
	});
	if (result.error !== undefined) {
		throw result.error;
	}
	if (result.status !== 0) {
		process.exit(result.status ?? 1);
	}
}

function main(): void {
	const base = parseBase(process.argv.slice(2));
	let paths: string[] | undefined;
	try {
		paths = changedPaths(base);
	} catch {
		console.log(
			`No usable merge base with ${base}; selecting all scoped checks and frontend unit projects without fetching.`,
		);
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
		run(process.execPath, [cli, ...command]);
	}
	console.log(
		"Push checks passed. Complete Node suites, Java unit and integration tests, browser suites and builds did not run; CI runs them.",
	);
}

if (import.meta.main) {
	main();
}
