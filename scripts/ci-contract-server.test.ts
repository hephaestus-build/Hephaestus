import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";
import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { minimatch } from "minimatch";
import { isMap, isSeq, visit } from "yaml";
import {
	type Completion,
	escapeRegExp,
	evaluateTierOutput,
	expandExpressions,
	integrationShards,
	job,
	jobRuns,
	matrixValues,
	namedStep,
	parseDocument,
	pathFilter,
	pullRequestPaths,
	runScript,
	SERVER_TIERS,
	serverShards,
	step,
	stepInputs,
	workflowSources,
} from "./ci-contract-support.ts";
import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

void describe("ci-contract: server", () => {
	void test("server CI uses native Gradle caching and verifies JUnit selection", async () => {
		const action = await readFile(".github/actions/setup-caches/action.yml", "utf8");
		assert.match(action, /gradle\/actions\/setup-gradle@/u);
		assert.doesNotMatch(action, /cache-type|\.m2/u);
		const workflow = parseDocument(await readFile(".github/workflows/ci-tests.yml", "utf8"));
		const caller = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.deepEqual(
			serverShards(caller, true).filter((shard) => !SERVER_TIERS.has(shard)),
			integrationShards(),
		);
		assert.equal(
			runScript(workflow, ["jobs", "server-verification"], "Verify test tier and shard discovery"),
			"vp run test:server:selection",
		);
	});

	void test("weekly server profiles run CI's integration shards, never the whole tier", async () => {
		const ci = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const shards = serverShards(ci, true).filter((shard) => !SERVER_TIERS.has(shard));
		for (const [file, name, selectorAt] of [
			[".github/workflows/ci-profile.yml", "server-integration", undefined],
			[
				".github/workflows/ci-server-clean-reference.yml",
				"integration",
				"Profile integration execution",
			],
		] as const) {
			const workflow = parseDocument(await readFile(file, "utf8"));
			const jobPath = ["jobs", name];
			const matrix = workflow.getIn([...jobPath, "strategy", "matrix"]);
			assert.ok(isMap(matrix), `${file} profiles each shard`);
			assert.deepEqual(matrixValues(matrix, "shard"), shards, `${file} profiles CI's shards`);
			const environment =
				selectorAt === undefined
					? workflow.getIn([...jobPath, "env"])
					: namedStep(workflow, jobPath, selectorAt).get("env");
			assert.ok(isMap(environment), `${file} sets the shard selector`);
			assert.match(
				String(environment.get("HEPHAESTUS_INTEGRATION_SHARD")),
				/^\$\{\{ matrix\.shard \}\}$/u,
				`${file} selects the shard through the environment`,
			);
			assert.equal(matrix.has("exclude"), false, `${file} skips no shard`);
			const condition = workflow.getIn([...jobPath, "if"]);
			assert.ok(condition === undefined || typeof condition === "string");
			assert.doesNotMatch(condition ?? "", /matrix\./u, `${file} skips no shard`);
			assert.equal(
				workflow.getIn([...jobPath, "strategy", "fail-fast"]),
				false,
				`${file} profiles every shard when one fails`,
			);
			// One heavy runner at a time keeps weekly diagnostics cheap.
			assert.equal(
				workflow.getIn([...jobPath, "strategy", "max-parallel"]),
				1,
				`${file} profiles one shard at a time`,
			);
		}
		const profile = parseDocument(await readFile(".github/workflows/ci-profile.yml", "utf8"));
		assert.match(
			String(profile.getIn(["jobs", "server-integration", "env", "HISTORY_ARTIFACT"])),
			/\$\{\{ matrix\.shard \}\}/u,
			"each shard keeps its own history artifact",
		);
		const reference = parseDocument(
			await readFile(".github/workflows/ci-server-clean-reference.yml", "utf8"),
		);
		assert.equal(reference.getIn(["jobs", "integration", "needs"]), "verify");
		assert.match(
			String(reference.getIn(["jobs", "integration", "if"])),
			/^\$\{\{ !cancelled\(\) \}\}$/u,
			"shards start after the cold phase job, even when it fails",
		);
	});

	void test("migration PRs select database checks without promoting every shard", async () => {
		const caller = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const build = parseDocument(await readFile(".github/workflows/ci-build.yml", "utf8"));
		const filters = asRecord(
			parseDocument(
				String(step(caller, ["jobs", "detect-changes"], "dorny/paths-filter").get("filters")),
			).toJSON(),
			"filters",
		);
		const migrate = "server/application/src/main/resources/db/changelog/next/migration.xml";
		const source = "server/application/src/main/java/Application.java";
		// files, event, ci:full, release candidate, migration-only, queue tier, database checks
		for (const [files, event, full, candidate, only, queue, database] of [
			[[source], "pull_request", "false", "false", false, false, false],
			[[migrate, ".changeset/migration.md"], "pull_request", "false", "false", true, false, true],
			[
				[migrate, ".migration/schema.md", "docs/contributor/erd/schema.mmd"],
				"pull_request",
				"false",
				"false",
				true,
				false,
				true,
			],
			[[migrate, source], "pull_request", "false", "false", false, false, true],
			[[migrate, ".changeset/config.json"], "pull_request", "false", "false", false, false, true],
			[[".github/workflows/cicd.yml"], "pull_request", "false", "false", false, false, false],
			[["webapp/e2e/review.spec.ts"], "pull_request", "false", "false", false, false, false],
			[[migrate], "pull_request", "true", "false", false, true, true],
			[[migrate], "pull_request", undefined, "false", false, true, true],
			[[migrate], "pull_request", "false", "true", false, false, true],
			[[migrate], "merge_group", "false", "false", false, true, true],
			[[source], "merge_group", "false", "false", false, true, true],
			[[migrate], "workflow_dispatch", "false", "false", false, true, true],
			[[migrate], "push", "false", "false", false, true, false],
		] as const) {
			const label = JSON.stringify({ files, event, full, candidate });
			const selected = Object.fromEntries(
				Object.entries(filters).map(([key, value]) => {
					const patterns = asArray(value, key).map((pattern) => asString(pattern, "path pattern"));
					const matches = files.some(
						(file) =>
							patterns
								.filter((pattern) => !pattern.startsWith("!"))
								.some((pattern) => minimatch(file, pattern, { dot: true })) &&
							patterns
								.filter((pattern) => pattern.startsWith("!"))
								.every((pattern) => !minimatch(file, pattern.slice(1), { dot: true })),
					);
					return [key, String(matches)];
				}),
			);
			const run = { full, releaseCandidate: candidate, filter: selected };
			const output = (name: string) =>
				evaluateTierOutput(
					String(caller.getIn(["jobs", "detect-changes", "outputs", name])),
					event,
					run,
				);
			assert.equal(output("queue-tier"), String(queue), label);
			assert.equal(output("migration-only"), String(only), label);
			assert.equal(
				caller.getIn(["jobs", "Test", "with", "migration_only"]),
				`\${{ needs.detect-changes.outputs.migration-only }}`,
			);
			assert.equal(
				caller.getIn(["jobs", "Build", "with", "migrations_changed"]),
				`\${{ needs.detect-changes.outputs.migrations-changed }}`,
			);
			const server = output("server-tests") === "true";
			const shards = server ? serverShards(caller, queue, only) : [];
			assert.deepEqual(
				shards,
				server
					? [...(only ? [] : SERVER_TIERS), ...(queue ? integrationShards() : ["review"])]
					: [],
			);
			const contracts = expandExpressions(
				caller.getIn(["jobs", "Build", "with", "contracts_changed"]),
				{
					github: { event_name: event },
					needs: {
						"detect-changes": {
							outputs: { ...selected, unfiltered: output("unfiltered"), "version-bump": "false" },
						},
					},
				},
			);
			for (const name of ["server-database", "postgres-drill"]) {
				assert.equal(
					jobRuns(build, name, event, {}, false, {
						should_skip: "false",
						contracts_changed: contracts,
						queue_tier: output("queue-tier"),
						migrations_changed: output("migrations-changed"),
					}),
					database,
					`${name}: ${label}`,
				);
			}
			if (only) {
				assert.equal(
					jobRuns(build, "server-api", event, {}, false, {
						should_skip: "false",
						contracts_changed: contracts,
					}),
					true,
					"migration-only PRs keep generated schema and ERD drift proof",
				);
			}
		}
	});

	void test("migration caller conditions preserve draft and fork checks and the stacked light tier", async () => {
		const caller = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const build = parseDocument(await readFile(".github/workflows/ci-build.yml", "utf8"));
		for (const [draft, light, fork] of [
			[false, "false", false],
			[true, "false", false],
			[false, "true", false],
			[true, "true", false],
			[false, "false", true],
			[true, "false", true],
			[false, undefined, false],
		] as const) {
			const run = {
				full: "false",
				preview: "false",
				light,
				filter: { "application-server": "true", migrations: "true", "non-migration": "false" },
				pullRequest: { draft, head: { repo: { full_name: fork ? "fork/repo" : "owner/repo" } } },
			};
			const declared = caller.getIn(["jobs", "detect-changes", "outputs"]);
			assert.ok(isMap(declared));
			const names = [
				"server-tests",
				"migration-only",
				"migrations-changed",
				"queue-tier",
				"light-tier",
				"draft",
				"head-images",
				"images",
				"any-code",
				"all-images",
				"unfiltered",
				"publishable",
			];
			const outputs: Record<string, string> = {
				...run.filter,
				should_skip: "false",
				"queue-images-complete": "false",
			};
			for (const name of names) {
				outputs[name] = evaluateTierOutput(String(declared.get(name)), "pull_request", run);
			}
			const results: Record<string, Completion> = {
				"detect-changes": { result: "success", outputs },
				Security: { result: "success" },
			};
			const packaged = jobRuns(caller, "server-package", "pull_request", results);
			results["server-package"] = { result: packaged ? "success" : "skipped" };
			assert.equal(packaged, light !== "true", JSON.stringify(run));
			assert.equal(jobRuns(caller, "Build", "pull_request", results), packaged);
			assert.equal(jobRuns(caller, "Test", "pull_request", results), packaged);
			const inputs = Object.fromEntries(
				["should_skip", "contracts_changed", "queue_tier", "migrations_changed"].map((name) => [
					name,
					expandExpressions(caller.getIn(["jobs", "Build", "with", name]), {
						github: { event_name: "pull_request" },
						needs: results,
					}),
				]),
			);
			for (const name of ["server-database", "postgres-drill"]) {
				assert.equal(
					packaged && jobRuns(build, name, "pull_request", {}, false, inputs),
					packaged,
					`${name}: ${JSON.stringify(run)}`,
				);
			}
			assert.equal(outputs.publishable, String(!fork));
		}
	});

	void test("one main-branch workflow is the only Gradle cache producer", async () => {
		const action = parseDocument(await readFile(".github/actions/setup-caches/action.yml", "utf8"));
		assert.equal(action.getIn(["inputs", "cache-write", "default"]), "false");
		assert.equal(
			namedStep(action, ["runs"], "Set up Gradle").getIn(["with", "cache-cleanup"]),
			"never",
			"raw verification metadata must survive the producer's save",
		);
		assert.equal(
			namedStep(action, ["runs"], "Set up JDK").getIn(["with", "java-version-file"]),
			".java-version",
		);
		const source = await readFile(".github/actions/setup-caches/action.yml", "utf8");
		assert.match(source, /inputs.cache-write != 'true' \|\| github.ref != format/u);
		// The Gradle action restores an older entry of the same job before a newer entry of another,
		// so a second writer, such as a package job that runs only when reuse fails, would pin stale state.
		const sources = await workflowSources();
		const writers = [...sources].filter(([, text]) => text.includes('cache-write: "true"'));
		assert.deepEqual(
			writers.map(([file]) => file),
			[".github/workflows/cache-gradle.yml"],
		);
		const producer = parseDocument(await readFile(".github/workflows/cache-gradle.yml", "utf8"));
		const branches = producer.getIn(["on", "push", "branches"]);
		assert.ok(isSeq(branches));
		assert.deepEqual(branches.toJSON(), ["main"]);
		const paths = producer.getIn(["on", "push", "paths"]);
		assert.ok(isSeq(paths));
		// Every Gradle input of the server, so a dependency or build change refreshes the cache.
		for (const file of [
			"server/build.gradle.kts",
			"server/application/build.gradle.kts",
			"server/application/gradle.lockfile",
			"server/generated-clients/gradle.lockfile",
			"server/settings-gradle.lockfile",
			"server/buildscript-gradle.lockfile",
			"server/gradle.properties",
			"server/gradle/libs.versions.toml",
			"server/gradle/wrapper/gradle-wrapper.properties",
			".java-version",
			"package.json",
			"pnpm-lock.yaml",
			"tsconfig.agent-runtime.json",
			"scripts/compile-agent-runtime.ts",
			".github/actions/setup-toolchain/action.yml",
			".github/actions/setup-caches/action.yml",
			".github/workflows/cache-gradle.yml",
		]) {
			assert.ok(
				paths.items.some((pattern) => minimatch(file, String(pattern), { dot: true })),
				`${file} must refresh the Gradle cache`,
			);
		}
		// The producer runs what consumers resolve: packaging, test runtime and the server gates.
		const build = runScript(producer, ["jobs", "gradle"], "Build what the server jobs restore");
		for (const task of [
			"resolveDependencies",
			":application:bootJar",
			":application:testClasses",
			":application:testInventory",
			":application:pmdMain",
			":application:pmdTest",
			":application:spotlessCheck",
		]) {
			assert.ok(build.split(/\s+/u).includes(task), `the producer must run ${task}`);
		}
		const orchestrator = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const steps = orchestrator.getIn(["jobs", "server-package", "steps"]);
		assert.ok(isSeq(steps));
		const cache = steps.items.find(
			(item) => isMap(item) && item.get("uses") === "./.github/actions/setup-caches",
		);
		assert.ok(isMap(cache));
		assert.equal(cache.getIn(["with", "cache-write"]), undefined);
	});

	void test("OpenAPI generation uses the read-only Gradle setup but runtime-only E2E does not", async () => {
		const api = parseDocument(await readFile(".github/workflows/openapi-autocommit.yml", "utf8"));
		const setup = namedStep(api, ["jobs", "generate"], "Set up Java build");
		assert.equal(setup.get("uses"), "./.github/actions/setup-caches");
		assert.notEqual(setup.getIn(["with", "cache-write"]), "true");
		assert.equal(api.getIn(["jobs", "generate", "permissions", "contents"]), "read");
		assert.doesNotMatch(job(String(api), "generate"), /actions\/setup-java@/u);
		assert.doesNotMatch(job(String(api), "commit"), /setup-caches|gradlew|vp run/u);
		const browserSource = await readFile(".github/workflows/ci-browser-tests.yml", "utf8");
		const build =
			(await readFile(".github/workflows/ci-build.yml", "utf8")) +
			browserSource.split("jobs:\n")[1];
		for (const name of ["webapp-e2e", "extension-e2e"]) {
			assert.match(job(build, name), /actions\/setup-java@/u);
			assert.doesNotMatch(job(build, name), /setup-caches|gradlew/u);
		}
	});

	void test("Gradle lock changes rebuild and verify the shipped server", async () => {
		const build = await readFile("server/build.gradle.kts", "utf8");
		assert.match(build, /lockAllConfigurations\(\)/u);
		assert.match(build, /lockMode\.set\(LockMode\.STRICT\)/u);
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		for (const gate of ["application-server-image", "e2e", "extension-e2e", "pmd-canary"]) {
			const paths = asArray(filters[gate], gate);
			for (const input of [
				"server/application/gradle.lockfile",
				"server/settings-gradle.lockfile",
				"server/buildscript-gradle.lockfile",
			]) {
				assert.ok(paths.includes(input), `${gate} must include ${input}`);
			}
		}
		for (const gate of ["e2e", "extension-e2e"]) {
			assert.ok(asArray(filters[gate], `${gate} paths`).includes(".java-version"));
		}
	});

	void test("test-report inputs use permitted expressions and annotate every failed test leg", async () => {
		const reporters = new Set<string>();
		for (const [file, source] of await workflowSources()) {
			visit(parseDocument(source), {
				Map(_key, declaration) {
					if (!String(declaration.get("uses")).startsWith("dorny/test-reporter@")) {
						return;
					}
					const inputs = stepInputs(declaration);
					const template = asString(inputs.get("max-annotations"), `${file} max-annotations`);
					const expression = /^\$\{\{(?<body>[\s\S]+)\}\}$/u.exec(template)?.groups?.body;
					assert.ok(isSet(expression), `${file} must calculate annotation count`);
					// Status functions are available in step `if`, not action `with` inputs.
					// No custom status functions are registered: the native expression parser rejects them.
					const parsed = new Parser(
						new Lexer(expression).lex().tokens,
						["job", "steps"],
						[],
					).parse();
					const name = String(inputs.get("name"));
					reporters.add(`${file}: ${name}`);
					for (const status of ["success", "failure", "cancelled"]) {
						for (const tests of ["success", "failure", "skipped"]) {
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
									(name === "Test Results - Webapp stories" && tests === "failure") ||
									(name === "Test Results - Chromatic" && chromatic === "failure");
								assert.equal(
									new Evaluator(parsed, context).evaluate().coerceString(),
									failed ? "50" : "0",
									`${file}: ${name}, ${status}, tests=${tests}, chromatic=${chromatic}`,
								);
							}
						}
					}
				},
			});
		}
		assert.equal(reporters.size, 6);
	});

	void test("security mutation checks follow their Gradle inputs and compile the tests once", async () => {
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
		]) {
			assert.ok(paths.includes(input), `security mutation checks must include ${input}`);
		}
		// The compile step and PIT share one value, so PIT does not recompile the tests.
		assert.equal(
			workflow.getIn(["jobs", "pitest", "env", "ORG_GRADLE_PROJECT_testAnalysis"]),
			"false",
		);
	});

	void test("every CI/CD run validates the Gradle wrapper in a job without a condition", async () => {
		// Scorecard exempts the committed wrapper JAR only for a successful run of this file at the
		// latest commit that contains the validation action.
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.equal(workflow.getIn(["jobs", "detect-changes", "if"]), undefined);
		assert.equal(workflow.getIn(["jobs", "detect-changes", "needs"]), undefined);
		const steps = workflow.getIn(["jobs", "detect-changes", "steps"]);
		assert.ok(isSeq(steps));
		const validation = steps.items.find(
			(item) =>
				isMap(item) && String(item.get("uses")).startsWith("gradle/actions/wrapper-validation@"),
		);
		assert.ok(isMap(validation), "Detect changes validates the Gradle wrapper");
		assert.equal(validation.get("if"), undefined);
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
		]) {
			assert.ok(paths.includes(input), `PMD canary must include ${input}`);
		}
	});

	void test("every Gradle build installs the trusted runtime compiler first", async () => {
		for (const [file, source] of await workflowSources()) {
			const jobs = parseDocument(source).get("jobs");
			assert.ok(isMap(jobs), file);
			for (const { key, value } of jobs.items) {
				if (!isMap(value)) {
					continue;
				}
				const steps = value.get("steps");
				if (!isSeq(steps)) {
					continue;
				}
				let installed = false;
				const conditionalInstalls = new Set<string>();
				for (const declaration of steps.items) {
					if (!isMap(declaration)) {
						continue;
					}
					const condition = declaration.get("if");
					const installsDependencies =
						declaration.get("uses") === "./.github/actions/setup-toolchain" &&
						declaration.getIn(["with", "install"]) === "frozen";
					if (installsDependencies && condition === undefined) {
						installed = true;
					}
					if (installsDependencies && typeof condition === "string") {
						conditionalInstalls.add(condition);
					}
					const command = declaration.get("run");
					if (typeof command !== "string" || !/\.\/gradlew\b/u.test(command)) {
						continue;
					}
					// Dependency reporting resolves metadata without running the resource build.
					if (
						/^\.\/gradlew dependencies buildEnvironment --no-configuration-cache\s*$/u.test(command)
					) {
						continue;
					}
					assert.ok(
						installed || (typeof condition === "string" && conditionalInstalls.has(condition)),
						`${file}:${String(key)} must install frozen dependencies before Gradle under the same condition`,
					);
				}
			}
		}
	});

	void test("packages the server once and runs every artifact gate against it", async () => {
		const browserSource = await readFile(".github/workflows/ci-browser-tests.yml", "utf8");
		const build =
			(await readFile(".github/workflows/ci-build.yml", "utf8")) +
			browserSource.split("jobs:\n")[1];
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		const packageJob = job(orchestrator, "server-package");
		const packaging = /^\s+run: (?<command>\.\/gradlew .*)$/mu.exec(packageJob)?.groups?.command;
		assert.ok(isSet(packaging));
		assert.match(packaging, /:application:bootJar/u);
		assert.match(packaging, /:application:testClasses/u);
		// The suites wait for these classes, and the analysis does not change them, so it runs in
		// the server quality leg, after that leg's other Gradle gate: one build per checkout.
		assert.match(packaging, / -PtestAnalysis=false(?:\s|$)/u);
		const config = await readFile("vite.config.ts", "utf8");
		assert.match(config, /"ci:server": group\(\[[^\]]*"gate:server-test-analysis"/u);
		assert.match(
			config,
			/"gate:server-test-analysis": run\(`\$\{gradlew\} :application:compileTestJava --quiet`, \{\s*dependsOn: \["gate:server"\],/u,
		);
		assert.equal((packageJob.match(/actions\/upload-artifact@/gu) ?? []).length, 1);
		assert.match(packageJob, /overwrite: true/u);
		for (const name of ["server-api", "server-database", "postgres-drill"]) {
			const consumer = job(build, name);
			assert.doesNotMatch(consumer, /needs:/u);
			assert.match(consumer, /uses: \.\/\.github\/actions\/restore-server-build/u);
			// Goals against the restored classes; a lifecycle phase would compile again.
			assert.doesNotMatch(
				consumer,
				/gradlew[^\n]* :(?:application|generated-clients):(?:compileJava|compileTestJava|bootJar|classes|testClasses)(?:\s|$)/u,
			);
		}
		assert.match(job(build, "server-database"), /:application:databaseTest -PpackagedServer=true/u);
		assert.match(job(build, "server-api"), /HEPHAESTUS_APPLICATION_JAR/u);
		// The drills migrate from the JAR this job restored, never from the checkout's resources.
		const drill = parseDocument(build);
		const drillSteps = drill.getIn(["jobs", "postgres-drill", "steps"]);
		assert.ok(isSeq(drillSteps));
		const restored = drillSteps.items.find(
			(candidate) =>
				isMap(candidate) && candidate.get("uses") === "./.github/actions/restore-server-build",
		);
		assert.ok(isMap(restored));
		assert.equal(restored.get("id"), "server");
		for (const drillName of ["backup-restore", "pitr"]) {
			assert.equal(
				namedStep(drill, ["jobs", "postgres-drill"], `Run the PostgreSQL ${drillName} drill`).getIn(
					["env", "HEPHAESTUS_APPLICATION_JAR"],
				),
				`\${{ steps.server.outputs.executable-jar }}`,
			);
		}
		const changedPaths = step(
			parseDocument(orchestrator),
			["jobs", "detect-changes"],
			"dorny/paths-filter",
		);
		const filters = asRecord(
			parseDocument(asString(changedPaths.get("filters"), "filters")).toJS(),
			"filters",
		);
		const drillInputs = asArray(filters["build-config"], "build-config paths");
		for (const file of [
			"scripts/postgres-backup-restore-test.ts",
			"scripts/postgres-pitr-test.ts",
			"scripts/lib/database-migration.ts",
		]) {
			assert.ok(drillInputs.includes(file), `${file} must select the recovery drills`);
		}
		// docs/contributor/ci-cd.mdx § Parallelism and concurrency owns action-context setup ordering.
		for (const [file, names] of [
			[".github/workflows/ci-build.yml", ["server-api", "server-database", "postgres-drill"]],
			[".github/workflows/ci-browser-tests.yml", ["webapp-e2e", "extension-e2e"]],
			[".github/workflows/ci-tests.yml", ["server-verification", "server-integration"]],
		] as const) {
			const workflow = parseDocument(await readFile(file, "utf8"));
			for (const name of names) {
				const steps = workflow.getIn(["jobs", name, "steps"]);
				assert.ok(isSeq(steps));
				const firstCommand = steps.items.findIndex((item) => isMap(item) && item.has("run"));
				assert.ok(firstCommand > 0, `${name} prepares its consumer before running it`);
				const preparation = steps.items.slice(0, firstCommand);
				assert.ok(
					preparation.every(
						(item) => isMap(item) && item.has("uses") && item.get("background") !== true,
					),
					`${name} must finish each action before starting the next`,
				);
				const uses = preparation.map((item) => {
					assert.ok(isMap(item));
					return String(item.get("uses"));
				});
				if (uses.includes("./.github/actions/setup-browsers")) {
					assert.ok(
						uses.indexOf("./.github/actions/setup-toolchain") <
							uses.indexOf("./.github/actions/setup-browsers") &&
							uses.includes("./.github/actions/setup-toolchain"),
						`${name} installs its toolchain before browsers`,
					);
				}
				assert.ok(
					uses.some(
						(action) =>
							action === "./.github/actions/restore-server-build" ||
							action.startsWith("actions/download-artifact@"),
					),
					`${name} does not hold the packaged server before its consumer`,
				);
			}
		}

		for (const name of ["webapp-e2e", "extension-e2e"]) {
			const e2e = job(build, name);
			assert.doesNotMatch(e2e, /needs:/u);
			assert.equal((e2e.match(/actions\/download-artifact@/gu) ?? []).length, 1);
			assert.match(e2e, /name: Upload diagnostics\s+if: always\(\)/u);
			assert.match(e2e, /e2e-server\.log/u);
			assert.match(e2e, /http:\/\/localhost:8080\/readyz/u);
			assert.doesNotMatch(e2e, /actuator\/health\/liveness/u);
		}
		// The browser suites need the JAR, so either one's paths must start the package job.
		for (const filter of ["e2e", "extension-e2e"]) {
			assert.match(
				job(orchestrator, "server-package"),
				new RegExp(`needs\\.detect-changes\\.outputs\\.${escapeRegExp(filter)} == 'true'`, "u"),
			);
		}
		// The application-server image is built from this run's packaged JAR, never recompiled.
		const image = job(orchestrator, "Docker");
		assert.match(
			image,
			/needs: \[detect-changes, vulnerability-database, server-package, Security\]/u,
		);
		assert.match(image, /application-artifact: server-build-\$\{\{ github\.run_id \}\}/u);
		assert.match(image, /needs\.server-package\.result == 'success'/u);

		// The suites run the packaged classes rather than compiling the tests once per job.
		const tests = await readFile(".github/workflows/ci-tests.yml", "utf8");
		assert.equal(
			parseDocument(tests).getIn(["env", "ORG_GRADLE_PROJECT_packagedServer"]),
			"true",
			"Every Gradle invocation in the suites consumes the restored classes",
		);
		for (const [name, command] of [
			[
				"server-verification",
				/TASK: test:server:\$\{\{ env\.TEST_TIER \}\}\n\s+run: vp run "\$TASK"/u,
			],
			["server-integration", /vp run test:server:integration/u],
		] as const) {
			const suite = job(tests, name);
			assert.match(suite, command);
			assert.match(suite, /uses: \.\/\.github\/actions\/restore-server-build/u);
			assert.doesNotMatch(suite, /^ {4}needs:/mu);
		}
		assert.match(job(orchestrator, "Build"), /needs: \[detect-changes, server-package\]/u);
		assert.match(job(orchestrator, "Test"), /needs: \[detect-changes, server-package, Security\]/u);
		// A package skipped while the suites are selected must fail them, never skip them.
		assert.match(job(orchestrator, "Test"), /needs\.server-package\.result != 'failure'/u);
		assert.doesNotMatch(job(orchestrator, "Test"), /needs\.server-package\.result == 'success'/u);
		// One output decides the suites, and packaging reads the same one, so they cannot disagree.
		assert.match(
			job(orchestrator, "Test"),
			/needs\.detect-changes\.outputs\.server-tests == 'true'/u,
		);
		assert.match(
			job(orchestrator, "server-package"),
			/needs\.detect-changes\.outputs\.server-tests == 'true'/u,
		);

		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		const packBuilds = [
			...reusable.replaceAll(/\\\n\s*/gu, " ").matchAll(/^\s+pack build .*$/gmu),
		].map((match) => match[0]);
		assert.ok(packBuilds.length > 0, "the buildpacks path must call pack build");
		for (const packBuild of packBuilds) {
			for (const flag of ["--path", "--descriptor", "--run-image", "dependencies[@]"]) {
				assert.ok(packBuild.includes(flag), `pack build must pass ${flag}`);
			}
		}
		// One invocation exports to the registry; the fork path builds the same image locally.
		assert.equal(packBuilds.filter((call) => call.includes("--publish")).length, 1);
		for (const [file, source] of await workflowSources()) {
			assert.doesNotMatch(source, /bootBuildImage/u, `${file} must build the image from the JAR`);
		}
	});

	void test("routes tooling-only changes away from server infrastructure", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const detection = job(source, "detect-changes");
		const tooling = pathFilter(detection, "tooling");
		for (const pattern of ["docs/**", ".vscode/settings.json", "**/AGENTS.md", "**/CLAUDE.md"]) {
			assert.match(tooling, new RegExp(`- '${escapeRegExp(pattern)}'`, "u"));
		}
		assert.doesNotMatch(pathFilter(detection, "application-server"), /- 'docs\/\*\*'/u);
		assert.match(pathFilter(detection, "postgres-image"), /- 'docker\/postgres\/\*\*'/u);
		assert.match(pathFilter(detection, "webapp-image"), /- 'patches\/\*\*'/u);
		assert.match(pathFilter(detection, "webapp-image"), /- 'docker\/agents\/pi\/patches\/\*\*'/u);
	});

	void test("generates verified Gradle snapshots without giving pull request code write access", async () => {
		const source = await readFile(".github/workflows/dependency-graph.yml", "utf8");
		const workflow = parseDocument(source);
		assert.equal(workflow.get("name"), "Security / Gradle dependency graph");
		assert.equal(workflow.getIn(["jobs", "generate", "permissions", "contents"]), "read");
		assert.doesNotMatch(
			source,
			/: write|verification=off|write-verification-metadata|dependency-submission@/u,
		);
		const setup = stepInputs(namedStep(workflow, ["jobs", "generate"], "Set up the Java build"));
		assert.equal(setup.get("dependency-graph"), "generate-and-upload");
		const resolve = namedStep(workflow, ["jobs", "generate"], "Resolve the dependency graph");
		assert.equal(resolve.get("working-directory"), "server");
		assert.equal(
			resolve.get("run"),
			"./gradlew dependencies buildEnvironment --no-configuration-cache",
		);
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

	void test("dependency reporting runs, with the Gradle snapshot it reads, when a dependency input changes", async () => {
		const globs = await pullRequestPaths(".github/workflows/dependency-review.yml");
		// Without the head snapshot, the review spends its whole retry window waiting for one.
		assert.deepEqual(await pullRequestPaths(".github/workflows/dependency-graph.yml"), globs);
		const requiresReport = (file: string) =>
			globs.some((pattern) => minimatch(file, pattern, { dot: true }));
		const tracked = spawnSync(
			"git",
			[
				"ls-files",
				"--",
				":(glob)**/package.json",
				":(glob)**/pnpm-lock.yaml",
				":(glob)**/pnpm-workspace.yaml",
				":(glob)**/*.gradle.kts",
				":(glob)**/*gradle.lockfile",
				":(glob)server/gradle/**",
				":(glob).github/workflows/**",
				":(glob).github/actions/**",
			],
			{ encoding: "utf8" },
		);
		assert.equal(tracked.status, 0, tracked.stderr);
		const manifests = tracked.stdout.trim().split("\n");
		assert.ok(manifests.includes("server/application/gradle.lockfile"));
		assert.deepEqual(
			manifests.filter((file) => !requiresReport(file)),
			[],
			"every manifest the dependency graph reads selects the review",
		);
		for (const [file, expected] of [
			["pnpm-workspace.yaml", true],
			["docker/agents/pi/pnpm-workspace.yaml", true],
			["server/gradle.properties", true],
			[".github/dependency-review-config.yml", true],
			[".changeset/review-progress.md", false],
			["docs/user/reviews.mdx", false],
			["server/application/src/main/java/Application.java", false],
			["server/application/src/main/resources/application.yml", false],
			["webapp/src/main.tsx", false],
		] as const) {
			assert.equal(requiresReport(file), expected, file);
		}
	});
});
