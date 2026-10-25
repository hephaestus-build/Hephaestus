import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	realpathSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { mock, test } from "node:test";
import { scan } from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";

import {
	GOLDEN_RECORD,
	GOLDEN_DIRECTIONS,
	GOLDEN_FACTS,
	GOLDEN_INPUTS,
	GOLDEN_UNRATED,
	LEAD_LABELS,
	PRACTICES_DIR,
	goldenLeads,
	stageGoldenWorkspace,
} from "./golden-review-workspace.ts";

// Run each golden capture in its own process; runner paths and state are module-local.

import { SUPPORTED_SCHEMA_VERSION } from "../../../main/resources/agent/pi-task-paths.ts";
import { mockChange, mockPiCodingAgent } from "./program-host-runner.ts";

const GOLDEN = nodePath.join(import.meta.dirname, "golden-prompts");

/** The whole assignment section of a practice turn, up to the next heading; empty when the turn has none. */
function assignmentOf(prompt: string): string {
	return (
		/^## Assignment\n(?<text>[\s\S]*?)(?=^#{1,3} |(?![\s\S]))/mu
			.exec(prompt)
			?.groups?.text?.trim() ?? ""
	);
}
const LIB = nodePath.resolve(
	import.meta.dirname,
	"../../../../../../docker/agents/practice-scripts/lib",
);

const recordCitation = {
	sourceKind: "scm.pull-request.core",
	sourcePath: "evidence/metadata.json",
	path: "evidence/metadata.json",
	startLine: 1,
	endLine: 1,
	quote: JSON.stringify(GOLDEN_RECORD),
};

function preparingProgram(many: boolean): string {
	const leads = goldenLeads(many).map(({ kind, ...lead }) => ({
		...lead,
		label: LEAD_LABELS[kind],
	}));
	return `import { definePracticeScript } from "../lib/core/practice-script.ts";
export default definePracticeScript({
meta: {},
async run(ctx) {
	return ctx.review(${JSON.stringify({ leads, facts: GOLDEN_FACTS, directions: GOLDEN_DIRECTIONS, limitations: GOLDEN_UNRATED })});
},
});
`;
}

function runScenario(name: string): Map<string, string> {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-golden-")));
	try {
		stageGoldenWorkspace(
			cwd,
			SUPPORTED_SCHEMA_VERSION,
			readFileSync(
				new URL("../../../main/resources/agent/pi-orchestrator.md", import.meta.url),
				"utf8",
			),
		);
		const roles: Record<string, { kind: string; about: unknown }> = {
			reviewer: {
				kind: "scm.pull_request_review",
				about: { role: "REVIEWER", developerId: "8675309" },
			},
			assignee: { kind: "scm.pull_request", about: { role: "ASSIGNEE", developerId: "8675309" } },
			participant: { kind: "chat.thread", about: { role: "AUTHOR", developerId: "8675309" } },
			unattributed: { kind: "scm.pull_request", about: null },
		};
		const role = roles[name];
		if (role !== undefined) {
			writeFileSync(
				nodePath.join(cwd, PRACTICES_DIR, "test-practice.inputs.json"),
				JSON.stringify({
					...GOLDEN_INPUTS,
					kind: role.kind,
					about: role.about,
					scope: { ...GOLDEN_INPUTS.scope, item: { ...GOLDEN_INPUTS.scope.item, kind: role.kind } },
				}),
			);
		}
		if (name === "scan") {
			const inputs = scan("src/healthy.ts");
			inputs.practice.slug = "test-practice";
			mkdirSync(nodePath.join(cwd, "context"), { recursive: true });
			mkdirSync(nodePath.join(cwd, "repos/reviewed/src"), { recursive: true });
			writeFileSync(nodePath.join(cwd, "context/item.json"), JSON.stringify(inputs.item));

			writeFileSync(nodePath.join(cwd, "repos/reviewed/src/healthy.ts"), "const healthy = true;\n");
			const task: unknown = JSON.parse(readFileSync(nodePath.join(cwd, "task.json"), "utf8"));
			assert.ok(typeof task === "object" && task !== null);
			writeFileSync(
				nodePath.join(cwd, "task.json"),
				JSON.stringify({
					...task,
					contextRoot: "context",
					repositoryRoot: "repos/reviewed",
					manifest: "INDEX.json",
				}),
			);
			writeFileSync(
				nodePath.join(cwd, PRACTICES_DIR, "test-practice.inputs.json"),
				JSON.stringify(inputs),
			);
			writeFileSync(
				nodePath.join(cwd, "catalog/practices/index.json"),
				JSON.stringify([
					{
						slug: "test-practice",
						name: "Test practice",
						readsSources: ["scm.repository.tree"],
						exhaustiveSources: ["scm.repository.tree"],
						contextPaths: [],
					},
				]),
			);
			writeFileSync(
				nodePath.join(cwd, "INDEX.json"),
				JSON.stringify({
					itemKind: "scm.file",
					files: [{ kind: "scm.repository.tree", file: { path: "context/item.json" } }],
					citableTexts: [
						{
							source: "scm.repository.tree",
							path: "src/healthy.ts",
							side: "NEW",
							commit: "a".repeat(40),
							stagedPath: "repos/reviewed/src/healthy.ts",
							sha256: createHash("sha256").update("const healthy = true;\n").digest("hex"),
						},
					],
					sources: [
						{
							kind: "scm.repository.tree",
							state: { availability: "AVAILABLE", completeness: "COMPLETE" },
							files: [{ path: "context/item.json" }],
							citation: { side: "FORBIDDEN", commit: "OPTIONAL" },
						},
					],
				}),
			);
		}

		if (name === "registered-text") {
			const text = "First line.\nSecond line is the open question.\n";
			writeFileSync(nodePath.join(cwd, "evidence/message.txt"), text);
			writeFileSync(
				nodePath.join(cwd, "catalog/practices/index.json"),
				JSON.stringify([
					{
						slug: "test-practice",
						name: "Test practice",
						readsSources: ["scm.pull-request.core"],
						exhaustiveSources: ["scm.pull-request.core"],
					},
				]),
			);
			writeFileSync(
				nodePath.join(cwd, "evidence/manifest.json"),
				JSON.stringify({
					itemKind: "scm.pull_request",
					files: [{ kind: "scm.pull-request.core", file: { path: "evidence/metadata.json" } }],
					citableTexts: [
						{
							source: "scm.pull-request.core",
							path: "thread/message",
							side: "NEW",
							commit: null,
							stagedPath: "evidence/message.txt",
							sha256: createHash("sha256").update(text).digest("hex"),
						},
					],
					sources: [
						{
							kind: "scm.pull-request.core",
							state: { availability: "AVAILABLE", completeness: "COMPLETE" },
							files: [{ path: "evidence/metadata.json" }],
							citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
						},
					],
				}),
			);
		}

		if (name.startsWith("leads")) {
			writeFileSync(nodePath.join(cwd, "package.json"), '{"type":"module"}\n');
			symlinkSync(LIB, nodePath.join(cwd, nodePath.dirname(PRACTICES_DIR), "lib"));
			writeFileSync(
				nodePath.join(cwd, PRACTICES_DIR, "test-practice.ts"),
				preparingProgram(name === "leads-truncated"),
			);
		}
		const child = spawnSync(
			process.execPath,
			["--experimental-test-module-mocks", import.meta.filename],
			{
				env: {
					...process.env,
					PI_GOLDEN_SCENARIO: name.startsWith("leads") ? "leads" : name,
					PI_RUNNER_CWD: cwd,
					PI_CODING_AGENT_DIR: cwd,
					AGENT_BUDGET_MS: "60000",
					PI_PRACTICE_MODEL_CALLS: "2",
					PI_PRACTICE_CONCURRENCY: "1",
					PI_PRACTICE_OUTPUT_TOKENS: "8000",
					PI_DIRECT_MODEL_TOKENS: "1000",
					LLM_PROXY_URL: "https://unused.invalid",
					LLM_PROXY_TOKEN: "test-token",
				},
				encoding: "utf8",
				timeout: 30_000,
			},
		);
		assert.equal(child.error, undefined);
		assert.equal(child.status, 0, child.stderr);
		const captured = nodePath.join(cwd, "captured");
		return new Map(
			readdirSync(captured).map((file) => [
				file,
				readFileSync(nodePath.join(captured, file), "utf8"),
			]),
		);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
}

const scenario = process.env.PI_GOLDEN_SCENARIO;
if (scenario !== undefined && scenario !== "") {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd !== undefined && cwd !== "");
	const capture = (name: string, text: string) =>
		writeFileSync(nodePath.join(cwd, "captured", name), text);
	mkdirSync(nodePath.join(cwd, "captured"), { recursive: true });
	await mockChange({ writeChangeView: () => undefined });
	mock.method(globalThis, "fetch", async (_input: unknown, init?: RequestInit) => {
		assert.ok(typeof init?.body === "string");
		const posted: unknown = JSON.parse(init.body);
		assert.ok(typeof posted === "object" && posted !== null);
		const results: unknown = Reflect.get(posted, "results");
		assert.ok(Array.isArray(results));
		if (scenario === "scan" || scenario === "registered-text") {
			capture("admission.json", JSON.stringify(posted));
		}
		return Response.json({
			contractVersion: 4,
			admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			admitted: [],
			withheld: [],
		});
	});
	const manager = {
		appendCustomEntry: () => undefined,
		getSessionFile: () => undefined,
		getSessionId: () => "golden-session",
		buildSessionProjection: () => ({ messages: [] }),
	};
	let prompts = 0;
	let steers = 0;
	interface CustomTool {
		name: string;
		description: string;
		parameters: unknown;
		prepareArguments?: (args: unknown) => unknown;
		execute: (id: string, input: unknown) => Promise<unknown>;
	}
	mockPiCodingAgent(
		cwd,
		manager,
		{
			async createAgentSession(options: { tools: string[]; customTools: CustomTool[] }) {
				const handlers = new Set<(event: unknown) => void>();
				const emit = (event: unknown) => {
					for (const handler of handlers) {
						handler(event);
					}
				};
				capture(
					"tools.json",
					`${JSON.stringify(
						{
							tools: options.tools,
							customTools: options.customTools.map((custom) => ({
								name: custom.name,
								description: custom.description,
								parameters: custom.parameters,
							})),
						},
						null,
						1,
					)}\n`,
				);
				const tool = (name: string) => {
					const found = options.customTools.find((item) => item.name === name);
					assert.ok(found, `${name} is registered`);
					return async (id: string, args: unknown) =>
						found.execute(id, found.prepareArguments?.(args) ?? args);
				};
				const submitResult = async () => {
					if (scenario === "registered-text") {
						const reply = await tool("submit_result")("registered-1", {
							outcome: "COULD_NOT_DETERMINE",
							summary: "Captured text leaves one open question",
							explanation: "The captured logical text names the question without settling it.",
							citations: [
								{
									sourceKind: "scm.pull-request.core",
									sourcePath: "evidence/metadata.json",
									path: "thread/message",
									startLine: 2,
									endLine: 2,
									quote: "Second line is the open question.",
								},
							],
							evidence: {
								openQuestions: [
									{
										openQuestion: "What settles the question?",
										wouldSettleIt: "The missing decision.",
									},
								],
							},
						});
						capture("submitted.json", JSON.stringify(reply));
						return;
					}

					if (scenario === "scan") {
						await assert.rejects(
							tool("submit_result")("outside-grant", {
								outcome: "MET",
								summary: "A file outside the frozen context",
								explanation: "This citation must never be read by the host.",
								citations: [
									{
										sourceKind: "scm.repository.tree",
										sourcePath: "context/item.json",
										path: "src/foreign.ts",
										startLine: 1,
										endLine: 1,
										quote: "const foreign = true;",
									},
								],
								evidence: {},
							}),
							/outside its frozen context/u,
						);

						const reply = await tool("submit_result")("scan-1", {
							outcome: "MET",
							summary: "Captured source meets the criterion",
							explanation: "The complete assigned file meets the bounded criterion.",
							citations: [
								{
									sourceKind: "scm.repository.tree",
									sourcePath: "context/item.json",
									path: "src/healthy.ts",
									startLine: 1,
									endLine: 1,
									quote: "const healthy = true;",
								},
							],
							evidence: {},
						});
						capture("submitted.json", JSON.stringify(reply));
						return;
					}

					const reply = await tool("submit_result")("golden-1", {
						outcome: "NOT_MET",
						summary: "Added authentication call is insecure",
						explanation: "The changed authentication code calls insecure().",
						citations: [],
						problems: [
							{
								severity: "MAJOR",
								summary: "Login calls insecure() on every sign-in",
								explanation:
									"The added line calls insecure() where the criteria ask for checked().",
								citations: [recordCitation],
								basis: {
									type: "duty",
									criterionQuote: "Added authentication calls use checked(), not insecure().",
									event: 0,
									explanation:
										"The pull request adds a sign-in, which the criteria require to use checked().",
								},
							},
						],
						evidence: {},
					});
					capture("submitted.json", `${JSON.stringify(reply, null, 1)}\n`);
				};
				const callUsage = {
					input: 1000,
					output: 100,
					cacheRead: 0,
					cacheWrite: 0,
					cost: { total: 0 },
				};
				return {
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						agent: { state: { tools: [] }, finishTurn: async () => ({ action: "continue" }) },
						systemPrompt: "",
						model: { contextWindow: 128_000, maxTokens: 16_384 },
						settingsManager: { getCompactionSettings: () => ({ reserveTokens: 16_384 }) },
						sessionManager: manager,
						subscribe(handler: (event: unknown) => void) {
							handlers.add(handler);
							return () => {
								handlers.delete(handler);
							};
						},
						clearQueue: () => undefined,
						pendingMessageCount: 0,
						getContextUsage: () => ({ tokens: 1000, contextWindow: 128_000, percent: 0 }),
						compact: async () => ({}),
						isIdle: true,
						isStreaming: false,
						waitForIdle: async () => undefined,
						abortCompaction: () => undefined,
						abort: async () => undefined,
						dispose: () => undefined,
						steer: async (message?: string) => {
							steers += 1;
							capture(`steer-${steers}.md`, message ?? "");
						},
						async prompt(
							text: string,
							promptOptions?: { preflightResult?: (disposition: string) => void },
						) {
							promptOptions?.preflightResult?.("started");
							prompts += 1;
							capture(`prompt-${prompts}.md`, text);
							if (prompts === 1 && scenario === "nudge") {
								for (let call = 1; call <= 4; call += 1) {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage,
											content: [{ type: "toolCall", id: `r-${call}`, name: "read", arguments: {} }],
										},
									});
									emit({ type: "turn_end" });
								}
								return;
							}
							if (prompts === 1 && scenario === "compaction") {
								emit({
									type: "compaction_end",
									reason: "threshold",
									result: undefined,
									aborted: false,
								});
								return;
							}
							await submitResult();
						},
					},
				};
			},
		},
		(options) => capture("system-prompt.md", options.systemPrompt),
	);
	const { runReviewJob } = await import("../../../main/resources/agent/pi-runner.ts");
	await runReviewJob();
} else {
	const SCENARIOS = ["first-turn", "nudge", "compaction", "leads", "leads-truncated"];
	const captures = new Map(SCENARIOS.map((name) => [name, runScenario(name)]));
	if (process.env.PI_GOLDEN_DUMP !== undefined) {
		for (const [name, captured] of captures) {
			const dir = nodePath.join(process.env.PI_GOLDEN_DUMP, name);
			mkdirSync(dir, { recursive: true });
			for (const [file, text] of captured) {
				writeFileSync(nodePath.join(dir, file), text);
			}
		}
	}

	void test("the runner verifies logical plaintext against its registered bytes rather than metadata JSON", () => {
		const captured = runScenario("registered-text");
		const posted: unknown = JSON.parse(captured.get("admission.json") ?? "null");
		assert.ok(typeof posted === "object" && posted !== null);
		const results: unknown = Reflect.get(posted, "results");
		assert.ok(Array.isArray(results) && results.length === 1);
		assert.partialDeepStrictEqual(results[0], {
			candidate: {
				citations: [
					{
						path: "thread/message",
						quote: "Second line is the open question.",
						startLine: 2,
						endLine: 2,
					},
				],
			},
		});
	});

	void test("the native scan submits pinned file proof with its item attribution and governed tools", () => {
		const captured = runScenario("scan");
		const posted: unknown = JSON.parse(captured.get("admission.json") ?? "null");
		assert.ok(typeof posted === "object" && posted !== null);
		const results: unknown = Reflect.get(posted, "results");
		assert.ok(Array.isArray(results) && results.length === 1);
		const result: unknown = results[0];
		assert.ok(typeof result === "object" && result !== null);
		assert.equal(Reflect.get(result, "itemId"), "2");
		assert.equal(Reflect.get(result, "itemVersion"), scan("src/healthy.ts").scope.version);
		const receipts: unknown = Reflect.get(result, "readReceipts");
		assert.ok(Array.isArray(receipts) && receipts.length === 1);
		assert.partialDeepStrictEqual(receipts[0], {
			sourcePath: "context/item.json",
			path: "src/healthy.ts",
			status: "COMPLETE",
			version: "a".repeat(40),
		});
		const tools: unknown = JSON.parse(captured.get("tools.json") ?? "null");
		assert.ok(typeof tools === "object" && tools !== null);
		assert.deepEqual(Reflect.get(tools, "tools"), [
			"read",
			"upsert_problem",
			"remove_problem",
			"read_problem",
			"submit_result",
		]);
		assert.match(
			captured.get("prompt-1.md") ?? "",
			/Assigned scan file[\s\S]*Commit: a{40}[\s\S]*Source path: context\/item\.json[\s\S]*const healthy = true;/u,
		);
		const lens = assignmentOf(captured.get("prompt-1.md") ?? "");
		assert.match(lens, /no person/u);
		assert.doesNotMatch(lens, /AUTHOR|REVIEWER|ASSIGNEE|MERGER/u);
	});

	for (const name of SCENARIOS) {
		void test(`the ${name} session receives the review tools and captured source facts`, () => {
			const captured = captures.get(name);
			assert.ok(captured !== undefined);
			assert.deepEqual(
				JSON.parse(captured.get("tools.json") ?? "null"),
				JSON.parse(readFileSync(nodePath.join(GOLDEN, "tools.json"), "utf8")),
			);
			const prompt = captured.get("prompt-1.md") ?? "";
			assert.ok(prompt.includes("evidence/metadata.json"));
			assert.ok(prompt.includes("[L10] + insecure();"));
			assert.ok(prompt.includes("test-practice"));
			assert.match(
				captured.get("system-prompt.md") ?? "",
				/Use `read` and `grep` for source content you will rely on\./u,
			);
			assert.match(
				captured.get("system-prompt.md") ?? "",
				/A tail slice does not clear an unread middle\./u,
			);
		});
	}
	void test("each practice turn states whose work its pinned assignment is about, also after compaction", () => {
		const author = assignmentOf(captures.get("first-turn")?.get("prompt-1.md") ?? "");
		assert.match(author, /\bAUTHOR\b/u);
		assert.match(author, /scm\.pull_request/u);
		assert.equal(assignmentOf(captures.get("compaction")?.get("prompt-2.md") ?? ""), author);
		const reviewer = runScenario("reviewer").get("prompt-1.md") ?? "";
		assert.match(assignmentOf(reviewer), /the REVIEWER of this scm\.pull_request_review item/u);
		// An SCM capture names provider accounts; the internal developer id is never offered to match them.
		assert.doesNotMatch(reviewer, /8675309/u);
		const assignee = runScenario("assignee").get("prompt-1.md") ?? "";
		assert.match(assignmentOf(assignee), /one ASSIGNEE of this scm\.pull_request item/u);
		assert.match(assignmentOf(assignee), /does not say which captured account/u);
		assert.doesNotMatch(assignee, /8675309/u);
		const unattributed = assignmentOf(runScenario("unattributed").get("prompt-1.md") ?? "");
		assert.match(unattributed, /no person/u);
		assert.doesNotMatch(unattributed, /AUTHOR|REVIEWER|ASSIGNEE|MERGER/u);
	});

	void test("a conversation turn names its participant by the internal account id its own capture records", () => {
		const participant = assignmentOf(runScenario("participant").get("prompt-1.md") ?? "");
		// The same internal id as each captured turn's accountId: an internal match, not a provider identity.
		assert.match(
			participant,
			/one participant of this chat\.thread item[\s\S]*`context\/conversation_thread\.json` whose `accountId` is 8675309/u,
		);
		assert.match(participant, /the other turns are context/u);
		assert.doesNotMatch(participant, /the AUTHOR of this chat\.thread/u);
	});
	void test("truncated leads disclose the omitted context and locate the full input", () => {
		const prompt = captures.get("leads-truncated")?.get("prompt-1.md") ?? "";
		const omitted =
			/^- \d+ more lines are not shown, \d+ of them leads\. The full input is in `work\/review-input\/test-practice\.json`\.$/mu;
		assert.match(prompt, omitted);
		assert.doesNotMatch(captures.get("leads")?.get("prompt-1.md") ?? "", omitted);
	});
	void test("compaction restores the captured change and complete criterion before another result", () => {
		const prompt = captures.get("compaction")?.get("prompt-2.md") ?? "";
		assert.ok(prompt.includes("[L10] + insecure();"));
		assert.ok(prompt.includes("Added authentication calls use checked(), not insecure()."));
	});

	void test("the built-in program opens one native review session and returns its result, with no measuring session after it", () => {
		const captured = captures.get("first-turn");
		assert.ok(captured !== undefined);
		assert.deepEqual(
			[...captured.keys()].filter((file) => file.startsWith("prompt-")),
			["prompt-1.md"],
		);
		assert.match(captured.get("submitted.json") ?? "", /test-practice: stored \(negative\)/u);
	});
}
