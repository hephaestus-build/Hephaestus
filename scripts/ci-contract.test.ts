import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { chmod, glob, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { type Document, isMap, isScalar, isSeq, parseDocument, visit, type YAMLMap } from "yaml";

import { evaluate as evaluateVulnerabilityPolicy } from "./check-release-vulnerabilities.ts";
import { versionBranch } from "./dispatch-version-pr-ci.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";
import { planRelease, releaseOutputs } from "./plan-release.ts";
import { resolveAliasBase } from "./resolve-alias-base.ts";
import { planSubjects } from "./scan-main-images.ts";
import { PLATFORMS, planUpstreamSubjects } from "./scan-upstream-images.ts";
import { validateManifest } from "./verify-release-evidence.ts";

function job(source: string, name: string): string {
	const match = source.match(
		new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [A-Za-z][\\w-]*:\\s*$|(?![\\s\\S]))`, "m"),
	);
	assert.ok(match, `Missing ${name} job`);
	return match[0];
}

function pathFilter(source: string, name: string): string {
	const match = source.match(
		new RegExp(`^            ${name}:\\n([\\s\\S]*?)(?=^            [\\w-]+:\\s*$)`, "m"),
	);
	assert.ok(match, `Missing ${name} path filter`);
	return match[0];
}

/** Repository-relative `/`-separated paths, whatever separator `fs.glob` yields on this platform. */
async function posixGlob(pattern: string): Promise<string[]> {
	return (await Array.fromAsync(glob(pattern)))
		.map((file) => file.split(path.sep).join("/"))
		.toSorted();
}

async function workflowSources(): Promise<Map<string, string>> {
	return readSources(await posixGlob(".github/workflows/*.{yml,yaml}"));
}

async function readSources(files: string[]): Promise<Map<string, string>> {
	return new Map(
		await Promise.all(files.map(async (file) => [file, await readFile(file, "utf8")] as const)),
	);
}

/** A task name as a workflow writes it, including an unresolved matrix expression. */
// Flags, and a matrix value standing for flags, may come before the task name; `--filter` selects
// a package script instead of a task and is left where the caller can see it.
const TASK_INVOCATION =
	/\bvp run (?:(?:--(?!filter\b)[\w-]+|\$\{\{ *matrix\.\w+ *\}\}) +)*((?:[\w:-]|\$\{\{ *matrix\.\w+ *\}\})+)/g;

// The two gates `check` cannot run: the k6 syntax check needs the pinned container, and the PMD
// canary runs only when CI decides PMD inputs changed. Every other CI gate is part of `check`.
const CI_ONLY_GATES = new Set(["gate:load-syntax", "gate:pmd-canary"]);

/** The task names one job's steps invoke, with a `${{ matrix.<key> }}` resolved from its matrix. */
function invokedTasks(definition: YAMLMap): string[] {
	const names: string[] = [];
	const matrix = definition.getIn(["strategy", "matrix"]);
	const steps = definition.get("steps");
	if (!isSeq(steps)) return names;
	for (const item of steps.items) {
		if (!isMap(item) || typeof item.get("run") !== "string") continue;
		// A command a step prints as guidance is not a command it runs.
		const command = String(item.get("run")).replaceAll(/`[^`]*`/g, "");
		for (const [, raw] of command.matchAll(TASK_INVOCATION)) {
			// `vp run --filter <package> <script>` runs a package script rather than a task.
			if (raw === undefined || raw.startsWith("-")) continue;
			const expression = /\$\{\{ *matrix\.(\w+) *\}\}/.exec(raw);
			if (expression?.[1] === undefined) {
				names.push(raw);
				continue;
			}
			const values = isMap(matrix) ? matrixValues(matrix, expression[1]) : [];
			assert.ok(values.length > 0, `matrix.${expression[1]} has no values`);
			for (const value of values) names.push(raw.replace(expression[0], value));
		}
	}
	return names;
}

/** Every value a matrix key takes, in a plain list and across `include` entries. */
function matrixValues(matrix: YAMLMap, key: string): string[] {
	const values: string[] = [];
	const direct = matrix.get(key);
	if (isSeq(direct))
		for (const item of direct.items) if (isScalar(item)) values.push(String(item.value));
	const include = matrix.get("include");
	if (isSeq(include))
		for (const entry of include.items) {
			const node = isMap(entry) ? entry.get(key, true) : undefined;
			if (isScalar(node)) values.push(String(node.value));
		}
	return values;
}

function escapeRegExp(value: string): string {
	return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Every repository script a given entry point loads, transitively — its static relative imports,
 * plus any sibling script it names as a string, which is how the scanners reach the policy
 * evaluator (they spawn it rather than importing it, so that its exit status is the verdict).
 */
async function importClosure(entry: string): Promise<string[]> {
	const seen = new Set<string>();
	const queue = [entry];
	while (queue.length > 0) {
		const file = queue.shift();
		if (file === undefined || seen.has(file)) continue;
		seen.add(file);
		const source = await readFile(file, "utf8");
		const directory = path.posix.dirname(file);
		for (const [, specifier] of source.matchAll(/from\s+"(\.[^"]+\.ts)"/g))
			queue.push(path.posix.normalize(path.posix.join(directory, specifier ?? "")));
		for (const [, name] of source.matchAll(/"([\w.-]+\.ts)"/g)) {
			const candidate = path.posix.normalize(path.posix.join(directory, "..", name ?? ""));
			if (candidate.startsWith("scripts/") && existsSync(candidate)) queue.push(candidate);
		}
	}
	return [...seen].toSorted();
}

/** The `with` map of the first step in a job whose `uses` starts with `action`. */
function step(workflow: Document, jobPath: string[], action: string): YAMLMap {
	const steps = workflow.getIn([...jobPath, "steps"]);
	assert.ok(isSeq(steps), `${jobPath.join(".")} has no steps`);
	for (const item of steps.items) {
		if (!isMap(item)) continue;
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
function namedStep(workflow: Document, jobPath: string[], name: string): YAMLMap {
	const steps = workflow.getIn([...jobPath, "steps"]);
	assert.ok(isSeq(steps), `${jobPath.join(".")} has no steps`);
	for (const item of steps.items) if (isMap(item) && item.get("name") === name) return item;
	throw new Error(`${jobPath.join(".")} has no ${name} step`);
}

/**
 * Why one entry of `security/trivy-dependency-ignore.yaml` is not a usable exception, or `undefined`
 * when it is. Trivy honours an entry it can parse and says nothing about the rest, so an exception
 * that names no package or expires in 2099 suppresses the gate silently — the fields and the 90-day
 * ceiling are `docs/contributor/vulnerability-remediation.mdx` § Dependency exceptions.
 */
function exceptionFault(entry: unknown, now: number): string | undefined {
	if (!isRecord(entry)) return "is not a mapping";
	if (typeof entry.id !== "string" || entry.id.trim() === "") return "names no vulnerability id";
	if (
		!Array.isArray(entry.purls) ||
		entry.purls.length === 0 ||
		!entry.purls.every((purl) => typeof purl === "string" && purl.startsWith("pkg:"))
	)
		return "names no package URLs";
	if (typeof entry.statement !== "string" || entry.statement.trim() === "")
		return "carries no statement";
	if (typeof entry.expired_at !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(entry.expired_at))
		return "has no YYYY-MM-DD expired_at";
	if (Date.parse(`${entry.expired_at}T00:00:00Z`) - now > 90 * 24 * 60 * 60 * 1000)
		return "expires more than 90 days out";
	return undefined;
}

/** A step's `with` map. */
function stepInputs(declaration: YAMLMap): YAMLMap {
	const map = declaration.get("with");
	assert.ok(isMap(map), "step declares no inputs");
	return map;
}

/** The shell a named `run` step ships. */
function runScript(workflow: Document, jobPath: string[], name: string): string {
	const shell = namedStep(workflow, jobPath, name).get("run");
	assert.ok(typeof shell === "string", `${name} is not a run step`);
	return shell;
}

/**
 * Runs a step's shell the way the runner does — `bash -e`, with `GITHUB_OUTPUT` pointing at a file
 * of its own — and reports the exit status and the outputs it wrote. A gate whose verdict is bash
 * is only pinned by running that bash.
 */
async function runStep(
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
		env: environmentForGitFixture({ ...environment, GITHUB_OUTPUT: outputFile }),
	});
	assert.equal(run.error, undefined);
	const outputs = (await readFile(outputFile, "utf8"))
		.split("\n")
		.filter((line) => line.length > 0)
		.map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)] as const);
	return {
		failed: run.status !== 0,
		outputs: Object.fromEntries(outputs),
		// What the shell said, for the assertion to carry. A step's own message is the difference
		// between a failure somebody can fix and `true !== false` on a machine they do not have.
		diagnosis: `exit ${run.status}\n${run.stderr.trim()}`.trim(),
	};
}

/**
 * Whether this machine's bash is the one the runner's steps are written for. GitHub's runners give a
 * step bash 4 or later; macOS ships 3.2, which has no `mapfile`, so a step that reads a command's
 * output into an array fails there for a reason that says nothing about the workflow.
 */
function bashRunsRunnerSteps(): boolean {
	const probe = spawnSync("bash", ["--noprofile", "--norc", "-c", "mapfile -t _ < /dev/null"], {
		encoding: "utf8",
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
const runnerStepsOnly = {
	skip: !bashRunsRunnerSteps()
		? "this bash has no mapfile; runner steps need bash 4 or later"
		: process.platform === "win32"
			? "the buildpack step runs only on Linux runners; PATH stubs are not reliably executable here"
			: false,
};

/**
 * Substitutes the workflow expressions a step's shell reads, as the runner substitutes them: by
 * text, before bash sees any of it. An expression this cannot evaluate throws rather than rendering
 * empty, so a new one has to be taught here instead of quietly leaving its case untested.
 */
function render(
	shell: string,
	results: Readonly<Record<string, string>>,
	outputs: Readonly<Record<string, string>>,
): string {
	const value = (operand: string): string => {
		const result = /^needs\.([\w-]+)\.result$/.exec(operand)?.[1];
		if (result !== undefined) {
			const conclusion = results[result];
			assert.ok(conclusion !== undefined, `${operand} is not one of the job's needs`);
			return conclusion;
		}
		const output = /^needs\.detect-changes\.outputs\.([\w-]+)$/.exec(operand)?.[1];
		if (output !== undefined) {
			const declared = outputs[output];
			assert.ok(declared !== undefined, `${operand} is not a detect-changes output`);
			return declared;
		}
		const contained = /^contains\(needs\.\*\.result, '(\w+)'\)$/.exec(operand)?.[1];
		if (contained !== undefined) return String(Object.values(results).includes(contained));
		throw new Error(`this test cannot evaluate \`${operand}\``);
	};
	return shell.replaceAll(/\$\{\{ (.+?) }}/g, (_, expression: string) => {
		const terms = expression.split(" && ");
		if (terms.length === 1 && !/ (?:==|!=) /.test(expression)) return value(expression);
		// A conjunction of comparisons renders as the literal `true` or `false`, which is why the
		// workflow reduces its conditions to one before a shell ever sees them.
		return String(
			terms.every((term) => {
				const comparison = /^(.+) (==|!=) '(\w*)'$/.exec(term);
				assert.ok(comparison, `this test cannot evaluate \`${term}\``);
				const [, operand, operator, literal] = comparison;
				const equal = value(operand ?? "") === literal;
				return operator === "==" ? equal : !equal;
			}),
		);
	});
}

function taskClosure(tasks: Record<string, unknown>, roots: Iterable<string>): Set<string> {
	const closure = new Set<string>();
	const expand = (name: string): void => {
		if (closure.has(name)) return;
		assert.ok(name in tasks, `${name} is named as a dependency but is not a task`);
		closure.add(name);
		const task = asRecord(tasks[name], name);
		if (Array.isArray(task.dependsOn))
			for (const dependency of task.dependsOn)
				if (typeof dependency === "string") expand(dependency);
		for (const command of commandsOf(task)) {
			const nested = /^vp run ([\w:-]+)$/.exec(command)?.[1];
			if (nested !== undefined) expand(nested);
		}
	};
	for (const root of roots) expand(root);
	return closure;
}

void describe("CI contract", () => {
	void test("task names follow the vocabulary in AGENTS.md", async () => {
		const tasks = await loadTasks();
		const instructions = await readFile("AGENTS.md", "utf8");
		const vocabulary = /^### Task vocabulary\n([\s\S]*?)(?=^###? )/m.exec(instructions)?.[1];
		assert.ok(vocabulary, "AGENTS.md § Verifying must contain the task vocabulary");
		const allowedPrefixes = new Set(
			[...vocabulary.matchAll(/^\| `([a-z]+)` \|/gm)].map((match) => match[1] ?? ""),
		);
		assert.ok(allowedPrefixes.size > 0, "AGENTS.md task vocabulary lists no prefixes");
		const used = new Set<string>();
		for (const name of Object.keys(tasks).toSorted()) {
			assert.match(name, /^[a-z]+(?::[a-z0-9-]+)*$/, `${name} is not a valid task name`);
			const prefix = name.split(":", 1)[0] ?? "";
			assert.ok(
				allowedPrefixes.has(prefix),
				`${name} does not use a task prefix listed in AGENTS.md § Verifying`,
			);
			used.add(prefix);
		}
		assert.deepEqual(
			[...allowedPrefixes].filter((prefix) => !used.has(prefix)),
			[],
			"AGENTS.md § Verifying lists a task prefix no task uses",
		);
	});

	void test("every gate belongs to quality or the CI-only inventory", async () => {
		const tasks = await loadTasks();
		const reachable = taskClosure(tasks, ["quality"]);
		assert.deepEqual(
			Object.keys(tasks)
				.filter((taskName) => taskName.startsWith("gate:") && !reachable.has(taskName))
				.toSorted(),
			[...CI_ONLY_GATES].toSorted(),
			"every gate must be reachable from quality or listed as CI-only",
		);
	});

	void test("required tooling CI renders docs after its checks", async () => {
		const tasks = await loadTasks();
		const tooling = asRecord(tasks["ci:tooling"], "ci:tooling");
		assert.deepEqual(commandsOf(tooling), ["vp run verification:docs-build"]);
		const dependencies = asArray(tooling.dependsOn, "ci:tooling.dependsOn").map((dependency) =>
			asString(dependency, "ci:tooling dependency"),
		);
		const checks = taskClosure(tasks, dependencies);
		assert.ok(checks.has("gate:docs-lint"));
		assert.ok(!checks.has("docs:build"), "docs must render after, not alongside, the checks");
		assert.ok(taskClosure(tasks, ["ci:tooling"]).has("docs:build"));
	});

	void test("every local gate runs in a workflow, and every CI gate is a local gate", async () => {
		const tasks = await loadTasks();
		const local = taskClosure(tasks, ["quality"]);
		const ciRoots = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			if (!isMap(jobs)) continue;
			for (const entry of jobs.items) {
				if (!isMap(entry.value)) continue;
				for (const name of invokedTasks(entry.value)) {
					assert.ok(name in tasks, `${file} runs ${name}, which is not a task`);
					ciRoots.add(name);
				}
			}
		}
		const ci = taskClosure(tasks, ciRoots);
		const gates = (names: Set<string>): string[] =>
			[...names].filter((name) => name.startsWith("gate:")).toSorted();
		assert.deepEqual(
			gates(local).filter((gate) => !ci.has(gate)),
			[],
		);
		assert.deepEqual(
			gates(ci).filter((gate) => !local.has(gate) && !CI_ONLY_GATES.has(gate)),
			[],
		);
		assert.deepEqual(
			[...CI_ONLY_GATES].filter((gate) => !ci.has(gate)),
			[],
			"every CI-only gate must run in CI",
		);
	});

	void test("server CI uses native Gradle caching and verifies JUnit selection", async () => {
		const action = await readFile(".github/actions/setup-caches/action.yml", "utf8");
		assert.match(action, /gradle\/actions\/setup-gradle@/);
		assert.doesNotMatch(action, /cache-type|\.m2/);
		const workflow = parseDocument(await readFile(".github/workflows/ci-tests.yml", "utf8"));
		const matrix = workflow.getIn(["jobs", "server-integration", "strategy", "matrix"]);
		assert.ok(isMap(matrix));
		assert.deepEqual(matrixValues(matrix, "shard"), ["providers-and-startup", "application"]);
		assert.equal(
			runScript(workflow, ["jobs", "server-verification"], "Verify test tier and shard discovery"),
			"vp run test:server:selection",
		);
	});

	void test("the server package job is the only Gradle cache producer", async () => {
		const action = parseDocument(await readFile(".github/actions/setup-caches/action.yml", "utf8"));
		assert.equal(action.getIn(["inputs", "cache-write", "default"]), "false");
		assert.equal(
			namedStep(action, ["runs"], "Set up JDK").getIn(["with", "java-version-file"]),
			".java-version",
		);
		const sources = await workflowSources();
		const writers = [...sources].filter(([, source]) => source.includes('cache-write: "true"'));
		assert.deepEqual(
			writers.map(([file]) => file),
			[".github/workflows/cicd.yml"],
		);
		const build = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const steps = build.getIn(["jobs", "server-package", "steps"]);
		assert.ok(isSeq(steps));
		const cache = steps.items.find(
			(item) => isMap(item) && item.get("uses") === "./.github/actions/setup-caches",
		);
		assert.ok(isMap(cache));
		assert.equal(cache.getIn(["with", "cache-write"]), "true");
		const source = await readFile(".github/actions/setup-caches/action.yml", "utf8");
		assert.match(source, /inputs.cache-write != 'true' \|\| github.ref != format/);
	});

	void test("OpenAPI generation uses the read-only Gradle setup but runtime-only E2E does not", async () => {
		const api = parseDocument(await readFile(".github/workflows/openapi-autocommit.yml", "utf8"));
		const setup = namedStep(api, ["jobs", "generate"], "Set up Java build");
		assert.equal(setup.get("uses"), "./.github/actions/setup-caches");
		assert.notEqual(setup.getIn(["with", "cache-write"]), "true");
		assert.equal(api.getIn(["jobs", "generate", "permissions", "contents"]), "read");
		assert.doesNotMatch(job(String(api), "generate"), /actions\/setup-java@/);
		assert.doesNotMatch(job(String(api), "commit"), /setup-caches|gradlew|vp run/);
		const build = await readFile(".github/workflows/ci-build.yml", "utf8");
		assert.match(job(build, "webapp-e2e"), /actions\/setup-java@/);
		assert.doesNotMatch(job(build, "webapp-e2e"), /setup-caches|gradlew/);
	});

	void test("GHCR-only rescans share login while the image builder keeps its registry input", async () => {
		const rescan = parseDocument(
			await readFile(".github/workflows/rescan-main-images.yml", "utf8"),
		);
		const login = namedStep(rescan, ["jobs", "rescan"], "Log in to Container Registry");
		assert.equal(login.get("uses"), "./.github/actions/ghcr-login");
		assert.equal(login.getIn(["with", "username"]), `\${{ github.actor }}`);
		assert.equal(login.getIn(["with", "password"]), `\${{ secrets.GITHUB_TOKEN }}`);
		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		for (const name of ["build", "merge", "scan"]) {
			const registry = step(reusable, ["jobs", name], "docker/login-action");
			assert.equal(registry.get("registry"), `\${{ inputs.registry }}`);
		}
	});

	void test("image scans are delegated only to required release preflight", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.equal(
			workflow.getIn(["jobs", "detect-changes", "outputs", "release-preflight"]),
			`\${{ (github.event_name == 'workflow_dispatch' && inputs.release-preflight) || steps.release_candidate.outputs.release-candidate == 'true' }}`,
		);
		for (const name of ["application-server-image", "Docker"])
			assert.equal(
				workflow.getIn(["jobs", name, "with", "scan-images"]),
				`\${{ needs.detect-changes.outputs.release-preflight != 'true' }}`,
			);
		for (const file of ["ci-docker-build.yml", "reusable-docker-build.yml"]) {
			const reusable = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
			assert.equal(
				reusable.getIn(["on", "workflow_call", "inputs", "scan-images", "default"]),
				true,
			);
			const jobs = reusable.get("jobs");
			assert.ok(isMap(jobs));
			for (const { value } of jobs.items)
				if (isMap(value) && value.get("uses") === "./.github/workflows/reusable-docker-build.yml")
					assert.equal(value.getIn(["with", "scan-images"]), `\${{ inputs.scan-images }}`);
		}
		const docker = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		assert.match(
			String(docker.getIn(["jobs", "scan", "if"])),
			/inputs.scan-images && inputs.publish/,
		);
	});

	void test("path exclusions cannot select unrelated files for server tests or webapp images", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const steps = workflow.getIn(["jobs", "detect-changes", "steps"]);
		assert.ok(isSeq(steps));
		const filters = steps.items.filter(
			(item) => isMap(item) && String(item.get("uses")).startsWith("dorny/paths-filter@"),
		);
		assert.equal(filters.length, 1);
		const filter = filters[0];
		assert.ok(isMap(filter));
		assert.equal(filter.getIn(["with", "predicate-quantifier"]), "some-with-excludes");
	});

	void test("Gradle lock changes rebuild and verify the shipped server", async () => {
		const build = await readFile("server/build.gradle.kts", "utf8");
		assert.match(build, /lockAllConfigurations\(\)/);
		assert.match(build, /lockMode\.set\(LockMode\.STRICT\)/);
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		for (const gate of ["application-server-image", "e2e", "pmd-canary"]) {
			const paths = asArray(filters[gate], gate);
			for (const input of ["server/application/gradle.lockfile", "server/settings-gradle.lockfile"])
				assert.ok(paths.includes(input), `${gate} must include ${input}`);
		}
		assert.ok(asArray(filters.e2e, "browser test paths").includes(".java-version"));
	});

	void test("test-report inputs use permitted expressions and annotate every failed test leg", async () => {
		const reporters = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			visit(parseDocument(source), {
				Map(_key, declaration) {
					if (!String(declaration.get("uses")).startsWith("dorny/test-reporter@")) return;
					const inputs = stepInputs(declaration);
					const template = asString(inputs.get("max-annotations"), `${file} max-annotations`);
					const expression = template.match(/^\$\{\{([\s\S]+)}}$/)?.[1];
					assert.ok(expression, `${file} must calculate annotation count`);
					// Status functions are available in step `if`, not action `with` inputs.
					// No custom status functions are registered: the native expression parser rejects them.
					const parsed = new Parser(
						new Lexer(expression).lex().tokens,
						["job", "steps"],
						[],
					).parse();
					const name = String(inputs.get("name"));
					reporters.add(`${file}: ${name}`);
					for (const status of ["success", "failure", "cancelled"])
						for (const tests of ["success", "failure", "skipped"])
							for (const chromatic of ["success", "failure", "skipped"]) {
								const context: unknown = JSON.parse(
									JSON.stringify({
										job: { status },
										steps: { tests: { outcome: tests }, chromatic: { outcome: chromatic } },
									}),
									data.reviver,
								);
								assert.ok(context instanceof data.Dictionary);
								const failed =
									status === "failure" ||
									(name === "Test Results - Webapp Stories" && tests === "failure") ||
									(name === "Test Results - Chromatic" && chromatic === "failure");
								assert.equal(
									new Evaluator(parsed, context).evaluate().coerceString(),
									failed ? "50" : "0",
									`${file}: ${name}, ${status}, tests=${tests}, chromatic=${chromatic}`,
								);
							}
				},
			});
		}
		assert.equal(reporters.size, 6);
	});

	void test("Chromatic retains structured evidence without credential-bearing debug files", async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		const jobPath = ["jobs", "webapp-stories"];
		const chromatic = step(workflow, jobPath, "chromaui/action");
		assert.equal(chromatic.has("logFile"), false);
		assert.equal(chromatic.has("diagnosticsFile"), false);
		assert.equal(chromatic.get("logLevel"), "warn");
		assert.equal(chromatic.get("junitReport"), "chromatic-report.xml");
		const retained = stepInputs(
			namedStep(workflow, jobPath, "Retain full Storybook and visual test diagnostics"),
		);
		assert.deepEqual(asString(retained.get("path"), "report paths").trim().split("\n"), [
			"webapp/test-results",
			"webapp/chromatic-report.xml",
		]);
	});

	void test("buildpack reporting changes exercise the image pipeline", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		assert.ok(
			asArray(filters["application-server-image"], "image paths").includes(
				"scripts/summarize-buildpack-log.ts",
			),
		);
	});

	void test("security mutation checks follow their Gradle launcher and toolchain inputs", async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/security-mutation.yml", "utf8"),
		);
		const node = workflow.getIn(["on", "pull_request", "paths"]);
		assert.ok(isSeq(node));
		const paths = asArray(node.toJSON(), "mutation paths");
		for (const input of [
			".github/actions/setup-caches/**",
			".java-version",
			"scripts/run-gradlew.ts",
		])
			assert.ok(paths.includes(input), `security mutation checks must include ${input}`);
	});

	void test("changes to the PMD canary exercise it even without server source changes", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		const paths = asArray(filters["pmd-canary"], "PMD canary paths");
		for (const input of [
			"scripts/check-pmd-canary.ts",
			"scripts/check-pmd-canary.test.ts",
			"vite.config.ts",
		])
			assert.ok(paths.includes(input), `PMD canary must include ${input}`);
	});

	void test("runs the integration tier once, under the status gate, off an untemplated command", async () => {
		const source = await readFile(".github/workflows/ci-tests.yml", "utf8");
		const workflow = parseDocument(source);
		const integration = job(source, "server-integration");
		assert.match(integration, /fail-fast: false/, "One failing shard must not hide the other");
		const jobPath = ["jobs", "server-integration"];
		const run = namedStep(workflow, jobPath, "Run integration tests");
		const environment = run.get("env");
		assert.ok(isMap(environment));
		assert.match(
			String(environment.get("HEPHAESTUS_INTEGRATION_SHARD")),
			/matrix\.shard/,
			"The shard's selector reaches the task as an environment value",
		);
		assert.doesNotMatch(
			runScript(workflow, jobPath, "Run integration tests"),
			/\$\{\{/,
			"The selector reaches Gradle through the environment, never through a run: script",
		);
		assert.equal((source.match(/run: vp run test:server:integration/g) ?? []).length, 1);
		assert.match(integration, /Test Results - App Server Integration \(\$\{\{ matrix.shard \}\}\)/);
		const tasks = await readFile("vite.config.ts", "utf8");
		assert.match(
			await readFile("server/application/build.gradle.kts", "utf8"),
			/HEPHAESTUS_INTEGRATION_SHARD/,
		);
		assert.match(tasks, /"test:server:integration": run\([\s\S]*?:application:integrationTest/);
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		assert.match(job(orchestrator, "Test"), /uses: \.\/\.github\/workflows\/ci-tests.yml/);
		assert.match(job(orchestrator, "all-ci-passed"), /needs\.Test\.result/);
	});

	void test("packages the server once and runs every artifact gate against it", async () => {
		const build = await readFile(".github/workflows/ci-build.yml", "utf8");
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		const packageJob = job(orchestrator, "server-package");
		const packaging = packageJob.match(/^\s+run: (\.\/gradlew .*)$/m)?.[1];
		assert.ok(packaging);
		assert.match(packaging, /:application:bootJar/);
		assert.match(packaging, /:application:testClasses/);
		assert.equal((packageJob.match(/actions\/upload-artifact@/g) ?? []).length, 1);
		assert.match(packageJob, /overwrite: true/);
		for (const name of ["server-api", "server-database"]) {
			const consumer = job(build, name);
			assert.doesNotMatch(consumer, /needs:/);
			assert.match(consumer, /uses: \.\/\.github\/actions\/restore-server-build/);
			// Goals against the restored classes; a lifecycle phase would compile again.
			assert.doesNotMatch(
				consumer,
				/gradlew[^\n]* :(?:application|generated-clients):(?:compileJava|compileTestJava|bootJar|classes|testClasses)(?:\s|$)/,
			);
		}
		assert.match(job(build, "server-database"), /:application:databaseTest -PpackagedServer=true/);
		assert.match(job(build, "server-api"), /HEPHAESTUS_APPLICATION_JAR/);
		const e2e = job(build, "webapp-e2e");
		assert.doesNotMatch(e2e, /needs:/);
		assert.equal((e2e.match(/actions\/download-artifact@/g) ?? []).length, 1);
		assert.match(e2e, /name: Upload diagnostics\s+if: always\(\)/);
		assert.match(e2e, /e2e-server\.log/);
		assert.match(e2e, /http:\/\/localhost:8080\/actuator\/health\/readiness/);
		assert.doesNotMatch(e2e, /actuator\/health\/liveness/);
		const image = job(orchestrator, "application-server-image");
		assert.match(image, /needs: \[detect-changes, server-package, vulnerability-database\]/);
		assert.match(image, /use-buildpacks: true/);

		// The long suites compile from source and never wait for the package job.
		const tests = await readFile(".github/workflows/ci-tests.yml", "utf8");
		assert.doesNotMatch(tests, /^ {4}needs:|download-artifact|restore-server-build/m);
		assert.match(job(tests, "server-verification"), /vp run test:server:verification/);
		assert.match(job(tests, "server-integration"), /vp run test:server:integration/);
		assert.match(job(orchestrator, "Build"), /needs: \[detect-changes, server-package\]/);

		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		const packBuilds = [...reusable.replace(/\\\n\s*/g, " ").matchAll(/^\s+pack build .*$/gm)].map(
			(match) => match[0],
		);
		assert.ok(packBuilds.length > 0, "the buildpacks path must call pack build");
		for (const packBuild of packBuilds)
			for (const flag of ["--path", "--descriptor", "--run-image"])
				assert.ok(packBuild.includes(flag), `pack build must pass ${flag}`);
		// One invocation exports to the registry; the fork path builds the same image locally.
		assert.equal(packBuilds.filter((call) => call.includes("--publish")).length, 1);
		for (const [file, source] of await workflowSources()) {
			assert.doesNotMatch(source, /bootBuildImage/, `${file} must build the image from the JAR`);
		}
	});

	void test("image consumers do not wait for unrelated artifact checks, but the final verdict does", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		for (const name of ["Build", "application-server-image"]) {
			const dependencies = workflow.getIn(["jobs", name, "needs"]);
			assert.ok(isSeq(dependencies));
			assert.deepEqual(dependencies.toJSON(), [
				"detect-changes",
				"server-package",
				...(name === "application-server-image" ? ["vulnerability-database"] : []),
			]);
		}
		for (const name of ["Supported-host-smoke", "Release-preflight"]) {
			const dependencies = workflow.getIn(["jobs", name, "needs"]);
			assert.ok(isSeq(dependencies));
			assert.deepEqual(dependencies.toJSON(), [
				"detect-changes",
				"application-server-image",
				"Docker",
				...(name === "Release-preflight" ? ["vulnerability-database"] : []),
			]);
		}
		const gate = workflow.getIn(["jobs", "all-ci-passed", "needs"]);
		assert.ok(isSeq(gate));
		for (const name of [
			"server-package",
			"application-server-image",
			"Build",
			"Test",
			"Docker",
			"Supported-host-smoke",
			"Release-preflight",
		])
			assert.ok(
				gate.items.some((item) => isScalar(item) && item.value === name),
				`The final verdict must require ${name}`,
			);
	});

	void test("builds Storybook once and gives its TurboSnap stats to Chromatic", async () => {
		const storybook = job(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
			"webapp-stories",
		);
		assert.equal((storybook.match(/vp run --filter webapp build-storybook/g) ?? []).length, 1);
		assert.match(storybook, /test -s webapp\/storybook-static\/preview-stats\.json/);
		assert.match(storybook, /storybookBuildDir: storybook-static/);
		assert.match(storybook, /onlyChanged: true/);
		assert.match(storybook, /surge \.\/webapp\/storybook-static/);
		assert.match(storybook, /render-preview-comment\.ts storybook webapp\/storybook-static/);
		assert.match(storybook, /github\.event\.pull_request\.base\.sha/);
		assert.match(
			storybook,
			/PREVIEW_COMMENT_PATH: \$\{\{ runner\.temp \}\}\/storybook-preview\.json/,
		);
		assert.match(storybook, /name: Create Storybook status check\s+if: >-\s+success\(\)/);
	});

	void test("publishes documentation links derived from the built pages", async () => {
		const docs = await readFile(".github/workflows/cd-docs.yml", "utf8");
		assert.ok(
			String(parseDocument(docs).getIn(["on", "pull_request", "paths"])).includes(
				"scripts/render-preview-comment.ts",
			),
		);
		const buildPreview = job(docs, "build-preview");
		assert.match(buildPreview, /fetch-depth: 0/);
		assert.match(buildPreview, /render-preview-comment\.ts docs docs\/\.docusaurus/);
		assert.match(buildPreview, /github\.event\.pull_request\.base\.sha/);
		assert.match(
			job(docs, "preview"),
			/PREVIEW_COMMENT_PATH: preview-comment\/docs-preview-comment\.json/,
		);
	});

	void test("shares a continuation-aware publisher across both previews and teardown", async () => {
		for (const [file, kind] of [
			["ci-quality-gates.yml", "storybook"],
			["cd-docs.yml", "docs"],
		]) {
			const source = await readFile(`.github/workflows/${file}`, "utf8");
			assert.match(source, /scripts\/publish-preview-comments\.ts/);
			assert.ok(source.includes(`kind: "${kind}", path: process.env.PREVIEW_COMMENT_PATH`));
		}
		const docs = parseDocument(await readFile(".github/workflows/cd-docs.yml", "utf8"));
		for (const dependency of [
			"scripts/publish-preview-comments.ts",
			"scripts/lib/preview-comment.ts",
		]) {
			assert.ok(String(docs.getIn(["on", "pull_request", "paths"])).includes(dependency));
		}
		const teardown = await readFile(".github/workflows/cd-docs-teardown.yml", "utf8");
		assert.match(teardown, /scripts\/publish-preview-comments\.ts/);
		assert.match(teardown, /for \(const kind of \["docs", "storybook"\]\)/);
		assert.match(teardown, /publishPreviewComments\(\{ github, context, kind \}\)/);
	});

	void test("routes tooling-only changes away from server infrastructure", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const detection = job(source, "detect-changes");
		const tooling = pathFilter(detection, "tooling");
		for (const pattern of ["docs/**", ".vscode/settings.json", "**/AGENTS.md", "**/CLAUDE.md"]) {
			assert.match(tooling, new RegExp(`- '${escapeRegExp(pattern)}'`));
		}
		assert.doesNotMatch(pathFilter(detection, "application-server"), /- 'docs\/\*\*'/);
		assert.match(pathFilter(detection, "postgres-image"), /- 'docker\/postgres\/\*\*'/);
		assert.match(pathFilter(detection, "webapp-image"), /- 'patches\/\*\*'/);
	});

	void test("invalidates CI legs through their owned workflow dependencies", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const detection = job(source, "detect-changes");
		const expected = new Map([
			["quality-config", ".github/workflows/ci-quality-gates.yml"],
			["build-config", ".github/workflows/ci-build.yml"],
			["test-config", ".github/workflows/ci-tests.yml"],
			["security-config", ".github/workflows/ci-security-scan.yml"],
		]);
		for (const [filter, workflow] of expected) {
			const patterns = pathFilter(detection, filter);
			assert.match(patterns, new RegExp(`- '${escapeRegExp(workflow)}'`));
			assert.match(patterns, /- '\.github\/workflows\/cicd\.yml'/);
			assert.doesNotMatch(patterns, /- '\.github\/workflows\/\*\*'/);
			assert.match(source, new RegExp(`outputs\\.${escapeRegExp(filter)} == 'true'`));
		}
	});

	void test("separates pre-merge validation from post-merge artifact production", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		for (const event of ["workflow_dispatch", "push", "pull_request", "merge_group"]) {
			assert.match(source, new RegExp(`^ {2}${event}:`, "m"));
		}
		assert.match(
			job(source, "detect-changes"),
			/version-bump: \${{ steps\.version_bump\.outputs\.changed }}/,
		);
		for (const name of ["workflow-lint", "zizmor", "Quality", "Security", "Test", "Compose"]) {
			assert.match(
				job(source, name),
				name === "Security"
					? /needs: \[detect-changes, vulnerability-database\]/
					: /needs: \[detect-changes\]/,
			);
			assert.match(
				job(source, name),
				/github\.event_name != 'push'.*needs\.detect-changes\.outputs\.version-bump == 'true'/s,
			);
		}
		// Every push to main packages and publishes the final-SHA images; only a version bump
		// repeats source validation, so the artifact gates inside Build follow the same rule.
		assert.doesNotMatch(job(source, "Docker"), /version-bump/);
		const build = job(source, "Build");
		assert.doesNotMatch(build.slice(0, build.indexOf("with:")), /version-bump/);
		for (const gate of ["contracts_changed", "e2e_changed"])
			assert.match(build, new RegExp(`^\\s+${gate}:.*version-bump == 'true'`, "m"));
		assert.match(job(source, "all-ci-passed"), /needs: \[[^\]]*Compose[^\]]*Docker[^\]]*\]/);

		const compose = await readFile(".github/workflows/ci-compose-validate.yml", "utf8");
		assert.match(compose, /on:\n {2}workflow_call:\n/);
		assert.match(job(source, "Compose"), /uses: \.\/\.github\/workflows\/ci-compose-validate\.yml/);
	});

	void test("reports a failed merge-group run on its pull request, and only a failure", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const reporter = job(source, "report-unsuccessful-merge-group");
		assert.match(reporter, /needs: \[all-ci-passed\]/);
		assert.match(reporter, /github\.event_name == 'merge_group'/);
		// A merge queue cancels the runs it regroups, which says nothing about the pull request.
		assert.match(reporter, /needs\.all-ci-passed\.result == 'failure'/);
		assert.doesNotMatch(reporter, /always\(\)/);
		assert.match(reporter, /pull-requests: write/);
		// A ref the job cannot read is a missing report, so it fails rather than commenting nowhere.
		assert.match(reporter, /core\.setFailed/);
	});

	void test("a merge group selects its jobs by the paths its own diff touches", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const workflow = parseDocument(source);

		// `!= 'pull_request'` reads every other event as "run everything", so it opts each new event
		// into the whole workflow. The ban cannot cover `detect-changes`, which holds the two outputs
		// that do test the event, so the decision every filtered job reads is pinned on its own.
		assert.doesNotMatch(
			source.replace(job(source, "detect-changes"), ""),
			/github\.event_name != 'pull_request'/,
		);
		assert.match(
			String(workflow.getIn(["jobs", "detect-changes", "outputs", "unfiltered"])),
			/^\$\{\{ github\.event_name == 'push' \|\| github\.event_name == 'workflow_dispatch' }}$/,
		);

		// Asserted on the condition, not the whole job: a `with:` input the condition never reads
		// must not satisfy it.
		for (const name of [
			"workflow-lint",
			"zizmor",
			"Quality",
			"server-package",
			"Security",
			"Test",
			"Docker",
		])
			assert.match(
				String(workflow.getIn(["jobs", name, "if"])),
				/needs\.detect-changes\.outputs\.unfiltered == 'true'/,
				`${name} does not read the path-selection decision`,
			);

		assert.doesNotMatch(
			String(workflow.getIn(["jobs", "detect-changes", "outputs", "all-images"])),
			/event_name/,
		);
		// The caller owns which commit a run is a diff from; `ci-build.yml` derives nothing from
		// the event.
		const build = await readFile(".github/workflows/ci-build.yml", "utf8");
		assert.doesNotMatch(build, /github\.event\.pull_request\.base\.sha/);
	});

	void test("decides an image's architectures once, for every image the run builds", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const caller = job(source, "Docker");
		for (const secret of ["SENTRY_AUTH_TOKEN", "SENTRY_ORG", "SENTRY_PROJECT"]) {
			assert.match(
				caller,
				new RegExp(`${secret}: \\$\\{\\{ github\\.event_name == 'push' && secrets\\.${secret}`),
			);
		}

		const docker = await readFile(".github/workflows/ci-docker-build.yml", "utf8");
		// The architecture set is one decision, taken in cicd.yml and handed to every image build, so
		// a run cannot evidence one image on both platforms and its sibling on one. The test below
		// owns what that decision is; this owns that nothing decides it locally.
		for (const image of [
			job(docker, "webapp-build"),
			job(docker, "agent-pi-build"),
			job(docker, "postgres-build"),
		]) {
			assert.match(image, /single-arch: \${{ inputs\.single_arch == 'true' }}/);
			assert.doesNotMatch(image, /^\s+tags:/m);
		}
		for (const called of [docker])
			// Required, and with no default: a caller that forgets it fails to start, rather than
			// silently publishing one architecture where a release needs two.
			assert.match(called, /^ {6}single_arch:\n(?: {8}.*\n)*? {8}required: true$/m);
		assert.match(
			job(source, "application-server-image"),
			/single-arch: \${{ needs\.detect-changes\.outputs\.single-arch == 'true' }}/,
		);
		for (const consumer of [job(source, "Docker")])
			assert.match(consumer, /single_arch: \${{ needs\.detect-changes\.outputs\.single-arch }}/);
		const inherited = job(docker, "tag-unchanged-images");
		assert.match(inherited, /HEAD_SHA/);
		assert.match(inherited, /pr-\$PR_NUMBER/);

		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		for (const secret of ["SENTRY_AUTH_TOKEN", "SENTRY_ORG", "SENTRY_PROJECT"]) {
			assert.match(reusable, new RegExp(`github\\.event_name == 'push'.*secrets\\.${secret}`));
		}
		assert.match(
			reusable,
			/inputs\.single-arch.*linux\/amd64.*ubuntu-24\.04.*linux\/arm64.*ubuntu-24\.04-arm/,
		);
	});

	void test("a pull request based on an unmerged branch still reaches a Docker verdict", async () => {
		const orchestrator = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const detect = ["jobs", "detect-changes"];
		const resolve = namedStep(
			orchestrator,
			detect,
			"Resolve the commit unchanged images are aliased from",
		);
		assert.equal(resolve.get("run"), "node scripts/resolve-alias-base.ts");
		assert.match(
			String(orchestrator.getIn([...detect, "outputs", "alias-base"])),
			/^\$\{\{ steps\.alias_base\.outputs\.commit }}$/,
		);

		// A layer's base is the head of an open pull request, which published no image of its own. The
		// walk ends on the commit the default branch contains, and that is what the tagging job aliases.
		const published = "a".repeat(40);
		assert.equal(
			await resolveAliasBase("b".repeat(40), "main", {
				compare: (base) => Promise.resolve(base === published ? "ahead" : "diverged"),
				baseOf: () => Promise.resolve(published),
			}),
			published,
		);

		const docker = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));
		const tagging = ["jobs", "tag-unchanged-images"];
		assert.match(
			String(orchestrator.getIn(["jobs", "Docker", "with", "alias_base"])),
			/^\$\{\{ needs\.detect-changes\.outputs\.alias-base }}$/,
		);
		const shell = runScript(docker, tagging, "Verify and tag unchanged images");
		assert.match(shell, /\$ALIAS_BASE/);
		assert.doesNotMatch(shell, /BASE_SHA/);

		// A run that reaches no published commit builds every image rather than failing to alias one,
		// which leaves this job nothing to do and the Docker verdict green either way.
		assert.match(
			String(orchestrator.getIn([...detect, "outputs", "all-images"])),
			/steps\.alias_base\.outputs\.commit == ''/,
		);
		for (const image of [
			"webapp_changed",
			"application_server_changed",
			"agent_images_changed",
			"postgres_image_changed",
		]) {
			assert.match(
				String(orchestrator.getIn(["jobs", "Docker", "with", image])),
				/all-images == 'true'/,
				`${image} must be built when the run has nothing to alias`,
			);
			assert.match(
				String(docker.getIn([...tagging, "if"])),
				new RegExp(`inputs\\.${image} != 'true'`),
			);
		}
	});

	void test("builds fork pull-request images without registry writes", async () => {
		const orchestrator = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		// One decision, taken where the run can see whose repository the head branch is on. A fork's
		// GITHUB_TOKEN is read-only, so a publishing build there fails on its first write.
		assert.match(
			String(orchestrator.getIn(["jobs", "detect-changes", "outputs", "publishable"])),
			/^\$\{\{ github\.event_name != 'pull_request' \|\| github\.event\.pull_request\.head\.repo\.full_name == github\.repository }}$/,
		);
		for (const caller of ["application-server-image", "Docker"])
			assert.match(
				String(orchestrator.getIn(["jobs", caller, "with", "publish"])),
				/^\$\{\{ needs\.detect-changes\.outputs\.publishable == 'true' }}$/,
			);

		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const declaration = reusable.getIn(["on", "workflow_call", "inputs", "publish"]);
		assert.ok(isMap(declaration));
		assert.equal(declaration.get("type"), "boolean");
		// No default: a caller that forgets it fails to start, rather than silently writing to the
		// registry on behalf of a run that may not be allowed to.
		assert.equal(declaration.get("required"), true);
		assert.equal(declaration.get("default"), undefined);

		const build = ["jobs", "build"];
		for (const name of [
			"Log in to Container Registry",
			"Apply tags to buildpack image",
			"Capture single-architecture digest",
			"Generate build provenance attestation",
			"Install Cosign",
			"Sign and verify image",
			"Upload digest",
		])
			assert.match(
				String(namedStep(reusable, build, name).get("if")),
				/inputs\.publish/,
				`${name} writes to the registry and must be gated on publish`,
			);
		assert.match(
			String(namedStep(reusable, build, "Set up Docker Buildx").getIn(["with", "driver"])),
			/^\$\{\{ inputs\.use-buildpacks && 'docker' \|\| 'docker-container' }}$/,
		);
		assert.equal(
			namedStep(reusable, ["jobs", "merge"], "Set up Docker Buildx").getIn(["with", "driver"]),
			"docker",
		);
		const buildx = namedStep(reusable, build, "Build and push (Dockerfile)");
		const inputs = buildx.get("with");
		assert.ok(isMap(inputs));
		assert.match(String(inputs.get("push")), /^\$\{\{ inputs\.publish }}$/);
		// An untagged local image is one nothing can name, so a fork only loads what it also tags.
		assert.match(String(inputs.get("load")), /^\$\{\{ !inputs\.publish && inputs\.single-arch }}$/);
		assert.match(String(inputs.get("outputs")), /inputs\.publish &&/);
		for (const gated of ["merge", "scan"])
			assert.match(String(reusable.getIn(["jobs", gated, "if"])), /inputs\.publish/);

		const docker = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));
		assert.match(String(docker.getIn(["jobs", "tag-unchanged-images", "if"])), /inputs\.publish/);
	});

	void test(
		"a fork's buildpack build produces an image without contacting the registry",
		runnerStepsOnly,
		async () => {
			const reusable = parseDocument(
				await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
			);
			const shell = runScript(reusable, ["jobs", "build"], "Build with Buildpacks and CDS");
			const directory = await mkdtemp(path.join(tmpdir(), "buildpacks-"));
			await writeFile(path.join(directory, "hephaestus-application-1.0.0.jar"), "");
			const calls = path.join(directory, "calls");
			for (const tool of ["pack", "docker"]) {
				const stub = path.join(directory, tool);
				await writeFile(
					stub,
					`#!/bin/sh\necho "${tool} $*" >> "${calls}"\n[ "$1" = image ] && echo sha256:stub\nexit 0\n`,
				);
				await chmod(stub, 0o755);
			}
			const environment = {
				APPLICATION_DIRECTORY: directory,
				RUNNER_TEMP: directory,
				GITHUB_RUN_ID: "1",
				INPUT_IMAGE_NAME: "hephaestus-build/application-server",
				INPUT_REGISTRY: "ghcr.io",
				MATRIX_PLATFORM: "linux/amd64",
				PATH: `${directory}${path.delimiter}${process.env["PATH"] ?? ""}`,
				PLATFORM_PAIR: "linux-amd64",
				PROJECT_DESCRIPTOR: "project.toml",
				RUN_IMAGE: "paketobuildpacks/run",
			};

			const local = await runStep(shell, { ...environment, PUBLISH: "false" });
			assert.equal(local.failed, false, local.diagnosis);
			// The digest is the daemon's image ID: a local build has no registry to read a manifest back
			// from, and the step must still tell its caller what it produced.
			assert.match(local.outputs["digest"] ?? "", /^sha256:/);
			const invoked = await readFile(calls, "utf8");
			assert.doesNotMatch(invoked, /--publish/);
			assert.match(invoked, /^pack build .*--trust-builder/m);
			// A reporting pipe must never turn a failed archive build into a successful image.
			await writeFile(
				path.join(directory, "pack"),
				"#!/bin/sh\necho archive failed >&2\nexit 23\n",
			);
			const failedBuild = await runStep(shell, { ...environment, PUBLISH: "false" });
			assert.equal(failedBuild.failed, true);
			assert.match(
				await readFile(path.join(directory, "buildpacks.log"), "utf8"),
				/archive failed/,
			);
		},
	);

	void test("every job that boots the supported installation authenticates to the registry", async () => {
		// `packages: read` is inert without a login, and the boot's first pull is where that shows.
		// Only a boot the runner performs itself needs one: a deploy hands its script to a host that
		// authenticates on its own.
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			if (!isMap(jobs)) continue;
			for (const entry of jobs.items) {
				const steps = isMap(entry.value) ? entry.value.get("steps") : undefined;
				if (!isSeq(steps)) continue;
				const boots = steps.items.some(
					(item) =>
						isMap(item) &&
						typeof item.get("run") === "string" &&
						/docker compose .*--env-file[\s\S]*?\bup -d\b/.test(String(item.get("run"))),
				);
				const name = isScalar(entry.key) ? String(entry.key.value) : "";
				if (!boots) continue;
				assert.match(
					job(source, name),
					/uses: \.\/\.github\/actions\/ghcr-login/,
					`${file} ${name} boots first-party images and must log in to the registry`,
				);
			}
		}
	});

	void test("a pull request boots the supported installation from the images its own run built", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const orchestrator = parseDocument(source);
		const condition = String(orchestrator.getIn(["jobs", "Supported-host-smoke", "if"]));
		// A fork publishes no images, so there is nothing for this job to pull.
		assert.match(condition, /needs\.detect-changes\.outputs\.previews == 'true'/);
		assert.match(
			String(orchestrator.getIn(["jobs", "detect-changes", "outputs", "previews"])),
			/github\.event_name == 'pull_request' && github\.event\.pull_request\.head\.repo\.full_name == github\.repository/,
		);
		assert.match(condition, /needs\.detect-changes\.outputs\.supported-host-smoke == 'true'/);

		const smoke = job(source, "Supported-host-smoke");
		assert.match(
			smoke,
			/APPLICATION_DIGEST: \${{ needs\.application-server-image\.outputs\.manifest-digest }}/,
		);
		// The reduced topology an operator's first boot has to get through: no edge, no webapp. The
		// service list runs onto a continuation line, so the command is rejoined before it is read.
		const boot = /up -d --wait --wait-timeout \d+ ([^\n]+)/.exec(smoke.replace(/\s*\\\n\s+/g, " "));
		assert.ok(boot, "the boot smoke must start the installation and wait for it to be ready");
		assert.deepEqual(String(boot[1]).trim().split(/\s+/).toSorted(), [
			"application-server",
			"nats-server",
			"postgres",
		]);
		// The installer an operator runs, filled in by the one script both smoke jobs call.
		assert.match(smoke, /scripts\/prepare-host-smoke-env\.ts/);
		assert.match(
			job(await readFile(".github/workflows/release.yml", "utf8"), "supported-host-smoke"),
			/scripts\/prepare-host-smoke-env\.ts/,
		);
		// The paths that trigger the smoke also have to rebuild the images it boots.
		assert.match(job(source, "application-server-image"), /if:.*supported-host-smoke/);
		assert.match(source, /application_server_changed:.*supported-host-smoke/);
		assert.match(job(source, "all-ci-passed"), /needs: \[[^\]]*Supported-host-smoke[^\]]*\]/);
	});

	void test("the supported-host boot smoke runs on every source it can be broken by", async () => {
		const filter = pathFilter(
			await readFile(".github/workflows/cicd.yml", "utf8"),
			"supported-host-smoke",
		);
		const patterns = [...filter.matchAll(/^ +- '(.+)'$/gm)].map((match) => String(match[1]));
		const selected = new Set((await Promise.all(patterns.map(posixGlob))).flat());
		// Spring binds a class the moment it carries the annotation, wherever it lives, so the filter
		// has to select the whole binding tree rather than the part someone remembered.
		for (const file of await posixGlob("server/application/src/main/java/**/*.java")) {
			if (!/^@ConfigurationProperties(?:Scan)?\b/m.test(await readFile(file, "utf8"))) continue;
			assert.ok(
				selected.has(file),
				`${file} binds configuration at boot, so a change to it must run the supported-host boot smoke`,
			);
		}
	});

	void test("every image a consumer resolves is one the build published for that event", async () => {
		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const declared = reusable.getIn(["env", "STANDARD_IMAGE_TAGS"]);
		assert.equal(typeof declared, "string");
		// Native metadata-action entries keep the event guard alongside the value. A tag names
		// the artifact, not a run attempt that a re-run could never resolve.
		const guard =
			/^type=raw,value=\$\{\{ (.+?) }},enable=\$\{\{ github\.event_name (==|!=) '(\w+)' }}$/;
		const lines = String(declared)
			.split("\n")
			.filter((line) => line.length > 0);
		const publishedOn = (event: string): string[] =>
			lines.flatMap((line) => {
				const parsed = guard.exec(line);
				assert.ok(parsed, `image tag "${line}" is published under every event`);
				return (parsed[2] === "==") === (parsed[3] === event) ? [String(parsed[1])] : [];
			});

		assert.deepEqual(publishedOn("push"), [
			"github.ref_name",
			"format('ci-{0}', github.run_number)",
			"github.sha",
		]);
		assert.deepEqual(publishedOn("pull_request"), [
			"github.event.pull_request.head.sha",
			"format('pr-{0}', github.event.number)",
		]);
		for (const event of ["merge_group", "workflow_dispatch"])
			assert.deepEqual(publishedOn(event), ["github.sha"]);
		for (const name of ["build", "merge"]) {
			const metadata = step(reusable, ["jobs", name], "docker/metadata-action");
			assert.equal(
				metadata.get("tags"),
				name === "build"
					? `\${{ inputs.single-arch && env.STANDARD_IMAGE_TAGS || '' }}`
					: `\${{ env.STANDARD_IMAGE_TAGS }}`,
			);
		}
		assert.doesNotMatch(String(reusable), /steps\.tags\.outputs|Prepare tag configuration/);

		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const triggers = /^on:\n([\s\S]*?)^\S/m.exec(source)?.[1] ?? "";
		const events = [...triggers.matchAll(/^ {2}(\w+):/gm)].map((match) => String(match[1]));
		assert.ok(events.includes("workflow_dispatch") && events.includes("merge_group"));
		for (const event of events) {
			// A pull request resolves its own head: `github.sha` there is a merge commit no image
			// carries. Every other event resolves the commit it ran on.
			const resolved =
				event === "pull_request" ? "github.event.pull_request.head.sha" : "github.sha";
			assert.ok(
				publishedOn(event).includes(resolved),
				`a ${event} run resolves images by ${resolved}, which the image build does not publish`,
			);
		}

		// The consumers, and the commit each resolves through. The scan resolves a digest its own
		// producer job emitted, so it needs no tag at all.
		const preflight = job(source, "Release-preflight");
		assert.match(
			preflight,
			/COMMIT: \${{ github\.event\.pull_request\.head\.sha \|\| github\.sha }}/,
		);
		assert.match(preflight, /resolve-release-images\.ts "\$COMMIT"/);
		assert.match(preflight, /--commit "\$COMMIT"/);
		const smoke = job(source, "Supported-host-smoke");
		assert.match(smoke, /HEAD_SHA: \${{ github\.event\.pull_request\.head\.sha }}/);
		// `workflow_run.head_sha` is the producing push run's own `github.sha`.
		const release = await readFile(".github/workflows/release.yml", "utf8");
		assert.match(
			job(release, "tag-images"),
			/SOURCE_TAG: \${{ github\.event\.workflow_run\.head_sha }}/,
		);

		for (const [file, workflow] of await workflowSources())
			assert.doesNotMatch(
				workflow,
				/:run-\$/,
				`${file} must not resolve an image by the run that happened to build it`,
			);
	});

	void test("enforces the release vulnerability policy where images are built", async () => {
		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		const scan = job(reusable, "scan");
		// Blocking, and after the push: it needs both build paths, and a skipped `merge`
		// (single-architecture builds) must not skip it.
		assert.match(scan, /needs: \[build, merge\]/);
		assert.match(scan, /!cancelled\(\)/);
		assert.match(scan, /needs\.build\.result == 'success'/);
		// The same script and the same policy file the release and the rescan call. A second
		// copy of the policy is the failure this gate exists to prevent.
		assert.match(
			scan,
			/node scripts\/check-release-vulnerabilities\.ts[\s\S]*security\/vulnerability-policy\.json/,
		);
		assert.match(scan, /needs\.build\.outputs\.manifest-digest/);
		assert.match(scan, /needs\.merge\.outputs\.manifest-digest/);
		assert.match(scan, /IMAGE_REF:.*@\${{/);
		assert.doesNotMatch(scan, /github\.(?:run_id|run_attempt)/);
		assert.match(scan, /linux\/amd64/);
		assert.doesNotMatch(scan, /linux\/arm64/);
		// A gate that cannot say what it rejected is not finished.
		assert.match(scan, /uses: actions\/upload-artifact@/);
		assert.match(scan, /uses: \.\/\.github\/actions\/download-trivy-db/);

		let callSites = 0;
		for (const [file, source] of await workflowSources()) {
			for (const call of source.match(
				/node scripts\/check-release-vulnerabilities\.ts[\s\S]*?\.policy\.json"/g,
			) ?? []) {
				callSites += 1;
				assert.match(
					call,
					/(?<![\w./-])security\/vulnerability-policy\.json/,
					`${file} must evaluate the one release vulnerability policy`,
				);
			}
		}
		// The blocking build gate and the scheduled release rescan; the release path goes through
		// verify-release-evidence.ts and the main rescan through scan-main-images.ts, both of which
		// reach the same evaluator without a workflow-level call site.
		assert.equal(callSites, 2);
	});

	void test("keeps one release vulnerability policy behind every scan", async () => {
		// Every path that evaluates the policy — the build gate, the release, the release rescan and
		// the main rescan — must name this one file. A second copy is the failure the whole effort
		// removes, and it would be invisible: two policies both pass until they disagree.
		const sources = await readSources([
			...(await posixGlob(".github/workflows/*.{yml,yaml}")),
			...(await posixGlob("scripts/*.ts")),
		]);
		for (const [file, source] of sources) {
			if (file.endsWith(".test.ts")) continue;
			for (const reference of source.match(/[\w./-]*vulnerability-polic[\w-]*\.json/g) ?? [])
				assert.ok(
					// The evidence bundle carries a copy so a release can be re-audited against the
					// policy it was cut under; the generator is asserted below to copy, not author, it.
					["security/vulnerability-policy.json", "evidence/vulnerability-policy.json"].includes(
						reference,
					) || reference === "vulnerability-policy.json",
					`${file} must evaluate the one release vulnerability policy, not ${reference}`,
				);
		}
		assert.match(
			await readFile("scripts/generate-release-evidence.ts", "utf8"),
			/copyFile\(\n?\s*"security\/vulnerability-policy\.json",/,
		);
		// One committed policy, so "the same policy" is a fact rather than a convention.
		assert.deepEqual(await posixGlob("security/*vulnerability*.json"), [
			"security/vulnerability-policy.json",
		]);
	});

	void test("holds the committed exceptions to the policy every scan applies", async () => {
		// The gate reads this file only while scanning an image, so a mistyped platform, a malformed
		// digest or a lapsed expiry would surface as a red image scan instead of a failed check.
		// An empty report exercises the validation half of the evaluator on its own.
		const policy: unknown = JSON.parse(
			await readFile("security/vulnerability-policy.json", "utf8"),
		);
		assert.deepEqual(evaluateVulnerabilityPolicy("webapp", { Results: [] }, policy).errors, []);
	});

	void test("scans every release subject before the release, not only at the release gate", async () => {
		const inventory: unknown = JSON.parse(await readFile("security/release-images.json", "utf8"));
		const namespace = "ghcr.io/hephaestus-build";
		const digest = `sha256:${"c".repeat(64)}`;
		// The subject set the pre-release scans cover, derived from the inventory rather than listed:
		// the build gate and the weekly rescan take the first-party half, scan-upstream-images.ts the
		// pinned upstream half.
		const scanned = [
			...planSubjects(inventory, namespace, "main").map((subject) => ({
				...subject,
				indexDigest: digest,
				provenance: "first-party" as const,
			})),
			...planUpstreamSubjects(inventory).map((subject) => ({
				...subject,
				provenance: "upstream" as const,
			})),
		];
		// Parity with the release gate, asserted by the release gate itself: this is the manifest that
		// would evidence exactly the pre-release subject set, and validateManifest rejects a manifest
		// whose subjects are not exactly the inventory. So an image the pre-release scans miss, or one
		// they cover that the release does not, fails here — which is what v0.75.0 needed and did not
		// have when the upstream half was scanned nowhere before the release (#1741).
		const manifest = {
			schemaVersion: 1,
			subjects: scanned.flatMap((subject) =>
				(["linux/amd64", "linux/arm64"] as const).map((platform) => ({
					digest,
					image: subject.image,
					indexDigest: subject.indexDigest,
					platform,
					provenance: subject.provenance,
					repository: subject.repository,
				})),
			),
		};
		assert.doesNotThrow(() => validateManifest(manifest, inventory, namespace));

		// A planner nothing invokes covers nothing. The pinned digests need no build, so they are
		// scanned on the pull request that changes them — which is the pull request a Renovate digest
		// bump opens — and again in the weekly rescan, where a finding routes to the tracking issue.
		assert.match(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
			/run: node scripts\/scan-upstream-images\.ts reports\n/,
		);
		assert.match(
			await readFile(".github/workflows/rescan-main-images.yml", "utf8"),
			/run: node scripts\/scan-upstream-images\.ts reports --report-only\n/,
		);
		const detection = job(await readFile(".github/workflows/cicd.yml", "utf8"), "detect-changes");
		const filter = pathFilter(detection, "release-images");
		assert.match(
			filter,
			/- 'security\/release-images\.json'[\s\S]*- 'security\/vulnerability-policy\.json'/,
		);
		// The trigger is derived, not trusted: a filter that lists the entry point but not the module
		// it parses JSON with skips the gate on the pull request that breaks the parser. Re-walk the
		// imports and require every file the gate actually loads to appear.
		for (const file of await importClosure("scripts/scan-upstream-images.ts"))
			assert.ok(
				filter.includes(`- '${file}'`),
				`release-images must trigger on ${file}, which the upstream scan loads`,
			);
	});

	void test("bounds every captured subprocess above Node's 1 MiB default", async () => {
		// Node caps a captured subprocess at 1 MiB and throws past it. That default has broken the
		// release pipeline three times: a cosign attestation carrying an SBOM, a `gh api` release
		// listing, and a `gh api` workflow-run listing that stopped the Version PR being maintained
		// at all. The ceiling has one home, and a capture that does not use it is the fourth.
		const offenders: string[] = [];
		for (const file of await posixGlob("scripts/**/*.ts")) {
			if (file.endsWith(".test.ts")) continue;
			const source = await readFile(file, "utf8");
			// `stdio: inherit`/`ignore` streams to the parent and buffers nothing; only a capture,
			// which is what `encoding` marks, can overflow.
			const captures =
				source.match(/exec(?:File)?Sync\(|execFileAsync\(|spawnSync\(/g)?.length ?? 0;
			if (captures === 0 || !source.includes("encoding")) continue;
			if (!/maxBuffer/.test(source)) offenders.push(file);
		}
		assert.deepEqual(offenders, [], "these capture a subprocess without bounding its buffer");
	});

	void test("scans both released platforms before the release, not just linux/amd64", async () => {
		// The policy match key is `image | platform | vulnerability | package | installedVersion`, so
		// a single-platform pre-release scan leaves an arm64-only finding — or an arm64 exception
		// nobody wrote — to be discovered by the release gate, which is the failure this PR removes.
		assert.deepEqual([...PLATFORMS], ["linux/amd64", "linux/arm64"]);
		const source = await readFile("scripts/scan-upstream-images.ts", "utf8");
		assert.match(source, /for \(const platform of PLATFORMS\)/);
	});

	void test("performs every release evidence check that does not need a release before the release", async () => {
		// #1741 pinned *subject* parity: the pre-release scans cover the images the release covers.
		// This is *check* parity, which subject parity does not imply — the vulnerability policy was
		// only ever one of the things the release gate evaluates. The bundle it judges is produced by
		// one generator and judged by one verifier, and both run before a release exists, so the only
		// checks a release can be the first to perform are the ones this asserts are release-only.
		const release = await readFile(".github/workflows/release.yml", "utf8");
		const cicd = await readFile(".github/workflows/cicd.yml", "utf8");
		const preflight = job(cicd, "Release-preflight");
		for (const source of [job(release, "tag-images"), preflight]) {
			assert.match(source, /node scripts\/resolve-release-images\.ts /);
			assert.match(source, /node scripts\/generate-release-evidence\.ts evidence \\/);
			assert.match(source, /node scripts\/verify-release-evidence\.ts evidence --write-validation/);
		}
		// The preflight verifies twice, the second time without --write-validation, so the validation
		// documents are re-derived and compared exactly as the release re-derives them.
		assert.match(preflight, /node scripts\/verify-release-evidence\.ts evidence\n/);
		assert.match(preflight, /max-age-hours: "24"/);
		assert.match(preflight, /if: .*needs\.detect-changes\.outputs\.release-preflight == 'true'/);
		assert.match(cicd, /^ {6}release-preflight:$/m);
		assert.match(job(cicd, "all-ci-passed"), /needs: \[[^\]]*Release-preflight\]/);

		// What "everything except signatures" rests on: the verifier's checks are unconditional, and
		// the only thing any mode decides is whether the two signature checks run and whether a
		// validation document is written or compared. A new check gated on anything else is a check a
		// release could be the first to perform, and lands here rather than in a release.
		const verifier = await readFile("scripts/verify-release-evidence.ts", "utf8");
		// Template literals are elided so this test can quote the source lines it expects without
		// carrying interpolations of its own.
		const conditioned = verifier
			.split("\n")
			.map((line) => line.trim().replaceAll(/`[^`]*`/g, "<path>"))
			.filter((line) => line.includes("mode ==="));
		assert.deepEqual(conditioned, [
			'persistOrVerify(<path>, sbom, mode === "write-validation");',
			'persistOrVerify(<path>, policyResult, mode === "write-validation");',
			'if (mode === "verify-signatures" && subject.provenance === "first-party") {',
			'if (mode === "verify-signatures") verifyIndexSignatures(manifest, release);',
		]);
	});

	void test("gives the Version PR the CI its merge triggers a release on", async () => {
		const source = await readFile(".github/workflows/version-pr.yml", "utf8");
		// A GITHUB_TOKEN push starts no workflow run, which is why the Version PR carried no checks
		// and merged through a ruleset bypass. workflow_dispatch is one of the two documented
		// exceptions, so the same token runs the same CI/CD on the same branch — with the release
		// evidence preflight on, because that commit is the one whose merge cuts a release.
		assert.match(source, /run: node scripts\/dispatch-version-pr-ci\.ts/);
		assert.match(source, /^ {6}actions: write/m);
		const dispatcher = await readFile("scripts/dispatch-version-pr-ci.ts", "utf8");
		assert.match(dispatcher, /"release-preflight=true"/);
		assert.match(dispatcher, /export const CI_WORKFLOW = "cicd\.yml";/);
		// A dispatched run carries no pull_request payload, so the gate's status has to fall back to
		// the dispatched ref's head — which is exactly the Version PR's head commit.
		assert.match(
			job(await readFile(".github/workflows/cicd.yml", "utf8"), "all-ci-passed"),
			/const sha = context\.payload\.pull_request\?\.head\?\.sha \|\| context\.sha;/,
		);
	});

	void test("merge groups require release evidence when their aggregate tree changes the version", async (context) => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const candidateStep = namedStep(
			workflow,
			["jobs", "detect-changes"],
			"Detect the release candidate",
		);
		assert.equal(
			candidateStep.getIn(["env", "MERGE_BASE_SHA"]),
			`\${{ github.event.merge_group.base_sha }}`,
		);
		const directory = await mkdtemp(path.join(tmpdir(), "release-candidate-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		const env = environmentForGitFixture();
		const git = (...args: string[]) => {
			const result = spawnSync("git", args, { cwd: directory, env, encoding: "utf8" });
			assert.equal(result.status, 0, result.stderr);
			return result.stdout.trim();
		};
		await mkdir(path.join(directory, ".changeset"));
		await writeFile(path.join(directory, ".changeset/config.json"), '{"baseBranch":"main"}');
		await writeFile(path.join(directory, "package.json"), '{"version":"0.1.0"}');
		git("init", "-q");
		git("add", ".");
		git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "base");
		const base = git("rev-parse", "HEAD");
		const output = path.join(directory, "output");
		const detect = async (sha: string) => {
			await writeFile(output, "");
			const result = spawnSync(
				"bash",
				["--noprofile", "--norc", "-e", "-c", String(candidateStep.get("run"))],
				{
					cwd: directory,
					encoding: "utf8",
					env: {
						...env,
						HEAD_BRANCH: "gh-readonly-queue/main/pr-1-example",
						MERGE_BASE_SHA: sha,
						GITHUB_OUTPUT: output,
					},
				},
			);
			return { status: result.status, output: await readFile(output, "utf8") };
		};
		assert.deepEqual(await detect(base), { status: 0, output: "release-candidate=false\n" });
		await writeFile(path.join(directory, "package.json"), '{"version":"0.2.0"}');
		assert.deepEqual(await detect(base), { status: 0, output: "release-candidate=true\n" });
		assert.notEqual((await detect("0".repeat(40))).status, 0);
		await writeFile(path.join(directory, "package.json"), "{}");
		assert.notEqual((await detect(base)).status, 0);
	});

	void test("fails the CI gate on the Version PR when its release evidence preflight did not", async () => {
		// Every run that can satisfy the gate must require the candidate's own release evidence.
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const detection = ["jobs", "detect-changes"];

		// One home for the branch name: the workflow reads it out of the same `.changeset/config.json`
		// `versionBranch()` reads, so this runs the workflow's own shell and requires the two to agree
		// rather than restating either. A base-branch rename moves both or fails here.
		const detect = runScript(workflow, detection, "Detect the release candidate");
		const branch = versionBranch(JSON.parse(await readFile(".changeset/config.json", "utf8")));
		const detected = async (head: string): Promise<string | undefined> =>
			(await runStep(detect, { HEAD_BRANCH: head, MERGE_BASE_SHA: "" })).outputs[
				"release-candidate"
			];
		assert.equal(await detected(branch), "true");
		for (const other of ["main", `${branch}-old`, "changeset-release/release-1.0", "renovate/vite"])
			assert.equal(await detected(other), "false", `${other} is not the Version PR's branch`);

		// The gate can only demand a preflight it lets run. On that branch the preflight runs for
		// every event, and a duplicate-run skip must not take the image builds it needs away.
		assert.match(
			String(workflow.getIn(["jobs", "Release-preflight", "if"])),
			/needs\.detect-changes\.outputs\.release-preflight == 'true'/,
		);
		assert.match(
			String(workflow.getIn([...detection, "outputs", "should_skip"])),
			/steps\.release_candidate\.outputs\.release-candidate != 'true'/,
		);

		// The verdict itself, run rather than read. Its needs are the jobs it judges, so a new one is
		// covered here the moment it is added.
		const gate = ["jobs", "all-ci-passed"];
		const needed = workflow.getIn([...gate, "needs"]);
		assert.ok(isSeq(needed));
		const green = Object.fromEntries(
			needed.items.map((item) => {
				const name = isScalar(item) ? item.value : item;
				assert.ok(typeof name === "string");
				return [name, "success"];
			}),
		);
		const evaluate = runScript(workflow, gate, "Evaluate CI results");
		// Narrowed to what the verdict is: the step's own diagnosis is for a failure to carry, not
		// something an expected verdict should have to spell out.
		const verdict = async (results: Record<string, string>, onVersionBranch: boolean) => {
			const run = await runStep(
				render(
					evaluate,
					{ ...green, ...results },
					{ "release-candidate": String(onVersionBranch) },
				),
			);
			return { failed: run.failed, outputs: run.outputs };
		};
		const passes = { failed: false, outputs: { status: "success" } };

		for (const result of ["skipped", "failure", "cancelled"])
			assert.equal(
				(await verdict({ CodeQL: result }, false)).failed,
				true,
				`CodeQL ${result} must not release the merge queue ref`,
			);

		// An ordinary pull request legitimately skips the preflight, and blocking one would block
		// every pull request in the repository.
		assert.deepEqual(await verdict({ "Release-preflight": "skipped" }, false), passes);
		// Nothing else changes meaning on that branch: a dispatched run has no `Changesets` job, and
		// a path filter may skip any other leg, exactly as on `main`.
		assert.deepEqual(await verdict({ Changesets: "skipped" }, true), passes);
		// The Version PR, whichever run reports the gate: a preflight that did not succeed fails it.
		for (const preflight of ["skipped", "failure", "cancelled"])
			assert.equal(
				(await verdict({ "Release-preflight": preflight }, true)).failed,
				true,
				`preflight ${preflight} must fail the gate`,
			);

		// A demand the run cannot satisfy is not a gate, it is a wall. The preflight evidences every
		// image on both published platforms — the vulnerability policy's match key includes the
		// platform, so an arm64-only finding must not reach a release undiscovered (#1743) — and it
		// can only do that for manifests that exist. So wherever the gate demands a preflight, the
		// same run's image builds have to publish both. A pull request on that branch published
		// `linux/amd64` alone and could not, which is what blocked v0.75.1.
		const architectures = String(workflow.getIn([...detection, "outputs", "single-arch"]));
		const shape =
			/^\$\{\{ \(github\.event_name == '(\w+)' \|\| github\.event_name == '(\w+)'\) && steps\.release_candidate\.outputs\.release-candidate != 'true' }}$/.exec(
				architectures,
			);
		assert.ok(shape, `this test cannot evaluate \`${architectures}\``);
		const preMerge = shape.slice(1);
		const bothPlatforms = (event: string, onVersionBranch: boolean): boolean =>
			!preMerge.includes(event) || onVersionBranch;
		const events = ["pull_request", "merge_group", "workflow_dispatch", "push"];
		for (const onVersionBranch of [true, false]) {
			const demanded = (await verdict({ "Release-preflight": "skipped" }, onVersionBranch)).failed;
			for (const event of events)
				assert.ok(
					!demanded || bothPlatforms(event, onVersionBranch),
					`a ${event} run that the gate demands a preflight from must publish both platforms`,
				);
		}
		// And the cost stays where it was: everything else pre-merge is still one architecture, since
		// a candidate image is only ever run on amd64 before it merges.
		assert.deepEqual(
			events.map((event) => bothPlatforms(event, false)),
			[false, false, true, true],
		);

		// Nor can the gate demand evidence for images the run never published. Off that branch a pull
		// request builds what its diff touched and aliases the rest from `main`, which is right for a
		// preview and cannot serve the preflight: the alias falls back to the candidate the merged
		// pull request built — `linux/amd64` and an `unknown/unknown` attestation manifest, no arm64 —
		// and it falls back every time, because `main`'s push run was started by the same push that
		// wrote this head and has published nothing yet when the alias runs. So the version branch
		// publishes every release image itself, under every event, exactly as its dispatch run did.
		const complete = String(workflow.getIn([...detection, "outputs", "all-images"]));
		assert.match(
			complete,
			/^\$\{\{ steps\.alias_base\.outputs\.commit == '' \|\| steps\.release_candidate\.outputs\.release-candidate == 'true' }}$/,
		);
		// A run builds every image when it resolved no commit to alias from, so which events can
		// resolve one is read from the resolver's own condition rather than restated here.
		const steps = workflow.getIn([...detection, "steps"]);
		assert.ok(isSeq(steps));
		const resolver = steps.items.find(
			(candidate) => isMap(candidate) && candidate.get("id") === "alias_base",
		);
		assert.ok(isMap(resolver));
		// The resolver calls the API, and a merge group's failed run drops its pull requests from the
		// queue without failing a check on them. An unresolved base is already the safe answer.
		assert.equal(resolver.get("continue-on-error"), true);
		const resolves = String(resolver.get("if"));
		const aliasing = [...resolves.matchAll(/github\.event_name == '(\w+)'/g)].map((match) =>
			String(match[1]),
		);
		assert.equal(aliasing.length, 2, `this test cannot evaluate \`${resolves}\``);
		const buildsEveryImage = (
			event: string,
			onVersionBranch: boolean,
			nothingToAlias = false,
		): boolean => !aliasing.includes(event) || nothingToAlias || onVersionBranch;
		for (const event of events)
			assert.ok(
				buildsEveryImage(event, true),
				`a ${event} run on the Version PR's branch must publish every release image itself`,
			);
		assert.deepEqual(
			Object.fromEntries(events.map((event) => [event, buildsEveryImage(event, false)])),
			{ pull_request: false, merge_group: false, workflow_dispatch: true, push: true },
		);
		// The other run with nothing to alias: a pull request whose chain of bases reaches no
		// published commit builds the whole set rather than failing to alias one.
		assert.ok(buildsEveryImage("pull_request", false, true));

		// One home for that decision too: an input that selects an image reads it, and no input
		// re-tests the event on its own. The package and server image selectors read it directly.
		for (const name of ["server-package", "application-server-image"]) {
			const condition = String(workflow.getIn(["jobs", name, "if"]));
			assert.match(condition, /needs\.detect-changes\.outputs\.all-images == 'true'/);
			assert.doesNotMatch(condition, /github\.event_name/);
		}
		const imageInputs = {
			Docker: [
				"webapp_changed",
				"application_server_changed",
				"agent_images_changed",
				"postgres_image_changed",
			],
		};
		for (const [caller, inputs] of Object.entries(imageInputs))
			for (const name of inputs) {
				const value = String(workflow.getIn(["jobs", caller, "with", name]));
				assert.match(
					value,
					/needs\.detect-changes\.outputs\.all-images == 'true'/,
					`${caller}.${name} must read the one decision`,
				);
				assert.doesNotMatch(
					value,
					/github\.event_name/,
					`${caller}.${name} must not test the event itself`,
				);
			}
		// And the alias cannot run behind their backs: it is reachable only while some image is
		// unchanged, so a run that builds the whole set evidences nothing another run published.
		const images = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));
		const alias = String(images.getIn(["jobs", "tag-unchanged-images", "if"]));
		for (const name of imageInputs.Docker)
			assert.match(alias, new RegExp(`inputs\\.${name} != 'true'`));
		for (const image of ["webapp-build", "agent-pi-build", "postgres-build"])
			assert.doesNotMatch(
				String(images.getIn(["jobs", image, "if"])),
				/github\.event_name/,
				`${image} must not re-test the event its caller already decided on`,
			);
	});

	void test("rescans main's images weekly and reports drift to an issue, not a status", async () => {
		const source = await readFile(".github/workflows/rescan-main-images.yml", "utf8");
		// Weekly, matching the supported-release rescan: the remedy for drift is a rebuild of main,
		// which the next merge performs anyway, so a nightly run would report the same finding six
		// more times before anything could have changed.
		assert.match(source, /^ {4}- cron: "\d+ \d+ \* \* 1"$/m);
		assert.match(source, /workflow_dispatch:/);
		assert.match(source, /group: rescan-main-images/);
		// Writing an issue is the whole point; nothing else here needs a write.
		assert.match(source, /issues: write/);
		assert.doesNotMatch(source, /contents: write|packages: write|id-token: write/);
		// The scan and the reporting are tested TypeScript, not inline bash: the upsert in
		// particular has to not open a duplicate every week, which no eyeball review establishes.
		assert.match(source, /run: node scripts\/scan-main-images\.ts reports/);
		assert.match(source, /run: node scripts\/report-vulnerability-drift\.ts reports/);
		assert.match(source, /uses: \.\/\.github\/actions\/download-trivy-db/);
		assert.match(source, /uses: actions\/upload-artifact@/);
		// Nothing may turn a finding into a red status: no `continue-on-error` fig leaf, and no
		// second evaluator inline.
		assert.doesNotMatch(source, /continue-on-error/);
		assert.doesNotMatch(source, /trivy image/);
	});

	void test("fetches the Trivy database through one action", async () => {
		const action = await readFile(".github/actions/download-trivy-db/action.yml", "utf8");
		// GHCR answers TOOMANYREQUESTS often enough that a gate needs the mirror; stating it once
		// is why this action exists at all.
		assert.match(action, /public\.ecr\.aws\/aquasecurity\/trivy-db/);
		assert.match(action, /--download-db-only/);
		assert.match(action, /--download-java-db-only/);
		for (const file of [".github/workflows/cicd.yml", ".github/workflows/release.yml"]) {
			const workflow = parseDocument(await readFile(file, "utf8"));
			const evidenceJob = file.endsWith("cicd.yml") ? "Release-preflight" : "tag-images";
			const steps = workflow.getIn(["jobs", evidenceJob, "steps"]);
			assert.ok(isSeq(steps));
			const prepare = steps.items.find(
				(entry) => isMap(entry) && entry.get("uses") === "./.github/actions/download-trivy-db",
			);
			assert.ok(isMap(prepare));
			assert.equal(prepare.getIn(["with", "java-db"]), "true");
		}

		for (const [file, source] of await workflowSources())
			assert.doesNotMatch(
				source,
				/--download-db-only/,
				`${file} must download the Trivy database through .github/actions/download-trivy-db`,
			);
		// Everything that signs or publishes a scan result asserts the database is not stale.
		for (const file of [
			".github/workflows/release.yml",
			".github/workflows/rescan-release-images.yml",
			".github/workflows/rescan-main-images.yml",
		]) {
			const source = await readFile(file, "utf8");
			assert.match(
				source,
				/uses: \.\/\.github\/actions\/download-trivy-db\n\s+with:\n\s+max-age-hours: "24"/,
				`${file} must refuse a stale Trivy database`,
			);
		}
	});

	void test("generates verified Gradle snapshots without giving pull request code write access", async () => {
		const source = await readFile(".github/workflows/dependency-graph.yml", "utf8");
		const workflow = parseDocument(source);
		assert.equal(workflow.get("name"), "Gradle dependency graph");
		assert.equal(workflow.getIn(["jobs", "generate", "permissions", "contents"]), "read");
		assert.doesNotMatch(
			source,
			/: write|verification=off|write-verification-metadata|dependency-submission@/,
		);
		const setup = stepInputs(namedStep(workflow, ["jobs", "generate"], "Set up the Java build"));
		assert.equal(setup.get("dependency-graph"), "generate-and-upload");
		const resolve = namedStep(workflow, ["jobs", "generate"], "Resolve the dependency graph");
		assert.equal(resolve.get("working-directory"), "server");
		assert.equal(resolve.get("run"), "./gradlew dependencies --no-configuration-cache");
		assert.equal(
			workflow.getIn(["jobs", "generate", "env", "GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR"]),
			"server",
		);
		assert.equal(
			namedStep(workflow, ["jobs", "generate"], "Require the snapshot").get("run"),
			"test -s dependency-graph-reports/server.json",
		);
		const action = parseDocument(await readFile(".github/actions/setup-caches/action.yml", "utf8"));
		assert.equal(action.getIn(["inputs", "dependency-graph", "default"]), "disabled");
	});

	void test("submits dependency snapshot data without executing the originating checkout", async () => {
		const source = await readFile(".github/workflows/submit-dependency-graph.yml", "utf8");
		const workflow = parseDocument(source);
		assert.equal(workflow.getIn(["on", "workflow_run", "workflows", 0]), "Gradle dependency graph");
		assert.equal(workflow.getIn(["on", "workflow_run", "types", 0]), "completed");
		assert.equal(
			workflow.getIn(["jobs", "submit", "if"]),
			"github.event.workflow_run.conclusion == 'success'",
		);
		assert.equal(workflow.getIn(["jobs", "submit", "permissions", "contents"]), "write");
		assert.equal(workflow.getIn(["jobs", "submit", "permissions", "actions"]), "read");
		const submission = step(workflow, ["jobs", "submit"], "gradle/actions/dependency-submission");
		assert.equal(submission.get("dependency-graph"), "download-and-submit");
		assert.equal(submission.get("cache-disabled"), true);
		assert.doesNotMatch(
			source,
			/uses: actions\/checkout|uses: \.\/|run:.*(?:gradlew|node|bash|curl)/,
		);
		assert.equal(
			namedStep(workflow, ["jobs", "submit"], "Require the submitted snapshot").get("run"),
			"test -s dependency-graph-reports/server.json",
		);
	});

	void test("blocks dependency regressions across the release trust boundary", async () => {
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		const securityConfig = pathFilter(orchestrator, "security-config");
		assert.match(securityConfig, /- '\.github\/dependency-review-config\.yml'/);
		assert.match(
			securityConfig,
			/- 'security\/trivy-dependency-ignore\.yaml'/,
			"a pull request that suppresses a finding has to select the job that would have reported it",
		);
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
		);
		const reviewPath = ["jobs", "dependency-review"];
		assert.ok(
			String(workflow.getIn([...reviewPath, "if"])).includes("github.event_name == 'pull_request'"),
		);
		const review = step(workflow, reviewPath, "actions/dependency-review-action");
		assert.equal(review.get("config-file"), "./.github/dependency-review-config.yml");
		assert.equal(review.get("retry-on-snapshot-warnings"), true);
		assert.equal(review.get("retry-on-snapshot-warnings-timeout"), 600);

		const config = parseDocument(await readFile(".github/dependency-review-config.yml", "utf8"));
		assert.equal(config.get("warn-only"), true);
		assert.ok(
			!config.has("fail-on-severity"),
			"warn-only reports every severity, so a threshold here names a policy the action does not apply",
		);
		const scopes = config.get("fail-on-scopes");
		assert.ok(isSeq(scopes));
		assert.deepEqual(
			new Set(scopes.items.map((scope) => (isScalar(scope) ? scope.value : undefined))),
			new Set(["runtime", "development", "unknown"]),
		);

		const scanPath = ["jobs", "security-scan"];
		assert.equal(workflow.getIn([...scanPath, "env", "TRIVY_INCLUDE_DEV_DEPS"]), "true");
		const secretScan = stepInputs(namedStep(workflow, scanPath, "Secret detection"));
		assert.equal(secretScan.get("image"), "ghcr.io/trufflesecurity/trufflehog");
		assert.match(String(secretScan.get("version")), /^\d+\.\d+\.\d+@sha256:[a-f0-9]{64}$/);
		assert.equal(
			secretScan.get("base"),
			`\${{ github.event.pull_request.base.sha || github.event.merge_group.base_sha || github.event.before || '' }}`,
		);
		assert.equal(
			secretScan.get("head"),
			`\${{ github.event.pull_request.head.sha || github.event.merge_group.head_sha || github.sha }}`,
		);
		assert.ok(String(secretScan.get("extra_args")).split(" ").includes("--fail-on-scan-errors"));
		assert.ok(String(secretScan.get("extra_args")).split(" ").includes("--only-verified"));
		const report = stepInputs(namedStep(workflow, scanPath, "Trivy dependency scan"));
		assert.equal(report.get("format"), "sarif");
		assert.ok(
			!report.has("severity"),
			"SARIF output reports every severity unless limit-severities-for-sarif is true, so a filter here states a policy Trivy does not apply",
		);
		assert.ok(
			!report.has("exit-code"),
			"the SARIF pass is the code-scanning feed; the pass below is the verdict",
		);
		assert.ok(
			!report.has("scanners"),
			"trivy fs defaults to vuln,secret, and narrowing this pass drops tree-wide secret findings from code scanning",
		);

		const gateStep = namedStep(workflow, scanPath, "Enforce dependency vulnerability policy");
		const gate = stepInputs(gateStep);
		assert.equal(gate.get("format"), "table");
		assert.equal(gate.get("scanners"), "vuln");
		assert.equal(gate.get("severity"), "HIGH,CRITICAL");
		assert.equal(gate.get("ignore-unfixed"), true);
		assert.equal(gate.get("exit-code"), "1");
		assert.equal(gate.get("trivyignores"), "security/trivy-dependency-ignore.yaml");
		assert.equal(gate.get("skip-setup-trivy"), true);

		// The step is continue-on-error so the table reaches the log; the aggregator is what turns
		// its outcome into the job's verdict.
		assert.equal(gateStep.get("id"), "dependency-policy");
		const aggregator = namedStep(workflow, scanPath, "Evaluate security checks");
		const env = aggregator.get("env");
		assert.ok(isMap(env));
		assert.match(
			String(env.get("DEPENDENCY_POLICY")),
			/^\${{ steps\.dependency-policy\.outcome }}$/,
		);
		assert.match(String(aggregator.get("run")), /for result in [^\n]*"\$DEPENDENCY_POLICY"/);

		const ignore: unknown = parseDocument(
			await readFile("security/trivy-dependency-ignore.yaml", "utf8"),
		).toJS();
		assert.ok(isRecord(ignore) && Array.isArray(ignore.vulnerabilities));
		const now = Date.now();
		for (const entry of ignore.vulnerabilities) {
			const fault = exceptionFault(entry, now);
			assert.equal(fault, undefined, `security/trivy-dependency-ignore.yaml entry ${fault}`);
		}
	});

	void test("rejects a dependency exception that names no subject or outlives the ceiling", () => {
		const now = Date.parse("2026-01-01T00:00:00Z");
		const exception = {
			id: "CVE-2026-0001",
			purls: ["pkg:npm/example@1.3.0"],
			statement: "Upstream has no release carrying the fix; the risk issue tracks the upgrade.",
			expired_at: "2026-03-01",
		};
		assert.equal(exceptionFault(exception, now), undefined);
		for (const [fault, malformed] of [
			["is not a mapping", "CVE-2026-0001"],
			["names no vulnerability id", { ...exception, id: "" }],
			["names no package URLs", { ...exception, purls: [] }],
			["names no package URLs", { ...exception, purls: ["example@1.3.0"] }],
			["carries no statement", { ...exception, statement: "  " }],
			["has no YYYY-MM-DD expired_at", { ...exception, expired_at: "March 2026" }],
			["expires more than 90 days out", { ...exception, expired_at: "2026-06-01" }],
		] as const) {
			assert.equal(exceptionFault(malformed, now), fault);
		}
	});

	void test("publishes repository posture outside the pull-request path", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/scorecard.yml", "utf8"));
		assert.equal(workflow.hasIn(["on", "pull_request"]), false);
		for (const event of ["branch_protection_rule", "schedule", "push"])
			assert.equal(workflow.hasIn(["on", event]), true);
		assert.equal(workflow.getIn(["jobs", "analysis", "permissions", "id-token"]), "write");
		assert.equal(workflow.getIn(["jobs", "analysis", "permissions", "security-events"]), "write");
		const jobPath = ["jobs", "analysis"];
		assert.equal(step(workflow, jobPath, "actions/checkout").get("persist-credentials"), false);
		assert.equal(step(workflow, jobPath, "ossf/scorecard-action").get("publish_results"), true);
	});

	void test("pins every external action to a full commit SHA with a version comment", async () => {
		const invalid: string[] = [];
		for (const [file, source] of await readSources(
			await posixGlob(".github/{actions,workflows}/**/*.{yml,yaml}"),
		)) {
			for (const [index, line] of source.split("\n").entries()) {
				const uses = line.match(/^\s+(?:- )?uses:\s+(\S+)(.*)$/);
				if (!uses) continue;
				const reference = uses[1];
				const comment = uses[2];
				assert.ok(reference && comment !== undefined);
				if (reference.startsWith("./")) continue;
				if (!/@[0-9a-f]{40}$/.test(reference) || !/^ # v\S+(?:\s.*)?$/.test(comment)) {
					invalid.push(`${file}:${index + 1}: ${reference}`);
				}
			}
		}
		assert.deepEqual(invalid, [], "External uses must match @<40 lowercase hex> # v...");
	});

	void test("centralises dependency installation and browser setup", async () => {
		const sources = await workflowSources();
		for (const [file, source] of sources) {
			assert.doesNotMatch(
				source,
				/pnpm install --frozen-lockfile/,
				`${file} must install through setup-toolchain`,
			);
			visit(parseDocument(source), {
				Map(_key, node) {
					if (node.get("uses") !== "./.github/actions/setup-toolchain") return;
					const options = node.get("with");
					assert.ok(isMap(options), `${file} must configure setup-toolchain`);
					const mode = options.get("install");
					assert.ok(
						mode === "none" || mode === "frozen",
						`${file} must select an explicit setup-toolchain install mode`,
					);
				},
			});
		}
		for (const file of [
			".github/workflows/ci-build.yml",
			".github/workflows/ci-quality-gates.yml",
		]) {
			const source = sources.get(file);
			assert.ok(source);
			assert.equal((source.match(/uses: \.\/\.github\/actions\/setup-browsers/g) ?? []).length, 1);
			assert.doesNotMatch(source, /playwright install chromium/);
		}
	});

	void test("runs release evidence verification through tested TypeScript", async () => {
		const release = await readFile(".github/workflows/release.yml", "utf8");
		const rescan = await readFile(".github/workflows/rescan-release-images.yml", "utf8");
		assert.match(release, /SOURCE_TAG: \${{ github\.event\.workflow_run\.head_sha }}/);
		assert.match(release, /node scripts\/resolve-release-images\.ts "\$SOURCE_TAG"/);
		assert.doesNotMatch(job(release, "tag-images"), /imagetools/);
		assert.match(rescan, /node scripts\/verify-release-evidence\.ts release-evidence/);
		assert.doesNotMatch(rescan, /node scripts\/check-release-sbom\.ts/);
		assert.equal((release.match(/node scripts\/verify-release-evidence\.ts/g) ?? []).length, 3);
		assert.doesNotMatch(release, /node scripts\/check-release-(?:sbom|vulnerabilities)\.ts/);
	});

	void test("creates the draft release only once the evidence gate has passed", async () => {
		const source = await readFile(".github/workflows/release.yml", "utf8");
		const decide = job(source, "release");
		const gate = job(source, "tag-images");
		// Release images are promoted by digest and never rebuilt, so a draft cut before the gate
		// can never pass at that commit.
		assert.doesNotMatch(decide, /gh release create/);
		const created = gate.indexOf('gh release create "$TAG_NAME"');
		const gated = gate.lastIndexOf("node scripts/verify-release-evidence.ts");
		const uploaded = gate.indexOf('gh release upload "$TAG_NAME"');
		assert.ok(gated >= 0, "tag-images must run the evidence verifier");
		assert.ok(created > gated, "the draft must be created after the evidence gate");
		assert.ok(uploaded > created, "release assets need a draft to upload to");
		const publication = job(source, "publish-release");
		const published = publication.indexOf('gh release edit "$TAG_NAME"');
		const immutable = publication.indexOf("--json isImmutable");
		const promoted = publication.indexOf("docker buildx imagetools create");
		assert.ok(published >= 0 && immutable > published, "publication must verify immutable state");
		assert.ok(promoted > immutable, "image aliases must only move after immutable publication");

		// A re-run resumes the draft, so uploads must replace assets instead of failing on names.
		for (const upload of source.matchAll(/gh release upload[\s\S]*?\n\n/g)) {
			assert.match(upload[0], /--clobber/);
		}
	});

	void test("decides what to release in tested TypeScript, on outputs the workflow reads", async () => {
		const source = await readFile(".github/workflows/release.yml", "utf8");
		const decide = job(source, "release");
		// Four branches over the release listing, one of them "cut a release on this push"; inline
		// bash cannot be tested, and the ordinary feature merge is the case that must not regress.
		assert.match(decide, /node scripts\/plan-release\.ts "\$SHA"/);
		assert.doesNotMatch(decide, /PARENT_VERSION|gh release view/);
		// Every output the workflow reads is one the planner writes, and nothing it writes is dead.
		const plan = planRelease("cafe", "0.75.0", [
			{ isDraft: false, isPrerelease: false, tag: "v0.74.0", targetCommitish: "main" },
		]);
		const read = [...source.matchAll(/steps\.cut\.outputs\.([\w-]+)/g)].map((match) => {
			const name = match[1];
			assert.ok(name);
			return name;
		});
		assert.deepEqual(
			[...new Set(read)].toSorted(),
			Object.keys(releaseOutputs(plan, true)).toSorted(),
		);
	});

	void test("exempts a verified revert from the changeset freeze rules", async () => {
		const source = await readFile(".github/workflows/verify-changesets.yml", "utf8");
		const detect = source.indexOf("- name: Detect a verified revert");
		const guard = source.indexOf("- name: Check release-note presence");
		assert.ok(detect >= 0 && guard > detect, "the revert check must precede the freeze guard");
		assert.match(source, /run: node scripts\/verify-revert\.ts "\$BASE_SHA" HEAD/);
		assert.match(source, /steps\.revert\.outputs\.verified-revert != 'true'/);
		// The exemption is structural: a title or branch name is attacker-chosen and never read.
		assert.doesNotMatch(source, /pull_request\.title|github\.head_ref/);
	});

	void test("creates every automation commit through the API, so GitHub signs it", async () => {
		// Actions holds no signing key, so a commit a workflow pushes with git is unsigned and a
		// required-signature rule rejects it. `scripts/commit-via-api.ts` commits through
		// `createCommitOnBranch`, which GitHub signs. A dry run is the one push that reaches no ref:
		// the pre-push hook probe needs a push to prove the hook runs, and writes nothing.
		const pushes: string[] = [];
		for (const [file, source] of await workflowSources()) {
			for (const [index, line] of source.split("\n").entries()) {
				const statement = line.trim();
				if (statement.startsWith("#")) continue;
				const command = /\bgit push\b.*/.exec(statement)?.[0];
				if (!command || /\s--dry-run(?:\s|$)/.test(command)) continue;
				pushes.push(`${file}:${index + 1}: ${command}`);
			}
		}
		assert.deepEqual(pushes, [], "A workflow commits through scripts/commit-via-api.ts");
	});

	void test("checks out no ref taken straight from a pull_request_target or workflow_run event", async () => {
		// Scorecard's Dangerous-Workflow rule, checked here because Scorecard itself runs only after merge.
		for (const [file, source] of await workflowSources()) {
			if (!/^ {2}(?:pull_request_target|workflow_run):/m.test(source)) continue;
			assert.doesNotMatch(
				source,
				/^ +ref:.*github\.event\.(?:pull_request|workflow_run)/m,
				`${file} checks out a ref taken from the triggering event`,
			);
		}
		// The release jobs check out the run's commit through an output; this is what makes it safe.
		assert.match(
			job(await readFile(".github/workflows/release.yml", "utf8"), "release"),
			/git merge-base --is-ancestor "\$SHA" origin\/main/,
		);
	});

	void test("resolves every commit's author without checking out the pull request", async () => {
		const identity = job(
			await readFile(".github/workflows/pull-request.yml", "utf8"),
			"validate-pr",
		);
		// The workflow's trigger is justified to Zizmor by the claim that it checks out and runs no
		// pull-request code; a job that reads the pull request's own commits is where that slips.
		assert.match(identity, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
		assert.match(identity, /uses: actions\/github-script@/);
	});

	void test("never invokes a repository-local action before checkout", async () => {
		for (const [file, source] of await workflowSources()) {
			for (const jobSource of source.split(/^ {2}(?=[A-Za-z][\w-]*:\s*$)/m).slice(1)) {
				const firstLocalAction = jobSource.indexOf("uses: ./.github/actions/");
				if (firstLocalAction < 0) continue;
				const checkout = jobSource.indexOf("uses: actions/checkout@");
				assert.ok(
					checkout >= 0 && checkout < firstLocalAction,
					`${file} invokes a local action before checking it out`,
				);
			}
		}
	});

	void test("bounds every job that runs steps of its own", async () => {
		// A job that declares no `timeout-minutes` inherits GitHub's six-hour default, so one hung
		// step holds a runner for a working day and reports nothing until it is killed. A job that
		// only `uses:` a reusable workflow runs no step here: its bound is on the callee's jobs.
		const unbounded: string[] = [];
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			if (!isMap(jobs)) continue;
			for (const entry of jobs.items) {
				const definition = entry.value;
				if (!isMap(definition) || !isSeq(definition.get("steps"))) continue;
				if (definition.get("timeout-minutes") === undefined)
					unbounded.push(`${file}#${String(entry.key)}`);
			}
		}
		assert.deepEqual(unbounded, [], "these jobs run steps without bounding how long they may run");
	});
});

void test("the task graph keeps its cache posture", async () => {
	const tasks = await loadTasks();
	for (const entry of ["check", "verify", "quality", "verification"])
		assert.ok(entry in tasks, `${entry} must be a task`);
	for (const [name, task] of Object.entries(tasks)) {
		if (!isRecord(task)) continue;
		const commands = commandsOf(task);
		// The runner never caches a command that delegates (scripts/check-runner-contract.ts).
		// vite.config.ts rejects the literal form, so only a command widened to string reaches here.
		if (task.cache !== false)
			for (const command of commands) {
				assert.doesNotMatch(command, /\bvp run\b/, `${name} must own its command to cache`);
				assert.doesNotMatch(command, /\bvp exec\b/, `${name} cannot cache through vp exec`);
			}
		// Shell parameter expansion is outside the runner contract (scripts/check-runner-contract.ts).
		for (const command of commands)
			assert.doesNotMatch(command, /\$/, `${name} must not rely on shell expansion`);
		// A `check:` name is a read-only entry point a contributor types: it either collects gates,
		// which carry the verdicts and the caching, or it runs uncached.
		if (name === "check" || name.startsWith("check:")) {
			const isGroup =
				commands.length === 0 && Array.isArray(task.dependsOn) && task.dependsOn.length > 0;
			assert.ok(isGroup || task.cache === false, `${name} must be a group or uncached`);
		}
	}
	// One native build owns Java quality, inputs and cached outputs; no outer verdict can hide it.
	const serverCommands = commandsOf(tasks["gate:server"]);
	assert.equal(serverCommands.length, 1);
	assert.match(
		serverCommands[0] ?? "",
		/run-gradlew\.ts.*:spotlessCheck.*:application:spotlessCheck.*:application:pmdMain/,
	);
	assert.equal(tasks["prepare:server:generated"], undefined, "Gradle owns generation dependencies");
	for (const entry of ["gate:server", "format:java:check", "lint:java"]) {
		assert.equal(
			asRecord(tasks[entry], entry).cache,
			false,
			`${entry} delegates caching to Gradle`,
		);
	}
	assert.ok(Array.isArray(asRecord(tasks["gate:docs-lint"], "gate:docs-lint").input));

	for (const [file, source] of await workflowSources()) {
		assert.doesNotMatch(
			source,
			/^\s*(?:run:\s*)?(?:vp exec )?(?:vite|vitest|oxlint|oxfmt)(?:\s|$)/m,
			`${file} bypasses the pinned Vite+ interface`,
		);
	}
});

// Vite+ loads the task graph from every workspace config, and those configs import dependencies, so a
// job that calls `vp` has installed them; `none` is for jobs that run `node scripts/…` or shell alone.
void test("installs dependencies in every job that calls vp", async () => {
	const offenders: string[] = [];
	for (const [file, source] of await workflowSources()) {
		const jobs = parseDocument(source).get("jobs");
		if (!isMap(jobs)) continue;
		for (const pair of jobs.items) {
			const jobName = String(pair.key);
			const jobDefinition = pair.value;
			if (!isMap(jobDefinition)) continue;
			const steps = jobDefinition.get("steps");
			if (!isSeq(steps)) continue;
			let install: string | undefined;
			let callsVp = false;
			let installsItself = false;
			for (const item of steps.items) {
				if (!isMap(item)) continue;
				const uses = item.get("uses");
				if (uses === "./.github/actions/setup-toolchain") {
					const inputs = item.get("with");
					install = isMap(inputs) ? String(inputs.get("install")) : undefined;
				}
				const run = item.get("run");
				if (typeof run === "string" && /\bvp install\b/.test(run)) installsItself = true;
				// An invocation starts a command; `vp run …` quoted in a message for the summary does not.
				if (
					typeof run === "string" &&
					/(?:^|[|&;]\s*|timeout \S+ \S+ )(?:\S+=\S+ )*vp (?:run|exec|-C)\b/m.test(
						run.replaceAll(/\\?`[^`]*\\?`/g, ""),
					)
				)
					callsVp = true;
			}
			if (callsVp && install !== "frozen" && !installsItself)
				offenders.push(`${file}#${jobName} (install: ${install ?? "no setup-toolchain step"})`);
		}
	}
	assert.deepEqual(offenders, [], "A job that calls vp must install dependencies first");
});

void test("the Scorecard ratchet runs on every event that can move a score, outside publishing", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/scorecard-ratchet.yml", "utf8"));
	for (const trigger of ["push", "branch_protection_rule", "schedule", "workflow_dispatch"])
		assert.ok(workflow.hasIn(["on", trigger]), `the ratchet must run on ${trigger}`);
	assert.equal(workflow.getIn(["jobs", "ratchet", "permissions", "contents"]), "read");
	assert.equal(workflow.hasIn(["jobs", "ratchet", "permissions", "id-token"]), false);
	assert.equal(
		step(workflow, ["jobs", "ratchet"], "actions/upload-artifact").get("path"),
		"tmp/scorecard-assessment.json",
	);
	assert.match(
		pathFilter(await readFile(".github/workflows/cicd.yml", "utf8"), "tooling"),
		/security\/scorecard-baseline\.json/,
	);

	// The publishing workflow may only run allowlisted actions, which is why the ratchet is not a job in it.
	const publishing = parseDocument(await readFile(".github/workflows/scorecard.yml", "utf8"));
	const steps = publishing.getIn(["jobs", "analysis", "steps"]);
	assert.ok(isSeq(steps));
	for (const item of steps.items) {
		assert.ok(isMap(item));
		assert.equal(item.get("run"), undefined);
		assert.match(
			String(item.get("uses")),
			/^(actions\/(checkout|upload-artifact)|github\/codeql-action\/upload-sarif|ossf\/scorecard-action)@/,
		);
	}
});

/**
 * A workflow whose newest run on `main` simply replaces the one before it, so cancelling the older
 * run loses nothing a later job reads.
 */
const REPLACEABLE_MAIN_RUNS: Record<string, string> = {
	".github/workflows/cd-docs.yml": "The site the last deploy published is the site.",
};

// Every other run on `main` is the only one its commit will ever get, and something downstream reads
// the record it leaves: a cancelled CodeQL analysis is a commit Scorecard counts as unscanned, and a
// cancelled ratchet is a merge nobody checked. Two merges a minute apart are enough to lose one.
void test("a workflow triggered by main does not cancel the run main is judged by", async () => {
	const sources = await workflowSources();
	const offenders: string[] = [];
	for (const [file, source] of sources) {
		const branches = parseDocument(source).getIn(["on", "push", "branches"]);
		if (!isSeq(branches) || !branches.items.some((item) => isScalar(item) && item.value === "main"))
			continue;
		if (
			parseDocument(source).getIn(["concurrency", "cancel-in-progress"]) === true &&
			!Object.hasOwn(REPLACEABLE_MAIN_RUNS, file)
		)
			offenders.push(file);
	}
	assert.deepEqual(offenders, [], "these cancel a run on main that nothing else will repeat");
	for (const [file, reason] of Object.entries(REPLACEABLE_MAIN_RUNS)) {
		assert.ok(reason.trim(), `${file} must say why its run is replaceable`);
		assert.equal(
			parseDocument(sources.get(file) ?? "").getIn(["concurrency", "cancel-in-progress"]),
			true,
			`${file} no longer cancels, so it no longer needs an exception`,
		);
	}
});

void test("proves the toolchain on Windows and from a clean clone", async () => {
	const source = await readFile(".github/workflows/ci-quality-gates.yml", "utf8");
	const legSource = await readFile(".github/workflows/ci-quality-leg.yml", "utf8");
	const workflow = parseDocument(legSource);
	const qualityPath = ["jobs", "quality"];
	const quality = job(legSource, "quality");
	assert.match(quality, /runs-on: .*inputs\.leg == 'windows'.*'windows-latest'.*'ubuntu-latest'/);
	assert.match(quality, /shell: bash/);
	assert.match(quality, /windows\) vp run --no-cache ci:windows/);
	for (const name of ["Restore Vite task cache", "Save Vite task cache"])
		assert.match(
			String(namedStep(workflow, qualityPath, name).get("if")),
			/inputs\.leg != 'windows'/,
		);
	const install = job(source, "clean-install");
	assert.doesNotMatch(install, /setup-toolchain|pnpm\/setup/);
	assert.match(install, /vp install --frozen-lockfile/);
	assert.match(install, /vp run gate:toolchain/);
	const hookCondition = "(inputs.leg == 'tooling' || inputs.leg == 'windows') && !cancelled()";
	for (const name of ["Commit-msg hook", "Pre-push hook"])
		assert.equal(namedStep(workflow, qualityPath, name).get("if"), hookCondition);
	const commitHook = runScript(workflow, qualityPath, "Commit-msg hook");
	assert.match(commitHook, /node scripts\/enable-hooks\.ts/);
	assert.match(commitHook, /git config core\.hooksPath/);
	assert.equal((commitHook.match(/git commit --allow-empty/g) ?? []).length, 2);
	const prePushHook = runScript(workflow, qualityPath, "Pre-push hook");
	assert.match(prePushHook, /node scripts\/enable-hooks\.ts/);
	assert.match(prePushHook, /git push --dry-run\b/);
	// The step moves the launcher aside, so it must put it back however it exits.
	assert.match(prePushHook, /trap .*EXIT/);
	assert.doesNotMatch(prePushHook, /^\s*vp run check\s*$/m);
});

void test("pnpm reuses only trusted native verification verdicts and still runs a frozen install", async () => {
	const action = parseDocument(
		await readFile(".github/actions/setup-toolchain/action.yml", "utf8"),
	);
	const actionPath = ["runs"];
	const locate = namedStep(action, actionPath, "Locate pnpm verification cache");
	const restore = namedStep(action, actionPath, "Restore pnpm verification cache");
	const install = namedStep(action, actionPath, "Install dependencies");
	const proof = namedStep(action, actionPath, "Verify pnpm verification cache");
	const save = namedStep(action, actionPath, "Save pnpm verification cache");
	const steps = action.getIn(["runs", "steps"]);
	assert.ok(isSeq(steps));
	assert.ok(steps.items.indexOf(locate) < steps.items.indexOf(restore));
	assert.ok(steps.items.indexOf(restore) < steps.items.indexOf(install));
	assert.ok(steps.items.indexOf(install) < steps.items.indexOf(proof));
	assert.ok(steps.items.indexOf(proof) < steps.items.indexOf(save));
	for (const declaration of [locate, restore, install, proof])
		assert.equal(declaration.get("if"), "inputs.install == 'frozen'");
	assert.equal(install.get("run"), "pnpm install --frozen-lockfile --ignore-scripts");
	assert.match(String(locate.get("run")), /\$\(pnpm cache path\)/);
	assert.match(
		String(locate.get("run")),
		/require\("node:path"\)\.join\(process\.argv\[1\], "lockfile-verified\.jsonl"\)/,
	);
	assert.equal(
		proof.getIn(["env", "PNPM_VERIFICATION_CACHE"]),
		`\${{ steps.pnpm-verification-path.outputs.path }}`,
	);
	assert.match(String(proof.get("run")), /statSync\(process\.argv\[1\]\)\.isFile\(\)/);
	assert.match(String(restore.get("uses")), /^actions\/cache\/restore@/);
	assert.match(String(save.get("uses")), /^actions\/cache\/save@/);
	const restored = stepInputs(restore);
	assert.deepEqual(stepInputs(save).toJSON(), restored.toJSON());
	assert.equal(restored.get("path"), `\${{ steps.pnpm-verification-path.outputs.path }}`);
	assert.equal(restored.has("restore-keys"), false);
	assert.equal(
		restored.get("key"),
		`pnpm-verification-v1-\${{ runner.os }}-\${{ runner.arch }}-\${{ steps.pnpm.outputs.version }}-\${{ hashFiles('pnpm-lock.yaml', 'pnpm-workspace.yaml', '.npmrc') }}`,
	);
	const parsed = new Parser(
		new Lexer(String(save.get("if"))).lex().tokens,
		["inputs", "steps", "github"],
		[],
	).parse();
	for (const [event, ref, branch, outcome, hit, mode, expected] of [
		["push", "refs/heads/main", "main", "success", "false", "frozen", true],
		["push", "refs/heads/trunk", "trunk", "success", "false", "frozen", true],
		["push", "refs/heads/topic", "main", "success", "false", "frozen", false],
		["schedule", "refs/heads/main", "main", "success", "false", "frozen", false],
		["workflow_dispatch", "refs/heads/main", "main", "success", "false", "frozen", false],
		["pull_request", "refs/pull/1/merge", "main", "success", "false", "frozen", false],
		["pull_request_target", "refs/heads/main", "main", "success", "false", "frozen", false],
		["workflow_run", "refs/heads/main", "main", "success", "false", "frozen", false],
		[
			"merge_group",
			"refs/heads/gh-readonly-queue/main/pr-1",
			"main",
			"success",
			"false",
			"frozen",
			false,
		],
		["workflow_dispatch", "refs/heads/topic", "main", "success", "false", "frozen", false],
		["push", "refs/heads/main", "main", "failure", "false", "frozen", false],
		["push", "refs/heads/main", "main", "skipped", "false", "frozen", false],
		["push", "refs/heads/main", "main", "success", "true", "frozen", false],
		["push", "refs/heads/main", "main", "success", "false", "none", false],
	] as const) {
		const context: unknown = JSON.parse(
			JSON.stringify({
				inputs: { install: mode },
				steps: {
					"pnpm-install": { outcome },
					"pnpm-verification-cache": { outputs: { "cache-hit": hit } },
				},
				github: { ref, event_name: event, event: { repository: { default_branch: branch } } },
			}),
			data.reviver,
		);
		assert.ok(context instanceof data.Dictionary);
		assert.equal(
			new Evaluator(parsed, context).evaluate().coerceString(),
			String(expected),
			`${event} ${ref} ${outcome} ${hit} ${mode}`,
		);
	}
});

void test(
	"quality dispatch selects one task and rejects an unknown leg",
	{ skip: !bashRunsRunnerSteps() },
	async () => {
		const workflow = parseDocument(await readFile(".github/workflows/ci-quality-leg.yml", "utf8"));
		const script = runScript(workflow, ["jobs", "quality"], "Quality gates");
		const probe = `vp() { printf 'command=%s\\n' "$*" >> "$GITHUB_OUTPUT"; }\n${script}`;
		for (const leg of ["server", "tooling", "webapp", "windows"]) {
			const result = await runStep(probe, { LEG: leg });
			assert.equal(result.failed, false, result.diagnosis);
			assert.equal(
				result.outputs.command,
				`run ${leg === "windows" ? "--no-cache " : ""}ci:${leg}`,
			);
		}
		const invalid = await runStep(probe, { LEG: "unknown" });
		assert.equal(invalid.failed, true);
		assert.deepEqual(invalid.outputs, {});
		const failed = await runStep(`vp() { return 1; }\n${script}`, { LEG: "server" });
		assert.equal(failed.failed, true, "a failing quality task must fail the job");
	},
);

void test("unchanged quality legs are skipped before runner allocation", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	for (const [leg, scope] of [
		["server", "application_server"],
		["tooling", "tooling"],
		["webapp", "webapp"],
		["windows", "tooling"],
	]) {
		assert.equal(
			workflow.getIn(["jobs", leg, "if"]),
			leg === "server"
				? "inputs.should_skip != 'true' && (inputs.application_server_changed == 'true' || inputs.pmd_canary == 'true')"
				: `inputs.should_skip != 'true' && inputs.${scope}_changed == 'true'`,
		);
		assert.equal(workflow.getIn(["jobs", leg, "uses"]), "./.github/workflows/ci-quality-leg.yml");
		assert.equal(workflow.getIn(["jobs", leg, "with", "leg"]), leg);
	}
});

void test("a change to the task graph or the hooks selects every quality leg", async () => {
	const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
	const filter = pathFilter(orchestrator, "quality-config");
	for (const entry of [
		"vite.config.ts",
		".vite-hooks/**",
		".java-version",
		".github/workflows/ci-quality-leg.yml",
	])
		assert.match(
			filter,
			new RegExp(`'${escapeRegExp(entry)}'`),
			`quality-config must list ${entry}`,
		);
	for (const name of ["build-config", "test-config"])
		assert.match(
			pathFilter(orchestrator, name),
			/'\.java-version'/,
			`${name} must list .java-version`,
		);
});

void test("Semgrep scans PRs, main and merge queues without a privileged trigger or mutable rules", async () => {
	const source = await readFile(".github/workflows/semgrep.yml", "utf8");
	const workflow = parseDocument(source);
	for (const trigger of ["pull_request", "push", "merge_group"])
		assert.ok(workflow.hasIn(["on", trigger]), `semgrep.yml must run on ${trigger}`);
	assert.equal(workflow.hasIn(["on", "pull_request_target"]), false);
	assert.match(
		String(workflow.getIn(["jobs", "scan", "env", "SEMGREP_IMAGE"])),
		/^semgrep\/semgrep:[\d.]+@sha256:[a-f0-9]{64}$/,
	);
	for (const [command] of source.matchAll(/docker run.*/g))
		assert.match(command, /--network none/, "the scanner runs without a network");
	assert.match(source, /--test --config security\/semgrep/);
	assert.match(source, /--disable-nosem\b/);
	assert.match(source, /--strict\b/);
	assert.match(source, /--error\b/);
	assert.match(source, /--sarif-output\b/);
	assert.doesNotMatch(source, /continue-on-error|secrets\.|--config (auto|p\/)/);
	const upload = namedStep(workflow, ["jobs", "scan"], "Upload code scanning results");
	assert.equal(stepInputs(upload).get("category"), "semgrep-tls");
	assert.match(
		String(upload.get("if")),
		/github\.event\.pull_request\.head\.repo\.full_name == github\.repository/,
		"a fork pull request cannot upload SARIF and must skip the step",
	);
	assert.match(
		pathFilter(await readFile(".github/workflows/cicd.yml", "utf8"), "tooling"),
		/security\/semgrep\/\*\*/,
	);
});

void test("CodeQL runs advanced setup and excludes the Semgrep fixtures it would otherwise flag", async () => {
	const source = await readFile(".github/workflows/codeql.yml", "utf8");
	const workflow = parseDocument(source);
	for (const trigger of ["workflow_call", "schedule"])
		assert.ok(workflow.hasIn(["on", trigger]), `codeql.yml must run on ${trigger}`);
	assert.equal(workflow.hasIn(["on", "pull_request_target"]), false);
	const permissions = workflow.getIn(["jobs", "analyze", "permissions"]);
	assert.ok(isMap(permissions));
	assert.deepEqual(permissions.toJSON(), {
		actions: "read",
		contents: "read",
		"security-events": "write",
	});
	for (const action of ["github/codeql-action/init", "github/codeql-action/analyze"])
		for (const match of source.matchAll(new RegExp(`uses: ${action}@([\\w.-]+)`, "g")))
			assert.match(match[1] ?? "", /^[a-f0-9]{40}$/, `${action} must be pinned by commit`);
	const init = step(workflow, ["jobs", "analyze"], "github/codeql-action/init");
	for (const input of ["debug", "debug-artifact-name", "debug-database-name"]) {
		assert.equal(
			init.has(input),
			false,
			"full debug databases are temporary evidence, not routine CI artifacts",
		);
	}
	assert.match(
		String(init.get("build-mode")),
		/matrix\.language == 'java-kotlin' && 'manual' \|\| 'none'/,
	);
	const java = namedStep(workflow, ["jobs", "analyze"], "Set up Java build");
	assert.equal(java.get("uses"), "./.github/actions/setup-caches");
	assert.equal(java.get("if"), "matrix.language == 'java-kotlin'");
	assert.equal(java.has("with"), false, "analysis consumes the default read-only Gradle cache");
	const steps = workflow.getIn(["jobs", "analyze", "steps"]);
	assert.ok(isSeq(steps));
	assert.ok(
		steps.items.indexOf(java) <
			steps.items.indexOf(namedStep(workflow, ["jobs", "analyze"], "Initialize CodeQL")),
		"provision dependencies before starting the extractor",
	);
	const compile = namedStep(workflow, ["jobs", "analyze"], "Compile Java for analysis");
	assert.equal(compile.get("if"), "matrix.language == 'java-kotlin'");
	assert.equal(compile.get("working-directory"), "server");
	assert.equal(
		compile.get("run"),
		"./gradlew --no-daemon --no-build-cache --no-configuration-cache -PcodeqlExtraction=true clean :application:testClasses",
	);
	const ci = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.equal(ci.getIn(["jobs", "CodeQL", "uses"]), "./.github/workflows/codeql.yml");
	const gate = ci.getIn(["jobs", "all-ci-passed", "needs"]);
	assert.ok(isSeq(gate));
	assert.ok(gate.toJSON().includes("CodeQL"));
	assert.match(
		String(namedStep(ci, ["jobs", "all-ci-passed"], "Evaluate CI results").get("run")),
		/needs.CodeQL.result/,
	);
	for (const trigger of ["pull_request", "push", "merge_group"])
		assert.ok(ci.hasIn(["on", trigger]));
	assert.equal(workflow.hasIn(["on", "pull_request"]), false);
	assert.equal(workflow.hasIn(["on", "merge_group"]), false);
	assert.equal(workflow.hasIn(["on", "push"]), false);
	assert.equal(init.get("config-file"), "./.github/codeql/codeql-config.yml");
	assert.match(String(init.get("languages")), /^\$\{\{ *matrix\.language *\}\}$/);
	const analyze = step(workflow, ["jobs", "analyze"], "github/codeql-action/analyze");
	assert.match(String(analyze.get("category")), /^\/language:\$\{\{ *matrix\.language *\}\}$/);
	const config = asRecord(
		parseDocument(await readFile(".github/codeql/codeql-config.yml", "utf8")).toJSON(),
		"codeql-config.yml",
	);
	assert.deepEqual(config["paths-ignore"], ["security/semgrep/**"]);
});

void test("only CodeQL extraction opts out of duplicate compiler analysis", async () => {
	const build = await readFile("server/application/build.gradle.kts", "utf8");
	assert.match(
		build,
		/enabled\.set\(providers\.gradleProperty\("codeqlExtraction"\)\.map \{ it != "true" \}\.orElse\(true\)\)/,
		"ordinary compilation must keep ErrorProne enabled unless extraction explicitly opts out",
	);
	assert.match(build, /error\("NullAway", "RequireExplicitNullMarking"\)/);
	assert.match(build, /"-Werror"/);
	const callers: string[] = [];
	for (const file of await posixGlob(".github/**/*.yml")) {
		if ((await readFile(file, "utf8")).includes("codeqlExtraction")) callers.push(file);
	}
	assert.deepEqual(callers, [".github/workflows/codeql.yml"]);
	assert.doesNotMatch(await readFile("vite.config.ts", "utf8"), /codeqlExtraction/);
});

void test("every Semgrep rule ships a positive and a negative fixture", async () => {
	const files = await posixGlob("security/semgrep/*");
	const rulesets = files.filter((file) => file.endsWith(".yaml"));
	assert.ok(rulesets.length > 0, "security/semgrep holds no ruleset");
	const fixtures = (
		await Promise.all(
			files.filter((file) => !file.endsWith(".yaml")).map((file) => readFile(file, "utf8")),
		)
	).join("\n");
	for (const file of rulesets) {
		const ruleset = asRecord(parseDocument(await readFile(file, "utf8")).toJSON(), file);
		const ids = asArray(ruleset.rules, `${file} rules`).map((rule) =>
			asString(asRecord(rule, "rule").id, "rule.id"),
		);
		assert.ok(ids.length > 0, `${file} declares no rule`);
		for (const id of ids) {
			assert.match(fixtures, new RegExp(`ruleid: ${id}$`, "m"), `${id} has no violating example`);
			assert.match(fixtures, new RegExp(`ok: ${id}$`, "m"), `${id} has no compliant example`);
		}
	}
});

void test("CodeQL selects languages with native change detection", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/codeql.yml", "utf8"));
	assert.equal(workflow.getIn(["jobs", "analyze", "needs"]), "changes");
	assert.equal(
		workflow.getIn(["jobs", "analyze", "if"]),
		"needs.changes.outputs.languages != '[]'",
	);
	assert.equal(
		workflow.getIn(["jobs", "analyze", "strategy", "matrix", "language"]),
		`\${{ fromJSON(needs.changes.outputs.languages) }}`,
	);
	assert.equal(
		workflow.getIn(["jobs", "changes", "outputs", "languages"]),
		`\${{ steps.filter.outputs.changes || '["actions","java-kotlin","javascript-typescript"]' }}`,
	);
	const steps = workflow.getIn(["jobs", "changes", "steps"]);
	assert.ok(isSeq(steps));
	const selection = steps.items.find((item) => isMap(item) && item.get("id") === "filter");
	assert.ok(isMap(selection));
	assert.equal(
		selection.get("if"),
		"github.event_name == 'pull_request' || github.event_name == 'merge_group'",
	);
	const filter = step(workflow, ["jobs", "changes"], "dorny/paths-filter");
	const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CodeQL filters");
	assert.deepEqual(Object.keys(filters), ["actions", "java-kotlin", "javascript-typescript"]);
	for (const [language, patterns] of Object.entries(filters)) {
		const paths = asArray(patterns, language);
		assert.ok(
			paths.some((pattern) => typeof pattern === "string" && pattern.startsWith(".github/")),
		);
	}
	assert.ok(asArray(filters["java-kotlin"], "Java paths").includes("server/**"));
	assert.ok(
		asArray(filters["java-kotlin"], "Java paths").includes(".github/actions/setup-caches/**"),
	);
	assert.ok(asArray(filters["javascript-typescript"], "JS paths").includes("pnpm-lock.yaml"));
});

void test("Stories enforces visual evidence independently of preview publication", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	const jobPath = ["jobs", "webapp-stories"];
	assert.equal(
		workflow.getIn([...jobPath, "env", "CHROMATIC_POLICY_SKIP"]),
		`\${{ (github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository) || startsWith(github.head_ref || github.ref_name, 'dependabot/') || startsWith(github.head_ref || github.ref_name, 'renovate/') }}`,
	);
	const chromatic = namedStep(workflow, jobPath, "Chromatic visual testing");
	assert.equal(
		chromatic.get("if"),
		"success() && env.CHROMATIC_POLICY_SKIP != 'true' && steps.visual_policy.outputs.paused != 'true'",
	);
	assert.equal(workflow.getIn([...jobPath, "env", "CHROMATIC_PAUSED_UNTIL"]), "2026-09-30");
	assert.equal(
		namedStep(workflow, jobPath, "Clear previous Chromatic evidence").get("id"),
		"visual_policy",
	);
	assert.equal(
		namedStep(workflow, jobPath, "Deploy public Storybook preview").get("if"),
		"success() && github.event_name == 'pull_request' && env.CHROMATIC_POLICY_SKIP != 'true'",
	);
	assert.equal(chromatic.getIn(["with", "autoAcceptChanges"]), false);
	assert.equal(chromatic.getIn(["with", "exitZeroOnChanges"]), false);
	assert.equal(chromatic.getIn(["with", "exitOnceUploaded"]), false);
	assert.equal(chromatic.getIn(["with", "skip"]), false);
	const report = namedStep(workflow, jobPath, "Report Chromatic visual coverage");
	assert.equal(report.get("if"), "always()");
	assert.equal(report.get("continue-on-error"), true);
	assert.equal(report.get("run"), "node scripts/report-chromatic.ts");
	assert.equal(report.getIn(["env", "CHROMATIC_OUTCOME"]), `\${{ steps.chromatic.outcome }}`);
	for (const [name, output] of Object.entries({
		CODE: "code",
		CAPTURED: "actualCaptureCount",
		INHERITED: "inheritedCaptureCount",
		TESTS: "testCount",
		ERRORS: "errorCount",
		CHANGES: "changeCount",
		INTERACTIONS: "interactionTestFailuresCount",
		BUILD_URL: "buildUrl",
	}))
		assert.equal(
			report.getIn(["env", `CHROMATIC_${name}`]),
			`\${{ steps.chromatic.outputs.${output} }}`,
		);
	const gate = namedStep(workflow, jobPath, "Evaluate stories checks");
	assert.equal(gate.get("if"), "always()");
	assert.equal(gate.getIn(["env", "CHROMATIC"]), `\${{ steps.visual_coverage.outcome }}`);
});

void test("global styles and assets retain full-snapshot invalidation", async () => {
	const config: unknown = JSON.parse(await readFile("webapp/chromatic.config.json", "utf8"));
	assert.ok(
		config &&
			typeof config === "object" &&
			"externals" in config &&
			Array.isArray(config.externals),
	);
	for (const pattern of [
		"webapp/public/**",
		"webapp/src/assets/**",
		"webapp/**/*.css",
		"webapp/**/*.scss",
		"webapp/**/*.sass",
		"webapp/tailwind.config.*",
		"webapp/vite.*",
		"webapp/components.json",
	])
		assert.ok(config.externals.includes(pattern), `${pattern} must invalidate snapshots`);
});

void test(
	"Stories final verdict rejects every incomplete or failed leg",
	{ skip: !bashRunsRunnerSteps() },
	async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		const command = runScript(workflow, ["jobs", "webapp-stories"], "Evaluate stories checks");
		for (const stories of ["success", "failure", "skipped"])
			for (const coverage of ["success", "failure", "skipped", "cancelled", ""]) {
				const result = await runStep(command, { STORYBOOK_TESTS: stories, CHROMATIC: coverage });
				assert.equal(
					result.failed,
					stories !== "success" || coverage !== "success",
					result.diagnosis,
				);
			}
	},
);

void test("toolchain cache producers cover Linux and Windows without repeating quality gates", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cache-toolchain.yml", "utf8"));
	const triggers = workflow.get("on");
	assert.ok(isMap(triggers));
	assert.deepEqual(triggers.toJSON(), {
		push: {
			branches: ["main"],
			paths: [
				"package.json",
				"pnpm-lock.yaml",
				"pnpm-workspace.yaml",
				".npmrc",
				".github/actions/setup-toolchain/**",
				".github/workflows/cache-toolchain.yml",
			],
		},
	});
	const jobs = workflow.get("jobs");
	assert.ok(isMap(jobs));
	assert.equal(jobs.items.length, 1);
	const install = jobs.get("install");
	assert.ok(isMap(install));
	assert.equal(install.get("runs-on"), `\${{ matrix.os }}`);
	assert.equal(install.has("if"), false);
	const platforms = install.getIn(["strategy", "matrix", "os"]);
	assert.ok(isSeq(platforms));
	assert.deepEqual(platforms.toJSON(), ["ubuntu-latest", "windows-latest"]);
	assert.equal(install.getIn(["strategy", "fail-fast"]), false);
	const steps = install.get("steps");
	assert.ok(isSeq(steps));
	assert.equal(steps.items.length, 2);
	const checkout = steps.items[0];
	const setup = steps.items[1];
	assert.ok(isMap(checkout) && isMap(setup));
	assert.match(String(checkout.get("uses")), /^actions\/checkout@/);
	assert.equal(checkout.getIn(["with", "persist-credentials"]), false);
	assert.equal(setup.get("uses"), "./.github/actions/setup-toolchain");
	assert.equal(setup.getIn(["with", "install"]), "frozen");
	assert.equal(setup.has("if"), false);
});
