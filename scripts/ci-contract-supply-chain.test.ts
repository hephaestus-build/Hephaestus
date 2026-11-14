import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";
import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { isMap, isSeq, visit } from "yaml";
import {
	escapeRegExp,
	exceptionFault,
	hasWorkflowTrigger,
	job,
	namedStep,
	parseDocument,
	pathFilter,
	posixGlob,
	readSources,
	step,
	VP_INVOCATION,
	workflowSources,
} from "./ci-contract-support.ts";
import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void describe("ci-contract: supply-chain", () => {
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
			assert.match(patterns, new RegExp(`- '${escapeRegExp(workflow)}'`, "u"));
			assert.match(patterns, /- '\.github\/workflows\/cicd\.yml'/u);
			assert.doesNotMatch(patterns, /- '\.github\/workflows\/\*\*'/u);
			assert.match(source, new RegExp(`outputs\\.${escapeRegExp(filter)} == 'true'`, "u"));
		}
	});

	void test("submits dependency snapshot data without executing the originating checkout", async () => {
		const source = await readFile(".github/workflows/submit-dependency-graph.yml", "utf8");
		const workflow = parseDocument(source);
		const upstream = parseDocument(
			await readFile(".github/workflows/dependency-graph.yml", "utf8"),
		);
		assert.equal(workflow.getIn(["on", "workflow_run", "workflows", 0]), upstream.get("name"));
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
			/uses: actions\/checkout|uses: \.\/|run:.*(?:gradlew|node|bash|curl)/u,
		);
		assert.equal(
			namedStep(workflow, ["jobs", "submit"], "Require the submitted snapshot").get("run"),
			"test -s dependency-graph-reports/server.json",
		);
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
		for (const event of ["branch_protection_rule", "schedule", "push"]) {
			assert.equal(workflow.hasIn(["on", event]), true);
		}
		assert.equal(workflow.getIn(["jobs", "analysis", "permissions", "id-token"]), "write");
		assert.equal(workflow.getIn(["jobs", "analysis", "permissions", "security-events"]), "write");
		const jobPath = ["jobs", "analysis"];
		assert.equal(step(workflow, jobPath, "actions/checkout").get("persist-credentials"), false);
		assert.equal(step(workflow, jobPath, "ossf/scorecard-action").get("publish_results"), true);
	});

	void test("pins every external action to a full commit SHA", async () => {
		const invalid: string[] = [];
		for (const [file, source] of await readSources(
			await posixGlob(".github/{actions,workflows}/**/*.{yml,yaml}"),
		)) {
			for (const [index, line] of source.split("\n").entries()) {
				const uses = /^\s+(?:- )?uses:\s+(?<reference>\S+)(?:.*)$/u.exec(line);
				if (!uses) {
					continue;
				}
				const reference = uses.groups?.reference;
				assert.ok(reference !== undefined);
				if (reference.startsWith("./")) {
					continue;
				}
				if (!/@[0-9a-f]{40}$/u.test(reference)) {
					invalid.push(`${file}:${index + 1}: ${reference}`);
				}
			}
		}
		assert.deepEqual(invalid, [], "External uses must match @<40 lowercase hex>");
	});

	void test("centralises dependency installation and browser setup", async () => {
		const sources = await workflowSources();
		for (const [file, source] of sources) {
			assert.doesNotMatch(
				source,
				/pnpm install --frozen-lockfile/u,
				`${file} must install through setup-toolchain`,
			);
			visit(parseDocument(source), {
				Map(_key, node) {
					if (node.get("uses") !== "./.github/actions/setup-toolchain") {
						return;
					}
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
		// One setup per job that runs a browser: the two E2E suites, Storybook, and the extension leg.
		for (const [file, browserJobs] of [
			[".github/workflows/ci-browser-tests.yml", 2],
			[".github/workflows/ci-quality-gates.yml", 2],
		] as const) {
			const source = sources.get(file);
			assert.ok(isSet(source));
			assert.equal(
				(source.match(/uses: \.\/\.github\/actions\/setup-browsers/gu) ?? []).length,
				browserJobs,
				file,
			);
			assert.doesNotMatch(source, /playwright install chromium/u);
		}
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
				if (statement.startsWith("#")) {
					continue;
				}
				const command = /\bgit push\b.*/u.exec(statement)?.[0];
				if (command === undefined || /\s--dry-run(?:\s|$)/u.test(command)) {
					continue;
				}
				pushes.push(`${file}:${index + 1}: ${command}`);
			}
		}
		assert.deepEqual(pushes, [], "A workflow commits through scripts/commit-via-api.ts");
	});

	void test("checks out no ref taken straight from a pull_request_target or workflow_run event", async () => {
		// Scorecard's Dangerous-Workflow rule, checked here because Scorecard itself runs only after merge.
		for (const [file, source] of await workflowSources()) {
			if (!/^ {2}(?:pull_request_target|workflow_run):/mu.test(source)) {
				continue;
			}
			assert.doesNotMatch(
				source,
				/^ +ref:.*github\.event\.(?:pull_request|workflow_run)/mu,
				`${file} checks out a ref taken from the triggering event`,
			);
		}
		// The release jobs check out the run's commit through an output; this is what makes it safe.
		assert.match(
			job(await readFile(".github/workflows/release.yml", "utf8"), "release"),
			/git merge-base --is-ancestor "\$SHA" origin\/main/u,
		);
	});

	void test("resolves every commit's author without checking out the pull request", async () => {
		const identity = job(
			await readFile(".github/workflows/pull-request-policy.yml", "utf8"),
			"validate-pr",
		);
		// The workflow_run trigger is justified to Zizmor by the claim that it checks out and runs no
		// pull-request code; a job that reads the pull request's own commits is where that slips.
		assert.match(identity, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/u);
		assert.match(identity, /uses: actions\/github-script@/u);
	});

	// GitHub blocks pull_request_target in public repositories by default. The pull request policy
	// records on pull_request and judges on workflow_run instead; a new pull_request_target would
	// also be a Zizmor dangerous-triggers finding, which this test keeps an ignore comment from hiding.
	void test("the dangerous-trigger guard covers every YAML trigger form", () => {
		for (const trigger of [
			"pull_request_target",
			"[push, pull_request_target]",
			"{ pull_request_target: {} }",
		]) {
			assert.equal(
				hasWorkflowTrigger(parseDocument(`on: ${trigger}`), "pull_request_target"),
				true,
				trigger,
			);
		}
		for (const trigger of ["push", "[push, pull_request]", "{ pull_request: {} }"]) {
			assert.equal(
				hasWorkflowTrigger(parseDocument(`on: ${trigger}`), "pull_request_target"),
				false,
				trigger,
			);
		}
	});

	void test("the dangerous-trigger guard resolves YAML aliases", () => {
		for (const source of [
			"name: &event pull_request_target\non: *event",
			"name: &events [push, pull_request_target]\non: *events",
		]) {
			assert.equal(hasWorkflowTrigger(parseDocument(source), "pull_request_target"), true);
		}
		assert.equal(
			hasWorkflowTrigger(parseDocument("name: &event push\non: *event"), "pull_request_target"),
			false,
		);
	});

	void test("no workflow runs on pull_request_target", async () => {
		for (const [file, source] of await workflowSources()) {
			assert.equal(hasWorkflowTrigger(parseDocument(source), "pull_request_target"), false, file);
		}
	});

	void test("never invokes a repository-local action before checkout", async () => {
		for (const [file, source] of await workflowSources()) {
			for (const jobSource of source.split(/^ {2}(?=[A-Za-z][\w-]*:\s*$)/mu).slice(1)) {
				const firstLocalAction = jobSource.indexOf("uses: ./.github/actions/");
				if (firstLocalAction === -1) {
					continue;
				}
				const checkout = jobSource.indexOf("uses: actions/checkout@");
				assert.ok(
					checkout !== -1 && checkout < firstLocalAction,
					`${file} invokes a local action before checking it out`,
				);
			}
		}
	});
});

void test("private dependency and platform reports run only on the default branch", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/rescan-images.yml", "utf8"));
	const condition = workflow.getIn(["jobs", "dependencies-and-platform", "if"]);
	const source = condition === undefined ? "true" : asString(condition, "private scan condition");
	const parsed = new Parser(new Lexer(source).lex().tokens, ["github"], []).parse();
	for (const [defaultBranch, ref, expected] of [
		["main", "refs/heads/main", true],
		["main", "refs/heads/topic", false],
		["trunk", "refs/heads/trunk", true],
		["trunk", "refs/heads/main", false],
		["main", "refs/pull/7/merge", false],
		["main", "refs/tags/v1.0.0", false],
	] as const) {
		const context: unknown = JSON.parse(
			JSON.stringify({ github: { ref, event: { repository: { default_branch: defaultBranch } } } }),
			data.reviver,
		);
		assert.ok(context instanceof data.Dictionary);
		assert.equal(
			new Evaluator(parsed, context).evaluate().coerceString() === "true",
			expected,
			ref,
		);
	}
});

void test("checkouts never retain Git credentials", async () => {
	for (const file of await posixGlob(".github/{workflows/*.yml,actions/*/action.yml}")) {
		visit(parseDocument(await readFile(file, "utf8")), {
			Map(_key, node) {
				const uses = node.get("uses");
				if (typeof uses === "string" && uses.startsWith("actions/checkout@")) {
					assert.equal(
						node.getIn(["with", "persist-credentials"]),
						false,
						`${file}: checkout credential persistence must be explicit`,
					);
				}
			},
		});
	}
});

void test("the task graph keeps its cache posture", async () => {
	const tasks = await loadTasks();
	for (const entry of ["check", "verify", "quality", "verification"]) {
		assert.ok(entry in tasks, `${entry} must be a task`);
	}
	for (const [name, task] of Object.entries(tasks)) {
		if (!isRecord(task)) {
			continue;
		}
		const commands = commandsOf(task);
		// The runner never caches a command that delegates (scripts/check-runner-contract.ts).
		// vite.config.ts rejects the literal form, so only a command widened to string reaches here.
		if (task.cache !== false) {
			for (const command of commands) {
				assert.doesNotMatch(command, /\bvp run\b/u, `${name} must own its command to cache`);
				assert.doesNotMatch(command, /\bvp exec\b/u, `${name} cannot cache through vp exec`);
			}
		}
		// Shell parameter expansion is outside the runner contract (scripts/check-runner-contract.ts).
		for (const command of commands) {
			assert.doesNotMatch(command, /\$/u, `${name} must not rely on shell expansion`);
		}
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
		/run-gradlew\.ts.*:spotlessCheck.*:application:spotlessCheck.*:application:pmdMain/u,
	);
	assert.equal(tasks["prepare:server:generated"], undefined, "Gradle owns generation dependencies");
	for (const entry of ["gate:server", "format:java:check", "lint:java"]) {
		assert.equal(
			asRecord(tasks[entry], entry).cache,
			false,
			`${entry} delegates caching to Gradle`,
		);
	}
	const docsLintCache = asRecord(
		asRecord(tasks["gate:docs-lint"], "gate:docs-lint").cache,
		"gate:docs-lint cache",
	);
	assert.ok(Array.isArray(docsLintCache.input));
	assert.equal(
		asRecord(tasks["gate:changesets"], "gate:changesets").cache,
		false,
		"pending note additions and edits must be read on every local check",
	);
	assert.match(commandsOf(tasks["gate:changesets"]).join(" "), /verify-changesets\.ts --local/u);

	for (const [file, source] of await workflowSources()) {
		assert.doesNotMatch(
			source,
			/^\s*(?:run:\s*)?(?:vp exec )?(?:vite|vitest|oxlint|oxfmt)(?:\s|$)/mu,
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
		if (!isMap(jobs)) {
			continue;
		}
		for (const pair of jobs.items) {
			const jobName = String(pair.key);
			const jobDefinition = pair.value;
			if (!isMap(jobDefinition)) {
				continue;
			}
			const steps = jobDefinition.get("steps");
			if (!isSeq(steps)) {
				continue;
			}
			let install: string | undefined;
			let callsVp = false;
			let installsItself = false;
			for (const item of steps.items) {
				if (!isMap(item)) {
					continue;
				}
				const uses = item.get("uses");
				if (uses === "./.github/actions/setup-toolchain") {
					const inputs = item.get("with");
					install = isMap(inputs) ? String(inputs.get("install")) : undefined;
				}
				const run = item.get("run");
				if (typeof run === "string" && /\bvp install\b/u.test(run)) {
					installsItself = true;
				}
				// An invocation starts a command; `vp run …` quoted in a message for the summary does not.
				if (typeof run === "string" && VP_INVOCATION.test(run.replaceAll(/\\?`[^`]*\\?`/gu, ""))) {
					callsVp = true;
				}
			}
			if (callsVp && install !== "frozen" && !installsItself) {
				offenders.push(`${file}#${jobName} (install: ${install ?? "no setup-toolchain step"})`);
			}
		}
	}
	assert.deepEqual(offenders, [], "A job that calls vp must install dependencies first");
});

void test("the Scorecard ratchet runs on every event that can move a score, outside publishing", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/scorecard-ratchet.yml", "utf8"));
	for (const trigger of ["push", "branch_protection_rule", "schedule", "workflow_dispatch"]) {
		assert.ok(workflow.hasIn(["on", trigger]), `the ratchet must run on ${trigger}`);
	}
	assert.equal(workflow.getIn(["jobs", "ratchet", "permissions", "contents"]), "read");
	assert.equal(workflow.hasIn(["jobs", "ratchet", "permissions", "id-token"]), false);
	assert.equal(
		step(workflow, ["jobs", "ratchet"], "actions/upload-artifact").get("path"),
		"tmp/scorecard-assessment.json",
	);
	assert.match(
		pathFilter(await readFile(".github/workflows/cicd.yml", "utf8"), "tooling"),
		/security\/scorecard-baseline\.json/u,
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
			/^(?:actions\/(?:checkout|upload-artifact)|github\/codeql-action\/upload-sarif|ossf\/scorecard-action)@/u,
		);
	}
});

void test("every Semgrep rule ships a positive and a negative fixture", async () => {
	const files = await posixGlob("security/semgrep/*");
	const rulesets = files.filter((file) => file.endsWith(".yaml"));
	assert.ok(rulesets.length > 0, "security/semgrep holds no ruleset");
	const fixtureSources = await Promise.all(
		files.filter((file) => !file.endsWith(".yaml")).map(async (file) => readFile(file, "utf8")),
	);
	const fixtures = fixtureSources.join("\n");
	for (const file of rulesets) {
		const ruleset = asRecord(parseDocument(await readFile(file, "utf8")).toJSON(), file);
		const ids = asArray(ruleset.rules, `${file} rules`).map((rule) =>
			asString(asRecord(rule, "rule").id, "rule.id"),
		);
		assert.ok(ids.length > 0, `${file} declares no rule`);
		for (const id of ids) {
			assert.match(fixtures, new RegExp(`ruleid: ${id}$`, "mu"), `${id} has no violating example`);
			assert.match(fixtures, new RegExp(`ok: ${id}$`, "mu"), `${id} has no compliant example`);
		}
	}
});

void test("global styles and assets retain full-snapshot invalidation", async () => {
	const config: unknown = JSON.parse(await readFile("webapp/chromatic.config.json", "utf8"));
	assert.ok(
		config !== null &&
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
	]) {
		assert.ok(config.externals.includes(pattern), `${pattern} must invalidate snapshots`);
	}
});

void test("CI does not run CodeQL analysis or retain extraction-only compiler exceptions", async () => {
	assert.equal(existsSync(".github/workflows/codeql.yml"), false);
	assert.equal(existsSync(".github/codeql/codeql-config.yml"), false);
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.equal(workflow.hasIn(["jobs", "CodeQL"]), false);
	for (const file of await posixGlob(".github/workflows/*.yml")) {
		const source = await readFile(file, "utf8");
		assert.doesNotMatch(
			source,
			/github\/codeql-action\/(?:init|analyze)@|workflows\/codeql\.yml|needs\.CodeQL/u,
		);
	}
	for (const file of ["server/build.gradle.kts", "server/application/build.gradle.kts"]) {
		assert.doesNotMatch(await readFile(file, "utf8"), /codeqlExtraction/u);
	}
	const build = await readFile("server/application/build.gradle.kts", "utf8");
	assert.match(build, /error\(\s*"NullAway",\s*"RequireExplicitNullMarking"[,)]/u);
	assert.match(build, /"-Werror"/u);
});
