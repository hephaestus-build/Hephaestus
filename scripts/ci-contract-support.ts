/** Helpers shared by `scripts/ci-contract*.test.ts`. */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { glob, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { isBuiltin } from "node:module";
import { tmpdir } from "node:os";
import path from "node:path";
import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { parse as parseJsonc } from "jsonc-parser";
import {
	type Document,
	isMap,
	isScalar,
	isSeq,
	parseDocument as parseYamlDocument,
	type YAMLMap,
	stringify,
} from "yaml";
import { isSet } from "./lib/env.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { committedShards } from "./lib/integration-durations.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { exitStatus, CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import { commandsOf } from "./lib/task-graph.ts";

/** Expand native YAML aliases before inspecting a worker's steps. */
export function parseDocument(source: string) {
	return parseYamlDocument(
		stringify(parseYamlDocument(source).toJS({ maxAliasCount: -1 }), {
			aliasDuplicateObjects: false,
		}),
	);
}

export function job(source: string, name: string): string {
	const match = new RegExp(
		`^  ${name}:\\n(?:[\\s\\S]*?)(?=^  [A-Za-z][\\w-]*:\\s*$|(?![\\s\\S]))`,
		"mu",
	).exec(source);
	assert.ok(match, `Missing ${name} job`);
	return match[0];
}

/**
 * Every number in a `timeout-minutes` value. A quoted number counts too, because `fromJSON('5')`
 * yields 5, so a number that is not a limit can only make the check stricter.
 */
export function limitMinutes(limit: unknown): number[] {
	if (typeof limit === "number") {
		return [limit];
	}
	if (typeof limit !== "string") {
		return [];
	}
	return [...limit.matchAll(/\d+/gu)].map(Number);
}

export function pathFilter(source: string, name: string): string {
	const match = new RegExp(
		`^            ${name}:\\n(?:[\\s\\S]*?)(?=^            [\\w-]+:\\s*$)`,
		"mu",
	).exec(source);
	assert.ok(match, `Missing ${name} path filter`);
	return match[0];
}

export function assertNeeds(source: string, name: string, ...required: string[]): void {
	const dependencies = parseDocument(source).getIn(["jobs", name, "needs"]);
	assert.ok(isSeq(dependencies), `${name} must declare its dependencies`);
	for (const dependency of required) {
		assert.ok(
			dependencies.items.some((item) => isScalar(item) && item.value === dependency),
			`${name} must require ${dependency}`,
		);
	}
}

/** Repository-relative `/`-separated paths, whatever separator `fs.glob` yields on this platform. */
export async function posixGlob(pattern: string): Promise<string[]> {
	const files = await Array.fromAsync(glob(pattern));
	return files.map((file) => file.split(path.sep).join("/")).toSorted();
}

/** The `paths` filter of a workflow's `pull_request` trigger. */
export async function pullRequestPaths(file: string): Promise<string[]> {
	const workflow = parseDocument(await readFile(file, "utf8"));
	assert.equal(workflow.hasIn(["on", "pull_request", "branches"]), false, file);
	assert.equal(workflow.hasIn(["on", "pull_request", "paths-ignore"]), false, file);
	const patterns = workflow.getIn(["on", "pull_request", "paths"]);
	assert.ok(isSeq(patterns), `${file} must filter pull requests on paths`);
	return asArray(patterns.toJSON(), file).map((pattern) => asString(pattern, file));
}

export function hasWorkflowTrigger(document: Document, event: string): boolean {
	const trigger = asRecord(document.toJS(), "workflow").on;
	return (
		trigger === event ||
		(Array.isArray(trigger) && trigger.includes(event)) ||
		(isRecord(trigger) && Object.hasOwn(trigger, event))
	);
}

export async function workflowSources(): Promise<Map<string, string>> {
	return readSources(await posixGlob(".github/workflows/*.{yml,yaml}"));
}

export async function readSources(files: string[]): Promise<Map<string, string>> {
	return new Map(
		await Promise.all(files.map(async (file) => [file, await readFile(file, "utf8")] as const)),
	);
}

/** A task name as a workflow writes it, including an unresolved matrix expression. */
// Flags, and a matrix value standing for flags, may come before the task name; `--filter` selects
// a package script instead of a task and is left where the caller can see it.
export const TASK_INVOCATION =
	/\bvp run (?:(?:--(?!filter\b)[\w-]+|\$\{\{ *matrix\.\w+ *\}\}) +)*(?<name>(?:[\w:-]|\$\{\{ *matrix\.\w+ *\}\})+)/gu;

// The gates outside `check`: the k6 syntax check and the alert rule tests need pinned containers, and
// the PMD canary runs only when CI decides PMD inputs changed. The server test analysis compiles every
// test, and `check` does not, so the pre-push hook stays fast; every local server test task runs it.
// The public contract gate compares with a release that moves too, so it runs next to the changesets.
// The API compatibility check judges the spec that CI generates from the packaged server against a base
// that moves with each release. Every other CI gate is part of `check`.
export const CI_ONLY_GATES = new Set([
	"gate:alerts",
	"gate:api-compatibility",
	"gate:load-syntax",
	"gate:pmd-canary",
	"gate:public-contract",
	"gate:server-test-analysis",
]);

// Assignment names exclude '=' so each token has only one possible split.
export const VP_INVOCATION =
	/(?:^|[|&;]\s*)(?:timeout (?:-\S+ )*[^\s-]\S* (?:env )?)?(?:[^\s=]+=\S+ )*vp (?:run|exec|-C)\b/mu;

/** The task names one job's steps invoke, with a `${{ matrix.<key> }}` resolved from its matrix. */
export function invokedTasks(definition: YAMLMap): string[] {
	const names: string[] = [];
	const matrix = definition.getIn(["strategy", "matrix"]);
	const steps = definition.get("steps");
	if (!isSeq(steps)) {
		return names;
	}
	for (const item of steps.items) {
		if (!isMap(item) || typeof item.get("run") !== "string") {
			continue;
		}
		// A command a step prints as guidance is not a command it runs.
		const command = String(item.get("run")).replaceAll(/`[^`]*`/gu, "");
		for (const invocation of command.matchAll(TASK_INVOCATION)) {
			const raw = invocation.groups?.name;
			// `vp run --filter <package> <script>` runs a package script rather than a task.
			if (raw === undefined || raw.startsWith("-")) {
				continue;
			}
			const expression = /\$\{\{ *matrix\.(?<key>\w+) *\}\}/u.exec(raw);
			const key = expression?.groups?.key;
			if (expression === null || key === undefined) {
				names.push(raw);
				continue;
			}
			const values = isMap(matrix) ? matrixValues(matrix, key) : [];
			assert.ok(values.length > 0, `matrix.${key} has no values`);
			for (const value of values) {
				names.push(raw.replace(expression[0], value));
			}
		}
	}
	return names;
}

/** Every value a matrix key takes, in a plain list and across `include` entries. */
export function matrixValues(matrix: YAMLMap, key: string): string[] {
	const values: string[] = [];
	const direct = matrix.get(key);
	if (isSeq(direct)) {
		for (const item of direct.items) {
			if (isScalar(item)) {
				values.push(String(item.value));
			}
		}
	}
	const include = matrix.get("include");
	if (isSeq(include)) {
		for (const entry of include.items) {
			const node = isMap(entry) ? entry.get(key, true) : undefined;
			if (isScalar(node)) {
				values.push(String(node.value));
			}
		}
	}
	return values;
}

export function escapeRegExp(value: string): string {
	return value.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
}

/**
 * Every repository script a given entry point loads, transitively — its static relative imports,
 * plus any sibling script it names as a string, which is how the scanners reach the policy
 * evaluator (they spawn it rather than importing it, so that its exit status is the verdict).
 */
export async function importClosure(entry: string): Promise<string[]> {
	const seen = new Set<string>();
	const queue = [entry];
	while (queue.length > 0) {
		const file = queue.shift();
		if (file === undefined || seen.has(file)) {
			continue;
		}
		seen.add(file);
		const source = await readFile(file, "utf8");
		const directory = path.posix.dirname(file);
		for (const { groups } of source.matchAll(/from\s+"(?<specifier>\.[^"]+\.ts)"/gu)) {
			queue.push(path.posix.normalize(path.posix.join(directory, groups?.specifier ?? "")));
		}
		for (const { groups } of source.matchAll(/"(?<name>[\w.-]+\.ts)"/gu)) {
			const candidate = path.posix.normalize(path.posix.join(directory, "..", groups?.name ?? ""));
			if (candidate.startsWith("scripts/") && existsSync(candidate)) {
				queue.push(candidate);
			}
		}
	}
	return [...seen].toSorted();
}

/** The `with` map of the first step in a job whose `uses` starts with `action`. */
export function step(workflow: Document, jobPath: string[], action: string): YAMLMap {
	const steps = workflow.getIn([...jobPath, "steps"]);
	assert.ok(isSeq(steps), `${jobPath.join(".")} has no steps`);
	for (const item of steps.items) {
		if (!isMap(item)) {
			continue;
		}
		const uses = item.get("uses");
		if (typeof uses === "string" && uses.startsWith(`${action}@`)) {
			const inputs = item.get("with");
			assert.ok(isMap(inputs), `${action} declares no inputs`);
			return inputs;
		}
	}
	throw new Error(`${jobPath.join(".")} has no ${action} step`);
}

/** A job's step by its `name`. */
export function namedStep(workflow: Document, jobPath: string[], name: string): YAMLMap {
	const steps = workflow.getIn([...jobPath, "steps"]);
	assert.ok(isSeq(steps), `${jobPath.join(".")} has no steps`);
	for (const item of steps.items) {
		if (isMap(item) && item.get("name") === name) {
			return item;
		}
	}
	throw new Error(`${jobPath.join(".")} has no ${name} step`);
}

/**
 * Why one entry of `security/trivy-dependency-ignore.yaml` is not a usable exception, or `undefined`
 * when it is. Trivy honours an entry it can parse and says nothing about the rest, so an exception
 * that names no package or expires in 2099 suppresses the gate silently — the fields and the 90-day
 * ceiling are `docs/contributor/vulnerability-remediation.mdx` § Dependency exceptions.
 */
export function exceptionFault(entry: unknown, now: number): string | undefined {
	if (!isRecord(entry)) {
		return "is not a mapping";
	}
	if (typeof entry.id !== "string" || entry.id.trim() === "") {
		return "names no vulnerability id";
	}
	if (
		!Array.isArray(entry.purls) ||
		entry.purls.length === 0 ||
		!entry.purls.every((purl) => typeof purl === "string" && purl.startsWith("pkg:"))
	) {
		return "names no package URLs";
	}
	if (typeof entry.statement !== "string" || entry.statement.trim() === "") {
		return "carries no statement";
	}
	if (typeof entry.expired_at !== "string" || !/^\d{4}-\d{2}-\d{2}$/u.test(entry.expired_at)) {
		return "has no YYYY-MM-DD expired_at";
	}
	if (Date.parse(`${entry.expired_at}T00:00:00Z`) - now > 90 * 24 * 60 * 60 * 1000) {
		return "expires more than 90 days out";
	}
	return undefined;
}

/** A step's `with` map. */
export function stepInputs(declaration: YAMLMap): YAMLMap {
	const map = declaration.get("with");
	assert.ok(isMap(map), "step declares no inputs");
	return map;
}

/** The branch changesets maintains the Version PR on, from its configured base branch. */
export function versionBranch(config: unknown): string {
	const baseBranch = asString(asRecord(config, "changeset config").baseBranch, "baseBranch");
	assert.ok(baseBranch, "changeset config declares no baseBranch");
	return `changeset-release/${baseBranch}`;
}

/** What a `detect-changes` expression reads about one run, apart from its event. */
export interface TierRun {
	/** The live `ci:full` read; undefined is a read that failed and wrote no output. */
	readonly full?: string;
	readonly preview?: string;
	readonly filter?: Readonly<Record<string, string>>;
	readonly pullRequest?: Readonly<Record<string, unknown>>;
	readonly action?: string;
	readonly releaseCandidate?: string;
	/** The opt-in step's light-tier decision for a layer of a stack. */
	readonly light?: string;
	readonly attempt?: number;
}

/** A `detect-changes` tier output for one event and run. */
export function evaluateTierOutput(source: string, event: string, run: TierRun = {}): string {
	const body = /^\$\{\{ (?<body>.+) \}\}$/u.exec(source)?.groups?.body;
	assert.ok(isSet(body));
	const parsed = new Parser(new Lexer(body).lex().tokens, ["github", "steps"], []).parse();
	const optIn = {
		...(run.full === undefined ? {} : { full: run.full }),
		...(run.preview === undefined ? {} : { preview: run.preview }),
		...(run.light === undefined ? {} : { light: run.light }),
	};
	const context: unknown = JSON.parse(
		JSON.stringify({
			github: {
				event_name: event,
				repository: "owner/repo",
				run_attempt: run.attempt ?? 1,
				event: { action: run.action ?? "synchronize", pull_request: run.pullRequest ?? {} },
			},
			steps: {
				opt_in: { outputs: optIn },
				filter: { outputs: run.filter ?? {} },
				skip_check: { outputs: { should_skip: "true" } },
				release_candidate: { outputs: { "release-candidate": run.releaseCandidate ?? "false" } },
			},
		}),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	return new Evaluator(parsed, context).evaluate().coerceString();
}

/** The shell a named `run` step ships. */
export function runScript(workflow: Document, jobPath: string[], name: string): string {
	const shell = namedStep(workflow, jobPath, name).get("run");
	assert.ok(typeof shell === "string", `${name} is not a run step`);
	return shell;
}

/**
 * Runs a step's shell the way the runner does — `bash -e`, with `GITHUB_OUTPUT` pointing at a file
 * of its own — and reports the exit status and the outputs it wrote. A gate whose verdict is bash
 * is only pinned by running that bash.
 */
export async function runStep(
	shell: string,
	environment: Record<string, string> = {},
): Promise<{
	readonly failed: boolean;
	readonly outputs: Record<string, string>;
	readonly diagnosis: string;
}> {
	const outputFile = path.join(await mkdtemp(path.join(tmpdir(), "ci-contract-")), "output");
	await writeFile(outputFile, "");
	const run = spawnSync("bash", ["--noprofile", "--norc", "-e", "-c", shell], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentForGitFixture({ ...environment, GITHUB_OUTPUT: outputFile }),
	});
	assert.equal(run.error, undefined);
	const written = await readFile(outputFile, "utf8");
	const outputs = written
		.split("\n")
		.filter((line) => line.length > 0)
		.map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)] as const);
	return {
		failed: run.status !== 0,
		outputs: Object.fromEntries(outputs),
		// What the shell said, for the assertion to carry. A step's own message is the difference
		// between a failure somebody can fix and `true !== false` on a machine they do not have.
		diagnosis: `exit ${exitStatus(run.status, run.signal)}\n${run.stderr.trim()}`.trim(),
	};
}

/**
 * Whether this machine's bash is the one the runner's steps are written for. GitHub's runners give a
 * step bash 4 or later; macOS ships 3.2, which has no `mapfile`, so a step that reads a command's
 * output into an array fails there for a reason that says nothing about the workflow.
 */
export function bashRunsRunnerSteps(): boolean {
	const probe = spawnSync("bash", ["--noprofile", "--norc", "-c", "mapfile -t _ < /dev/null"], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	return probe.error === undefined && probe.status === 0;
}

/**
 * The buildpack step this guards is driven by replacing `pack` and `docker` with executable stubs
 * on PATH. That needs a bash new enough for the runner's own steps, and a platform where an
 * extensionless stub is executable: on Windows the exec goes through Git Bash and fails
 * intermittently, which is a property of the harness rather than of the step — the job that runs
 * this shell is pinned to the ubuntu-24.04 runners and never executes on Windows at all.
 */
export function runnerStepsSkipReason(): string | false {
	if (!bashRunsRunnerSteps()) {
		return "this bash has no mapfile; runner steps need bash 4 or later";
	}
	if (process.platform === "win32") {
		return "the buildpack step runs only on Linux runners; PATH stubs are not reliably executable here";
	}
	return false;
}

export const runnerStepsOnly = { skip: runnerStepsSkipReason() };

/**
 * Substitutes the workflow expressions a step's shell reads, as the runner substitutes them: by
 * text, before bash sees any of it. An expression this cannot evaluate throws rather than rendering
 * empty, so a new one has to be taught here instead of quietly leaving its case untested.
 */
export function render(
	shell: string,
	results: Readonly<Record<string, string>>,
	outputs: Readonly<Record<string, string>>,
): string {
	const value = (operand: string): string => {
		const result = /^needs\.(?<job>[\w-]+)\.result$/u.exec(operand)?.groups?.job;
		if (result !== undefined) {
			const conclusion = results[result];
			assert.ok(conclusion !== undefined, `${operand} is not one of the job's needs`);
			return conclusion;
		}
		const output = /^needs\.detect-changes\.outputs\.(?<name>[\w-]+)$/u.exec(operand)?.groups?.name;
		if (output !== undefined) {
			const declared = outputs[output];
			assert.ok(declared !== undefined, `${operand} is not a detect-changes output`);
			return declared;
		}
		const contained = /^contains\(needs\.\*\.result, '(?<result>\w+)'\)$/u.exec(operand)?.groups
			?.result;
		if (contained !== undefined) {
			return String(Object.values(results).includes(contained));
		}
		throw new Error(`this test cannot evaluate \`${operand}\``);
	};
	return shell.replaceAll(/\$\{\{ (?<expression>.+?) \}\}/gu, (_, expression: string) => {
		const terms = expression.split(" && ");
		if (terms.length === 1 && !/ (?:==|!=) /u.test(expression)) {
			return value(expression);
		}
		// A conjunction of comparisons renders as the literal `true` or `false`, which is why the
		// workflow reduces its conditions to one before a shell ever sees them.
		return String(
			terms.every((term) => {
				const comparison = /^(?<operand>.+) (?<operator>==|!=) '(?<literal>\w*)'$/u.exec(term);
				assert.ok(comparison, `this test cannot evaluate \`${term}\``);
				const { operand, operator, literal } = comparison.groups ?? {};
				const equal = value(operand ?? "") === literal;
				return operator === "==" ? equal : !equal;
			}),
		);
	});
}

/** `toJSON(needs)` as the runner writes it: each job its result, and its outputs. */
export function jobsOf(
	results: Readonly<Record<string, string>>,
): Record<string, { result: string; outputs: Record<string, string> }> {
	return Object.fromEntries(
		Object.entries(results).map(([name, result]) => [name, { result, outputs: {} }]),
	);
}

export interface Completion {
	result: string;
	outputs?: Record<string, string>;
}

/** The jobs that `name` lists under `needs`, in either of its two YAML forms. */
export function needsOf(workflow: Document, name: string): string[] {
	const dependencies = workflow.getIn(["jobs", name, "needs"]);
	if (isSeq(dependencies)) {
		return dependencies.items.map(String);
	}
	return dependencies === undefined ? [] : [asString(dependencies, "dependency")];
}

/**
 * Whether GitHub starts a job of `workflow` for `event`, given how the jobs it needs ended. A
 * condition without a status function gets an implicit `success()`: every job up its chain, not
 * only the ones it names, succeeded and the run was not cancelled. Modelling only the named jobs
 * reported `Build` as running on pull requests while GitHub skipped it (#3410 to #3719).
 */
export function jobRuns(
	workflow: Document,
	name: string,
	event: string,
	completions: Record<string, Completion>,
	cancelled = false,
	inputs: Record<string, unknown> = {},
): boolean {
	const needs = Object.fromEntries(
		needsOf(workflow, name).map((need) => {
			const completion = completions[need];
			assert.ok(completion !== undefined, `${name} needs ${need}, which this model does not run`);
			return [need, completion] as const;
		}),
	);
	const condition = workflow.getIn(["jobs", name, "if"]);
	const source =
		condition === undefined
			? "true"
			: asString(condition, `${name} condition`)
					.trim()
					.replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "");
	const implicitSuccess = !/\b(?:always|cancelled|failure|success)\(/u.test(source);
	const chain = [...needsClosure(workflow, name)].flatMap((need) => completions[need] ?? []);
	if (implicitSuccess && (cancelled || chain.some((need) => need.result !== "success"))) {
		return false;
	}
	const functions = [
		{ name: "always", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(true) },
		{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(cancelled) },
	];
	const context: unknown = JSON.parse(
		JSON.stringify({ needs, github: { event_name: event }, inputs }),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	const parsed = new Parser(
		new Lexer(source).lex().tokens,
		["needs", "github", "inputs"],
		functions,
	).parse();
	const evaluator = new Evaluator(
		parsed,
		context,
		new Map(functions.map((entry) => [entry.name, entry])),
	);
	return evaluator.evaluate().coerceString() === "true";
}

export function gates(names: Set<string>): string[] {
	return [...names].filter((name) => name.startsWith("gate:")).toSorted();
}

export function taskClosure(tasks: Record<string, unknown>, roots: Iterable<string>): Set<string> {
	const closure = new Set<string>();
	const expand = (name: string): void => {
		if (closure.has(name)) {
			return;
		}
		assert.ok(name in tasks, `${name} is named as a dependency but is not a task`);
		closure.add(name);
		const task = asRecord(tasks[name], name);
		if (Array.isArray(task.dependsOn)) {
			for (const dependency of task.dependsOn) {
				if (typeof dependency === "string") {
					expand(dependency);
				}
			}
		}
		for (const command of commandsOf(task)) {
			const nested = /^vp run (?<task>[\w:-]+)$/u.exec(command)?.groups?.task;
			if (nested !== undefined) {
				expand(nested);
			}
		}
	};
	for (const root of roots) {
		expand(root);
	}
	return closure;
}

/**
 * Every repository file a config reads: each `new URL("…", import.meta.url)` it names and, for a
 * lint config, every file its `extends` names in turn — the closure `loadLintConfig` walks.
 */
export async function filesReadByConfig(config: string): Promise<Set<string>> {
	const files = new Set<string>();
	const collect = async (file: string): Promise<void> => {
		if (files.has(file)) {
			return;
		}
		files.add(file);
		const source = await readFile(file, "utf8");
		const references = file.endsWith(".ts")
			? [...source.matchAll(/new URL\("(?<path>[^"]+)", import\.meta\.url\)/gu)].map(({ groups }) =>
					asString(groups?.path, "config URL"),
				)
			: asArray(asRecord(parseJsonc(source), file).extends ?? [], `${file} extends`).map((entry) =>
					asString(entry, `${file} extends path`),
				);
		for (const reference of references) {
			await collect(path.posix.join(path.posix.dirname(file), reference));
		}
	};
	await collect(config);
	files.delete(config);
	return files;
}

/** The repository paths the first stage of a Dockerfile copies, as written: files and `dir/` trees. */
export function buildStageCopySources(dockerfile: string): string[] {
	const buildStage = dockerfile.split(/^FROM /mu)[1] ?? "";
	return [...buildStage.matchAll(/^COPY (?<arguments>.+)$/gmu)].flatMap(({ groups }) =>
		(groups?.arguments ?? "")
			.split(/\s+/u)
			.filter((word) => !word.startsWith("--"))
			.slice(0, -1),
	);
}

/**
 * The packages that a script needs before its first line runs, through its relative imports. A
 * `type` import is erased, and a dynamic import loads only on the path that reaches it.
 */
export function packagesLoadedBy(file: string, seen = new Set<string>()): Map<string, string> {
	const packages = new Map<string, string>();
	if (seen.has(file)) {
		return packages;
	}
	seen.add(file);
	const source = readFileSync(file, "utf8").replaceAll(/\/\*[\s\S]*?\*\//gu, "");
	for (const match of source.matchAll(
		/^[\t ]*(?:import|export)\s+(?<type>type\s+)?(?:[^"';]*?\sfrom\s+)?["'](?<specifier>[^"']+)["']/gmu,
	)) {
		const specifier = match.groups?.specifier ?? "";
		if (match.groups?.type !== undefined || isBuiltin(specifier)) {
			continue;
		}
		if (specifier.startsWith(".")) {
			const imported = packagesLoadedBy(path.join(path.dirname(file), specifier), seen);
			for (const [name, importer] of imported) {
				packages.set(name, importer);
			}
		} else {
			packages.set(specifier, file);
		}
	}
	return packages;
}

/**
 * A workflow whose newest run on `main` simply replaces the one before it, so cancelling the older
 * run loses nothing a later job reads.
 */
export const REPLACEABLE_MAIN_RUNS: Record<string, { reason: string; concurrency: string[] }> = {
	".github/workflows/cd-docs.yml": {
		reason:
			"A newer production build replaces the older build; publication finishes under its own lock.",
		concurrency: ["jobs", "build-production", "concurrency"],
	},
};

/**
 * A pull-request workflow whose superseded runs keep running, and why it must not cancel them: a run
 * cancelled halfway leaves state that no later run replaces. A workflow whose next run rewrites what
 * the cancelled one left, such as `pull-request.yml`'s sticky comments, cancels like any other.
 */
export const UNCANCELLED_PULL_REQUEST_RUNS: Record<string, string> = {
	".github/workflows/openapi-autocommit.yml":
		"Any label starts it, so an unrelated label would cancel the run that commits the regeneration.",
};

/** A workflow value as the runner expands it: each `${{ }}` evaluated against `context`. */
export function expandExpressions(value: unknown, context: Record<string, unknown>): string {
	const dictionary: unknown = JSON.parse(JSON.stringify(context), data.reviver);
	assert.ok(dictionary instanceof data.Dictionary);
	return String(value).replaceAll(/\$\{\{(?<expression>[\s\S]+?)\}\}/gu, (_, expression: string) =>
		new Evaluator(
			new Parser(new Lexer(expression).lex().tokens, Object.keys(context), []).parse(),
			dictionary,
		)
			.evaluate()
			.coerceString(),
	);
}

/** The static workers that run a whole tier rather than an integration shard. */
export const SERVER_TIERS = new Set(["unit", "architecture"]);

/** `review`, then the numbered shards that the committed integration test durations define. */
export function integrationShards(): string[] {
	return ["review", ...Array.from({ length: committedShards() }, (_, index) => `${index + 1}`)];
}

export function serverShards(workflow: Document, queue: boolean, migrationOnly = false): string[] {
	assert.equal(workflow.getIn(["jobs", "Test", "uses"]), "./.github/workflows/ci-tests.yml");
	assert.equal(workflow.hasIn(["jobs", "Test", "strategy"]), false);
	const suites = parseDocument(readFileSync(".github/workflows/ci-tests.yml", "utf8"));
	const jobs = suites.get("jobs");
	assert.ok(isMap(jobs));
	return jobs.items.flatMap(({ key, value }) => {
		assert.ok(isMap(value));
		const tier = value.getIn(["env", "TEST_TIER"]) ?? value.getIn(["env", "INTEGRATION_SHARD"]);
		if (tier === undefined) {
			return [];
		}
		return jobRuns(suites, String(key), "pull_request", {}, false, {
			should_skip: "false",
			application_server_changed: "true",
			queue_tier: String(queue),
			migration_only: String(migrationOnly),
		})
			? [asString(tier, "server tier or shard")]
			: [];
	});
}

export function validationConcurrencyScopes(
	workflow: Document,
	cancels: (location: string[]) => boolean,
): string[][] {
	if (workflow.hasIn(["concurrency", "group"])) {
		return [["concurrency"]];
	}
	const jobs = workflow.get("jobs");
	assert.ok(isMap(jobs));
	return jobs.items
		.map((entry) => ["jobs", String(entry.key), "concurrency"])
		.filter((location) => workflow.hasIn([...location, "group"]) && cancels(location));
}

/**
 * The SHA-256 that get.helm.sh publishes beside each v4.3.1 archive (`<archive>.sha256sum`), keyed
 * by the runner platform the installer maps to it.
 */
export const HELM_ARCHIVES = new Map([
	[
		"Linux/X64",
		["linux-amd64", "tar.gz", "0403a88ae20913d11b10a2467b50087721fea7581736da6b9d3992f5357fe54b"],
	],
	[
		"Linux/ARM64",
		["linux-arm64", "tar.gz", "8cf8f66ecbc252ac8f7f06cca68aba88508ddfde3a6a34feb1678ad1694b4f88"],
	],
	[
		"macOS/X64",
		["darwin-amd64", "tar.gz", "34bec4eba761ba5c16d1965ee6b8f5b0abe5993974a802274e2a4f0418893d89"],
	],
	[
		"macOS/ARM64",
		["darwin-arm64", "tar.gz", "6b4e61c8cd27d99e5a801e63ff25dd5c70b36875c2cd659d3e9ce6b76fc23198"],
	],
	[
		"Windows/X64",
		["windows-amd64", "zip", "fcfd7b796dcf0f225abc40b5636a3b01f55f3de2fb1dfb18475df94e62c14112"],
	],
] as const);

/** A workflow step that runs Helm, directly or through the chart release script. */
export const usesHelm = (item: unknown) =>
	isMap(item) &&
	typeof item.get("run") === "string" &&
	/\bhelm\b|release-chart\.ts/u.test(String(item.get("run")));
export const installsHelm = (item: unknown) =>
	isMap(item) && item.get("uses") === "./.github/actions/setup-helm";

export function clusterMatrix(queue: boolean, arm64 = false) {
	const workflow = parseDocument(readFileSync(".github/workflows/ci-kubernetes.yml", "utf8"));
	const include = ["k3d-1-35-amd64", "k3d-1-35-arm64", "k3d", "k3d-drills", "calico"].flatMap(
		(key) => {
			if (
				!jobRuns(workflow, key, "pull_request", { schema: { result: "success" } }, false, {
					queue,
					arm64,
				})
			) {
				return [];
			}
			const distribution = key === "calico" ? "calico" : "k3d";
			return [
				{
					leg: key === "k3d-drills" ? "drills" : distribution,
					minor:
						key === "calico"
							? "1.35"
							: String(workflow.getIn(["jobs", key, "env", "KUBERNETES_MINOR"])),
					arch:
						key === "calico"
							? "amd64"
							: String(workflow.getIn(["jobs", key, "env", "KUBERNETES_ARCH"])),
				},
			];
		},
	);
	return { include };
}

/** Names each leg of the cluster matrix as its distribution, minor and architecture. */
export function clusterLegs(queued: boolean, arm64 = false) {
	return clusterMatrix(queued, arm64).include.map((leg) => `${leg.leg} ${leg.minor} ${leg.arch}`);
}

/** Evaluates a `ci-kubernetes.yml` expression for one leg and one value of the `queue` input. */
export function evaluateLeg(
	source: unknown,
	queue: boolean,
	leg: Record<string, string | boolean> = {},
): string {
	const parsed = new Parser(
		new Lexer(String(source).replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "")).lex().tokens,
		["inputs", "env"],
		[],
	).parse();
	const context: unknown = JSON.parse(
		JSON.stringify({
			inputs: { queue, ...leg },
			env: { KUBERNETES_MINOR: leg.minor, KUBERNETES_ARCH: leg.arch },
		}),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	return new Evaluator(parsed, context).evaluate().coerceString();
}

// docs/contributor/ci-cd.mdx § Fail fast in the merge queue owns the reasons for the tests below.
export const CANCEL_WORKFLOW = ".github/workflows/merge-group-cancel.yml";
export const isCancelWorker = (worker: Record<string, unknown>) =>
	worker.name === "Stop the merge group early" && worker.uses === `./${CANCEL_WORKFLOW}`;

// The legs that take most of a merge group's runner time. In a merge group they start only after
// Security passed, so a base-wide advisory ends the group before they take runners.
export const HEAVY_LEGS = [
	"server-package",
	"Quality",
	"Build",
	"Test",
	"Docker",
	"Kubernetes",
	"Release-preflight",
];

/** The jobs that `cicd.yml` runs before the gate, in an order where each follows what it needs. */
export const RUN_ORDER = [
	"vulnerability-database",
	"Security",
	"server-package",
	"Quality",
	"upstream-images",
	"Test",
	"Build",
	"Browser-tests",
	"Docker",
	"Kubernetes",
	"Release-preflight",
];

/**
 * Which jobs of `cicd.yml` run for `event` when the diff selects every leg and a merge group's
 * Security scan ends with `security`. Every other job that runs succeeds.
 */
export function simulateRun(
	workflow: Document,
	event: string,
	security: string,
): Record<string, string> {
	const declared = workflow.getIn(["jobs", "detect-changes", "outputs"]);
	assert.ok(isMap(declared));
	const off: Record<string, string> = {
		should_skip: "false",
		draft: "false",
		"queue-images-complete": "false",
		"light-tier": "false",
		"queue-run": "",
		"alias-base": "",
	};
	const outputs = Object.fromEntries(
		declared.items.map((item) => [String(item.key), off[String(item.key)] ?? "true"] as const),
	);
	const completions: Record<string, Completion> = {
		"detect-changes": { result: "success", outputs },
	};
	for (const name of RUN_ORDER) {
		let result = "skipped";
		if (jobRuns(workflow, name, event, completions)) {
			result = name === "Security" ? security : "success";
		}
		const digest = name === "Docker" && result === "success" ? "sha256:image" : "";
		completions[name] = { result, outputs: { "application-server-digest": digest } };
	}
	return Object.fromEntries(
		Object.entries(completions).map(([name, completion]) => [name, completion.result] as const),
	);
}

export function needsClosure(workflow: Document, name: string): Set<string> {
	const closure = new Set<string>();
	const expand = (from: string): void => {
		for (const need of needsOf(workflow, from)) {
			if (!closure.has(need)) {
				closure.add(need);
				expand(need);
			}
		}
	};
	expand(name);
	return closure;
}

// `actions: write` also dispatches workflows, re-runs runs, and deletes caches and artifacts.
export const ACTIONS_WRITE_GRANTS: Record<string, string> = {
	".github/workflows/retarget.yml#rerun":
		"It re-runs a retargeted pull request's CI/CD run, so the layer leaves the light tier.",
	".github/workflows/continuous-staging.yml#follow-main":
		"It dispatches the promotion workflow for the build that passed CI on main.",
};

export const writesActions = (permissions: unknown) =>
	permissions === "write-all" || (isRecord(permissions) && permissions.actions === "write");
