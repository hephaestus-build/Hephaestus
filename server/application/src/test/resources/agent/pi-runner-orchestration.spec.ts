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

// The runner reads /workspace and the environment at module scope, so each scenario is a child
// process: this file re-enters itself with the SDK mocked and drives one review through it.

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

const changeCitation = {
	sourceKind: "scm.pull-request.diff",
	artifactPath: "evidence/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

function observation(slug: string, summary: string, citation: unknown = changeCitation) {
	return {
		practiceSlug: slug,
		summary,
		assessmentStatus: "ASSESSED",
		presence: "PRESENT",
		assessment: "BAD",
		severity: "MAJOR",
		evidenceRationale: "The changed authentication code calls insecure().",
		evidence: { citations: [citation] },
	};
}

interface CustomTool {
	name: string;
	description: string;
	execute: (id: string, input: unknown) => Promise<unknown>;
}

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
	const manager = { getSessionFile: () => undefined, getSessionId: () => "test-session" };
	let prompts = 0;
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
			SessionManager: { create: () => manager, inMemory: () => manager, open: () => manager },
			ModelRuntime: {
				create() {
					if (scenario === "setup") now += 20_000;
					return Promise.resolve({
						registerProvider() {},
						getModel: () => ({ contextWindow: 128_000 }),
					});
				},
			},
			createAgentSession(options: { tools: string[]; customTools: CustomTool[] }) {
				record(`create:session tools=${options.tools.join(",")}`);
				if (scenario === "session-init") throw new Error("session initialization failed");
				const tool = (name: string) => {
					const found = options.customTools.find((item) => item.name === name);
					assert.ok(found, `${name} is registered`);
					return found;
				};
				return Promise.resolve({
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe: () => () => {},
						clearQueue() {},
						abort: () => Promise.resolve(record("abort")),
						dispose: () => record("dispose"),
						steer: () => Promise.resolve(record("steer")),
						async prompt(text: string) {
							prompts++;
							record(`prompt:${prompts}`);
							writeFileSync(join(cwd, `prompt-${prompts}.md`), text);
							if (scenario === "budget") {
								now += 20_000;
								return;
							}
							if (text.includes("## This turn")) {
								// The composition turn, in the same session.
								const reply = await tool("report_feedback").execute("f-1", {
									units: [
										{
											channel: "IN_APP",
											practiceSlug: "test-practice",
											basedOn: ["observation-1"],
											action: "NEW",
											title: "Insecure call",
											body: "The pattern across your work.",
											nextStep: "Check the call before pushing.",
										},
										{
											channel: "IN_APP",
											practiceSlug: "test-practice",
											action: "NEW",
											basedOn: [],
										},
									],
								});
								record(`feedback:${JSON.stringify(reply)}`);
								return;
							}
							if (text.includes("## Unfinished practices")) {
								const reply = await tool("report_observation").execute("o-3", {
									observations: [observation("second-practice", "Recorded on the finishing turn")],
								});
								record(`finish:${JSON.stringify(reply)}`);
								return;
							}
							const report = tool("report_observation");
							assert.match(report.description, /local review state/);
							assert.match(report.description, /not a dry-run validator/);
							if (scenario === "refusal-cap") {
								const wrong = observation("test-practice", "Wrong quote", {
									...changeCitation,
									quote: "+ notInTheDiff();",
								});
								for (let attempt = 1; attempt <= 9; attempt++) {
									await report
										.execute(`o-${attempt}`, { observations: [wrong] })
										.then(() => record(`refusal-${attempt}:accepted`))
										.catch((error: unknown) =>
											record(
												`refusal-${attempt}:${error instanceof Error ? error.message : String(error)}`,
											),
										);
								}
								return;
							}
							if (scenario === "tree-citation") {
								const cite = (path: string, quote: string) =>
									report.execute("o-1", {
										observations: [
											observation("test-practice", "Unsafe authentication call", {
												sourceKind: "scm.repository.tree",
												artifactPath: "repos/primary/.git/HEAD",
												path,
												startLine: 2,
												quote,
											}),
										],
									});
								await assert.rejects(
									cite("src/Auth.java", "insecure(user);"),
									/\[L2\] reads " {2}insecure\(\);", not "insecure\(user\);"/,
								);
								await assert.rejects(cite("src/Missing.java", "insecure();"), /no such file/);
								await assert.rejects(cite("../task.json", "schemaVersion"), /no such file/);
								record("citation:refused");
								await cite("src/Auth.java", "insecure();");
								record("citation:stored");
								writeFileSync(join(cwd, "out", "stray.txt"), "left by a session");
								return;
							}
							// The ordinary turn: two observations in one call, one of them refused.
							const reply = await report.execute("o-1", {
								observations: [
									observation("test-practice", "Unsafe authentication call"),
									observation("test-practice", "A quote that is not in the change", {
										...changeCitation,
										quote: "+ somethingElse();",
									}),
								],
							});
							record(`batch:${JSON.stringify(reply)}`);
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
		"session-init",
		"budget",
		"batch",
		"finish",
		"refusal-cap",
		"tree-citation",
		"compose",
	]) {
		void test(
			{
				setup: "does not start a session when setup exhausts the budget",
				"session-init": "fails cleanly when the session cannot be created",
				budget: "aborts a turn that runs past its share and reports the practices as not reached",
				batch: "stores several observations from one call and answers per item",
				finish: "asks once more, in the same session, for the practices no turn recorded",
				"refusal-cap": "stops accepting a practice after eight refused submissions",
				"tree-citation":
					"verifies a HEAD repository citation against the checkout and finalizes out/",
				compose: "composes feedback in the same session from the admitted observations",
			}[stage] ?? stage,
			() => {
				const cwd = mkdtempSync(join(tmpdir(), "pi-orchestration-"));
				try {
					mkdirSync(join(cwd, "catalog/practices"), { recursive: true });
					mkdirSync(join(cwd, "evidence"), { recursive: true });
					mkdirSync(join(cwd, "work/change"), { recursive: true });
					writeFileSync(join(cwd, "AGENTS.md"), "Review the staged evidence.");
					writeFileSync(join(cwd, "feedback-composer.md"), "Compose from admitted observations.");
					writeFileSync(join(cwd, "events"), "");
					writeFileSync(
						join(cwd, "evidence/metadata.json"),
						JSON.stringify({ title: "Add login" }),
					);
					writeFileSync(
						join(cwd, "evidence/change.json"),
						JSON.stringify({ base_sha: "b".repeat(40), head_sha: "a".repeat(40) }),
					);
					// The change view the container derives from the checkout before the runner starts.
					writeFileSync(
						join(cwd, "work/change/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					writeFileSync(
						join(cwd, "catalog/practices/test-practice.md"),
						"# Test practice\nCriteria.",
					);
					if (stage === "compose") {
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
									kind: "scm.pull-request.core",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "evidence/metadata.json" }],
								},
								{
									kind: "scm.pull-request.diff",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "evidence/change.json" }],
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
							stage === "finish"
								? [
										{ slug: "test-practice", group: "code" },
										{ slug: "second-practice", group: "code" },
									]
								: [{ slug: "test-practice", group: "code" }],
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
					const events = readFileSync(join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
					const coverage: unknown = JSON.parse(
						readFileSync(join(cwd, "out/practice-coverage.json"), "utf8"),
					);
					const reached = (slugs: Record<string, "EVALUATED" | "NOT_REACHED">) =>
						assert.deepEqual(coverage, {
							eligible: Object.keys(slugs).length,
							evaluated: Object.values(slugs).filter((outcome) => outcome === "EVALUATED").length,
							outcomes: Object.entries(slugs).map(([practiceSlug, outcome]) => ({
								practiceSlug,
								outcome,
							})),
						});
					switch (stage) {
						case "setup":
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(events, []);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						case "session-init":
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.length, 1);
							break;
						case "budget":
							assert.equal(child.status, 1, child.stderr);
							assert.ok(events.includes("prompt:1"), child.stderr);
							assert.ok(events.includes("abort"), child.stderr);
							assert.ok(!events.includes("prompt:2"), events.join("\n"));
							reached({ "test-practice": "NOT_REACHED" });
							break;
						case "batch": {
							assert.equal(child.status, 0, child.stderr);
							const reply = events.find((event) => event.startsWith("batch:")) ?? "";
							assert.match(reply, /#1 test-practice: stored \(negative\)/);
							assert.match(
								reply,
								/#2 test-practice: refused — .*not in the diff|#2 test-practice: refused/,
							);
							assert.match(reply, /Every practice of this turn has a recorded result/);
							// One session, one measuring turn, no composition requested.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const first = readFileSync(join(cwd, "prompt-1.md"), "utf8");
							assert.match(first, /Review the practice\./);
							assert.match(first, /### `evidence\/metadata\.json`/);
							assert.match(first, /### `work\/change\/diff\.patch`/);
							assert.match(
								first,
								/### Practice `test-practice`\n[\s\S]*# Test practice\nCriteria\./,
							);
							assert.match(
								readFileSync(join(cwd, "work/notes/review.md"), "utf8"),
								/test-practice: PRESENT\/BAD — Unsafe authentication call/,
							);
							// The quote was copied with its diff marker; what is recorded is the line's content,
							// which is what admission reads out of the blob.
							assert.match(
								readFileSync(join(cwd, "out/review-state.json"), "utf8"),
								/"quote": " insecure\(\);"/,
							);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "finish": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const second = readFileSync(join(cwd, "prompt-2.md"), "utf8");
							assert.match(second, /## Recorded so far\n- test-practice: PRESENT\/BAD/);
							assert.match(second, /No observation was recorded for: second-practice/);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "refusal-cap": {
							assert.equal(child.status, 1, child.stderr);
							const refusals = events.filter((event) => event.startsWith("refusal-"));
							assert.equal(refusals.length, 9);
							assert.match(refusals[7] ?? "", /refused/);
							assert.match(
								refusals[8] ?? "",
								/8 submissions for 'test-practice' were refused; no more are accepted/,
							);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "tree-citation": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("citation:")),
								["citation:refused", "citation:stored"],
								child.stderr,
							);
							assert.ok(!existsSync(join(cwd, "out/stray.txt")));
							const result: unknown = JSON.parse(
								readFileSync(join(cwd, "out/result.json"), "utf8"),
							);
							assert.ok(
								typeof result === "object" && result !== null && "admissionDigest" in result,
							);
							assert.equal(result.admissionDigest, "admitted-digest");
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "compose": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const reply = events.find((event) => event.startsWith("feedback:")) ?? "";
							assert.match(reply, /#1: stored a IN_APP unit for test-practice \(NEW\); 1\/1 used/);
							assert.match(reply, /#2: a feedback unit needs a channel/);
							const second = readFileSync(join(cwd, "prompt-2.md"), "utf8");
							assert.match(second, /Compose from admitted observations\./);
							assert.match(second, /"id": "observation-1"/);
							const feedback: unknown = JSON.parse(
								readFileSync(join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							assert.equal(Reflect.get(feedback, "admissionDigest"), "admitted-digest");
							const units: unknown = Reflect.get(feedback, "units");
							assert.ok(Array.isArray(units) && units.length === 1);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						default:
							assert.fail(`unknown stage ${stage}`);
					}
				} finally {
					rmSync(cwd, { recursive: true, force: true });
				}
			},
		);
	}
}
