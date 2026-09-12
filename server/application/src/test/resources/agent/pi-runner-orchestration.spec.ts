import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	appendFileSync,
	existsSync,
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
	const manager = { getSessionFile: () => undefined, getSessionId: () => "test-session" };
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
				if (lane === "composer") {
					for (const tool of ["write", "edit", "bash"]) {
						assert.ok(!options.tools.includes(tool), `Composer must not carry ${tool}`);
					}
				}
				record(`create:${lane}`);
				if (scenario === `${lane}-init` || (lane === "composer" && scenario === "composer"))
					throw new Error(`${lane} initialization failed`);
				if (lane === "composer" && scenario === "composer-budget") now += 20_000;
				if (scenario === lane) now += 20_000;
				return Promise.resolve({
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe: () => () => {},
						clearQueue() {},
						abort: () => Promise.resolve(record(`abort:${lane}`)),
						dispose: () => record(`dispose:${lane}`),
						async prompt() {
							record(`prompt:${lane}`);
							if (lane === "recon") throw new Error("Reconnaissance unavailable");
							if (scenario === "tree-citation" && lane === "observer") {
								const tool = options.customTools.find((item) => item.name === "report_observation");
								assert.ok(tool);
								// The tool refuses synchronously; the session sees that as a failed call.
								const cite = (path: string, quote: string) =>
									new Promise<unknown>((resolve) => {
										resolve(
											tool.execute("report-1", {
												practiceSlug: "test-practice",
												summary: "Unsafe authentication call",
												assessmentStatus: "ASSESSED",
												presence: "PRESENT",
												assessment: "BAD",
												severity: "MAJOR",
												evidenceRationale: "The authentication code calls insecure().",
												evidence: {
													citations: [
														{
															sourceKind: "scm.repository.tree",
															artifactPath: "repos/primary/.git/HEAD",
															path,
															startLine: 2,
															quote,
														},
													],
												},
											}),
										);
									});
								await assert.rejects(
									cite("src/Auth.java", "insecure(user);"),
									/not in the artifact/,
								);
								await assert.rejects(cite("src/Missing.java", "insecure();"), /no such file/);
								await assert.rejects(cite("../task.json", "schemaVersion"), /no such file/);
								record("citation:refused");
								await cite("src/Auth.java", "insecure();");
								record("citation:stored");
								writeFileSync(join(cwd, "out", "stray.txt"), "left by a session");
								return;
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
								await tool.execute("report-1", {
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
							}
						},
					},
				});
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
		"tree-citation",
	]) {
		void test(
			stage.endsWith("-init")
				? `preserves review progress when ${stage} throws`
				: stage === "composer"
					? "preserves admitted observations when composer initialization fails"
					: stage === "tree-citation"
						? "verifies a HEAD repository citation against the checkout and finalizes out/"
						: `does not prompt a session whose ${stage} initialization exhausts the budget`,
			() => {
				const cwd = mkdtempSync(join(tmpdir(), "pi-orchestration-"));
				try {
					mkdirSync(join(cwd, "catalog/practices"), { recursive: true });
					mkdirSync(join(cwd, "evidence"), { recursive: true });
					writeFileSync(join(cwd, "AGENTS.md"), "Review the staged evidence.");
					writeFileSync(join(cwd, "feedback-composer.md"), "Compose from admitted observations.");
					writeFileSync(join(cwd, "events"), "");
					writeFileSync(
						join(cwd, "evidence/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					if (stage.startsWith("composer")) {
						writeFileSync(
							join(cwd, "evidence/composition.json"),
							JSON.stringify({
								enabled: true,
								channels: { IN_APP: { enabled: true, maxUnits: 1 } },
							}),
						);
					}
					if (stage === "tree-citation") {
						mkdirSync(join(cwd, "repos/primary/.git"), { recursive: true });
						mkdirSync(join(cwd, "repos/primary/src"), { recursive: true });
						writeFileSync(join(cwd, "repos/primary/.git/HEAD"), `${"a".repeat(40)}\n`);
						writeFileSync(
							join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n}\n",
						);
					}
					writeFileSync(
						join(cwd, "evidence/manifest.json"),
						JSON.stringify({
							sources: [
								{
									kind: "scm.pull-request.diff",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "evidence/diff.patch" }],
								},
								...(stage === "tree-citation"
									? [
											{
												kind: "scm.repository.tree",
												state: { availability: "AVAILABLE" },
												artifacts: [{ path: "repos/primary/.git/HEAD" }],
											},
										]
									: []),
							],
						}),
					);
					writeFileSync(
						join(cwd, "catalog/practices/index.json"),
						JSON.stringify(
							stage === "retry-init"
								? [{ slug: "test-practice" }, { slug: "missing-practice" }]
								: [{ slug: "test-practice" }],
						),
					);
					writeFileSync(
						join(cwd, "pi-provider.json"),
						JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
					);
					writeFileSync(
						join(cwd, "task.json"),
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
					assert.equal(
						child.status,
						stage === "composer"
							? 2
							: stage === "composer-budget" || stage.endsWith("-init") || stage === "tree-citation"
								? 0
								: 1,
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
					} else if (stage === "tree-citation") {
						assert.deepEqual(
							events.filter((event) => event.startsWith("citation:")),
							["citation:refused", "citation:stored"],
							child.stderr,
						);
						assert.ok(!existsSync(join(cwd, "out/stray.txt")));
						const result: unknown = JSON.parse(readFileSync(join(cwd, "out/result.json"), "utf8"));
						assert.ok(typeof result === "object" && result !== null && "admissionDigest" in result);
						assert.equal(result.admissionDigest, "admitted-digest");
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
							readFileSync(join(cwd, "out/feedback.json"), "utf8"),
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
						readFileSync(join(cwd, "out/practice-coverage.json"), "utf8"),
					);
					assert.deepEqual(coverage, {
						eligible: stage === "retry-init" ? 2 : 1,
						evaluated:
							stage.startsWith("composer") || stage.endsWith("-init") || stage === "tree-citation"
								? 1
								: 0,
						outcomes: [
							{
								practiceSlug: "test-practice",
								outcome:
									stage.startsWith("composer") ||
									stage.endsWith("-init") ||
									stage === "tree-citation"
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
			},
		);
	}
}
