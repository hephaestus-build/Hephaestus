import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { runInNewContext } from "node:vm";
import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { minimatch } from "minimatch";
import { isMap, isScalar, isSeq, visit } from "yaml";
import {
	ACTIONS_WRITE_GRANTS,
	bashRunsRunnerSteps,
	CANCEL_WORKFLOW,
	CI_ONLY_GATES,
	clusterLegs,
	clusterMatrix,
	evaluateLeg,
	evaluateTierOutput,
	expandExpressions,
	gates,
	HEAVY_LEGS,
	integrationShards,
	invokedTasks,
	isCancelWorker,
	job,
	jobRuns,
	jobsOf,
	namedStep,
	needsClosure,
	needsOf,
	parseDocument,
	pathFilter,
	posixGlob,
	readSources,
	render,
	REPLACEABLE_MAIN_RUNS,
	runnerStepsOnly,
	runScript,
	runStep,
	SERVER_TIERS,
	serverShards,
	simulateRun,
	step,
	stepInputs,
	taskClosure,
	UNCANCELLED_PULL_REQUEST_RUNS,
	validationConcurrencyScopes,
	versionBranch,
	workflowSources,
	writesActions,
} from "./ci-contract-support.ts";
import { isSet } from "./lib/env.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { committedShards } from "./lib/integration-durations.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { loadTasks } from "./lib/task-graph.ts";
import { QUEUE_TIER_JOBS } from "./report-ci-latency.ts";
import { resolveAliasBase } from "./resolve-alias-base.ts";

void describe("ci-contract: merge-queue", () => {
	void test("every local gate runs in a workflow, and every CI gate is a local gate", async () => {
		const tasks = await loadTasks();
		const local = taskClosure(tasks, ["quality"]);
		const ciRoots = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			if (!isMap(jobs)) {
				continue;
			}
			for (const entry of jobs.items) {
				if (!isMap(entry.value)) {
					continue;
				}
				for (const name of invokedTasks(entry.value)) {
					assert.ok(name in tasks, `${file} runs ${name}, which is not a task`);
					ciRoots.add(name);
				}
			}
		}
		const ci = taskClosure(tasks, ciRoots);
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

	void test("a pull request runs the fast tier, and every other event also runs the queue tier", async () => {
		const orchestratorSource = await readFile(".github/workflows/cicd.yml", "utf8");
		const orchestrator = parseDocument(orchestratorSource);
		const decision = String(
			orchestrator.getIn(["jobs", "detect-changes", "outputs", "queue-tier"]),
		);
		const skip = String(orchestrator.getIn(["jobs", "detect-changes", "outputs", "should_skip"]));
		// `full` is the live label read; undefined is a read that failed and wrote no output.
		for (const [event, full, queue, skipped] of [
			["pull_request", "false", "false", "true"],
			["pull_request", "true", "true", "false"],
			["pull_request", undefined, "true", "false"],
			["merge_group", undefined, "true", "false"],
			["push", undefined, "true", "false"],
			["workflow_dispatch", undefined, "true", "false"],
		] as const) {
			const label = full ?? "unreadable";
			assert.equal(
				evaluateTierOutput(decision, event, { full }),
				queue,
				`${event} with ci:full ${label}`,
			);
			// A duplicate is skipped only without the opt-in, so a re-run after the label runs the tier.
			// A read that succeeds also reports the preview label, here absent.
			const preview = full === undefined ? undefined : "false";
			assert.equal(
				evaluateTierOutput(skip, event, { full, preview }),
				skipped,
				`duplicate skip, ${event} ${label}`,
			);
		}
		// A layer of a stack runs the light tier: no queue tier, even when its inputs changed.
		assert.equal(
			evaluateTierOutput(decision, "pull_request", {
				full: "false",
				light: "true",
				filter: { migrations: "true" },
			}),
			"false",
		);
		const tests = String(orchestrator.getIn(["jobs", "detect-changes", "outputs", "server-tests"]));
		assert.equal(
			evaluateTierOutput(tests, "pull_request", {
				light: "true",
				filter: { "application-server": "true" },
			}),
			"false",
		);
		assert.equal(
			evaluateTierOutput(tests, "pull_request", {
				light: "false",
				filter: { "application-server": "true" },
			}),
			"true",
		);
		// A re-run, such as the one after a retarget, is never skipped as a duplicate.
		assert.equal(
			evaluateTierOutput(skip, "pull_request", { full: "false", preview: "false", attempt: 2 }),
			"false",
		);
		// A labelled preview repeats a tree whose earlier run may have published no image.
		assert.equal(
			evaluateTierOutput(skip, "pull_request", { full: "false", preview: "true" }),
			"false",
		);
		// Changing CI, browser specs or migrations does not promote every suite on a PR.
		for (const filter of ["migrations", "test-config", "build-config", "e2e", "extension-e2e"]) {
			assert.equal(
				evaluateTierOutput(decision, "pull_request", {
					full: "false",
					filter: { [filter]: "true" },
				}),
				"false",
				filter,
			);
		}
		assert.equal(
			evaluateTierOutput(decision, "pull_request", { full: "false", releaseCandidate: "true" }),
			"false",
			"release evidence does not opt a Version PR into every queue-tier suite",
		);
		assert.equal(orchestratorSource.includes("queue-tier-inputs"), false);

		// The queue tier is exactly these jobs, and the latency report counts them by these names.
		const guarded: string[] = [];
		for (const [file, caller] of [
			[".github/workflows/ci-build.yml", "Build"],
			[".github/workflows/ci-browser-tests.yml", "Browser tests"],
			[".github/workflows/ci-tests.yml", "Server tests"],
		] as const) {
			const callee = parseDocument(await readFile(file, "utf8"));
			const jobs = callee.get("jobs");
			assert.ok(isMap(jobs));
			for (const entry of jobs.items) {
				if (isMap(entry.value) && String(entry.value.get("if")).includes("inputs.queue_tier")) {
					const name = `${caller} / ${String(entry.value.get("name"))}`;
					guarded.push(String(entry.key));
					assert.ok(
						QUEUE_TIER_JOBS.some((pattern) => pattern.test(name)),
						`report-ci-latency.ts does not count ${name} as queue tier`,
					);
				}
			}
		}
		assert.deepEqual(guarded.toSorted(), [
			"extension-e2e",
			"postgres-drill",
			"server-database",
			...Array.from({ length: committedShards() }, (_, index) => `server-integration-${index + 1}`),
			"webapp-e2e",
		]);
		// A pull request packages for the browser suites only when it runs them.
		assert.match(
			String(orchestrator.getIn(["jobs", "server-package", "if"])),
			/\(needs\.detect-changes\.outputs\.queue-tier == 'true' && \(needs\.detect-changes\.outputs\.e2e == 'true' \|\| needs\.detect-changes\.outputs\.extension-e2e == 'true'\)\)/u,
		);

		// A draft runs the fast tier only, unless it carries the label, and builds no image.
		const outputs = (name: string) =>
			String(orchestrator.getIn(["jobs", "detect-changes", "outputs", name]));
		const draft = { draft: true, head: { repo: { full_name: "owner/repo" } } };
		assert.equal(
			evaluateTierOutput(outputs("draft"), "pull_request", { full: "false", pullRequest: draft }),
			"true",
		);
		assert.equal(evaluateTierOutput(outputs("draft"), "pull_request", { full: "false" }), "false");
		assert.equal(
			evaluateTierOutput(outputs("draft"), "merge_group", { pullRequest: draft }),
			"false",
		);
		assert.equal(
			evaluateTierOutput(decision, "pull_request", {
				full: "false",
				filter: { "test-config": "true" },
				pullRequest: draft,
			}),
			"false",
			"a draft that changes the tier's inputs still runs only the fast tier",
		);
		assert.equal(
			evaluateTierOutput(decision, "pull_request", { full: "true", pullRequest: draft }),
			"true",
		);
		// The preview label is an explicit opt-in, and a release candidate always builds its evidence.
		const labelled = { full: "false", preview: "true", pullRequest: draft };
		assert.equal(evaluateTierOutput(outputs("draft"), "pull_request", labelled), "false");
		assert.equal(evaluateTierOutput(outputs("head-images"), "pull_request", labelled), "true");
		assert.equal(
			evaluateTierOutput(outputs("head-images"), "pull_request", {
				full: "false",
				pullRequest: draft,
			}),
			"false",
		);
		// A pull request builds images only for its preview, the Kubernetes jobs or release evidence;
		// the merge group builds every other changed image. An unreadable label builds them, except on
		// a draft. A fork builds none.
		const ready = { head: { repo: { full_name: "owner/repo" } } };
		const fork = { head: { repo: { full_name: "fork/repo" } } };
		for (const [event, run, images, head] of [
			["pull_request", { preview: "false", pullRequest: ready }, "false", "false"],
			["pull_request", { preview: "true", pullRequest: ready }, "true", "true"],
			["pull_request", { pullRequest: ready }, "true", "true"],
			["pull_request", { pullRequest: draft }, "false", "false"],
			[
				"pull_request",
				{ preview: "false", pullRequest: ready, filter: { kubernetes: "true" } },
				"true",
				"true",
			],
			[
				"pull_request",
				{ preview: "false", pullRequest: draft, filter: { kubernetes: "true" } },
				"false",
				"false",
			],
			[
				"pull_request",
				{ preview: "false", pullRequest: ready, releaseCandidate: "true" },
				"true",
				"false",
			],
			[
				"pull_request",
				{ preview: "false", pullRequest: draft, releaseCandidate: "true" },
				"true",
				"false",
			],
			[
				"pull_request",
				{ preview: "true", pullRequest: fork, filter: { kubernetes: "true" } },
				"false",
				"false",
			],
			["merge_group", {}, "true", "false"],
			["push", {}, "true", "false"],
			["workflow_dispatch", {}, "true", "false"],
		] as const) {
			const label = `${event} ${JSON.stringify(run)}`;
			assert.equal(evaluateTierOutput(outputs("images"), event, run), images, `images: ${label}`);
			assert.equal(
				evaluateTierOutput(outputs("head-images"), event, run),
				head,
				`head-images: ${label}`,
			);
		}
		assert.equal(
			evaluateTierOutput(outputs("draft"), "pull_request", {
				full: "false",
				pullRequest: draft,
				releaseCandidate: "true",
			}),
			"false",
		);
		// Marking a draft ready repeats its tree, so it must not be skipped as a duplicate.
		assert.equal(
			evaluateTierOutput(skip, "pull_request", { full: "false", action: "ready_for_review" }),
			"false",
		);
		for (const name of ["vulnerability-database", "upstream-images", "Docker"]) {
			assert.match(
				String(orchestrator.getIn(["jobs", name, "if"])),
				/needs\.detect-changes\.outputs\.draft != 'true'/u,
				`${name} skips drafts`,
			);
		}
		// Marking a draft ready starts the run it skipped.
		const types = orchestrator.getIn(["on", "pull_request", "types"]);
		assert.ok(isSeq(types));
		assert.deepEqual(types.toJSON(), ["opened", "synchronize", "reopened", "ready_for_review"]);
		const optIn = namedStep(orchestrator, ["jobs", "detect-changes"], "Read the opt-in labels");
		assert.equal(optIn.get("continue-on-error"), true);
		assert.match(
			String(optIn.get("run")),
			/gh api "repos\/\$GITHUB_REPOSITORY\/pulls\/\$PR_NUMBER"/u,
		);
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		// The event payload keeps the labels of the push, so a re-run would not see the opt-in.
		assert.doesNotMatch(source, /github\.event\.pull_request\.labels/u);
		assert.doesNotMatch(
			JSON.stringify(orchestrator.getIn(["on", "pull_request"]) ?? {}),
			/labeled/u,
		);
		for (const caller of ["Build", "Browser-tests", "Test"]) {
			assert.equal(
				orchestrator.getIn(["jobs", caller, "with", "queue_tier"]),
				`\${{ needs.detect-changes.outputs.queue-tier }}`,
				`${caller} receives the tier decision`,
			);
		}
		for (const [file, queue, fast] of [
			[".github/workflows/ci-build.yml", ["server-database", "postgres-drill"], ["server-api"]],
			[".github/workflows/ci-browser-tests.yml", ["webapp-e2e", "extension-e2e"], []],
			[
				".github/workflows/ci-tests.yml",
				Array.from({ length: committedShards() }, (_, index) => `server-integration-${index + 1}`),
				["server-verification", "server-architecture", "server-integration"],
			],
		] as const) {
			const workflow = parseDocument(await readFile(file, "utf8"));
			assert.equal(
				workflow.getIn(["on", "workflow_call", "inputs", "queue_tier", "required"]),
				true,
			);
			for (const name of queue) {
				assert.match(
					String(workflow.getIn(["jobs", name, "if"])),
					name === "server-database" || name === "postgres-drill"
						? /&& \(inputs\.queue_tier == 'true' \|\| inputs\.migrations_changed == 'true'\)$/u
						: /&& inputs\.queue_tier == 'true'$/u,
					`${name} belongs to the queue tier`,
				);
			}
			for (const name of fast) {
				assert.doesNotMatch(
					String(workflow.getIn(["jobs", name, "if"])),
					/queue_tier/u,
					`${name} belongs to the fast tier`,
				);
			}
		}
	});

	void test("test reporters stop on cancellation and tolerate absent diagnostic files", async () => {
		const reporters = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			visit(parseDocument(source), {
				Map(_key, declaration) {
					if (!String(declaration.get("uses")).startsWith("dorny/test-reporter@")) {
						return;
					}
					const inputs = stepInputs(declaration);
					const name = asString(inputs.get("name"), "report name");
					reporters.add(`${file}: ${name}`);
					for (const status of ["success", "failure", "cancelled"]) {
						assert.equal(
							expandExpressions(inputs.get("fail-on-empty"), { job: { status } }),
							String(name !== "Test Results - Chromatic" && status === "success"),
							`${file}: ${name}, ${status}: passing jobs still need reports`,
						);
					}
					assert.equal(inputs.get("fail-on-error"), false, `${file}: ${name}`);
					const expression = asString(declaration.get("if"), "report condition").replaceAll(
						/^\$\{\{\s*|\s*\}\}$/gu,
						"",
					);
					for (const cancelled of [false, true]) {
						const functions = [
							{ name: "always", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(true) },
							{
								name: "cancelled",
								minArgs: 0,
								maxArgs: 0,
								call: () => new data.BooleanData(cancelled),
							},
						];
						const parsed = new Parser(
							new Lexer(expression).lex().tokens,
							["env", "steps"],
							functions,
						).parse();
						const context: unknown = JSON.parse(
							JSON.stringify({
								env: { QUALITY_LEG: "webapp" },
								steps: {
									chromatic: { outputs: { storybookUrl: "https://example.test/storybook" } },
								},
							}),
							data.reviver,
						);
						assert.ok(context instanceof data.Dictionary);
						assert.equal(
							new Evaluator(parsed, context, new Map(functions.map((entry) => [entry.name, entry])))
								.evaluate()
								.coerceString(),
							String(!cancelled),
							`${file}: ${name}, cancelled=${String(cancelled)}`,
						);
					}
				},
			});
		}
		assert.equal(reporters.size, 6);
	});

	void test("runs the integration tier once, under the status gate, off an untemplated command", async () => {
		const source = await readFile(".github/workflows/ci-tests.yml", "utf8");
		const workflow = parseDocument(source);
		const caller = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.equal(caller.hasIn(["jobs", "Test", "strategy"]), false);
		assert.equal(workflow.hasIn(["jobs", "server-integration", "strategy"]), false);
		const jobPath = ["jobs", "server-integration"];
		const run = namedStep(workflow, jobPath, "Run integration tests");
		const environment = run.get("env");
		assert.ok(isMap(environment));
		assert.match(
			String(environment.get("HEPHAESTUS_INTEGRATION_SHARD")),
			/env\.INTEGRATION_SHARD/u,
			"The shard's selector reaches the task as an environment value",
		);
		const steps = workflow.getIn([...jobPath, "steps"]);
		assert.ok(isSeq(steps));
		const postgres = steps.items.find(
			(item) => isMap(item) && item.get("uses") === "./.github/actions/postgres-image",
		);
		assert.ok(isMap(postgres), "Integration shards resolve the published PostgreSQL image");
		assert.equal(
			environment.get("HEPHAESTUS_TEST_POSTGRES_IMAGE"),
			stepInputs(postgres).get("tag"),
			"Testcontainers starts the image that the job resolved",
		);
		// A broken hand-off would rebuild the Dockerfile in every shard and still pass.
		const property = "hephaestus.test.postgres-image";
		const gradle = await readFile("server/application/build.gradle.kts", "utf8");
		assert.match(gradle, /providers\.environmentVariable\("HEPHAESTUS_TEST_POSTGRES_IMAGE"\)/u);
		assert.ok(gradle.includes(`systemProperty("${property}", testPostgresImage.get())`));
		const container = await readFile(
			"server/application/src/test/java/de/tum/cit/aet/hephaestus/testconfig/PostgreSQLTestContainer.java",
			"utf8",
		);
		assert.ok(container.includes(`System.getProperty("${property}"`));
		assert.doesNotMatch(
			runScript(workflow, jobPath, "Run integration tests"),
			/\$\{\{/u,
			"The selector reaches Gradle through the environment, never through a run: script",
		);
		assert.equal((source.match(/run: vp run test:server:integration/gu) ?? []).length, 1);
		assert.equal(
			namedStep(workflow, jobPath, "Upload test results").getIn(["with", "name"]),
			`Test Results - Integration (\${{ env.INTEGRATION_SHARD == 'review' && 'review' || format('{0} of {1}', env.INTEGRATION_SHARD, env.INTEGRATION_COUNT) }})`,
		);
		const tasks = await readFile("vite.config.ts", "utf8");
		assert.match(
			await readFile("server/application/build.gradle.kts", "utf8"),
			/HEPHAESTUS_INTEGRATION_SHARD/u,
		);
		assert.match(tasks, /"test:server:integration": run\([\s\S]*?:application:integrationTest/u);
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		assert.match(job(orchestrator, "Test"), /uses: \.\/\.github\/workflows\/ci-tests.yml/u);
		assert.ok(needsOf(parseDocument(orchestrator), "all-ci-passed").includes("Test"));
	});

	void test("image consumers do not wait for unrelated artifact checks, but the final verdict does", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const build = workflow.getIn(["jobs", "Build", "needs"]);
		assert.ok(isSeq(build));
		assert.deepEqual(build.toJSON(), ["detect-changes", "server-package"]);
		const docker = workflow.getIn(["jobs", "Docker", "needs"]);
		assert.ok(isSeq(docker));
		assert.deepEqual(docker.toJSON(), [
			"detect-changes",
			"vulnerability-database",
			"server-package",
			"Security",
		]);
		for (const name of ["Release-preflight"]) {
			const dependencies = workflow.getIn(["jobs", name, "needs"]);
			assert.ok(isSeq(dependencies));
			assert.deepEqual(dependencies.toJSON(), [
				"detect-changes",
				"Docker",
				...(name === "Release-preflight" ? ["vulnerability-database"] : []),
			]);
		}
		const gate = workflow.getIn(["jobs", "all-ci-passed", "needs"]);
		assert.ok(isSeq(gate));
		for (const name of [
			"server-package",
			"Build",
			"Test",
			"Docker",
			"Kubernetes",
			"Release-preflight",
		]) {
			assert.ok(
				gate.items.some((item) => isScalar(item) && item.value === name),
				`The final verdict must require ${name}`,
			);
		}
	});

	void test("reports a failed merge-group run on its pull request, and only a failure", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const reporter = job(source, "report-unsuccessful-merge-group");
		assert.match(reporter, /needs: \[all-ci-passed\]/u);
		// A merge queue cancels the runs it regroups, which says nothing about the pull request. A
		// fail-fast cancel makes `cancelled()` true for the whole run, so only the gate's verdict tells
		// the run that failed from the run that a regroup dissolved.
		const workflow = parseDocument(source);
		const condition = asString(
			workflow.getIn(["jobs", "report-unsuccessful-merge-group", "if"]),
			"reporter condition",
		);
		assert.equal(
			condition,
			`\${{ always() && github.event_name == 'merge_group' && needs.all-ci-passed.outputs.verdict == 'failure' }}`,
		);
		assert.doesNotMatch(condition, /cancelled\(\)/u);
		// A leg can still fail while a regroup cancels the run: the comment waits until the queue has
		// dropped the entry, which a regroup never does.
		const reporterJob = asRecord(
			asRecord(asRecord(workflow.toJSON(), "cicd.yml").jobs, "cicd.yml jobs")[
				"report-unsuccessful-merge-group"
			],
			"reporter",
		);
		const steps = asArray(reporterJob.steps, "reporter steps");
		const lookup = asRecord(asRecord(steps[0], "reporter lookup").with, "reporter lookup with");
		assert.match(String(lookup.script), /mergeQueueEntry \{ id \}/u);
		assert.match(String(lookup.script), /mergeQueueEntry === null/u);
		assert.equal(
			asRecord(steps[1], "reporter comment").if,
			"steps.pull-request.outputs.number != ''",
		);
		const gate = ["jobs", "all-ci-passed"];
		assert.equal(
			workflow.getIn([...gate, "outputs", "verdict"]),
			`\${{ steps.evaluate.outputs.status }}`,
		);
		assert.equal(namedStep(workflow, gate, "Evaluate CI results").get("id"), "evaluate");
		for (const event of ["merge_group", "pull_request", "push", "workflow_dispatch"]) {
			for (const verdict of ["failure", "cancelled", "success", ""]) {
				for (const cancelled of [false, true]) {
					const result = verdict === "success" ? "success" : "failure";
					assert.equal(
						jobRuns(
							workflow,
							"report-unsuccessful-merge-group",
							event,
							{ "all-ci-passed": { result, outputs: { verdict } } },
							cancelled,
						),
						event === "merge_group" && verdict === "failure",
						`${event}, verdict ${verdict || "unset"}, run cancelled: ${String(cancelled)}`,
					);
				}
			}
		}
		// After a fail-fast cancel the gate publishes no `All CI Passed` status. No ruleset requires
		// that status: the required verdict is the `CI Status Gate` check itself.
		assert.match(
			String(namedStep(workflow, gate, "Create commit status").get("if")),
			/!cancelled\(\)/u,
		);
		assert.match(reporter, /pull-requests: write/u);
		// A ref the job cannot read is a missing report, so it fails rather than commenting nowhere.
		assert.match(reporter, /core\.setFailed/u);
	});

	void test("a merge group selects its jobs by the paths its own diff touches", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const workflow = parseDocument(source);

		// `!= 'pull_request'` reads every other event as "run everything", so it opts each new event
		// into the whole workflow. The ban cannot cover `detect-changes`, which holds the two outputs
		// that do test the event, so the decision every filtered job reads is pinned on its own.
		assert.doesNotMatch(
			source.replace(job(source, "detect-changes"), ""),
			/github\.event_name != 'pull_request'/u,
		);
		assert.match(
			String(workflow.getIn(["jobs", "detect-changes", "outputs", "unfiltered"])),
			/^\$\{\{ github\.event_name == 'push' \|\| github\.event_name == 'workflow_dispatch' \}\}$/u,
		);

		// Asserted on the condition, not the whole job: a `with:` input the condition never reads
		// must not satisfy it.
		for (const name of ["workflow-lint", "Quality", "server-package", "Security", "Docker"]) {
			assert.match(
				String(workflow.getIn(["jobs", name, "if"])),
				/needs\.detect-changes\.outputs\.unfiltered == 'true'/u,
				`${name} does not read the path-selection decision`,
			);
		}
		// Test and the package job share one decision, which states the unfiltered events itself.
		assert.match(
			String(workflow.getIn(["jobs", "Test", "if"])),
			/needs\.detect-changes\.outputs\.server-tests == 'true'/u,
		);
		assert.ok(
			String(workflow.getIn(["jobs", "detect-changes", "outputs", "server-tests"])).includes(
				"github.event_name == 'push' || github.event_name == 'workflow_dispatch'",
			),
			"server-tests runs the suites for every unfiltered event",
		);

		// The image set may name one event, the merge group, whose Kubernetes legs install every image.
		assert.deepEqual(
			[
				...String(workflow.getIn(["jobs", "detect-changes", "outputs", "all-images"])).matchAll(
					/github\.event_name (?<operator>[!=]=) '(?<event>\w+)'/gu,
				),
			].map(({ groups }) => `${String(groups?.operator)} ${String(groups?.event)}`),
			["== merge_group"],
		);
		// The caller owns which commit a run is a diff from; `ci-build.yml` derives nothing from
		// the event.
		const browserSource = await readFile(".github/workflows/ci-browser-tests.yml", "utf8");
		const build =
			(await readFile(".github/workflows/ci-build.yml", "utf8")) +
			browserSource.split("jobs:\n")[1];
		assert.doesNotMatch(build, /github\.event\.pull_request\.base\.sha/u);
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
		// Only a pull request aliases, and only from a commit whose push run has published its images.
		assert.equal(
			resolve.getIn(["env", "ALIAS_IMAGES"]),
			`\${{ github.event_name == 'pull_request' }}`,
		);
		assert.match(
			String(orchestrator.getIn([...detect, "outputs", "alias-base"])),
			/^\$\{\{ steps\.alias_base\.outputs\.commit \}\}$/u,
		);

		// A layer's base is the head of an open pull request, which published no image of its own. The
		// walk ends on the commit the default branch contains, and that is what the tagging job aliases.
		const published = "a".repeat(40);
		assert.equal(
			await resolveAliasBase("b".repeat(40), "main", {
				compare: async (base) => (base === published ? "ahead" : "diverged"),
				baseOf: async () => published,
			}),
			published,
		);

		const docker = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const tagging = ["jobs", "tag-unchanged-images"];
		assert.match(
			String(orchestrator.getIn(["jobs", "Docker", "with", "alias_base"])),
			/^\$\{\{ needs\.detect-changes\.outputs\.alias-base \}\}$/u,
		);
		const shell = runScript(docker, tagging, "Verify and tag unchanged images");
		assert.match(shell, /\$ALIAS_BASE/u);
		assert.doesNotMatch(shell, /BASE_SHA/u);

		// A run that reaches no published commit builds every image rather than failing to alias one,
		// which leaves this job nothing to do and the Docker verdict green either way.
		assert.match(
			String(orchestrator.getIn([...detect, "outputs", "all-images"])),
			/steps\.alias_base\.outputs\.commit == ''/u,
		);
		for (const image of [
			"webapp_changed",
			"application_server_changed",
			"agent_images_changed",
			"postgres_image_changed",
		]) {
			assert.match(
				String(orchestrator.getIn(["jobs", "Docker", "with", image])),
				/all-images == 'true'/u,
				`${image} must be built when the run has nothing to alias`,
			);
			assert.match(
				String(docker.getIn([...tagging, "if"])),
				new RegExp(`inputs\\.${image} != 'true'`, "u"),
			);
		}
	});

	void test("the CI gate publishes its own job's status, not the evaluation's verdict", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const publisher = namedStep(workflow, ["jobs", "all-ci-passed"], "Create commit status");
		const dependencies = workflow.getIn(["jobs", "all-ci-passed", "needs"]);
		assert.ok(isSeq(dependencies));
		const complete = Object.fromEntries(
			dependencies.items.map((item) => {
				assert.ok(isScalar(item) && typeof item.value === "string");
				const completion = { result: "success" };
				return [item.value, completion] as const;
			}),
		);
		const [failedDependency, cancelledDependency] = Object.keys(complete);
		assert.ok(isSet(failedDependency) && isSet(cancelledDependency));
		const condition = asString(publisher.get("if"), "commit status condition");
		const expression = /^\$\{\{(?<body>[\s\S]+)\}\}$/u.exec(condition)?.groups?.body;
		assert.ok(isSet(expression));
		const parsed = new Parser(
			new Lexer(expression).lex().tokens,
			["needs"],
			[{ name: "cancelled", minArgs: 0, maxArgs: 0 }],
		).parse();
		// GitHub fills `job.status`, so this pins the wiring and runs the script against each value.
		assert.equal(publisher.getIn(["env", "JOB_STATUS"]), `\${{ job.status }}`);
		const script = String(stepInputs(publisher).get("script"));
		const published = async (
			jobStatus: string | undefined,
			results = complete,
			cancelled = false,
		) => {
			const context: unknown = JSON.parse(JSON.stringify({ needs: results }), data.reviver);
			assert.ok(context instanceof data.Dictionary);
			const functions = new Map([
				[
					"cancelled",
					{
						name: "cancelled",
						minArgs: 0,
						maxArgs: 0,
						call: () => new data.BooleanData(cancelled),
					},
				],
			]);
			const calls: unknown[] = [];
			const sandbox = {
				context: { payload: {}, sha: "a".repeat(40), repo: { owner: "owner", repo: "repo" } },
				github: {
					rest: {
						repos: {
							createCommitStatus: async (payload: unknown) => {
								calls.push(payload);
							},
						},
					},
				},
				process: { env: jobStatus === undefined ? {} : { JOB_STATUS: jobStatus } },
				done: Promise.resolve(),
			};
			if (new Evaluator(parsed, context, functions).evaluate().coerceString() === "true") {
				runInNewContext(`done = (async () => {\n${script}\n})();`, sandbox);
				await sandbox.done;
			}
			if (calls.length === 0) {
				return null;
			}
			assert.equal(calls.length, 1);
			const payload = asRecord(calls[0], "commit status");
			assert.equal(payload.sha, "a".repeat(40));
			return payload.state;
		};
		assert.equal(await published("success"), "success");
		assert.equal(await published("failure"), "failure");
		assert.equal(await published("cancelled"), "error");
		for (const unknown of [undefined, "", "neutral"]) {
			assert.equal(
				await published(unknown),
				"failure",
				`job status ${String(unknown)} is no success`,
			);
		}
		assert.equal(
			await published("success", { ...complete, [cancelledDependency]: { result: "skipped" } }),
			"success",
		);
		assert.equal(
			await published("failure", { ...complete, [failedDependency]: { result: "failure" } }),
			"failure",
		);
		for (const name of Object.keys(complete)) {
			assert.equal(
				await published("failure", { ...complete, [name]: { result: "cancelled" } }),
				null,
			);
		}
		assert.equal(
			await published("failure", {
				...complete,
				[failedDependency]: { result: "failure" },
				[cancelledDependency]: { result: "cancelled" },
			}),
			null,
		);
		assert.equal(await published("failure", complete, true), null);
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
		context.after(async () => rm(directory, { recursive: true, force: true }));
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
		const unknownBase = await detect("0".repeat(40));
		assert.notEqual(unknownBase.status, 0);
		await writeFile(path.join(directory, "package.json"), "{}");
		const unversioned = await detect(base);
		assert.notEqual(unversioned.status, 0);
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
		const detected = async (head: string): Promise<string | undefined> => {
			const result = await runStep(detect, { HEAD_BRANCH: head, MERGE_BASE_SHA: "" });
			return result.outputs["release-candidate"];
		};
		assert.equal(await detected(branch), "true");
		for (const other of [
			"main",
			`${branch}-old`,
			"changeset-release/release-1.0",
			"renovate/vite",
		]) {
			assert.equal(await detected(other), "false", `${other} is not the Version PR's branch`);
		}

		// The gate can only demand a preflight it lets run. On that branch the preflight runs for
		// every event, and a duplicate-run skip must not take the image builds it needs away.
		assert.match(
			String(workflow.getIn(["jobs", "Release-preflight", "if"])),
			/needs\.detect-changes\.outputs\.release-preflight == 'true'/u,
		);
		assert.match(
			String(workflow.getIn([...detection, "outputs", "should_skip"])),
			/steps\.release_candidate\.outputs\.release-candidate != 'true'/u,
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
		assert.equal(
			namedStep(workflow, gate, "Evaluate CI results").getIn(["env", "NEEDS"]),
			`\${{ toJSON(needs) }}`,
		);
		// Narrowed to what the verdict is: the step's own diagnosis is for a failure to carry, not
		// something an expected verdict should have to spell out. `needs` stands for what the runner
		// passes when it differs from what the rendered expressions read.
		const verdict = async (
			results: Record<string, string>,
			onVersionBranch: boolean,
			needs = JSON.stringify(jobsOf({ ...green, ...results })),
			summary?: string,
			queueUpgrade = false,
		) => {
			const run = await runStep(
				render(
					evaluate,
					{ ...green, ...results },
					{ "release-candidate": String(onVersionBranch), "queue-upgrade": String(queueUpgrade) },
				),
				{
					NEEDS: needs,
					MERGE_GROUP: "false",
					...(summary === undefined ? {} : { GITHUB_STEP_SUMMARY: summary }),
				},
			);
			return { failed: run.failed, outputs: run.outputs };
		};
		const passes = { failed: false, outputs: { status: "success" } };
		const fails = { failed: true, outputs: { status: "failure" } };

		// A merge group that changes a migration needs its seeded upgrade to have run and passed.
		assert.deepEqual(
			await verdict({ "Database-upgrade": "skipped" }, false, undefined, undefined, true),
			fails,
		);
		assert.deepEqual(
			await verdict({ "Database-upgrade": "success" }, false, undefined, undefined, true),
			passes,
		);
		assert.deepEqual(await verdict({ "Database-upgrade": "skipped" }, false), passes);

		// A job abandoned before it concluded is neither a failure nor a cancellation, and it is not a
		// success or a skip either.
		const skipped = Object.fromEntries(Object.keys(green).map((name) => [name, "skipped"]));
		const abandoned = {
			...skipped,
			Changesets: "success",
			"detect-changes": "abandoned",
			"gradle-wrapper": "abandoned",
		};
		assert.deepEqual(await verdict(abandoned, false), fails);
		// Each bad result on its own, among jobs that all succeeded, so it is the one the gate refuses.
		assert.deepEqual(await verdict({ "detect-changes": "" }, false), fails);
		const withDetection = (entry: unknown) =>
			JSON.stringify({ ...jobsOf(green), "detect-changes": entry });
		for (const invalid of [
			{ outputs: {} },
			{ result: null },
			{ result: "neutral" },
			{ result: "abandoned" },
			null,
		]) {
			assert.deepEqual(
				await verdict({}, false, withDetection(invalid)),
				fails,
				`${JSON.stringify(invalid)} is no result`,
			);
		}
		for (const needs of ["", "not json", "{}", "[]", `"success"`]) {
			assert.deepEqual(await verdict({}, false, needs), fails, `needs ${needs} judge nothing`);
		}
		// The same jobs with results do pass, skipped ones included.
		assert.deepEqual(await verdict({ ...skipped, Changesets: "success" }, false), passes);
		// A merge group that cancelled itself after a failed leg reports that failure. A regroup that
		// cancelled the run reports a cancellation. The merge-group reporter reads this difference.
		const afterCancel = { Test: "cancelled", Docker: "cancelled" };
		assert.deepEqual(await verdict({ ...afterCancel, Security: "failure" }, false), fails);
		assert.deepEqual(await verdict(afterCancel, false), {
			failed: true,
			outputs: { status: "cancelled" },
		});
		assert.deepEqual(await verdict({}, false), passes);

		// A directory cannot be opened as a summary file. Reporting loss does not change validation.
		assert.deepEqual(await verdict({}, false, JSON.stringify(jobsOf(green)), tmpdir()), passes);
		assert.deepEqual(
			await verdict({ Quality: "failure" }, false, JSON.stringify(jobsOf(green)), tmpdir()),
			fails,
		);

		// An ordinary pull request legitimately skips the preflight, and blocking one would block
		// every pull request in the repository.
		assert.deepEqual(await verdict({ "Release-preflight": "skipped" }, false), passes);
		// Nothing else changes meaning on that branch: a dispatched run has no `Changesets` job, and
		// a path filter may skip any other leg, exactly as on `main`.
		assert.deepEqual(await verdict({ Changesets: "skipped" }, true), passes);
		// The Version PR, whichever run reports the gate: a preflight that did not succeed fails it.
		for (const preflight of ["skipped", "failure", "cancelled"]) {
			const result = await verdict({ "Release-preflight": preflight }, true);
			assert.equal(result.failed, true, `preflight ${preflight} must fail the gate`);
		}

		// A demand the run cannot satisfy is not a gate, it is a wall. The preflight evidences every
		// image on both published platforms — the vulnerability policy's match key includes the
		// platform, so an arm64-only finding must not reach a release undiscovered — and it can only
		// do that for manifests that exist. So wherever the gate demands a preflight, the same run's
		// image builds have to publish both.
		const architectures = String(workflow.getIn([...detection, "outputs", "platforms"]));
		const shape =
			/^\$\{\{ github\.event_name == '(?<event>\w+)' && steps\.release_candidate\.outputs\.release-candidate != 'true' && '\["linux\/amd64"\]' \|\| '\["linux\/amd64","linux\/arm64"\]' \}\}$/u.exec(
				architectures,
			);
		assert.ok(shape, `this test cannot evaluate \`${architectures}\``);
		const bothPlatforms = (event: string, onVersionBranch: boolean): boolean =>
			event !== shape.groups?.event || onVersionBranch;
		const events = ["pull_request", "merge_group", "workflow_dispatch", "push"];
		for (const onVersionBranch of [true, false]) {
			const result = await verdict({ "Release-preflight": "skipped" }, onVersionBranch);
			const demanded = result.failed;
			for (const event of events) {
				assert.ok(
					!demanded || bothPlatforms(event, onVersionBranch),
					`a ${event} run that the gate demands a preflight from must publish both platforms`,
				);
			}
		}
		// A pull request push builds one architecture, so CI load stays per push what it was. The merge
		// group still builds both, so an arm64 failure is found once per merge, before main.
		assert.deepEqual(
			events.map((event) => bothPlatforms(event, false)),
			[false, true, true, true],
		);

		// Nor can the gate demand evidence for images the run never published. Off that branch a pull
		// request builds what its diff touched and aliases the rest from `main`, which is right for a
		// preview and cannot serve the preflight: an alias is an image another commit's run built —
		// often the merged pull request's `linux/amd64` candidate — and the evidence must describe
		// the artefacts this commit would release. So the version branch publishes every release
		// image itself, under every event, exactly as its dispatch run did.
		const complete = String(workflow.getIn([...detection, "outputs", "all-images"]));
		assert.match(
			complete,
			/^\$\{\{ steps\.alias_base\.outputs\.commit == '' \|\| steps\.release_candidate\.outputs\.release-candidate == 'true' \|\| \(github\.event_name == 'merge_group' && steps\.filter\.outputs\.kubernetes == 'true'\) \}\}$/u,
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
		const aliasing = [...resolves.matchAll(/github\.event_name == '(?<event>\w+)'/gu)].map(
			({ groups }) => String(groups?.event),
		);
		assert.equal(aliasing.length, 2, `this test cannot evaluate \`${resolves}\``);
		const buildsEveryImage = (
			event: string,
			onVersionBranch: boolean,
			nothingToAlias = false,
		): boolean => !aliasing.includes(event) || nothingToAlias || onVersionBranch;
		for (const event of events) {
			assert.ok(
				buildsEveryImage(event, true),
				`a ${event} run on the Version PR's branch must publish every release image itself`,
			);
		}
		assert.deepEqual(
			Object.fromEntries(events.map((event) => [event, buildsEveryImage(event, false)])),
			{ pull_request: false, merge_group: false, workflow_dispatch: true, push: true },
		);
		// The other run with nothing to alias: a pull request whose chain of bases reaches no
		// published commit builds the whole set rather than failing to alias one.
		assert.ok(buildsEveryImage("pull_request", false, true));

		// One home for that decision too: an input that selects an image reads it, and no input
		// re-tests the event on its own. The package and server image selectors read it directly.
		for (const at of [
			["jobs", "server-package", "if"],
			["jobs", "Docker", "with", "application-server"],
		]) {
			const condition = String(workflow.getIn(at));
			assert.match(condition, /needs\.detect-changes\.outputs\.all-images == 'true'/u);
			assert.doesNotMatch(condition, /github\.event_name/u);
		}
		const imageInputs = {
			Docker: [
				"webapp_changed",
				"application_server_changed",
				"agent_images_changed",
				"postgres_image_changed",
			],
		};
		for (const [caller, inputs] of Object.entries(imageInputs)) {
			for (const name of inputs) {
				const value = String(workflow.getIn(["jobs", caller, "with", name]));
				assert.match(
					value,
					/needs\.detect-changes\.outputs\.all-images == 'true'/u,
					`${caller}.${name} must read the one decision`,
				);
				assert.doesNotMatch(
					value,
					/github\.event_name/u,
					`${caller}.${name} must not test the event itself`,
				);
			}
		}
		// And the alias cannot run behind their backs: it is reachable only while some image is
		// unchanged, so a run that builds the whole set evidences nothing another run published.
		const images = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const alias = String(images.getIn(["jobs", "tag-unchanged-images", "if"]));
		for (const name of imageInputs.Docker) {
			assert.match(alias, new RegExp(`inputs\\.${name} != 'true'`, "u"));
		}
		for (const image of ["webapp-build", "agent-pi-build", "postgres-build"]) {
			assert.doesNotMatch(
				String(images.getIn(["jobs", image, "if"])),
				/github\.event_name/u,
				`${image} must not re-test the event its caller already decided on`,
			);
		}

		// Only a pull request aliases, so a merge group that runs the Kubernetes legs, which install
		// every image under the run's commit, must build them all.
		assert.match(alias, /github\.event_name == 'pull_request'/u);
		assert.match(
			String(workflow.getIn([...detection, "outputs", "kubernetes"])),
			/^\$\{\{ steps\.filter\.outputs\.kubernetes \}\}$/u,
		);
		assert.match(
			String(workflow.getIn(["jobs", "Kubernetes", "if"])),
			/needs\.detect-changes\.outputs\.kubernetes == 'true'/u,
		);
		assert.match(
			complete,
			/\(github\.event_name == 'merge_group' && steps\.filter\.outputs\.kubernetes == 'true'\)/u,
		);
	});
});

// Every other run on `main` is the only one its commit will ever get, and something downstream reads
// the record it leaves: a cancelled security scan is a commit Scorecard counts as unscanned, and a
// cancelled ratchet is a merge nobody checked. Two merges a minute apart are enough to lose one.
void test("a workflow triggered by main does not cancel the run main is judged by", async () => {
	const sources = await workflowSources();
	const offenders: string[] = [];
	for (const [file, source] of sources) {
		const branches = parseDocument(source).getIn(["on", "push", "branches"]);
		if (
			!isSeq(branches) ||
			!branches.items.some((item) => isScalar(item) && item.value === "main")
		) {
			continue;
		}
		if (
			parseDocument(source).getIn(["concurrency", "cancel-in-progress"]) === true &&
			!Object.hasOwn(REPLACEABLE_MAIN_RUNS, file)
		) {
			offenders.push(file);
		}
	}
	assert.deepEqual(offenders, [], "these cancel a run on main that nothing else will repeat");
	for (const [file, exception] of Object.entries(REPLACEABLE_MAIN_RUNS)) {
		assert.ok(exception.reason.trim(), `${file} must say why its run is replaceable`);
		assert.equal(
			parseDocument(sources.get(file) ?? "").getIn([
				...exception.concurrency,
				"cancel-in-progress",
			]),
			true,
			`${file} no longer cancels, so it no longer needs an exception`,
		);
	}
});

// A push to a pull request leaves its earlier run validating a commit nobody will merge, and every
// such run holds runners the next pull request queues for. Cancelling it must not reach another
// pull request's run, or a run on main or in the merge queue: those are the only runs their commits get.
void test("a pull-request workflow cancels its superseded runs and nothing else", async () => {
	const repository = "hephaestus-build/Hephaestus";
	// Every pull request pushes the same branch name: two forks can each push `patch-1`.
	const pullRequest = (event: string, number: number, sha: string, head = repository) => ({
		event_name: event,
		ref: event === "pull_request" ? `refs/pull/${number}/merge` : "refs/heads/main",
		ref_name: event === "pull_request" ? `${number}/merge` : "main",
		head_ref: "patch-1",
		sha,
		event: {
			action: "synchronize",
			number,
			pull_request: { number, head: { sha, ref: "patch-1", repo: { full_name: head } } },
			repository: { default_branch: "main" },
		},
	});
	const queue = `refs/heads/gh-readonly-queue/main/pr-1-${"c".repeat(40)}`;
	const otherRuns = [
		["push", { ref: "refs/heads/main", ref_name: "main" }],
		["schedule", { ref: "refs/heads/main", ref_name: "main" }],
		["merge_group", { ref: queue, ref_name: queue.slice("refs/heads/".length) }],
	] as const;
	const offenders: string[] = [];
	for (const [file, source] of await workflowSources()) {
		const workflow = parseDocument(source);
		const event = ["pull_request", "pull_request_target"].find((name) =>
			workflow.hasIn(["on", name]),
		);
		if (event === undefined) {
			continue;
		}
		const lane = (concurrency: string[], github: Record<string, unknown>) => {
			const context = { github: { workflow: workflow.get("name"), repository, ...github } };
			return {
				group: expandExpressions(workflow.getIn([...concurrency, "group"]), context),
				cancels:
					expandExpressions(workflow.getIn([...concurrency, "cancel-in-progress"]), context) ===
					"true",
			};
		};
		if (Object.hasOwn(UNCANCELLED_PULL_REQUEST_RUNS, file)) {
			const jobs = workflow.get("jobs");
			assert.ok(isMap(jobs), file);
			for (const concurrency of [
				["concurrency"],
				...jobs.items.map((entry) => ["jobs", String(entry.key), "concurrency"]),
			]) {
				if (lane(concurrency, pullRequest(event, 1, "a".repeat(40))).cancels) {
					offenders.push(
						`${file}: exempt, but ${concurrency.join(".")} cancels a pull-request run`,
					);
				}
			}
			continue;
		}
		const scopes = validationConcurrencyScopes(
			workflow,
			(location) => lane(location, pullRequest(event, 1, "a".repeat(40))).cancels,
		);
		assert.notEqual(
			scopes.length,
			0,
			`${file}: no concurrency group cancels superseded validation`,
		);
		for (const concurrency of scopes) {
			const first = lane(concurrency, pullRequest(event, 1, "a".repeat(40)));
			const again = lane(concurrency, pullRequest(event, 1, "b".repeat(40)));
			if (!first.cancels || again.group !== first.group) {
				offenders.push(`${file}: a push to a pull request does not cancel its earlier run`);
			}
			if (lane(concurrency, pullRequest(event, 2, "a".repeat(40))).group === first.group) {
				offenders.push(`${file}: one pull request cancels another's run`);
			}
			const fork = lane(concurrency, pullRequest(event, 3, "a".repeat(40), "fork/Hephaestus"));
			if (fork.group === first.group) {
				offenders.push(`${file}: a fork's pull request cancels a run with the same branch name`);
			}
			for (const [trigger, github] of otherRuns) {
				if (!workflow.hasIn(["on", trigger])) {
					continue;
				}
				const run = lane(concurrency, {
					event_name: trigger,
					head_ref: "",
					sha: "d".repeat(40),
					event: { repository: { default_branch: "main" } },
					...github,
				});
				if (run.group === first.group) {
					offenders.push(`${file}: a pull request cancels a ${trigger} run`);
				}
				if (run.cancels && !(trigger === "push" && Object.hasOwn(REPLACEABLE_MAIN_RUNS, file))) {
					offenders.push(`${file}: a ${trigger} run cancels its predecessor`);
				}
			}
		}
	}
	assert.deepEqual(offenders, []);
	const sources = await workflowSources();
	for (const [file, reason] of Object.entries(UNCANCELLED_PULL_REQUEST_RUNS)) {
		assert.ok(reason.trim(), `${file} must say why it keeps superseded runs`);
		const workflow = parseDocument(sources.get(file) ?? "");
		assert.ok(
			workflow.hasIn(["on", "pull_request"]) || workflow.hasIn(["on", "pull_request_target"]),
			`${file} no longer runs on pull requests, so it no longer needs an exception`,
		);
	}
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
	for (const declaration of [locate, restore, install, proof]) {
		assert.equal(declaration.get("if"), "inputs.install == 'frozen'");
	}
	assert.equal(
		install.get("run"),
		"pnpm install --frozen-lockfile --ignore-scripts --no-trust-lockfile",
	);
	assert.match(String(locate.get("run")), /\$\(pnpm cache path\)/u);
	assert.match(
		String(locate.get("run")),
		/require\("node:path"\)\.join\(process\.argv\[1\], "lockfile-verified\.jsonl"\)/u,
	);
	assert.equal(
		proof.getIn(["env", "PNPM_VERIFICATION_CACHE"]),
		`\${{ steps.pnpm-verification-path.outputs.path }}`,
	);
	assert.match(String(proof.get("run")), /statSync\(process\.argv\[1\]\)\.isFile\(\)/u);
	assert.match(String(restore.get("uses")), /^actions\/cache\/restore@/u);
	assert.match(String(save.get("uses")), /^actions\/cache\/save@/u);
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
	"Stories final verdict follows the interaction tests, not visual coverage",
	{ skip: !bashRunsRunnerSteps() },
	async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		const command = runScript(workflow, ["jobs", "webapp-stories"], "Evaluate stories checks");
		for (const stories of ["success", "failure", "skipped", "cancelled", ""]) {
			const result = await runStep(command, { STORYBOOK_TESTS: stories });
			assert.equal(result.failed, stories !== "success", result.diagnosis);
		}
	},
);

void test("a pull request runs the chart and the latest k3s on amd64; the queue adds the floor and kind; only the nightly run adds arm64", async () => {
	const kubernetes = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const queue = String(cicd.getIn(["jobs", "Kubernetes", "with", "queue"]));
	assert.equal(queue, `\${{ needs.detect-changes.outputs.queue-tier == 'true' }}`);
	for (const full of ["true", "false"]) {
		assert.equal(
			expandExpressions(queue, {
				needs: { "detect-changes": { outputs: { "queue-tier": full } } },
			}),
			full,
		);
	}
	// A push that a merge group tested runs no leg; a push with no merge-group run, such as a direct
	// push, runs them all. The nightly run on main runs every leg on the newest build that passed CI.
	assert.match(
		String(cicd.getIn(["jobs", "Kubernetes", "if"])),
		/github\.event_name == 'push' && needs\.detect-changes\.outputs\.queue-run == ''/u,
	);
	const nightly = parseDocument(await readFile(".github/workflows/kubernetes-nightly.yml", "utf8"));
	assert.ok(isMap(nightly.getIn(["on", "schedule", 0])));
	assert.equal(
		nightly.getIn(["jobs", "Kubernetes", "uses"]),
		"./.github/workflows/ci-kubernetes.yml",
	);
	assert.equal(nightly.getIn(["jobs", "Kubernetes", "with", "queue"]), true);
	assert.equal(nightly.getIn(["jobs", "Kubernetes", "with", "arm64"]), true);
	// arm64 runners are scarce, so the merge queue leaves the arm64 leg to the nightly run.
	assert.equal(cicd.getIn(["jobs", "Kubernetes", "with", "arm64"]), undefined);
	assert.equal(
		nightly.getIn(["jobs", "Kubernetes", "with", "ref"]),
		nightly.getIn(["jobs", "Kubernetes", "with", "commit"]),
	);
	// A failed scheduled run fails no pull request, so it reports on a tracking issue.
	assert.equal(nightly.getIn(["jobs", "report", "if"]), "failure()");
	assert.equal(nightly.getIn(["jobs", "report", "permissions", "issues"]), "write");
	// The sandbox footprint benchmark: on the tested main build only, read-only, and it yields to the
	// merge queue by skipping instead of failing; its failure has a tracking issue of its own.
	const triggers = nightly.get("on");
	assert.ok(isMap(triggers));
	assert.deepEqual(
		triggers.items.map((pair) => String(pair.key)),
		["schedule", "workflow_dispatch"],
	);
	assert.equal(nightly.getIn(["jobs", "footprint", "needs"]), "build");
	const footprintPermissions = nightly.getIn(["jobs", "footprint", "permissions"]);
	assert.ok(isMap(footprintPermissions));
	assert.deepEqual(asRecord(footprintPermissions.toJSON(), "footprint permissions"), {
		actions: "read",
		contents: "read",
	});
	const footprintSteps = nightly.getIn(["jobs", "footprint", "steps"]);
	assert.ok(isSeq(footprintSteps));
	const footprint = footprintSteps.items.filter(isMap);
	const queueIndex = footprint.findIndex((entry) => entry.get("id") === "queue");
	assert.ok(queueIndex > 0, "the footprint job waits for the merge queue");
	assert.doesNotMatch(String(footprint[queueIndex]?.get("run")), /exit 1/u, "a busy queue skips");
	for (const entry of footprint.slice(queueIndex + 1)) {
		assert.match(String(entry.get("if")), /steps\.queue\.outputs\.run == 'true'/u);
	}
	assert.match(
		String(footprint.at(-1)?.get("if")),
		/^always\(\) && /u,
		"the logs survive a failure",
	);
	const reportNeeds = nightly.getIn(["jobs", "report", "needs"]);
	assert.ok(isSeq(reportNeeds));
	assert.ok(reportNeeds.items.some((item) => isScalar(item) && item.value === "footprint"));
	assert.match(
		String(nightly.getIn(["jobs", "report", "steps", 0, "run"])),
		/The nightly sandbox footprint run failed/u,
	);

	const minors = ["1.35", "1.37"];
	assert.deepEqual(clusterLegs(false), ["k3d 1.37 amd64"]);
	assert.deepEqual(clusterLegs(false, true), ["k3d 1.37 amd64"]);
	// The floor is the version of the cluster that the first operator runs (ADR 0060), so kind with
	// Calico runs there.
	assert.deepEqual(clusterLegs(true), [
		"k3d 1.35 amd64",
		"k3d 1.37 amd64",
		"drills 1.37 amd64",
		"calico 1.35 amd64",
	]);
	assert.deepEqual(clusterLegs(true, true), [
		"k3d 1.35 amd64",
		"k3d 1.35 arm64",
		"k3d 1.37 amd64",
		"drills 1.37 amd64",
		"calico 1.35 amd64",
	]);
	for (const caller of [cicd, nightly]) {
		assert.equal(caller.hasIn(["jobs", "Kubernetes", "strategy"]), false);
		assert.equal(caller.getIn(["jobs", "Kubernetes", "name"]), "Kubernetes");
	}

	// kubeconform validates the render on the lowest and the latest minor of the legs.
	const schemaSteps = kubernetes.getIn(["jobs", "k3d", "steps"]);
	assert.ok(isSeq(schemaSteps));
	const lintStep = schemaSteps.items.find(
		(item) => isMap(item) && String(item.get("name")).startsWith("Lint the chart"),
	);
	assert.ok(isMap(lintStep));
	const versions = minors.map((minor) => `${minor}.0`);
	assert.match(
		String(lintStep.get("run")),
		new RegExp(`for version in ${versions.join(" ").replaceAll(".", String.raw`\.`)}; do`, "u"),
	);
});

void test("the queue runs roll, while nightly and dispatch add eviction", async () => {
	const kubernetes = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const rootEnv = kubernetes.get("env");
	assert.ok(isMap(rootEnv));
	const jobs = kubernetes.get("jobs");
	assert.ok(isMap(jobs));
	for (const event of [
		"pull_request",
		"merge_group",
		"workflow_dispatch",
		"schedule",
		"workflow_run",
		"push",
	]) {
		for (const queue of [false, true]) {
			assert.equal(jobRuns(kubernetes, "k3d-drills", event, {}, false, { queue }), queue);
			for (const key of ["k3d", "k3d-drills", "k3d-1-35-amd64", "k3d-1-35-arm64"]) {
				const localEnv = jobs.getIn([key, "env"]);
				assert.ok(isMap(localEnv));
				const rootValues = asRecord(rootEnv.toJSON(), "global cluster environment");
				const jobValues = asRecord(localEnv.toJSON(), `${key} environment`);
				const environment = Object.fromEntries(
					Object.entries({ ...rootValues, ...jobValues }).map(
						([name, value]) =>
							[name, expandExpressions(value, { github: { event_name: event } })] as const,
					),
				);
				const steps = jobs.getIn([key, "steps"]);
				assert.ok(isSeq(steps));
				const selected = (name: string, eviction = true) => {
					const worker = namedStep(kubernetes, ["jobs", key], name);
					const condition = asString(
						worker.get("if") ?? "true",
						"cluster step condition",
					).replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "");
					return (
						expandExpressions(`\${{ ${condition} }}`, {
							env: environment,
							inputs: {
								"backup-drill": true,
								"eviction-drill": eviction,
							},
							github: { event_name: event },
						}) === "true"
					);
				};
				const drills = key === "k3d-drills";
				assert.equal(
					selected("Verify kubelet memory eviction order on a disposable node", false),
					false,
				);
				assert.equal(selected("Keep a TURN run in its sandbox through a server roll"), drills);
				assert.equal(
					selected("Verify kubelet memory eviction order on a disposable node"),
					drills && event !== "pull_request" && event !== "merge_group",
				);
				assert.equal(selected("Install, upgrade and verify"), !drills);
				assert.equal(
					selected("Start a sandbox on a new node without an image pull"),
					key === "k3d",
				);
				assert.equal(selected("Back up, then restore to a point in time"), key === "k3d");
			}
		}
	}
	const drillJob = jobs.get("k3d-drills");
	assert.ok(isMap(drillJob));
	assert.equal(drillJob.has("needs"), false, "the drill cluster starts alongside qualification");
	const permissions = drillJob.get("permissions");
	assert.ok(isMap(permissions));
	assert.deepEqual(permissions.toJSON(), { contents: "read", packages: "read" });
	const steps = drillJob.get("steps");
	assert.ok(isSeq(steps));
	const join = steps.items.findIndex((item) => isMap(item) && item.get("wait-all") === true);
	for (const name of [
		"Keep a TURN run in its sandbox through a server roll",
		"Verify kubelet memory eviction order on a disposable node",
	]) {
		const index = steps.items.findIndex((item) => isMap(item) && item.get("name") === name);
		const worker = namedStep(kubernetes, ["jobs", "k3d-drills"], name);
		assert.equal(worker.get("background"), true);
		assert.equal(worker.has("continue-on-error"), false);
		assert.ok(index < join, "both native results must join before the job passes");
	}
	const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const filter = step(cicd, ["jobs", "detect-changes"], "dorny/paths-filter");
	const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
	assert.ok(
		asArray(filters.kubernetes, "Kubernetes paths").includes("scripts/kubernetes-memory-drill.ts"),
		"an eviction-drill change must select its blocking cluster jobs",
	);
	assert.equal(cicd.getIn(["jobs", "Release-preflight", "permissions", "actions"]), "read");
	const preflight = cicd.getIn(["jobs", "Release-preflight", "steps"]);
	assert.ok(isSeq(preflight));
	assert.ok(
		preflight.items.some(
			(item) => isMap(item) && item.get("run") === "node scripts/check-nightly-kubernetes.ts",
		),
	);
});

void test("replacement-storage recovery runs nightly and on relevant pull requests, never in the merge queue", async () => {
	const source = await readFile(".github/workflows/cicd.yml", "utf8");
	const cicd = parseDocument(source);
	const nightly = parseDocument(await readFile(".github/workflows/kubernetes-nightly.yml", "utf8"));
	const kubernetes = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const recoveryPatterns: unknown = parseDocument(pathFilter(source, "kubernetes-recovery")).get(
		"kubernetes-recovery",
	);
	assert.ok(isSeq(recoveryPatterns));
	const patterns = asArray(recoveryPatterns.toJSON(), "recovery paths").map((entry) =>
		asString(entry, "path"),
	);
	const selector = asString(
		cicd.getIn(["jobs", "detect-changes", "outputs", "kubernetes-recovery"]),
		"recovery selector",
	);
	const input = cicd.getIn(["jobs", "Kubernetes", "with", "backup-drill"]);
	const drill = namedStep(kubernetes, ["jobs", "k3d"], "Back up, then restore to a point in time");
	for (const [file, relevant] of [
		["deploy/chart/hephaestus/templates/postgresql-backup.yaml", true],
		["deploy/chart/hephaestus/templates/networkpolicy.yaml", true],
		["docker/postgres/Dockerfile", true],
		["scripts/kubernetes-cluster.ts", true],
		[".github/workflows/ci-kubernetes.yml", true],
		[".github/workflows/kubernetes-nightly.yml", true],
		[".github/workflows/cicd.yml", false],
		["webapp/src/routes/index.tsx", false],
		["server/application/src/main/java/example/Service.java", false],
		["docs/admin/backup-restore.mdx", false],
	] as const) {
		const changed = String(patterns.some((pattern) => minimatch(file, pattern)));
		assert.equal(changed, String(relevant), file);
		for (const event of ["pull_request", "merge_group", "push", "workflow_dispatch"]) {
			const selected = evaluateTierOutput(selector, event, {
				filter: { "kubernetes-recovery": changed },
			});
			const enabled = expandExpressions(input, {
				needs: { "detect-changes": { outputs: { "kubernetes-recovery": selected } } },
			});
			assert.equal(enabled, String(relevant && event === "pull_request"), `${event}: ${file}`);
			assert.equal(
				evaluateLeg(drill.get("if"), event === "merge_group", {
					minor: "1.37",
					arch: "amd64",
					"backup-drill": enabled === "true",
				}),
				enabled,
			);
		}
	}
	const enabled = nightly.getIn(["jobs", "Kubernetes", "with", "backup-drill"]);
	assert.equal(enabled, true);
	assert.deepEqual(
		clusterMatrix(true, true)
			.include.filter((leg) => leg.leg === "k3d")
			.filter(
				(leg) => evaluateLeg(drill.get("if"), true, { ...leg, "backup-drill": enabled }) === "true",
			)
			.map((leg) => `${leg.minor} ${leg.arch}`),
		["1.37 amd64"],
	);
	assert.equal(
		kubernetes.getIn(["on", "workflow_call", "inputs", "backup-drill", "default"]),
		false,
	);
});

void test("one Security scan is the heavy legs' prerequisite on every event, with advisory work independent", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.deepEqual(needsOf(workflow, "Security"), ["detect-changes"]);
	for (const leg of HEAVY_LEGS) {
		assert.ok(needsClosure(workflow, leg).has("Security"), leg);
	}
	for (const event of ["merge_group", "pull_request", "push", "workflow_dispatch"]) {
		for (const security of ["success", "failure", "cancelled"]) {
			const run = simulateRun(workflow, event, security);
			assert.equal(run.Security, security, event);
			for (const leg of HEAVY_LEGS) {
				assert.equal(
					run[leg],
					security === "success" ? "success" : "skipped",
					`${event} ${security}: ${leg}`,
				);
			}
			assert.equal(run["vulnerability-database"], "success");
		}
	}
});

void test("a failed leg cancels its own merge-group run, and only a merge-group run", async () => {
	const source = await readFile(".github/workflows/cicd.yml", "utf8");
	const workflow = parseDocument(source);
	const gate = workflow.getIn(["jobs", "all-ci-passed", "needs"]);
	assert.ok(isSeq(gate));
	// These legs never run in a merge group, so a cancel job for them could never fire.
	const pullRequestOnly = new Set(["Changesets"]);
	assert.equal(simulateRun(workflow, "merge_group", "success").Security, "success");
	assert.equal(workflow.getIn(["jobs", "Changesets", "if"]), "github.event_name == 'pull_request'");
	const legs = gate.items
		.map(String)
		.filter((name) => name !== "detect-changes" && !pullRequestOnly.has(name));
	const jobs = asRecord(asRecord(workflow.toJSON(), "cicd.yml").jobs, "cicd.yml jobs");
	const callers = Object.entries(jobs)
		.filter(([, definition]) => isCancelWorker(asRecord(definition, "job")))
		.map(([name]) => name);
	// One cancel job per leg that the gate judges, and the gate does not wait for any of them.
	assert.deepEqual(callers.toSorted(), legs.map((leg) => `fail-fast-${leg}`).toSorted());
	for (const leg of legs) {
		const name = `fail-fast-${leg}`;
		const cancel = asRecord(jobs[name], name);
		assert.deepEqual(cancel.needs, [leg], name);
		assert.deepEqual(cancel.permissions, { actions: "write" }, name);
		assert.equal("with" in cancel || "secrets" in cancel, false, name);
		const condition = `\${{ always() && github.event_name == 'merge_group' && contains(needs.*.result, 'failure') }}`;
		assert.equal(cancel.if, condition, name);
		for (const event of ["merge_group", "pull_request", "push", "workflow_dispatch"]) {
			for (const result of ["success", "failure", "cancelled", "skipped"]) {
				for (const cancelled of [false, true]) {
					assert.equal(
						jobRuns(workflow, name, event, { [leg]: { result } }, cancelled),
						event === "merge_group" && result === "failure",
						`${name}: ${event}, leg ${result}, run cancelled: ${String(cancelled)}`,
					);
				}
			}
		}
	}
	// Without `always()` GitHub adds `success()`, which a failed leg makes false: the job never runs.
	const withoutAlways = parseDocument(source.replaceAll(`\${{ always() && `, `\${{ `));
	assert.equal(
		jobRuns(withoutAlways, "fail-fast-Docker", "merge_group", { Docker: { result: "failure" } }),
		false,
	);
});

void test("each cancel worker calls the one cancel workflow, which runs only graceful cancellation with its token", async () => {
	let count = 0;
	for (const [file, source] of await workflowSources()) {
		const workflow = asRecord(parseDocument(source).toJSON(), file);
		for (const [key, definition] of Object.entries(asRecord(workflow.jobs, "jobs"))) {
			const worker = asRecord(definition, key);
			if (!key.startsWith("fail-fast-") && worker.uses !== `./${CANCEL_WORKFLOW}`) {
				continue;
			}
			count += 1;
			assert.ok(isCancelWorker(worker), `${file}#${key}`);
			// Each caller needs only its own leg, so the cancel starts when that leg fails.
			assert.deepEqual(worker.needs, [key.replace(/^fail-fast-/u, "")], `${file}#${key}`);
			assert.deepEqual(worker.permissions, { actions: "write" }, `${file}#${key}`);
			assert.equal("with" in worker || "secrets" in worker, false, `${file}#${key}`);
		}
	}
	assert.ok(count > 0);
	const cancel = asRecord(
		parseDocument(await readFile(CANCEL_WORKFLOW, "utf8")).toJSON(),
		CANCEL_WORKFLOW,
	);
	assert.deepEqual(cancel.on, { workflow_call: null });
	assert.deepEqual(cancel.permissions, {});
	const jobs = Object.values(asRecord(cancel.jobs, "cancel jobs"));
	assert.equal(jobs.length, 1);
	const cancelJob = asRecord(jobs[0], "cancel job");
	assert.equal(cancelJob["runs-on"], "ubuntu-slim");
	assert.equal(
		cancelJob.if,
		`\${{ always() }}`,
		"the caller decided; an implicit success() must not skip",
	);
	assert.equal(cancelJob.needs, undefined);
	assert.deepEqual(cancelJob.permissions, { actions: "write" });
	const steps = asArray(cancelJob.steps, "cancel steps");
	assert.equal(steps.length, 1);
	const cancelStep = asRecord(steps[0], "cancel step");
	assert.equal(cancelStep.run, 'gh run cancel "$RUN_ID" --repo "$GITHUB_REPOSITORY"');
	assert.equal(cancelStep.uses, undefined);
	assert.deepEqual(cancelStep.env, {
		GH_TOKEN: `\${{ github.token }}`,
		RUN_ID: `\${{ github.run_id }}`,
	});
});

void test("no workflow, action or script force-cancels a run", async () => {
	// This file names the forbidden forms in the patterns below.
	const scripts = await posixGlob("scripts/**/*.ts");
	const files = [
		...(await posixGlob(".github/**/*.{yml,yaml}")),
		...scripts.filter((file) => file !== "scripts/ci-contract-merge-queue.test.ts"),
	];
	for (const [file, source] of await readSources(files)) {
		// The REST path, the Octokit method and the GraphQL-style name; prose may still name a force-cancel.
		assert.doesNotMatch(source, /\/force-cancel\b|forceCancel|force_cancel/iu, file);
		assert.doesNotMatch(source, /run cancel\b[\s\S]{0,200}--force/u, file);
	}
});

void test("each worker can cancel a merge group without waiting for unrelated workers", async () => {
	const cases = ["merge_group", "pull_request", "push", "workflow_dispatch", "schedule"].flatMap(
		(event) =>
			["success", "skipped", "cancelled", "failure"].flatMap((result) =>
				[false, true].map((cancelled) => ({ event, result, cancelled })),
			),
	);
	for (const file of [
		"ci-quality-gates.yml",
		"ci-build.yml",
		"ci-browser-tests.yml",
		"ci-tests.yml",
		"reusable-docker-build.yml",
		"ci-kubernetes.yml",
	]) {
		const workflow = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
		const jobs = asRecord(asRecord(workflow.toJSON(), file).jobs, "jobs");
		for (const [key, definition] of Object.entries(jobs)) {
			const worker = asRecord(definition, key);
			if (isCancelWorker(worker) || key === "tag-unchanged-images") {
				continue;
			}
			const callbacks = Object.entries(jobs).filter(([, value]) => {
				const callback = asRecord(value, "callback");
				return (
					isCancelWorker(callback) &&
					Array.isArray(callback.needs) &&
					callback.needs.length === 1 &&
					callback.needs[0] === key
				);
			});
			assert.equal(callbacks.length, 1, `${file}#${key}: immediate callback`);
			const name = callbacks[0]?.[0];
			assert.ok(isSet(name));
			for (const { event, result, cancelled } of cases) {
				assert.equal(
					jobRuns(workflow, name, event, { [key]: { result } }, cancelled),
					event === "merge_group" && result === "failure",
					`${file}#${key}: ${event}, ${result}`,
				);
			}
		}
	}
	const ci = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.deepEqual(serverShards(ci, false), [...SERVER_TIERS, "review"]);
	assert.deepEqual(serverShards(ci, true), [...SERVER_TIERS, ...integrationShards()]);
});

void test("manifest publication waits for both platform verdicts and still signs wholly reused images", async () => {
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	assert.deepEqual(needsOf(workflow, "merge"), ["build", "build-arm64"]);
	const inputs = {
		publish: true,
		"application-server": true,
		platforms: '["linux/amd64","linux/arm64"]',
		"reuse-images": "[]",
	};
	for (const result of ["success", "skipped", "failure", "cancelled"]) {
		for (const cancelled of [false, true]) {
			assert.equal(
				jobRuns(
					workflow,
					"merge",
					"merge_group",
					{ build: { result }, "build-arm64": { result: "success" } },
					cancelled,
					inputs,
				),
				!cancelled && ["success", "skipped"].includes(result),
			);
		}
	}
	for (const reused of [false, true]) {
		const selected = { ...inputs, "reuse-images": reused ? '["application-server"]' : "[]" };
		for (const key of ["build", "build-arm64"]) {
			assert.equal(jobRuns(workflow, key, "push", {}, false, selected), !reused);
		}
		assert.equal(
			jobRuns(
				workflow,
				"merge",
				"push",
				{ build: { result: "skipped" }, "build-arm64": { result: "skipped" } },
				false,
				selected,
			),
			true,
		);
	}
});

void test("a superseded PR cancels its native gate while live failures still get a verdict", async () => {
	const fixture = asRecord(
		JSON.parse(await readFile("scripts/fixtures/ci-superseded-pr.json", "utf8")),
		"superseded PR",
	);
	assert.equal(fixture.conclusion, "cancelled");
	const observed = asRecord(fixture.needs, "recorded prerequisite results");
	const recordedNeeds = Object.fromEntries(
		Object.entries(observed).map(([name, value]) => [
			name,
			{ result: asString(asRecord(value, name).result, `${name} result`) },
		]),
	);
	const recordedJobs = asArray(fixture.jobs, "recorded jobs").map((entry) =>
		asRecord(entry, "recorded job"),
	);
	assert.equal(
		recordedJobs.find((entry) => entry.name === "CI Status Gate")?.conclusion,
		"failure",
	);
	assert.equal(
		recordedJobs.filter((entry) =>
			asArray(entry.steps, "recorded steps").some((entryStep) => {
				const result = asRecord(entryStep, "recorded step");
				return result.name === "Upload test results" && result.conclusion === "failure";
			}),
		).length,
		2,
	);
	const witness = asRecord(fixture.cancellation_witness, "hosted cancellation witness");
	assert.equal(witness.name, "Report unsuccessful merge group");
	assert.equal(witness.conclusion, "cancelled");
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.equal(
		jobRuns(
			workflow,
			"report-unsuccessful-merge-group",
			"pull_request",
			{
				"all-ci-passed": { result: "failure", outputs: { verdict: "cancelled" } },
			},
			true,
		),
		false,
	);
	assert.equal(
		jobRuns(
			workflow,
			"all-ci-passed",
			asString(fixture.event, "recorded event"),
			recordedNeeds,
			true,
		),
		false,
	);
	const green = Object.fromEntries(
		needsOf(workflow, "all-ci-passed").map((name) => [name, { result: "success" }]),
	);
	for (const event of ["pull_request", "merge_group", "push", "workflow_dispatch"]) {
		for (const result of ["success", "failure", "cancelled", "skipped"]) {
			const needs = { ...green, Test: { result } };
			assert.equal(
				jobRuns(workflow, "all-ci-passed", event, needs, false),
				true,
				`${event}, ${result}: live run must evaluate`,
			);
			assert.equal(
				jobRuns(workflow, "all-ci-passed", event, needs, true),
				event === "merge_group",
				`${event}, ${result}: only merge groups evaluate after cancellation`,
			);
		}
	}
});

void test(
	"a cancelled caller cannot hide a native failed leaf or reuse an earlier attempt's verdict",
	runnerStepsOnly,
	async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const gate = ["jobs", "all-ci-passed"];
		assert.equal(
			workflow.getIn([...gate, "if"]),
			`\${{ !cancelled() || github.event_name == 'merge_group' }}`,
		);
		assert.equal(workflow.getIn([...gate, "permissions", "actions"]), "read");
		const needs = {
			Build: { result: "cancelled" },
			"detect-changes": { result: "success", outputs: {} },
		};
		const shell = expandExpressions(runScript(workflow, gate, "Evaluate CI results"), { needs });
		for (const [counts, status, verdict] of [
			["0\n1", "0", "failure"],
			["0\n0", "0", "cancelled"],
			["", "1", "failure"],
		]) {
			const run = await runStep(
				`gh() { [ "$*" = "$EXPECTED" ] || return 1; printf '%s\\n' "$COUNTS"; return "$API_STATUS"; }\n${shell}`,
				{
					NEEDS: JSON.stringify(needs),
					MERGE_GROUP: "true",
					RUN_ID: "123",
					RUN_ATTEMPT: "2",
					GITHUB_REPOSITORY: "owner/repo",
					EXPECTED:
						'api --paginate repos/owner/repo/actions/runs/123/attempts/2/jobs?per_page=100 --jq [.jobs[] | select(.conclusion == "failure")] | length',
					COUNTS: counts ?? "",
					API_STATUS: status ?? "1",
				},
			);
			assert.equal(run.failed, true, run.diagnosis);
			assert.equal(run.outputs.status, verdict, run.diagnosis);
		}
	},
);

void test("only the fail-fast jobs, and the listed exceptions, can write to Actions", async () => {
	const grants: string[] = [];
	const sources = await workflowSources();
	const callsCancel = (file: string): boolean => {
		if (file === CANCEL_WORKFLOW) {
			return true;
		}
		const workflow = asRecord(parseDocument(sources.get(file) ?? "").toJSON(), file);
		return Object.values(asRecord(workflow.jobs, "jobs")).some((value) => {
			const workerDefinition = asRecord(value, "job");
			if (isCancelWorker(workerDefinition)) {
				return true;
			}
			const { uses } = workerDefinition;
			return (
				typeof uses === "string" &&
				uses.startsWith("./.github/workflows/") &&
				callsCancel(uses.slice(2))
			);
		});
	};
	for (const [file, source] of sources) {
		const workflow = asRecord(parseDocument(source).toJSON(), file);
		assert.equal(writesActions(workflow.permissions), false, `${file} grants it to every job`);
		if (file === CANCEL_WORKFLOW) {
			continue;
		}
		for (const [name, definition] of Object.entries(asRecord(workflow.jobs, `${file} jobs`))) {
			const worker = asRecord(definition, name);
			if (typeof worker.uses === "string") {
				const forwardsCancel =
					worker.uses.startsWith("./.github/workflows/") && callsCancel(worker.uses.slice(2));
				assert.equal(
					writesActions(worker.permissions),
					forwardsCancel,
					`${file}#${name}: helper permission must pass through every caller`,
				);
			} else if (writesActions(worker.permissions) && !isCancelWorker(worker)) {
				grants.push(`${file}#${name}`);
			}
		}
	}

	assert.deepEqual(grants.toSorted(), Object.keys(ACTIONS_WRITE_GRANTS).toSorted());
});

void test("the CI outline has static names and one reusable level, including skipped workers", async () => {
	const root = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const jobs = asRecord(asRecord(root.toJSON(), "CI/CD").jobs, "jobs");
	assert.equal(asRecord(jobs["all-ci-passed"], "gate").name, "CI Status Gate");
	assert.equal(jobs["start-ci"], undefined, "the status-only admission design was rejected");
	const gateScript = runScript(root, ["jobs", "all-ci-passed"], "Evaluate CI results");
	const labels = /jq -r '(?<labels>\{[\s\S]*?\}) as \$names/u.exec(gateScript)?.groups?.labels;
	assert.ok(isSet(labels), "the summary names every native result with its sidebar name");
	const names = asRecord(JSON.parse(labels), "summary names");
	assert.deepEqual(Object.keys(names).toSorted(), needsOf(root, "all-ci-passed").toSorted());
	for (const [key, label] of Object.entries(names)) {
		assert.equal(label, asRecord(jobs[key], key).name ?? key, key);
	}
	const suites = parseDocument(await readFile(".github/workflows/ci-tests.yml", "utf8"));
	assert.equal(suites.getIn(["env", "INTEGRATION_COUNT"]), String(committedShards()));
	for (let index = 1; index <= committedShards(); index += 1) {
		assert.equal(
			suites.getIn(["jobs", `server-integration-${index}`, "name"]),
			`Integration (${index} of ${committedShards()})`,
		);
	}
	for (const [key, definition] of Object.entries(jobs)) {
		const caller = asRecord(definition, key);
		const name = asString(caller.name ?? key, "caller name");
		assert.doesNotMatch(name, /\$\{\{| \/ /u, key);
		if (typeof caller.uses !== "string") {
			continue;
		}
		assert.equal(caller.strategy, undefined, `${key}: a caller matrix repeats its stage`);
		const callee = asRecord(
			parseDocument(await readFile(caller.uses.slice(2), "utf8")).toJSON(),
			key,
		);
		for (const [workerKey, value] of Object.entries(asRecord(callee.jobs, key))) {
			const worker = asRecord(value, workerKey);
			// A skipped call shows as one entry; the cancel level appears only in a group that failed.
			assert.ok(
				worker.uses === undefined || isCancelWorker(worker),
				`${key}/${workerKey}: a nested workflow adds a sidebar level`,
			);
			const label = asString(worker.name ?? workerKey, "worker name");
			assert.doesNotMatch(label, /\$\{\{|^(?:Gates|Build|Run)$/u, `${key}/${workerKey}`);
			assert.notEqual(label, name, `${key}/${workerKey}: the child repeats its stage`);
		}
	}
});
