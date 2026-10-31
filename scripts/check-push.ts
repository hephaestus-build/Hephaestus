import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

import { changedPaths, parseBase, scopesFor, type Scope } from "./check-affected.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { repositoryCli } from "./lib/process.ts";
import { PUSH_CHECK_ENV, pushStaticTasks } from "./lib/push-gates.ts";

const allScopes = Object.keys(pushStaticTasks).filter(
	(scope): scope is keyof typeof pushStaticTasks => Object.hasOwn(pushStaticTasks, scope),
);
const instructionPath = /^(?:\.agents|\.claude)\/|(?:^|\/)(?:AGENTS|CLAUDE)\.md$/u;

export function pushScopes(paths: string[] | undefined): Exclude<Scope, "full">[] {
	const selected = new Set<Exclude<Scope, "full">>();
	if (paths === undefined) {
		return allScopes;
	}
	for (const file of paths) {
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

/** Vitest owns the import graph. Resources, deleted files and shared inputs need the whole unit project. */
export function frontendCommands(paths: string[] | undefined, cwd = process.cwd()): string[][] {
	const scopes = pushScopes(paths);
	return (["webapp", "extension"] as const)
		.filter((tree) => scopes.includes(tree))
		.map((tree) => {
			const files = (paths ?? []).filter((file) => file.startsWith(`${tree}/`));
			const related =
				files.length > 0 &&
				files.every(
					(file) =>
						file.startsWith(`${tree}/src/`) &&
						/\.[jt]sx?$/u.test(file) &&
						existsSync(path.join(cwd, file)),
				) &&
				paths !== undefined &&
				paths.every(
					(file) =>
						!scopesFor([file]).includes("full") &&
						(file.startsWith(`${tree}/`) || !scopesFor([file]).includes(tree)),
				);
			return [
				"-C",
				tree,
				"test",
				...(related ? ["related"] : []),
				"--run",
				"--maxWorkers=1",
				...(tree === "extension" ? ["--project", "unit"] : []),
				...(related
					? ["--passWithNoTests", ...files.map((file) => file.slice(tree.length + 1))]
					: []),
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
		"Push checks passed. Complete Node suites, Gradle checks and tests, browser suites and builds did not run. Run `vp run check` and the applicable test tiers before pushing.",
	);
}

if (import.meta.main) {
	main();
}
