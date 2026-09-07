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
import { join } from "node:path";
import { mock, test } from "node:test";

const admittedObservation = {
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

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
if (scenario) {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd);
	let now = 1_000_000;
	mock.method(Date, "now", () => now);
	const record = (event: string) => appendFileSync(join(cwd, "events"), `${event}\n`);
	mock.method(globalThis, "fetch", () =>
		Promise.resolve(
			Response.json({
				schemaVersion: 1,
				admissionDigest: "admitted-digest",
				observations: [admittedObservation],
			}),
		),
	);
	let observerCount = 0;
	const manager = { getSessionFile: () => undefined };
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			defineTool: (tool: unknown) => tool,
			getAgentDir: () => cwd,
			DefaultResourceLoader: class {
				reload() {
					return Promise.resolve();
				}
			},
			SettingsManager: { create: () => ({}) },
			SessionManager: {
				create: () => manager,
				inMemory: () => manager,
				open: () => manager,
			},
			ModelRuntime: {
				create() {
					if (scenario === "setup") now += 20_000;
					return Promise.resolve({
						registerProvider() {},
						getModel: () => ({ contextWindow: 128_000 }),
					});
				},
			},
			createAgentSession(options: {
				tools: string[];
				customTools: Array<{
					name: string;
					execute: (id: string, input: unknown) => Promise<unknown>;
				}>;
			}) {
				const lane = options.tools.includes("report_feedback")
					? "composer"
					: options.tools.includes("report_observation")
						? ++observerCount === 1
							? "observer"
							: "retry"
						: "recon";
				record(`create:${lane}`);
				if (lane === "composer" && scenario === "composer")
					throw new Error("Composer initialization failed");
				if (lane === "composer" && scenario === "composer-budget") now += 20_000;
				if (scenario === lane) now += 20_000;
				return Promise.resolve({
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe: () => () => {},
						clearQueue() {},
						dispose: () => record(`dispose:${lane}`),
						async prompt() {
							record(`prompt:${lane}`);
							if (lane === "recon") throw new Error("Reconnaissance unavailable");
							if (scenario.startsWith("composer") && lane === "observer") {
								const tool = options.customTools.find((item) => item.name === "report_observation");
								assert.ok(tool);
								await tool.execute("report-1", {
									practiceSlug: "test-practice",
									summary: "Unsafe authentication call",
									outcome: "BEHAVIOR_PRESENT_BAD_MAJOR",
									evidenceRationale: "The changed authentication code calls insecure().",
									evidence: {
										citations: [
											{
												sourceKind: "scm.pull-request.diff",
												artifactPath: "inputs/diff.patch",
												path: "src/Auth.java",
												side: "NEW",
												startLine: 10,
												endLine: 10,
												quote: "+ insecure();",
											},
										],
									},
								});
							}
						},
					},
				});
			},
		},
	});
	await import("../../../main/resources/agent/pi-runner.ts");
} else {
	for (const stage of ["setup", "recon", "observer", "retry", "composer", "composer-budget"]) {
		void test(
			stage === "composer"
				? "preserves admitted observations when composer initialization fails"
				: `does not prompt a session whose ${stage} initialization exhausts the budget`,
			() => {
				const cwd = mkdtempSync(join(tmpdir(), "pi-orchestration-"));
				try {
					mkdirSync(join(cwd, "inputs/practices"), { recursive: true });
					writeFileSync(join(cwd, "AGENTS.md"), "Review the staged evidence.");
					writeFileSync(join(cwd, "feedback-composer.md"), "Compose from admitted observations.");
					writeFileSync(join(cwd, "events"), "");
					writeFileSync(
						join(cwd, "inputs/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					if (stage.startsWith("composer")) {
						writeFileSync(
							join(cwd, "inputs/feedback-composition.json"),
							JSON.stringify({
								enabled: true,
								channels: { IN_APP: { enabled: true, maxUnits: 1 } },
							}),
						);
					}
					writeFileSync(
						join(cwd, "inputs/manifest.json"),
						JSON.stringify({
							sources: [
								{
									kind: "scm.pull-request.diff",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "inputs/diff.patch" }],
								},
							],
						}),
					);
					writeFileSync(
						join(cwd, "inputs/practices/index.json"),
						JSON.stringify([{ slug: "test-practice" }]),
					);
					writeFileSync(
						join(cwd, "pi-provider.json"),
						JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
					);
					writeFileSync(
						join(cwd, "task.json"),
						JSON.stringify({
							schemaVersion: 1,
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
					assert.equal(
						child.status,
						stage === "composer" ? 2 : stage === "composer-budget" ? 0 : 1,
						child.stderr,
					);
					const events = readFileSync(join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
					if (stage === "setup") {
						assert.deepEqual(events, []);
					} else if (stage.startsWith("composer")) {
						assert.ok(events.includes("create:composer"), child.stderr);
						assert.ok(!events.includes("prompt:composer"));
						if (stage === "composer-budget") assert.ok(events.includes("dispose:composer"));
						const feedback: unknown = JSON.parse(
							readFileSync(join(cwd, "out/feedback.json"), "utf8"),
						);
						assert.deepEqual(feedback, {
							admissionDigest: "admitted-digest",
							observations: [admittedObservation],
							preparedTargets: [],
							units: [],
							lead: null,
						});
					} else {
						assert.ok(events.includes(`create:${stage}`), child.stderr);
						assert.ok(events.includes(`dispose:${stage}`), child.stderr);
						assert.ok(!events.includes(`prompt:${stage}`), events.join("\n"));
					}
					const coverage: unknown = JSON.parse(
						readFileSync(join(cwd, "out/practice-coverage.json"), "utf8"),
					);
					assert.deepEqual(coverage, {
						eligible: 1,
						evaluated: stage.startsWith("composer") ? 1 : 0,
						outcomes: [
							{
								practiceSlug: "test-practice",
								outcome: stage.startsWith("composer") ? "EVALUATED" : "NOT_REACHED",
							},
						],
					});
				} finally {
					rmSync(cwd, { recursive: true, force: true });
				}
			},
		);
	}
}
