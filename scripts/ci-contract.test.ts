import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { isMap, isScalar, isSeq } from "yaml";
import {
	assertNeeds,
	bashRunsRunnerSteps,
	buildStageCopySources,
	CI_ONLY_GATES,
	escapeRegExp,
	expandExpressions,
	job,
	limitMinutes,
	namedStep,
	packagesLoadedBy,
	parseDocument,
	pathFilter,
	posixGlob,
	runScript,
	runStep,
	step,
	stepInputs,
	taskClosure,
	VP_INVOCATION,
	workflowSources,
} from "./ci-contract-support.ts";
import { isSet } from "./lib/env.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void describe("ci-contract: tooling", () => {
	void test("task names follow the vocabulary in the local verification guide", async () => {
		const tasks = await loadTasks();
		const instructions = await readFile("docs/contributor/local-verification.mdx", "utf8");
		const vocabulary = /^### Task vocabulary\n(?<body>[\s\S]*?)(?=^###? )/mu.exec(instructions)
			?.groups?.body;
		assert.ok(
			isSet(vocabulary),
			"Local verification § Repository tooling must contain the task vocabulary",
		);
		const allowedPrefixes = new Set(
			[...vocabulary.matchAll(/^\| `(?<prefix>[a-z]+)` \|/gmu)].map(
				({ groups }) => groups?.prefix ?? "",
			),
		);
		assert.ok(allowedPrefixes.size > 0, "Local verification task vocabulary lists no prefixes");
		const used = new Set<string>();
		for (const name of Object.keys(tasks).toSorted()) {
			assert.match(name, /^[a-z]+(?::[a-z0-9-]+)*$/u, `${name} is not a valid task name`);
			const prefix = name.split(":", 1)[0] ?? "";
			assert.ok(
				allowedPrefixes.has(prefix),
				`${name} does not use a task prefix listed in Local verification § Repository tooling`,
			);
			used.add(prefix);
		}
		assert.deepEqual(
			[...allowedPrefixes].filter((prefix) => !used.has(prefix)),
			[],
			"Local verification § Repository tooling lists a task prefix no task uses",
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

	void test("every workflow's display name is unique and starts with its area, so the Actions sidebar groups them", async () => {
		const names = new Map<string, string>();
		const upstreams = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			const workflow = parseDocument(source);
			const name = String(workflow.get("name"));
			const previous = names.get(name);
			assert.equal(previous, undefined, `${file} repeats the name of ${String(previous)}`);
			names.set(name, file);
			const triggers = workflow.getIn(["on", "workflow_run", "workflows"]);
			if (isSeq(triggers)) {
				for (const item of triggers.items) {
					upstreams.add(String(isScalar(item) ? item.value : item));
				}
			}
		}
		// `workflow_run` matches a workflow by its display name, so a renamed upstream would stop
		// its consumers without an error. A name that a consumer matches keeps its form.
		for (const upstream of upstreams) {
			assert.ok(
				names.has(upstream),
				`a workflow_run trigger names ${upstream}, which no workflow has`,
			);
		}
		for (const [name, file] of names) {
			if (!upstreams.has(name)) {
				assert.match(name, /^(?:CI|Release|Security|Docs|Cache|Repo) \/ \S/u, file);
			}
		}
	});

	void test("a job that needs a job that can be skipped states a status function", async () => {
		// Without always(), !cancelled() or another status function, a condition gets the implicit
		// success(), which skips the job when any job up its chain was skipped: in a pull request, a job
		// that runs only in a merge group. Only detect-changes always runs.
		const ci = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const jobs = ci.get("jobs");
		assert.ok(isMap(jobs), "cicd.yml has jobs");
		const exposed = jobs.items.flatMap(({ key, value }) => {
			if (!isMap(value) || !value.has("needs")) {
				return [];
			}
			const needs = value.get("needs");
			const needed = isSeq(needs) ? needs.items.map(String) : [String(needs)];
			const raw = value.get("if");
			const condition = typeof raw === "string" ? raw : "";
			const guarded = /\b(?:always|success|failure|cancelled)\s*\(/u.test(condition);
			return guarded || needed.every((name) => name === "detect-changes") ? [] : [String(key)];
		});
		assert.deepEqual(exposed, []);
	});

	void test("the extension's generated client is regenerated, checked and committed with the webapp's", async () => {
		const tasks = await loadTasks();
		const [generate] = commandsOf(tasks["generate:api:client"]);
		assert.ok(isSet(generate));
		assert.match(generate, /^node scripts\/rm\.ts webapp\/src\/api extension\/src\/api && /u);
		for (const client of ["webapp", "extension"]) {
			assert.ok(generate.includes(`vp run --filter ${client} generate:api`), client);
		}
		const build = parseDocument(await readFile(".github/workflows/ci-build.yml", "utf8"));
		const openapi = runScript(build, ["jobs", "server-api"], "OpenAPI specification and client");
		assert.match(
			openapi,
			/assert-generated-clean\.ts server\/openapi\.yaml webapp\/src\/api extension\/src\/api/u,
		);
		const api = await readFile(".github/workflows/openapi-autocommit.yml", "utf8");
		assert.match(job(api, "generate"), /^ {12}extension\/src\/api$/mu);
		assert.match(job(api, "commit"), /rm -rf webapp\/src\/api extension\/src\/api/u);
		assert.match(
			job(api, "commit"),
			/-- server\/openapi\.yaml webapp\/src\/api extension\/src\/api/u,
		);
		// A client edited without a server change still meets the drift check, which needs the JAR.
		const orchestrator = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(orchestrator, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		const clients = asArray(filters["api-clients"], "api-clients paths");
		for (const client of ["webapp", "extension"]) {
			assert.ok(clients.includes(`${client}/src/api/**`), client);
		}
		assert.ok(clients.includes("scripts/api-generator/**"));
		for (const [name, location] of [
			["server-package", ["jobs", "server-package", "if"]],
			["contracts_changed", ["jobs", "Build", "with", "contracts_changed"]],
		] as const) {
			assert.match(
				String(orchestrator.getIn(location)),
				/needs\.detect-changes\.outputs\.api-clients == 'true'/u,
				name,
			);
		}
	});

	void test("the production extension zip is judged, not only the builds the browser suite runs", async () => {
		const tasks = await loadTasks();
		assert.deepEqual(commandsOf(tasks["verification:extension-build"]), [
			"vp run build:extension",
			"vp run check:extension-package",
		]);
		assert.deepEqual(commandsOf(tasks["check:extension-package"]), [
			"node scripts/check-extension-package.ts",
		]);
		assert.ok(taskClosure(tasks, ["ci:extension"]).has("check:extension-package"));
		// The browser suite runs the build it names, built first, wherever it is started.
		assert.deepEqual(commandsOf(tasks["test:extension:e2e"]), [
			"vp run --filter extension build:e2e",
			"vp run --filter extension test:e2e",
		]);
		const browserSource = await readFile(".github/workflows/ci-browser-tests.yml", "utf8");
		const build =
			(await readFile(".github/workflows/ci-build.yml", "utf8")) +
			browserSource.split("jobs:\n")[1];
		const e2e = job(build, "extension-e2e");
		assert.match(e2e, /vp run test:extension:e2e/u);
		// Every Playwright project runs: neither the task nor the job narrows the suite.
		assert.doesNotMatch(e2e, /--filter extension test:e2e|--project/u);
		for (const command of commandsOf(tasks["test:extension:e2e"])) {
			assert.doesNotMatch(command, /--project|--grep/u);
		}
		// The server the suite signs in to: GitLab integration on, both seeds in order, its address
		// handed to Playwright rather than assumed.
		assert.match(e2e, /--spring\.profiles\.active=e2e,playwright/u);
		assert.match(e2e, /--hephaestus\.integration\.gitlab\.enabled=true/u);
		const webappSeed = e2e.indexOf("< webapp/e2e/seed.sql");
		const extensionSeed = e2e.indexOf("< extension/e2e/seed.sql");
		assert.ok(
			webappSeed !== -1 && webappSeed < extensionSeed,
			"webapp seed runs before extension seed",
		);
		assert.ok(extensionSeed < e2e.indexOf("vp run test:extension:e2e"));
		const buildWorkflow = parseDocument(build);
		const e2eStep = namedStep(
			buildWorkflow,
			["jobs", "extension-e2e"],
			"Run extension end-to-end tests against the built server",
		);
		assert.equal(e2eStep.getIn(["env", "E2E_SERVER_URL"]), "http://127.0.0.1:8080");
		assert.match(String(e2eStep.get("run")), /SERVER_PORT=8080 /u);
		const leg = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
		const upload = namedStep(leg, ["jobs", "quality"], "Upload the extension package");
		assert.equal(upload.get("if"), "env.QUALITY_LEG == 'extension'");
		assert.equal(stepInputs(upload).get("path"), "extension/.output/*-production-chrome.zip");
		assert.equal(
			stepInputs(upload).get("include-hidden-files"),
			true,
			"the production archive lives under WXT's hidden .output directory",
		);
	});

	void test("extension checks and CI enforce the shared composition and story gates", async () => {
		const tasks = await loadTasks();
		for (const command of ["check:extension", "affected:extension", "ci:extension"]) {
			const closure = taskClosure(tasks, [command]);
			for (const gate of [
				"gate:extension",
				"gate:extension-format",
				"gate:components",
				"gate:stories",
			]) {
				assert.ok(closure.has(gate), `${command} must run ${gate}`);
			}
		}
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		for (const scope of ["webapp", "extension"]) {
			const inputs = asArray(filters[scope], `${scope} filter`);
			for (const input of [
				"oxlint.app.jsonc",
				"scripts/check-presentational-components.ts",
				"scripts/check-presentational-components.test.ts",
				"scripts/check-story-prose.ts",
			]) {
				assert.ok(inputs.includes(input), `${input} must select ${scope}`);
			}
		}
	});

	void test("generated brand assets use a versioned Linux renderer", async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		assert.equal(workflow.getIn(["jobs", "webapp-stories", "runs-on"]), "ubuntu-24.04");
	});

	void test("builds Storybook once and gives its TurboSnap stats to Chromatic", async () => {
		const storybook = job(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
			"webapp-stories",
		);
		assert.equal((storybook.match(/vp run --filter webapp build-storybook/gu) ?? []).length, 1);
		assert.match(storybook, /test -s webapp\/storybook-static\/preview-stats\.json/u);
		assert.match(storybook, /storybookBuildDir: storybook-static/u);
		assert.match(storybook, /onlyChanged: true/u);
		assert.doesNotMatch(storybook, /surge/u);
		assert.match(storybook, /render-preview-comment\.ts webapp\/storybook-static/u);
		assert.match(
			storybook,
			/CHROMATIC_STORYBOOK_URL: \$\{\{ steps\.chromatic\.outputs\.storybookUrl \}\}/u,
		);
		assert.match(storybook, /github\.event\.pull_request\.base\.sha/u);
		assert.match(
			storybook,
			/PREVIEW_COMMENT_PATH: \$\{\{ runner\.temp \}\}\/storybook-preview\.json/u,
		);
		assert.match(storybook, /name: Create Storybook status check\s+if: success\(\)/u);
	});

	void test("the Storybook preview publishes its links with the continuation-aware publisher", async () => {
		const source = await readFile(".github/workflows/ci-quality-gates.yml", "utf8");
		assert.match(source, /scripts\/publish-preview-comments\.ts/u);
		assert.ok(source.includes("path: process.env.PREVIEW_COMMENT_PATH"));
	});

	void test("runs duplicate checks only for ordinary pull requests", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const steps = workflow.getIn(["jobs", "detect-changes", "steps"]);
		assert.ok(isSeq(steps));
		const duplicate = steps.items.find((item) => isMap(item) && item.get("id") === "skip_check");
		assert.ok(isMap(duplicate));
		const candidateIndex = steps.items.findIndex(
			(item) => isMap(item) && item.get("id") === "release_candidate",
		);
		assert.ok(candidateIndex !== -1 && candidateIndex < steps.items.indexOf(duplicate));
		const condition = asString(duplicate.get("if"), "duplicate check event condition");
		const parsed = new Parser(new Lexer(condition).lex().tokens, ["github", "steps"], []).parse();
		for (const [event, candidate, expected] of [
			["pull_request", "false", true],
			["pull_request", "true", false],
			["push", "false", false],
			["workflow_dispatch", "false", false],
			["merge_group", "false", false],
		] as const) {
			const context: unknown = JSON.parse(
				JSON.stringify({
					github: { event_name: event },
					steps: { release_candidate: { outputs: { "release-candidate": candidate } } },
				}),
				data.reviver,
			);
			assert.ok(context instanceof data.Dictionary);
			assert.equal(
				new Evaluator(parsed, context).evaluate().coerceString(),
				String(expected),
				`${event}, release candidate ${candidate}`,
			);
		}
	});

	void test("separates pre-merge validation from post-merge artifact production", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		for (const event of ["workflow_dispatch", "push", "pull_request", "merge_group"]) {
			assert.match(source, new RegExp(`^ {2}${event}:`, "mu"));
		}
		assert.match(
			job(source, "detect-changes"),
			/version-bump: \$\{\{ steps\.version_bump\.outputs\.changed \}\}/u,
		);
		for (const name of ["workflow-lint", "Quality", "upstream-images", "Security", "Test"]) {
			const needs = {
				"upstream-images": /needs: \[detect-changes, vulnerability-database\]/u,
				Test: /needs: \[detect-changes, server-package, Security\]/u,
				Quality: /needs: \[detect-changes, Security\]/u,
			}[name];
			assert.match(job(source, name), needs ?? /needs: \[detect-changes\]/u);
			assert.match(
				job(source, name),
				/github\.event_name != 'push'.*needs\.detect-changes\.outputs\.version-bump == 'true'/su,
			);
		}
		// Every push to main packages and publishes the final-SHA images; only a version bump
		// repeats source validation, so the artifact gates inside Build follow the same rule.
		assert.doesNotMatch(job(source, "Docker"), /version-bump/u);
		for (const [name, inputGates] of [
			["Build", ["contracts_changed"]],
			["Browser-tests", ["e2e_changed", "extension_e2e_changed"]],
		] as const) {
			const build = job(source, name);
			assert.doesNotMatch(build.slice(0, build.indexOf("with:")), /version-bump/u);
			for (const gate of inputGates) {
				assert.match(build, new RegExp(`^\\s+${gate}:.*version-bump == 'true'`, "mu"));
			}
		}
		assertNeeds(source, "all-ci-passed", "Docker");
	});

	void test("bounds every captured subprocess above Node's 1 MiB default", async () => {
		// Node caps a captured subprocess at 1 MiB and throws past it. That default has broken the
		// release pipeline three times: a cosign attestation carrying an SBOM, a `gh api` release
		// listing, and a `gh api` workflow-run listing that stopped the Version PR being maintained
		// at all. The ceiling has one home, and a capture that does not use it is the fourth.
		const offenders: string[] = [];
		for (const file of await posixGlob("scripts/**/*.ts")) {
			if (file.endsWith(".test.ts")) {
				continue;
			}
			const source = await readFile(file, "utf8");
			// `stdio: inherit`/`ignore` streams to the parent and buffers nothing; only a capture,
			// which is what `encoding` marks, can overflow.
			const captures =
				source.match(/exec(?:File)?Sync\(|execFileAsync\(|spawnSync\(/gu)?.length ?? 0;
			if (captures === 0 || !source.includes("encoding")) {
				continue;
			}
			if (!source.includes("maxBuffer")) {
				offenders.push(file);
			}
		}
		assert.deepEqual(offenders, [], "these capture a subprocess without bounding its buffer");
	});

	void test("revalidates a push whose range changes the version, wherever the bump sits in it", async (context) => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const bumpStep = namedStep(workflow, ["jobs", "detect-changes"], "Detect version bump");
		assert.equal(bumpStep.getIn(["env", "PUSH_BEFORE"]), `\${{ github.event.before }}`);
		const directory = await mkdtemp(path.join(tmpdir(), "version-bump-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const env = environmentForGitFixture();
		const git = (...args: string[]) => {
			const result = spawnSync("git", args, { cwd: directory, env, encoding: "utf8" });
			assert.equal(result.status, 0, result.stderr);
			return result.stdout.trim();
		};
		const commit = async (manifest: string, message: string) => {
			await writeFile(path.join(directory, "package.json"), manifest);
			git("add", ".");
			git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", message);
			return git("rev-parse", "HEAD");
		};
		git("init", "-q");
		const released = await commit('{"version":"0.1.0"}', "previous tip");
		const bump = await commit('{"version":"0.2.0"}', "chore(release): version packages");
		await writeFile(path.join(directory, "later.txt"), "a pull request queued behind it");
		await commit('{"version":"0.2.0"}', "fix: queued behind the Version PR");
		const output = path.join(directory, "output");
		const detect = async (before: string) => {
			await writeFile(output, "");
			const result = spawnSync(
				"bash",
				["--noprofile", "--norc", "-e", "-c", String(bumpStep.get("run"))],
				{
					cwd: directory,
					encoding: "utf8",
					env: { ...env, PUSH_BEFORE: before, GITHUB_OUTPUT: output },
				},
			);
			assert.equal(result.status, 0, result.stderr);
			return readFile(output, "utf8");
		};
		// The last commit's parent already carries the new version; the replaced tip does not.
		assert.equal(await detect(released), "changed=true\n");
		assert.equal(await detect(bump), "changed=false\n");
		for (const before of ["0".repeat(40), ""]) {
			assert.equal(await detect(before), "changed=true\n", `before '${before}'`);
		}
	});

	void test("bounds every job that runs steps of its own", async () => {
		// A job that declares no `timeout-minutes` inherits GitHub's six-hour default, so one hung
		// step holds a runner for a working day and reports nothing until it is killed. A job that
		// only `uses:` a reusable workflow runs no step here: its bound is on the callee's jobs.
		const unbounded: string[] = [];
		const tight: string[] = [];
		const MIN_JOB_TIMEOUT_MINUTES = 15;
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			if (!isMap(jobs)) {
				continue;
			}
			for (const entry of jobs.items) {
				const definition = entry.value;
				if (!isMap(definition) || !isSeq(definition.get("steps"))) {
					continue;
				}
				const limit = definition.get("timeout-minutes");
				if (limit === undefined) {
					unbounded.push(`${file}#${String(entry.key)}`);
				} else {
					// A job limit below the floor can cancel a finished job (ci-cd.mdx § Parallelism and
					// concurrency). An expression limit, such as a matrix-dependent one, is held to it
					// through every number in it.
					const minutes = limitMinutes(limit);
					if (minutes.length === 0 || minutes.some((value) => value < MIN_JOB_TIMEOUT_MINUTES)) {
						tight.push(`${file}#${String(entry.key)}: ${JSON.stringify(limit)}`);
					}
				}
			}
		}
		assert.deepEqual(unbounded, [], "these jobs run steps without bounding how long they may run");
		assert.deepEqual(
			tight,
			[],
			`a job's limit is a hang detector of at least ${MIN_JOB_TIMEOUT_MINUTES} minutes (ci-cd.mdx)`,
		);
	});
});

void test("vp invocation detection handles assignments without exponential backtracking", () => {
	for (const command of [
		"vp run check",
		"A=x=y B=z vp exec tool",
		"timeout 10s vp run check",
		"timeout --kill-after=30s 15m vp run check",
		"timeout 10s env A=x vp -C docs lint .",
		"true && A=x vp run check",
		"true && timeout 10s vp run check",
	]) {
		assert.equal(VP_INVOCATION.test(command), true, command);
	}
	assert.equal(VP_INVOCATION.test('echo "vp run check"'), false);
	assert.equal(VP_INVOCATION.test('echo "timeout 10s vp run check"'), false);
	assert.equal(VP_INVOCATION.test("timeout 10s echo vp run check"), false);
	// Isolate the match so a regression cannot block the test process itself.
	const match = spawnSync(
		process.execPath,
		[
			"--input-type=module",
			"-e",
			`const pattern = new RegExp(${JSON.stringify(VP_INVOCATION.source)}, "mu");
        if (pattern.test("&" + "!==! ".repeat(20_000))) process.exit(1);
        if (pattern.test("timeout " + "--x=y ".repeat(20_000))) process.exit(1);`,
		],
		{ encoding: "utf8", timeout: 5000 },
	);
	assert.equal(match.status, 0, String(match.error ?? match.stderr));
});

// The release jobs hold release credentials, so they run `node scripts/…` with nothing installed. A
// package import anywhere below such a script stops the job when it loads, on a path that pull
// request CI may never run.
void test("a job without an install runs only scripts that load no package", async () => {
	const offenders: string[] = [];
	const checked = new Set<string>();
	for (const [file, source] of await workflowSources()) {
		const jobs = parseDocument(source).get("jobs");
		if (!isMap(jobs)) {
			continue;
		}
		for (const pair of jobs.items) {
			const steps = isMap(pair.value) ? pair.value.get("steps") : undefined;
			if (!isSeq(steps)) {
				continue;
			}
			const definitions = asArray(steps.toJSON(), "steps").map((entry) => asRecord(entry, "step"));
			const installs = definitions.some(
				(entry) =>
					(entry.uses === "./.github/actions/setup-toolchain" &&
						isRecord(entry.with) &&
						entry.with.install === "frozen") ||
					(typeof entry.run === "string" && /\b(?:vp|pnpm) install\b/u.test(entry.run)),
			);
			if (installs) {
				continue;
			}
			const scripts = definitions.flatMap(({ run }) =>
				[
					...(typeof run === "string" ? run : "").matchAll(
						/\bnode\s+(?:\.\.\/)?(?<script>scripts\/[\w./-]+\.ts)\b/gu,
					),
				].map((match) => match.groups?.script ?? ""),
			);
			for (const script of scripts) {
				checked.add(script);
				for (const [name, importer] of packagesLoadedBy(script)) {
					offenders.push(`${file}#${String(pair.key)}: ${script} loads ${name} in ${importer}`);
				}
			}
		}
	}
	for (const script of ["scripts/rescan-release-images.ts", "scripts/verify-release-evidence.ts"]) {
		assert.ok(checked.has(script), `${script} must stay in the check`);
	}
	assert.deepEqual([...new Set(offenders)], []);
});

void test("proves the toolchain on Windows and from a clean clone", async () => {
	const source = await readFile(".github/workflows/ci-quality-gates.yml", "utf8");
	const legSource = await readFile(".github/workflows/ci-quality-gates.yml", "utf8");
	const workflow = parseDocument(legSource);
	const qualityPath = ["jobs", "quality"];
	const quality = job(legSource, "quality");
	assert.equal(parseDocument(source).getIn(["jobs", "windows", "runs-on"]), "windows-latest");
	assert.equal(workflow.getIn(["jobs", "quality", "runs-on"]), "ubuntu-latest");
	assert.match(quality, /shell: bash/u);
	assert.match(quality, /windows\) deadline vp run --no-cache ci:windows/u);
	assert.match(quality, /deadline\(\) \{ node scripts\/ci-deadline\.ts 900 "\$@"; \}/u);
	for (const name of ["Restore Vite task cache", "Save Vite task cache"]) {
		assert.match(
			String(namedStep(workflow, qualityPath, name).get("if")),
			/env\.QUALITY_LEG != 'windows'/u,
		);
	}
	const install = job(source, "clean-install");
	assert.doesNotMatch(install, /setup-toolchain|pnpm\/setup/u);
	assert.match(install, /vp install --frozen-lockfile/u);
	assert.match(install, /vp run gate:toolchain/u);
	const hookCondition =
		"(env.QUALITY_LEG == 'tooling' || env.QUALITY_LEG == 'windows') && !cancelled()";
	for (const name of ["Commit-msg hook", "Pre-push hook"]) {
		assert.equal(namedStep(workflow, qualityPath, name).get("if"), hookCondition);
	}
	const commitHook = runScript(workflow, qualityPath, "Commit-msg hook");
	assert.match(commitHook, /node scripts\/enable-hooks\.ts/u);
	assert.match(commitHook, /git config core\.hooksPath/u);
	assert.equal((commitHook.match(/git commit --allow-empty/gu) ?? []).length, 2);
	const prePushHook = runScript(workflow, qualityPath, "Pre-push hook");
	assert.match(prePushHook, /node scripts\/enable-hooks\.ts/u);
	assert.match(prePushHook, /git push --dry-run\b/u);
	assert.match(prePushHook, /\[ "\$2" != check:push \]/u);
	// The step moves the launcher aside, so it must put it back however it exits.
	assert.match(prePushHook, /trap .*EXIT/u);
	assert.doesNotMatch(prePushHook, /^\s*vp run check\s*$/mu);
});

void test(
	"quality dispatch selects one task and rejects an unknown leg",
	{ skip: !bashRunsRunnerSteps() },
	async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		const script = runScript(workflow, ["jobs", "quality"], "Quality gates");
		// The task runs under the step's deadline: `node scripts/ci-deadline.ts <seconds> <vp arguments>`.
		const probe = `node() { printf 'deadline=%s %s\\n' "$1" "$2" >> "$GITHUB_OUTPUT"; shift 2; printf 'command=%s\\n' "$*" >> "$GITHUB_OUTPUT"; }\n${script}`;
		// This suite itself runs in a tooling test job, so each probe sets the half explicitly.
		for (const leg of ["server", "tooling", "webapp", "extension", "windows"]) {
			const result = await runStep(probe, { LEG: leg, HEPHAESTUS_NODE_TEST_SHARD: "" });
			assert.equal(result.failed, false, result.diagnosis);
			assert.equal(
				result.outputs.command,
				`${leg === "extension" ? "scripts/ci-deadline.ts 900 " : ""}vp run ${leg === "windows" ? "--no-cache " : ""}ci:${leg}`,
			);
			assert.equal(
				result.outputs.deadline,
				leg === "extension" ? "scripts/ci-browser.ts node" : "scripts/ci-deadline.ts 900",
			);
		}
		const half = await runStep(probe, { LEG: "tooling", HEPHAESTUS_NODE_TEST_SHARD: "2/2" });
		assert.equal(half.outputs.command, "vp run ci:tooling-tests");
		const invalid = await runStep(probe, { LEG: "unknown" });
		assert.equal(invalid.failed, true);
		assert.deepEqual(invalid.outputs, {});
		const failed = await runStep(`node() { return 1; }\n${script}`, { LEG: "server" });
		assert.equal(failed.failed, true, "a failing quality task must fail the job");
	},
);

void test("unchanged quality legs are skipped before runner allocation", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	for (const [leg, scope] of [
		["quality", "application_server"],
		["tooling", "tooling"],
		["webapp", "webapp"],
		["extension", "extension"],
		["windows", "tooling"],
	] as const) {
		const condition = asString(workflow.getIn(["jobs", leg, "if"]), `${leg} selection`);
		for (const [changed, skip, expected] of [
			["false", "false", "false"],
			["true", "true", "false"],
			["true", "false", "true"],
		] as const) {
			assert.equal(
				expandExpressions(`\${{ ${condition} }}`, {
					github: { event_name: "pull_request" },
					inputs: {
						[`${scope}_changed`]: changed,
						should_skip: skip,
						pmd_canary: "false",
						windows_changed: "true",
					},
				}),
				expected,
				`${leg}, changed=${changed}, skip=${skip}`,
			);
		}
		assert.equal(workflow.hasIn(["jobs", leg, "uses"]), false);
		assert.equal(
			workflow.getIn(["jobs", leg, "env", "QUALITY_LEG"]),
			leg === "quality" ? "server" : leg,
		);
	}
});

void test("a change to the task graph or the hooks selects every quality leg", async () => {
	const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
	const filter = pathFilter(orchestrator, "quality-config");
	for (const entry of [
		"vite.config.ts",
		"scripts/lib/push-gates.ts",
		".vite-hooks/**",
		".java-version",
		".github/workflows/ci-quality-gates.yml",
	]) {
		assert.match(
			filter,
			new RegExp(`'${escapeRegExp(entry)}'`, "u"),
			`quality-config must list ${entry}`,
		);
	}
	for (const name of ["build-config", "test-config"]) {
		assert.match(
			pathFilter(orchestrator, name),
			/'\.java-version'/u,
			`${name} must list .java-version`,
		);
	}
});

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
	assert.match(String(checkout.get("uses")), /^actions\/checkout@/u);
	assert.equal(checkout.getIn(["with", "persist-credentials"]), false);
	assert.equal(setup.get("uses"), "./.github/actions/setup-toolchain");
	assert.equal(setup.getIn(["with", "install"]), "frozen");
	assert.equal(setup.has("if"), false);
});

await test("extension unit and browser stories retain separate reports through the shared CI leg", async () => {
	const manifest = asRecord(
		JSON.parse(await readFile("extension/package.json", "utf8")),
		"extension package",
	);
	const scripts = asRecord(manifest.scripts, "extension scripts");
	for (const [script, report] of [
		["test", "junit-unit.xml"],
		["test:storybook", "junit-stories.xml"],
	] as const) {
		const command = asString(scripts[script], script);
		assert.ok(command.includes("--reporter=junit"));
		assert.ok(command.includes(`--outputFile.junit=test-results/${report}`));
	}
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	for (const name of [
		"Upload application test results",
		"Retain application test reports",
		"Summarize application tests",
		"Upload application test metrics",
	]) {
		const report = namedStep(workflow, ["jobs", "quality"], name);
		const condition = asString(report.get("if"), name);
		assert.ok(condition.includes("env.QUALITY_LEG == 'extension'"));
		assert.ok(condition.includes("!cancelled()"));
	}
});
void test("workflows do not comment on hard-coded issue numbers", async () => {
	const hardCodedComment =
		/\bgh(?:\s+(?:--repo|-R)\s+\S+)?\s+issue\s+comment(?:\s+(?:--repo|-R|--body|-b|--body-file|-F)\s+(?:"[^"]*"|'[^']*'|\S+))*\s+["']?#?\d+\b/u;
	for (const command of [
		"gh issue comment 1369 --body text",
		'gh issue comment "1369"',
		"gh issue comment '#1369'",
		"gh issue comment --repo owner/repo 1369",
		'gh issue comment --body "failure needs triage" 1369',
		"gh --repo owner/repo issue comment 1369",
	]) {
		assert.match(command, hardCodedComment);
	}
	assert.doesNotMatch('gh issue comment "$issue" --body text', hardCodedComment);
	for (const [file, source] of await workflowSources()) {
		assert.doesNotMatch(
			source.replaceAll(/\\\r?\n/gu, " "),
			hardCodedComment,
			`${file} must resolve its tracking issue dynamically`,
		);
	}
});

void test("the prose gate ratchets on Linux and Windows with the base history", async () => {
	const tasks = await loadTasks();
	for (const entry of ["quality", "ci:tooling", "ci:windows", "affected:docs", "affected:webapp"]) {
		assert.ok(taskClosure(tasks, [entry]).has("gate:prose"), `${entry} must run the prose gate`);
	}
	assert.equal(asRecord(tasks["gate:prose"], "gate:prose").cache, false);
	assert.match(
		commandsOf(tasks["gate:prose"]).join(" "),
		/node --test scripts\/check-prose\.test\.ts.*node scripts\/check-prose\.ts/u,
	);
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "quality", "steps"]);
	assert.ok(isSeq(steps));
	const checkout = steps.items.find(
		(item) => isMap(item) && String(item.get("uses")).startsWith("actions/checkout@"),
	);
	assert.ok(isMap(checkout));
	assert.equal(checkout.getIn(["with", "fetch-depth"]), 0);
	const source = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const filter = step(source, ["jobs", "detect-changes"], "dorny/paths-filter");
	const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
	// The practice guides are enforced prose, so a guide change reaches the leg that runs the gate.
	for (const file of [
		".vale/**",
		".vale.ini",
		"server/application/src/main/resources/practices/guidance/**",
	]) {
		assert.ok(asArray(filters.tooling, "tooling paths").includes(file));
	}
	const dockerfile = await readFile("webapp/Dockerfile", "utf8");
	for (const file of [".vale/", "docs/contributor/practice-feedback-language.md"]) {
		assert.ok(
			buildStageCopySources(dockerfile).includes(file),
			`Copy ${file} before the webapp lint runs`,
		);
	}
	for (const file of [
		".vale/**",
		"docs/contributor/practice-feedback-language.md",
		"scripts/lib/ste-words.ts",
		"scripts/lib/json.ts",
	]) {
		assert.ok(asArray(filters["webapp-image"], "image paths").includes(file));
	}
	assert.equal(
		namedStep(workflow, ["jobs", "quality"], "Quality gates").getIn(["env", "PR_BASE_SHA"]),
		`\${{ github.event.pull_request.base.sha }}`,
	);
});

void test("merged workflow lint retains Zizmor after an Actionlint failure and its original skip selection", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const worker = ["jobs", "workflow-lint"];
	assert.equal(workflow.getIn([...worker, "name"]), "Workflow lint");
	assert.equal(workflow.hasIn(["jobs", "zizmor"]), false);
	const sequence = workflow.getIn([...worker, "steps"]);
	assert.ok(isSeq(sequence));
	const zizmor = sequence.items.find((item) => isMap(item) && item.get("id") === "zizmor");
	assert.ok(isMap(zizmor));
	const condition = asString(zizmor.get("if"), "Zizmor guard").replaceAll(
		/^\$\{\{\s*|\s*\}\}$/gu,
		"",
	);
	const parsed = new Parser(
		new Lexer(condition).lex().tokens,
		["steps"],
		[{ name: "cancelled", minArgs: 0, maxArgs: 0 }],
	).parse();
	for (const cancelled of [false, true]) {
		const functions = new Map([
			[
				"cancelled",
				{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(cancelled) },
			],
		]);

		const context: unknown = JSON.parse(
			JSON.stringify({
				steps: { checkout: { outcome: "success" }, native: { outcome: "failure" } },
			}),
			data.reviver,
		);
		assert.ok(context instanceof data.Dictionary);
		assert.equal(
			new Evaluator(parsed, context, functions).evaluate().coerceString(),
			String(!cancelled),
		);
	}
	assert.equal(
		namedStep(workflow, worker, "Validate workflows with GitHub's parser").get("if"),
		"needs.detect-changes.outputs.should_skip != 'true'",
	);
});

void test("the focused stable task selects every architecture ratchet and requires an explicit version", async () => {
	const build = await readFile("server/application/build.gradle.kts", "utf8");
	const task = build.slice(
		build.indexOf('tasks.register<Test>("releaseRatchetTest")'),
		build.indexOf("tasks.jacocoTestReport"),
	);
	assert.match(task, /selectTestTier\("architecture & release-ratchet"\)/u);
	assert.match(task, /failOnNoDiscoveredTests\.set\(true\)/u);
	assert.match(task, /require\(!plannedVersion\.isNullOrBlank\(\)\)/u);
	assert.match(
		build,
		/releaseVersion\.orNull\?\.let \{ systemProperty\("hephaestus.releaseVersion", it\) \}/u,
	);
	const directory = "server/application/src/test/java/de/tum/cit/aet/hephaestus/architecture";
	const clients: string[] = [];
	for (const file of await readdir(directory)) {
		if (!file.endsWith(".java")) {
			continue;
		}
		const source = await readFile(`${directory}/${file}`, "utf8");
		if (
			!source.includes("ReleaseRatchet.assertEmptyForRelease(") ||
			source.includes("extends BaseUnitTest")
		) {
			continue;
		}
		clients.push(file);
		for (const call of source.matchAll(/ReleaseRatchet\.assertEmptyForRelease\(/gu)) {
			const testStart = source.lastIndexOf("\n    @Test", call.index);
			assert.ok(testStart !== -1, `${file}: a release ratchet must be a test`);
			const method = source.slice(testStart, call.index);
			assert.doesNotMatch(
				method,
				/\n {4}\}/u,
				`${file}: the preceding test must contain the ratchet`,
			);
			assert.match(
				method.slice(0, method.indexOf("{")),
				/\n {4}@Tag\("release-ratchet"\)/u,
				`${file}: tag the method that invokes the release ratchet`,
			);
		}
	}
	assert.ok(clients.includes("AfterCommitRepairArchTest.java"));
});
