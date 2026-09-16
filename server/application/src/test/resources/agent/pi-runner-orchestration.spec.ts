import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	appendFileSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { mock, test } from "node:test";

import { hasText } from "../../../main/resources/agent/pi-text.ts";

const admittedObservation = {
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	outcome: "NEGATIVE",
	id: "observation-1",
	practiceSlug: "test-practice",
	assessment: "BAD",
	severity: "MAJOR",
	anchorable: true,
	citations: [
		{
			index: 0,
			sourceKind: "scm.pull-request.diff",
			path: "src/Auth.java",
			side: "NEW",
			startLine: 10,
			endLine: 10,
			anchorable: true,
		},
	],
};

/** What the runner exits with when `stage` fails: a stage that keeps its progress exits clean. */
function expectedStatus(stage: string): number {
	if (stage === "composer") {
		return 2;
	}
	if (stage === "composer-budget" || stage.endsWith("-init")) {
		return 0;
	}
	return 1;
}

function testTitle(stage: string): string {
	if (stage.endsWith("-init")) {
		return `preserves review progress when ${stage} throws`;
	}
	if (stage === "composer") {
		return "preserves admitted observations when composer initialization fails";
	}
	return `does not prompt a session whose ${stage} initialization exhausts the budget`;
}

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
if (hasText(scenario)) {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(hasText(cwd));
	let now = 1_000_000;
	mock.method(Date, "now", () => now);
	const record = (event: string) => appendFileSync(path.join(cwd, "events"), `${event}\n`);
	mock.method(globalThis, "fetch", async () =>
		Response.json({
			schemaVersion: 1,
			admissionDigest: "admitted-digest",
			observations: [admittedObservation],
		}),
	);
	let observerCount = 0;
	// The first observation-recording session is the observer; every later one is its retry.
	const laneOf = (tools: string[]): string => {
		if (tools.includes("report_feedback")) {
			return "composer";
		}
		if (!tools.includes("report_observation")) {
			return "recon";
		}
		observerCount += 1;
		return observerCount === 1 ? "observer" : "retry";
	};
	const manager = { getSessionFile: () => undefined, getSessionId: () => "test-session" };
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			defineTool: (tool: unknown) => tool,
			getAgentDir: () => cwd,
			DefaultResourceLoader: class {
				reload = mock.fn(async () => {
					// The double has nothing to load.
				});
			},
			SettingsManager: { create: () => ({}) },
			SessionManager: {
				create: () => manager,
				inMemory: () => manager,
				open: () => manager,
			},
			ModelRuntime: {
				async create() {
					if (scenario === "setup") {
						now += 20_000;
					}
					return {
						registerProvider() {
							// The double registers nothing.
						},
						getModel: () => ({ contextWindow: 128_000 }),
					};
				},
			},
			async createAgentSession(options: {
				tools: string[];
				customTools: {
					name: string;
					description: string;
					execute: (id: string, input: unknown) => Promise<unknown>;
				}[];
			}) {
				const lane = laneOf(options.tools);
				record(`create:${lane}`);
				if (scenario === `${lane}-init` || (lane === "composer" && scenario === "composer")) {
					throw new Error(`${lane} initialization failed`);
				}
				if (lane === "composer" && scenario === "composer-budget") {
					now += 20_000;
				}
				if (scenario === lane) {
					now += 20_000;
				}
				return {
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe: () => () => undefined,
						clearQueue() {
							// The double queues nothing.
						},
						abort: async () => {
							record(`abort:${lane}`);
						},
						dispose: () => record(`dispose:${lane}`),
						async prompt() {
							record(`prompt:${lane}`);
							if (lane === "recon") {
								throw new Error("Reconnaissance unavailable");
							}
							if (
								((scenario.startsWith("composer") ||
									scenario === "recon-init" ||
									scenario === "retry-init") &&
									lane === "observer") ||
								(scenario === "observer-init" && lane === "retry")
							) {
								const tool = options.customTools.find((item) => item.name === "report_observation");
								assert.ok(tool);
								assert.match(tool.description, /local review state/u);
								assert.match(tool.description, /not a dry-run validator/u);
								assert.match(tool.description, /durable-submission boundary/u);
								const reply = await tool.execute("report-1", {
									practiceSlug: "test-practice",
									summary: "Unsafe authentication call",
									assessmentStatus: "ASSESSED",
									presence: "PRESENT",
									assessment: "BAD",
									severity: "MAJOR",
									evidenceRationale: "The changed authentication code calls insecure().",
									evidence: {
										citations: [
											{
												sourceKind: "scm.pull-request.diff",
												artifactPath: "evidence/diff.patch",
												path: "src/Auth.java",
												side: "NEW",
												startLine: 10,
												endLine: 10,
												quote: "+ insecure();",
											},
										],
									},
								});
								assert.match(
									JSON.stringify(reply),
									/Each practice in this group has a recorded result/u,
								);
								assert.match(JSON.stringify(reply), /does not certify exhaustive review/u);
								assert.doesNotMatch(JSON.stringify(reply), /group is complete|Still required/u);
							}
						},
					},
				};
			},
		},
	});
	await import("../../../main/resources/agent/pi-runner.ts");
} else {
	for (const stage of [
		"setup",
		"recon",
		"observer",
		"retry",
		"composer",
		"composer-budget",
		"recon-init",
		"observer-init",
		"retry-init",
	]) {
		void test(testTitle(stage), () => {
			const cwd = mkdtempSync(path.join(tmpdir(), "pi-orchestration-"));
			try {
				mkdirSync(path.join(cwd, "catalog/practices"), { recursive: true });
				mkdirSync(path.join(cwd, "evidence"), { recursive: true });
				writeFileSync(path.join(cwd, "AGENTS.md"), "Review the staged evidence.");
				writeFileSync(
					path.join(cwd, "feedback-composer.md"),
					"Compose from admitted observations.",
				);
				writeFileSync(path.join(cwd, "events"), "");
				writeFileSync(
					path.join(cwd, "evidence/diff.patch"),
					"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
				);
				if (stage.startsWith("composer")) {
					writeFileSync(
						path.join(cwd, "evidence/composition.json"),
						JSON.stringify({
							enabled: true,
							channels: { IN_APP: { enabled: true, maxUnits: 1 } },
						}),
					);
				}
				writeFileSync(
					path.join(cwd, "evidence/manifest.json"),
					JSON.stringify({
						sources: [
							{
								kind: "scm.pull-request.diff",
								state: { availability: "AVAILABLE" },
								artifacts: [{ path: "evidence/diff.patch" }],
							},
						],
					}),
				);
				writeFileSync(
					path.join(cwd, "catalog/practices/index.json"),
					JSON.stringify(
						stage === "retry-init"
							? [{ slug: "test-practice" }, { slug: "missing-practice" }]
							: [{ slug: "test-practice" }],
					),
				);
				writeFileSync(
					path.join(cwd, "pi-provider.json"),
					JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
				);
				writeFileSync(
					path.join(cwd, "task.json"),
					JSON.stringify({
						schemaVersion: 2,
						paths: {
							contextRoot: "evidence",
							repositoryRoot: "repos/primary",
							manifest: "evidence/manifest.json",
							practiceIndex: "catalog/practices/index.json",
							compositionRequest: "evidence/composition.json",
							preparedFeedback: "history/prepared.json",
							precomputeScripts: "scripts/practices",
						},
						task: { kind: "practice_review", prompt: "Review the practice." },
					}),
				);
				const child = spawnSync(
					process.execPath,
					["--experimental-test-module-mocks", import.meta.filename],
					{
						env: {
							...process.env,
							PI_ORCHESTRATION_SCENARIO: stage,
							PI_RUNNER_CWD: cwd,
							PI_CODING_AGENT_DIR: cwd,
							AGENT_BUDGET_MS: "10000",
							LLM_PROXY_URL: "https://unused.invalid",
							LLM_PROXY_TOKEN: "test-token",
						},
						encoding: "utf8",
						timeout: 10_000,
					},
				);
				assert.equal(child.error, undefined);
				assert.equal(child.status, expectedStatus(stage), child.stderr);
				const events = readFileSync(path.join(cwd, "events"), "utf8")
					.trim()
					.split("\n")
					.filter(Boolean);
				if (stage === "setup") {
					assert.deepEqual(events, []);
				} else if (stage.startsWith("composer")) {
					assert.ok(events.includes("create:composer"), child.stderr);
					assert.ok(!events.includes("prompt:composer"));
					if (stage === "composer-budget") {
						assert.ok(events.includes("dispose:composer"));
					}
				} else if (stage.endsWith("-init")) {
					const lane = stage.slice(0, -5);
					assert.ok(events.includes(`create:${lane}`), child.stderr);
					assert.ok(!events.includes(`prompt:${lane}`));
					assert.ok(
						events.includes(stage === "observer-init" ? "prompt:retry" : "prompt:observer"),
					);
				} else {
					assert.ok(events.includes(`create:${stage}`), child.stderr);
					assert.ok(events.includes(`dispose:${stage}`), child.stderr);
					assert.ok(!events.includes(`prompt:${stage}`), events.join("\n"));
				}
				if (stage.startsWith("composer") || stage.endsWith("-init")) {
					const feedback: unknown = JSON.parse(
						readFileSync(path.join(cwd, "out/feedback.json"), "utf8"),
					);
					assert.deepEqual(feedback, {
						admissionDigest: "admitted-digest",
						observations: [admittedObservation],
						preparedTargets: [],
						units: [],
						lead: null,
					});
				}
				const coverage: unknown = JSON.parse(
					readFileSync(path.join(cwd, "out/practice-coverage.json"), "utf8"),
				);
				assert.deepEqual(coverage, {
					eligible: stage === "retry-init" ? 2 : 1,
					evaluated: stage.startsWith("composer") || stage.endsWith("-init") ? 1 : 0,
					outcomes: [
						{
							practiceSlug: "test-practice",
							outcome:
								stage.startsWith("composer") || stage.endsWith("-init")
									? "EVALUATED"
									: "NOT_REACHED",
						},
						...(stage === "retry-init"
							? [{ practiceSlug: "missing-practice", outcome: "NOT_REACHED" }]
							: []),
					],
				});
			} finally {
				rmSync(cwd, { recursive: true, force: true });
			}
		});
	}
}
