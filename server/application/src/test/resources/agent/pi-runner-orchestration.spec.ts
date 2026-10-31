import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	appendFileSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	realpathSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { mock, test } from "node:test";

import { isDiagnosticLine } from "../../../main/resources/agent/pi-diagnostics.ts";
import { CANDIDATE_LIMITS } from "../../../main/resources/agent/work/practice-scripts/lib/core/contract.ts";

import type {
	AgentSession,
	ExtensionFactory,
	AgentToolResult,
	ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import { isJsonObject as isRecord } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { environmentForGitFixture } from "../../../../../../scripts/lib/git-environment.ts";
import {
	addModelCall,
	newModelCallTiming,
} from "../../../main/resources/agent/pi-model-failure.ts";
import { z } from "../../../main/resources/agent/work/practice-scripts/lib/core/practice-script.ts";
import { GOLDEN_INPUTS, PRACTICES_DIR } from "./golden-review-workspace.ts";

// Each child process mocks the SDK and drives one isolated review through runReviewJob.

import { mockChange, mockPiCodingAgent, practiceIndexOf } from "./program-host-runner.ts";

const LIB = nodePath.resolve(
	import.meta.dirname,
	"../../../../../../docker/agents/practice-scripts/lib",
);

const timing = (calls: number[][], failedElapsedMs: number) => ({
	timedCalls: calls.length,
	respondedCalls: calls.filter((call) => call.length > 1).length,
	elapsedMs: calls.reduce((sum, [elapsed = 0]) => sum + elapsed, 0),
	failedElapsedMs,
	maxElapsedMs: Math.max(0, ...calls.map(([elapsed = 0]) => elapsed)),
	responseMs: calls.reduce((sum, [, response = 0]) => sum + response, 0),
});

const changeCitation = {
	sourceKind: "scm.pull-request.diff",
	sourcePath: "evidence/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

const OVERLONG_SUMMARY = "A summary that runs on ".repeat(8).trim();

type SubmittedCitation = Record<string, string | number>;

function notMet(summary: string, citation: SubmittedCitation = changeCitation) {
	return {
		outcome: "NOT_MET",
		summary,
		explanation: "The changed authentication code calls insecure().",
		citations: [],
		problems: [
			{
				severity: "MAJOR",
				summary,
				explanation: "The added line calls insecure() where the criteria ask for checked().",
				citations: [citation],
				basis: { type: "introduced", head: 0, explanation: "The change adds the call." },
			},
		],
		evidence: {},
	};
}

const met = (summary: string) => ({
	outcome: "MET",
	summary,
	explanation: "The changed authentication code calls the checked helper.",
	citations: [changeCitation],
	evidence: {},
});

const recordCitation = {
	sourceKind: "scm.pull-request.core",
	sourcePath: "evidence/metadata.json",
	path: "evidence/metadata.json",
	startLine: 1,
	quote: '"title": "Add login"',
};

const undecided = (consulted: string[]) => ({
	outcome: "DOES_NOT_APPLY",
	summary: "Nothing to assess in this change",
	explanation: "The change touches only metadata.",
	citations: [recordCitation],
	evidence: {
		inapplicability: {
			consulted,
			prerequisite: "authentication calls",
			ruledOutBy: "no code changed",
		},
	},
});

/** A practice index entry as the server writes it: the sources its evidence requirements name. */
function practice(
	slug: string,
	readsSources = ["scm.pull-request.core", "scm.pull-request.diff"],
	versionId?: unknown,
	group = "code",
) {
	return {
		slug,
		name: "Test practice",
		group,
		readsSources,
		exhaustiveSources: readsSources.filter((source) => source === "scm.pull-request.diff"),
		...(versionId === undefined ? {} : { versionId }),
	};
}

const WORK_RECORD = {
	title: "Add login",
	basis: { admission_fields: [], other_fields: "CAPTURE" },
	pr_number: 3,
	pr_url: "https://gitlab.example/group/repo/-/merge_requests/3",
	repository_full_name: "group/repo",
	source_branch: "login",
	target_branch: "main",
	commit_sha: "a".repeat(40),
	subject_role: "AUTHOR",
	is_draft: false,
	is_merged: false,
	additions: 1,
	deletions: 0,
	changed_files: 1,
	labels: [],
	assignees: [],
};

const LEADS_PROGRAM = `import { definePracticeScript } from "../lib/core/practice-script.ts";
export default definePracticeScript({
	meta: {},
	async run(ctx) {
		return ctx.review({
			leads: [{ at: { change: "src/Auth.java", line: 10 }, label: "An added insecure call." }],
		});
	},
});
`;

function recordOf(value: unknown): Record<string, unknown> {
	assert.ok(isRecord(value), JSON.stringify(value));
	return value;
}

function listOf(value: unknown): unknown[] {
	assert.ok(Array.isArray(value), JSON.stringify(value));
	const items: unknown[] = value;
	return items;
}

function readResults(path: string) {
	const payload = recordOf(JSON.parse(readFileSync(path, "utf8")));
	assert.equal(payload.contractVersion, 3);
	return listOf(payload.results).map(recordOf);
}

function candidatesIn(path: string): Map<string, Record<string, unknown>> {
	return new Map(
		readResults(path).map((entry) => [String(entry.practiceSlug), recordOf(entry.candidate)]),
	);
}

/** What one model call reports it spent, as the SDK puts it on every assistant message. */
const callUsage = (output: number) => ({
	input: 1000,
	output,
	cacheRead: 0,
	cacheWrite: 0,
	cost: { total: 0 },
});

interface CustomTool {
	name: string;
	description: string;
	parameters: ToolDefinition["parameters"];
	prepareArguments?: (args: unknown) => unknown;
	execute: (id: string, input: unknown) => Promise<AgentToolResult<unknown>>;
}

type NativeStream = Awaited<ReturnType<AgentSession["agent"]["streamFunction"]>>;
function isStreamFactory(value: unknown): value is () => NativeStream {
	return typeof value === "function";
}

function submitCall(
	id: string,
	args: Extract<
		Awaited<ReturnType<NativeStream["result"]>>["content"][number],
		{ type: "toolCall" }
	>["arguments"],
) {
	return { type: "toolCall" as const, id, name: "submit_result", arguments: args };
}

function modelToolCall(name: string, id: string, args: Parameters<typeof submitCall>[1]) {
	return { type: "toolCall" as const, name, id, arguments: args };
}

function problemReplyText(reply: Record<string, unknown> | undefined): string {
	assert.ok(reply);
	const [part] = listOf(recordOf(reply.result).content).map(recordOf);
	assert.ok(typeof part?.text === "string");
	return part.text;
}

function assertDraftCandidate(
	stage: string,
	session: number,
	slug: string,
	candidate: Record<string, unknown>,
	replies: Record<string, unknown>[],
): void {
	if (stage === "scope-native-problem-isolation") {
		assert.equal(candidate.outcome, "MET");
		assert.equal(candidate.problems, undefined);
		if (session === 2) {
			const missing = problemReplyText(replies.find((reply) => reply.id === "sibling-key"));
			assert.match(missing, /Unknown local problem key/u);
			assert.ok(missing.includes("Available local drafts: []"));
			assert.equal(missing.includes("First gap of test-practice"), false);
		}
		return;
	}
	const problems = listOf(candidate.problems).map(recordOf);
	assert.deepEqual(
		problems.map((problem) => problem.key),
		stage === "scope-native-problem-drafts" ? ["third-gap", "first-gap"] : ["first-gap"],
	);
	assert.equal(
		problems.at(-1)?.summary,
		`${stage === "scope-native-problem-drafts" ? "Updated" : "First"} gap of ${slug}`,
	);
	if (stage === "scope-native-problem-drafts") {
		assert.equal(problems.at(-1)?.severity, "MINOR");
		const late = replies.find((reply) => reply.id === "late-edit");
		assert.equal(late?.isError, true);
		assert.match(JSON.stringify(late), /already submitted/u);
		assert.ok(replies.some((reply) => JSON.stringify(reply).includes('"action":"unchanged"')));
		assert.ok(replies.some((reply) => JSON.stringify(reply).includes('"action":"removed"')));
		for (const [id, action, key, problemCount] of [
			["add-a", "added", "first-gap", 1],
			["add-b", "added", "second-gap", 2],
			["edit-a", "updated", "first-gap", 2],
			["remove-b", "removed", "second-gap", 1],
			["remove-missing", "unchanged", "missing-gap", 1],
			["same-a", "unchanged", "first-gap", 2],
		] as const) {
			const text = problemReplyText(replies.find((reply) => reply.id === id));
			assert.deepEqual(JSON.parse(text), { state: "local-draft", action, key, problemCount });
			assert.equal(text.includes(`gap of ${slug}`), false);
		}
		return;
	}
	for (const id of [
		"wrong-quote",
		"wrong-index",
		"unknown-key",
		"unknown-read",
		"over-limit",
		"both-forms",
		"duplicate-keys",
	]) {
		assert.equal(replies.find((reply) => reply.id === id)?.isError, true, id);
	}
	const inventory = JSON.stringify([{ key: "first-gap", summary: `First gap of ${slug}` }]);
	for (const id of ["unknown-key", "unknown-read"]) {
		const text = problemReplyText(replies.find((reply) => reply.id === id));
		assert.ok(text.includes(`Available local drafts: ${inventory}`), text);
	}
}

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
const nativeSdk =
	scenario !== undefined && scenario.startsWith("scope-native-")
		? await import("@earendil-works/pi-coding-agent")
		: undefined;
let reviewExtensions: ExtensionFactory[] = [];
if (scenario !== undefined && scenario !== "") {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd !== undefined && cwd !== "");
	if (scenario === "scope-stop-hangs") {
		const timer = globalThis.setTimeout;
		mock.method(
			globalThis,
			"setTimeout",
			(callback: (...args: unknown[]) => void, ms?: number, ...args: unknown[]) =>
				timer(callback, ms === 30_000 ? 10 : ms, ...args),
		);
	}
	let now = 1_000_000;
	mock.method(Date, "now", () => now);
	const record = (event: string) => appendFileSync(nodePath.join(cwd, "events"), `${event}\n`);
	await mockChange({ writeChangeView: () => record("derive-change") });
	/** What the mocked server admits: nothing, or one result whose outcome is outside the vocabulary. */
	const admitted = (): unknown[] => {
		const outcome = new Map<string, unknown>([
			["admission-unknown-outcome", "PASSED"],
			["admission-null-outcome", null],
		]);
		return outcome.has(scenario)
			? [
					{
						resultId: "00000000-0000-4000-8000-000000000001",
						practiceSlug: "test-practice",
						outcome: outcome.get(scenario),
						evidence: { citations: [] },
						problems: [],
					},
				]
			: [];
	};
	mock.method(globalThis, "fetch", async (input: unknown, init?: RequestInit) => {
		if (nativeSdk) {
			assert.equal(String(input), "https://unused.invalid/admit-results");
		}
		assert.ok(String(input).endsWith("/admit-results"), String(input));
		record("admit");
		assert.ok(typeof init?.body === "string");
		writeFileSync(nodePath.join(cwd, "admission.json"), init.body);
		if (scenario === "draft-commit") {
			// Measurement closes before admission: the measured tool can no longer change a result.
			assert.ok(measuredTool);
			const closedTool = measuredTool;
			const before = [stateText(), notesText()];
			await assert.rejects(
				async () => closedTool.execute("after-measurement", notMet("Forbidden late replacement")),
				/Measurement is closed/u,
			);
			assert.deepEqual([stateText(), notesText()], before);
			record("draft-closed:Measurement is closed; result and notes unchanged");
		}
		return Response.json({
			contractVersion: 4,
			admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			admitted: admitted(),
			withheld: [],
		});
	});
	const manager = {
		appendCustomEntry: () => undefined,
		getSessionFile: () => undefined,
		getSessionId: () => "test-session",
		buildSessionProjection: () => ({ messages: [] }),
	};
	const sessionLoaders = new Set<object>();
	let sessions = 0;
	const firstProblemSaved = Promise.withResolvers<undefined>();
	const siblingSelectionFinished = Promise.withResolvers<undefined>();
	let prompts = 0;
	let measuredTool: CustomTool | undefined;
	let firstTool: CustomTool | undefined;
	/** What the runner steered the session with, in order. */
	const steered: string[] = [];
	const stateText = () => {
		const path = nodePath.join(cwd, "out/review-state.json");
		return existsSync(path) ? readFileSync(path, "utf8") : null;
	};
	const notesText = () => readFileSync(nodePath.join(cwd, "work/notes/review.md"), "utf8");
	let wasCompacted = false;
	/** The stall scenario's first prompt ends only when the runner aborts it, like a call in flight. */
	let releasePrompt: (() => void) | undefined;
	/** A threshold compaction in flight: a model call of its own, which abort() alone does not end. */
	let compacting = scenario === "settle-safety";
	let idleWaiters: (() => void)[] = [];
	const settleIdle = () => {
		if (releasePrompt || compacting) {
			return;
		}
		for (const resolve of idleWaiters) {
			resolve();
		}
		idleWaiters = [];
	};
	const PAST_SAFETY_MS = 120_000;
	const waitForIdle = async () => {
		if (scenario === "settle-safety" && compacting) {
			now += PAST_SAFETY_MS;
			compacting = false;
		}
		const { promise, resolve } = Promise.withResolvers<undefined>();
		idleWaiters.push(() => resolve(undefined));
		settleIdle();
		await promise;
	};
	mockPiCodingAgent(
		cwd,
		manager,
		{
			...(nativeSdk ? { estimateTokens: nativeSdk.estimateTokens } : {}),
			async createAgentSession(options: {
				tools: string[];
				customTools: CustomTool[];
				resourceLoader: object;
			}) {
				assert.equal(
					sessionLoaders.has(options.resourceLoader),
					false,
					"a stopped session must not leave extension bindings on a later session",
				);
				sessionLoaders.add(options.resourceLoader);
				const handlers = new Set<(event: unknown) => void>();
				let settlementUsageEmitted = false;
				let queued = 0;
				const emit = (event: unknown) => {
					for (const handler of handlers) {
						handler(event);
					}
				};
				record(`create:session tools=${options.tools.join(",")}`);
				sessions += 1;
				const sessionNumber = sessions;
				if (scenario === "settle-safety") {
					now += PAST_SAFETY_MS;
					compacting = false;
				}
				if (scenario === "session-init") {
					throw new Error("session initialization failed");
				}
				for (const custom of options.customTools) {
					record(`exposure:${custom.name}=${String(Reflect.get(custom, "exposure"))}`);
				}
				const tool = (name: string) => {
					const found = options.customTools.find((item) => item.name === name);
					assert.ok(found, `${name} is registered`);
					return {
						...found,
						execute: async (id: string, args: unknown) =>
							found.execute(id, found.prepareArguments?.(args) ?? args),
					};
				};
				if (nativeSdk) {
					const runtime = await nativeSdk.ModelRuntime.create({
						authPath: nodePath.join(cwd, "native-auth.json"),
						modelsPath: null,
						modelsStorePath: nodePath.join(cwd, "native-models.json"),
						refreshOnCreate: false,
						allowModelNetwork: false,
					});
					await runtime.setRuntimeApiKey("openai", "synthetic-native-test-key");
					const model = runtime.getModel("openai", "gpt-4.1");
					assert.ok(model);
					const settingsManager = nativeSdk.SettingsManager.inMemory(
						{
							compaction: {
								enabled: false,
								...(scenario === "scope-native-problem-compaction" ? { keepRecentTokens: 10 } : {}),
							},
							cacheWarming: "off",
						},
						{ projectTrusted: false },
					);
					const resourceLoader = new nativeSdk.DefaultResourceLoader({
						cwd,
						agentDir: cwd,
						settingsManager,
						noContextFiles: true,
						noExtensions: true,
						noSkills: true,
						noPromptTemplates: true,
						extensionFactories: [
							...reviewExtensions,
							...(scenario === "scope-native-problem-compaction"
								? [
										(api: Parameters<ExtensionFactory>[0]) => {
											api.on("session_before_compact", (event) => ({
												compaction: {
													summary:
														"Earlier evidence was reviewed. Consult the host's restored draft inventory before finishing.",
													firstKeptEntryId: event.preparation.firstKeptEntryId,
													tokensBefore: event.preparation.tokensBefore,
												},
											}));
										},
									]
								: []),
						],
					});
					await resourceLoader.reload();
					const opened = await nativeSdk.createAgentSession({
						cwd,
						agentDir: cwd,
						modelRuntime: runtime,
						model,
						settingsManager,
						resourceLoader,
						sessionManager: nativeSdk.SessionManager.inMemory(cwd),
						tools: options.tools,
						customTools: options.customTools.map((custom) => ({ ...custom, label: custom.name })),
					});
					const nativeAi: unknown = await import(
						import.meta.resolve(
							"@earendil-works/pi-ai",
							import.meta.resolve("@earendil-works/pi-coding-agent"),
						)
					);
					assert.ok(
						isRecord(nativeAi) && isStreamFactory(nativeAi.createAssistantMessageEventStream),
					);
					const makeStream = nativeAi.createAssistantMessageEventStream;
					let requests = 0;
					if (scenario === "scope-native-problem-compaction") {
						opened.session.agent.finishTurn = async () =>
							requests === 1 ? { action: "end" } : undefined;
					}

					opened.session.agent.streamFunction = async (streamModel, context, streamOptions) => {
						requests += 1;
						record(`native-request:${sessionNumber}:${requests}`);
						const slug = sessionNumber === 1 ? "test-practice" : "second-practice";
						const batchCorrection = scenario === "scope-native-batch-correction";
						const readCorrection = scenario === "scope-native-read-correction";
						const refusedRevision = scenario === "scope-native-commit" || readCorrection;

						const content: Awaited<ReturnType<NativeStream["result"]>>["content"] = [];
						if (
							scenario === "scope-native-submission-repair" ||
							scenario === "scope-native-submit-nudge"
						) {
							const payload = {
								model: "test-model",
								tools: [{ type: "function", function: { name: "submit_result" } }],
							};
							const transformed: unknown =
								(await streamOptions?.onPayload?.(payload, streamModel)) ?? payload;
							assert.ok(isRecord(transformed));
							assert.deepEqual(
								transformed.tool_choice,
								requests === 1
									? undefined
									: { type: "function", function: { name: "submit_result" } },
							);
						}
						if (scenario.startsWith("scope-native-problem-")) {
							const candidate = notMet(`Drafted ${slug}`);
							const original = candidate.problems[0];
							assert.ok(original);
							const a = { ...original, key: "first-gap", summary: `First gap of ${slug}` };
							const b = { ...original, key: "second-gap", summary: `Second gap of ${slug}` };
							const c = { ...original, key: "third-gap", summary: `Third gap of ${slug}` };
							const revised = { ...a, severity: "MINOR", summary: `Updated gap of ${slug}` };
							const { problems: _inline, ...result } = candidate;
							if (requests === 1) {
								content.push(modelToolCall("read", "read", { path: "work/change/diff.patch" }));
							}
							if (scenario === "scope-native-problem-compaction") {
								if (requests === 1) {
									content.push(modelToolCall("upsert_problem", "before-compact", a));
								} else if (requests === 2) {
									const restored = context.messages
										.filter((message) => message.role === "user")
										.map((message) => JSON.stringify(message.content))
										.join("\n");
									assert.match(restored, /Local problem drafts/u);
									assert.ok(restored.includes(a.summary));
									content.push(modelToolCall("read_problem", "recover", { key: a.key }));
								} else {
									assert.equal(requests, 3);
									const reply = context.messages.findLast(
										(message) =>
											message.role === "toolResult" && message.toolName === "read_problem",
									);
									assert.ok(reply?.role === "toolResult");
									const text = reply.content.find((part) => part.type === "text");
									assert.ok(text?.type === "text");
									const body = z
										.record(z.string(), z.json())
										.parse(recordOf(JSON.parse(text.text)).problem);
									assert.equal(body.summary, a.summary);
									content.push(
										modelToolCall("upsert_problem", "after-compact", {
											...body,
											summary: `Recovered gap of ${slug}`,
										}),
										submitCall("recovered-result", { ...result, problemKeys: [a.key] }),
									);
								}
							} else if (scenario === "scope-native-problem-drafts") {
								assert.equal(
									requests,
									1,
									"drafting and submission in one sequential native batch should settle",
								);
								content.push(
									modelToolCall("upsert_problem", "add-a", a),
									modelToolCall("upsert_problem", "add-b", b),
									modelToolCall("upsert_problem", "edit-a", revised),
									modelToolCall("remove_problem", "remove-b", { key: b.key }),
									modelToolCall("remove_problem", "remove-missing", { key: "missing-gap" }),
									modelToolCall("upsert_problem", "add-c", c),
									modelToolCall("upsert_problem", "same-a", revised),
									submitCall("select", { ...result, problemKeys: [c.key, a.key] }),
									modelToolCall("upsert_problem", "late-edit", a),
								);
							} else if (scenario === "scope-native-problem-repair") {
								if (requests === 1) {
									content.push(
										modelToolCall("upsert_problem", "valid-a", a),
										modelToolCall("upsert_problem", "wrong-quote", {
											...a,
											citations: [{ ...changeCitation, quote: "+ neverCaptured();" }],
										}),
										modelToolCall("upsert_problem", "wrong-index", {
											...a,
											basis: { ...a.basis, head: 9 },
										}),
									);
								} else if (requests === 2) {
									const errors = context.messages.filter(
										(message) => message.role === "toolResult" && message.isError,
									);
									assert.ok(
										errors.some((error) =>
											JSON.stringify(error.content).includes("citation does not match"),
										),
									);
									assert.ok(
										errors.some((error) => JSON.stringify(error.content).includes("basis")),
									);
									content.push(
										submitCall("unknown-key", { ...result, problemKeys: ["unknown-gap"] }),
										modelToolCall("read_problem", "unknown-read", { key: "unknown-gap" }),
									);
								} else if (requests === 3) {
									content.push(
										submitCall("over-limit", {
											...result,
											problemKeys: Array.from(
												{ length: CANDIDATE_LIMITS.problems + 1 },
												() => a.key,
											),
										}),
										submitCall("both-forms", { ...candidate, problemKeys: [a.key] }),
									);
								} else if (requests === 4) {
									content.push(
										submitCall("duplicate-keys", { ...result, problemKeys: [a.key, a.key] }),
									);
								} else {
									assert.equal(requests, 5);
									content.push(submitCall("corrected", { ...result, problemKeys: [a.key] }));
								}
							} else if (scenario === "scope-native-problem-no-submit") {
								if (sessionNumber === 2) {
									content.push(submitCall("healthy-sibling", candidate));
								} else if (requests === 1) {
									content.push(modelToolCall("upsert_problem", "unsubmitted-a", a));
								} else {
									content.push({
										type: "text",
										text: "Evidence remains unresolved; no result was submitted.",
									});
								}
							} else {
								if (requests === 1) {
									if (sessionNumber === 2) {
										await firstProblemSaved.promise;
										content.push(submitCall("sibling-key", { ...result, problemKeys: [a.key] }));
									} else {
										content.push(modelToolCall("upsert_problem", "unused-a", a));
									}
								} else {
									assert.equal(requests, 2);
									if (sessionNumber === 1) {
										firstProblemSaved.resolve(undefined);
										await siblingSelectionFinished.promise;
									}
									content.push(
										submitCall("met-no-leak", {
											outcome: "MET",
											summary: `Checked authentication of ${slug}`,
											explanation: "The captured change supplies the checked work.",
											citations: [changeCitation],
											evidence: {},
										}),
									);
								}
							}
						} else if (
							scenario === "scope-native-evidence-correction" ||
							scenario === "scope-native-evidence-cap"
						) {
							// Each attempt cites a different line the change never had, so the tool refuses it on its
							// evidence; the correction comes as the eighth attempt, or, for the cap, as the ninth.
							const correctedAt = scenario === "scope-native-evidence-correction" ? 8 : 9;
							content.push(
								submitCall(
									`evidence-${requests}`,
									requests === correctedAt
										? notMet(`Corrected ${slug}`)
										: notMet(`Attempt ${requests} for ${slug}`, {
												...changeCitation,
												quote: `+ notInTheDiff${requests}();`,
											}),
								),
							);
						} else if (scenario === "scope-native-submit-nudge") {
							if (requests === 1) {
								content.push({ type: "text", text: "The review is complete." });
							} else {
								content.push(submitCall("nudged", notMet(`Recorded ${slug}`)));
							}
						} else if (
							scenario === "scope-native-submission-repair" ||
							scenario === "scope-native-schema-cap"
						) {
							const errors = context.messages.filter(
								(message) => message.role === "toolResult" && message.isError,
							);
							for (const error of errors) {
								record(`native-repair-error:${sessionNumber}:${JSON.stringify(error.content)}`);
							}
							const candidate = notMet(
								scenario === "scope-native-schema-cap"
									? `Attempt ${requests} for ${slug}`
									: `Corrected ${slug}`,
							);
							if (scenario === "scope-native-schema-cap" || requests === 2) {
								content.push(
									submitCall(`flat-${requests}`, {
										...candidate,
										evidence: {
											consulted: ["scm.pull-request.diff"],
											lookedFor: "insecure call",
											boundary: "changed lines",
										},
									}),
								);
							} else if (requests === 1) {
								content.push(
									submitCall("wrong-index", {
										...candidate,
										problems: candidate.problems.map((problem) => ({
											...problem,
											basis: { ...problem.basis, head: 1 },
										})),
									}),
								);
							} else {
								assert.ok(
									errors.some((error) =>
										JSON.stringify(error.content).includes(
											"its basis names citation 1, and it has 1",
										),
									),
								);
								assert.ok(
									errors.some(
										(error) =>
											JSON.stringify(error.content).includes("evidence.consulted") &&
											JSON.stringify(error.content).includes("Corrected field example"),
									),
								);
								content.push(submitCall("repaired", candidate));
							}
						} else if (requests === 1) {
							content.push(
								{
									type: "toolCall",
									id: "read",
									name: "read",
									arguments: { path: "work/change/diff.patch" },
								},
								submitCall(
									"report",
									notMet(
										`Own ${slug}`,
										sessionNumber === 2 && batchCorrection
											? { ...changeCitation, quote: "+ notCaptured();" }
											: changeCitation,
									),
								),
							);
							if (sessionNumber === 1) {
								content.push(submitCall("commit", notMet("Revised within the batch")));
							} else if (batchCorrection) {
								content.push(submitCall("same-batch-correction", notMet(`Corrected ${slug}`)));
							} else if (refusedRevision) {
								content.push(
									submitCall(
										"bad-commit",
										notMet("Unverified correction", {
											...changeCitation,
											quote: "+ notCaptured();",
										}),
									),
								);
							}
						} else if (sessionNumber === 2 && readCorrection && requests === 2) {
							content.push({
								type: "toolCall",
								id: "read-again",
								name: "read",
								arguments: { path: "evidence/metadata.json" },
							});
						} else if (sessionNumber === 2 && requests === (readCorrection ? 3 : 2)) {
							content.push(submitCall("correction", notMet(`Corrected ${slug}`)));
						} else {
							content.push({ type: "text", text: "Unnecessary wrap-up." });
						}
						const stopReason = content.some((part) => part.type === "toolCall")
							? "toolUse"
							: "stop";
						const message: Awaited<ReturnType<NativeStream["result"]>> = {
							role: "assistant",
							api: model.api,
							provider: model.provider,
							model: model.id,
							content,
							stopReason,
							timestamp: Date.now(),
							usage: {
								...callUsage(20),
								totalTokens: 1020,
								cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
							},
						};
						const stream = makeStream();
						stream.push({ type: "done", reason: stopReason, message });
						return stream;
					};
					let nudged = false;
					opened.session.subscribe((event) => {
						if (
							scenario.startsWith("scope-native-problem-") &&
							event.type === "tool_execution_end"
						) {
							if (event.toolCallId === "sibling-key") {
								siblingSelectionFinished.resolve(undefined);
							}
							const result: unknown = event.result;
							record(
								`native-problem-reply:${sessionNumber}:${JSON.stringify({ tool: event.toolName, id: event.toolCallId, isError: event.isError, result })}`,
							);
						}
						if (
							event.type === "message_end" &&
							event.message.role === "assistant" &&
							!nudged &&
							scenario !== "scope-native-submission-repair" &&
							scenario !== "scope-native-submit-nudge" &&
							scenario !== "scope-native-schema-cap" &&
							scenario !== "scope-native-evidence-correction" &&
							scenario !== "scope-native-evidence-cap" &&
							!scenario.startsWith("scope-native-problem-")
						) {
							nudged = true;
							void (async () => {
								await opened.session.steer("Record this practice.");
								await opened.session.followUp("Finish recording this practice.");
								record(`native-queued:${sessionNumber}:${opened.session.pendingMessageCount}`);
							})();
						}
					});
					const prompt = opened.session.prompt.bind(opened.session);
					opened.session.prompt = async (...args) => {
						await prompt(...args);
						if (scenario === "scope-native-problem-compaction" && requests === 1) {
							await opened.session.compact();
							assert.ok(
								opened.session.sessionManager
									.buildSessionProjection()
									.messages.some((message) => message.role === "compactionSummary"),
							);
							record(`native-problem-compacted:${sessionNumber}`);
						}
						record(`native-pending:${sessionNumber}:${opened.session.pendingMessageCount}`);
					};
					return opened;
				}
				// A request the runner lets through reaches this stream with the output it was granted.
				const agent = {
					state: { tools: [] },
					finishTurn: async () => {
						record("native-finish-turn");
						return { action: "continue" };
					},
					streamFunction: (
						_model: unknown,
						_context: unknown,
						requestOptions?: { maxTokens?: number },
					) => {
						record(`stream-request:${String(requestOptions?.maxTokens)}`);
					},
				};
				return {
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						agent,
						systemPrompt: "",
						model: { contextWindow: 128_000, maxTokens: 16_384 },
						settingsManager: { getCompactionSettings: () => ({ reserveTokens: 16_384 }) },
						sessionManager: manager,
						subscribe(handler: (event: unknown) => void) {
							handlers.add(handler);
							return async () => {
								handlers.delete(handler);
								if (scenario === "scope-stop-hangs") {
									const before = [stateText(), notesText()];
									await assert.rejects(
										async () =>
											tool("submit_result").execute("late", notMet("Late overwrite of the result")),
										/Measurement is closed/u,
									);
									assert.deepEqual([stateText(), notesText()], before);
									record("late-record-closed");
								}
							};
						},
						clearQueue: () => {
							queued = 0;
						},
						get pendingMessageCount() {
							return queued;
						},
						getContextUsage: () => ({
							tokens: scenario === "measure-context-compact" && !wasCompacted ? 120_000 : 1000,
							contextWindow: 128_000,
							percent: 0,
						}),
						compact: async () => {
							record("compact");
							wasCompacted = true;
							emit({ type: "compaction_end", reason: "manual", result: undefined, aborted: false });
							return {};
						},
						get isIdle() {
							return releasePrompt === undefined && !compacting;
						},
						get isStreaming() {
							return releasePrompt !== undefined || compacting;
						},
						waitForIdle,
						abortCompaction() {
							if (!compacting) {
								return;
							}
							record("abort-compaction");
							compacting = false;
							settleIdle();
						},
						abort: async () => {
							record("abort");
							if (scenario === "scope-stop-hangs") {
								return Promise.withResolvers<undefined>().promise;
							}
							if (scenario === "scope-stop-failure") {
								throw new Error("Native session did not settle");
							}
							if (scenario === "late-usage" && !settlementUsageEmitted) {
								settlementUsageEmitted = true;
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "stop",
										usage: callUsage(77),
										content: [],
									},
								});
							}
							// The aborted call ends a moment later, and abort() settles once the session is
							// idle — which a compaction still in flight keeps it from being.
							setTimeout(() => releasePrompt?.(), 50);
							return waitForIdle();
						},
						dispose: () => record("dispose"),
						steer: async (message?: string) => {
							queued += 1;
							record("steer");
							if (message !== undefined) {
								steered.push(message);
								appendFileSync(nodePath.join(cwd, "steered"), `${JSON.stringify(message)}\n`);
							}
						},
						async prompt(
							text: string,
							promptOptions?: { preflightResult?: (disposition: string) => void },
						) {
							promptOptions?.preflightResult?.("started");
							prompts += 1;
							record(`prompt:${prompts}`);
							writeFileSync(nodePath.join(cwd, `prompt-${prompts}.md`), text);
							if (scenario === "request-caps") {
								// Each request asks for the model's ceiling; the turn grants only what it has left.
								const request = () => {
									try {
										agent.streamFunction({ maxTokens: 16_384 }, {}, { maxTokens: 16_384 });
									} catch {
										record("stream-request-refused");
									}
								};
								// Each response stays within what it was granted, so the turn ends on its work budget.
								for (const output of [16_383, 7616, 1]) {
									request();
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "stop",
											usage: callUsage(output),
											content: [],
										},
									});
								}
								request();
								await tool("submit_result").execute(
									"capped",
									notMet("Submitted after the turn spent its work"),
								);
								return;
							}
							if (scenario.startsWith("provider-reply-")) {
								const mode = scenario.slice("provider-reply-".length);
								if (prompts === 1) {
									if (mode === "refused-error" || mode === "refused-stall") {
										emit({
											type: "tool_execution_start",
											toolCallId: "refused",
											toolName: "submit_result",
											args: {},
										});
										await assert.rejects(tool("submit_result").execute("refused", {}));
										record("answered-refusal");
									} else if (mode === "stored-error") {
										await tool("submit_result").execute(
											"stored",
											notMet("Submitted before the provider failed"),
										);
									} else if (mode === "empty-error") {
										emit({
											type: "message_end",
											message: { role: "assistant", stopReason: "stop", content: [] },
										});
										return;
									} else if (mode === "partial-error" || mode === "partial-abort") {
										emit({
											type: "message_end",
											message: {
												role: "assistant",
												stopReason: mode === "partial-error" ? "error" : "aborted",
												content:
													mode === "partial-error"
														? [{ type: "text", text: "The changed authentication call" }]
														: [
																{
																	type: "toolCall",
																	id: "partial",
																	name: "submit_result",
																	arguments: {},
																},
															],
											},
										});
									} else if (mode === "abort-only") {
										emit({
											type: "message_end",
											message: { role: "assistant", stopReason: "aborted", content: [] },
										});
									}
								}
								if (mode.endsWith("stall")) {
									now += 301_000;
									const inFlight = Promise.withResolvers<undefined>();
									releasePrompt = () => inFlight.resolve(undefined);
									await inFlight.promise;
									releasePrompt = undefined;
									settleIdle();
								} else {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "error",
											content: mode === "blank-error" ? [{ type: "text", text: "  " }] : [],
											errorMessage: "Provider unavailable",
										},
									});
								}
								return;
							}
							if (scenario === "provider-error") {
								// The provider answers every call with an error the SDK does not retry.
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "error",
										errorMessage: "404: No available model deployments for model 'm' for this key",
										content: [],
									},
								});
								return;
							}
							// Like the SDK: a prompt while the last one is still ending is refused, and the
							// aborted call ends only when abort() is called.
							if (releasePrompt) {
								throw new Error("Agent is already processing.");
							}
							if (scenario === "cut-off" && prompts === 1) {
								// A runaway call: the model writes until its output limit, twice, and each call reaches
								// its tool incomplete. The first is explained; the second ends the turn.
								for (let call = 1; call <= 2; call += 1) {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(16_384),
											content: [
												{ type: "toolCall", id: `c-${call}`, name: "codemode", arguments: {} },
											],
										},
									});
									emit({ type: "turn_end" });
								}
								return;
							}
							if (scenario === "work-budget") {
								// The model reads call after call; the sixth also records. The runner nudges it two
								// calls before its budget and ends the turn after the call that spends it, once that
								// call's tools have run, so the recording it carries lands.
								for (let call = 1; call <= 6; call += 1) {
									const recording = call === 6;
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(100),
											content: [
												{
													type: "toolCall",
													id: `w-${call}`,
													name: recording ? "submit_result" : "read",
													arguments: {},
												},
											],
										},
									});
									if (recording) {
										const reply = await tool("submit_result").execute(
											"w-6",
											notMet("Submitted in the call that spent the budget"),
										);
										record(`recorded:${JSON.stringify(reply)}`);
										emit({
											type: "tool_execution_end",
											toolCallId: "w-6",
											toolName: "submit_result",
											isError: false,
											result: reply,
										});
									}
									emit({ type: "turn_end" });
								}
								return;
							}
							if (scenario === "stall" && prompts === 1) {
								// The turn crossed the compaction threshold, and then nothing came: no token, no tool
								// progress, for longer than the stall bound.
								compacting = true;
								now += 301_000;
								const inFlight = Promise.withResolvers<undefined>();
								releasePrompt = () => inFlight.resolve(undefined);
								await inFlight.promise;
								releasePrompt = undefined;
								settleIdle();
								return;
							}
							if (scenario === "natural-stop" || scenario === "natural-stop-twice") {
								// The model answers in prose and the session ends on its own: prose records nothing.
								record(`natural-prompt:${prompts}:session-${sessionNumber}`);
								if (scenario === "natural-stop" && prompts > 1) {
									const reply = await tool("submit_result").execute(
										"o-natural",
										notMet("Unsafe authentication call"),
									);
									record(`natural-recorded:${JSON.stringify(reply)}`);
									return;
								}
								if (scenario === "natural-stop-twice" && prompts === 1) {
									emit({
										type: "tool_execution_start",
										toolCallId: "r-1",
										toolName: "read",
										args: { path: "evidence/missing.json" },
									});
									emit({
										type: "tool_execution_end",
										toolCallId: "r-1",
										toolName: "read",
										isError: true,
										result: { content: [{ type: "text", text: "ENOENT: no such file" }] },
									});
								}
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "stop",
										usage: callUsage(200),
										content: [
											{
												type: "text",
												text: "The change calls insecure(), so the practice is not met.",
											},
										],
									},
								});
								emit({ type: "turn_end" });
								return;
							}
							const currentSlug = /### Practice `(?<slug>[^`]+)`/u.exec(text)?.groups?.slug;
							assert.ok(currentSlug !== undefined && currentSlug !== "", text.slice(0, 400));
							if (text.includes("## Unfinished practices")) {
								const reply = await tool("submit_result").execute(
									"o-3",
									notMet("Submitted on the finishing turn"),
								);
								record(`finish:${currentSlug}:${JSON.stringify(reply)}`);
								return;
							}
							const report = tool("submit_result");
							measuredTool = report;
							if (
								scenario === "model-error" ||
								scenario === "model-recovered-error" ||
								scenario === "model-retry-failed"
							) {
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "error",
										usage: callUsage(17),
										content: [],
										errorMessage: "503 upstream body sk-orchestration-secret",
										diagnostics: new Map<string, unknown[]>([
											// The call never got its response, so it states no response time.
											[
												"model-error",
												[
													{
														type: "openai_completions_failure",
														timestamp: 1_760_000_000_000,
														details: { kind: "HTTP_ERROR", phase: "request", status: 503 },
													},
													{
														type: "openai_completions_call",
														timestamp: 1_760_000_000_000,
														details: { elapsedMs: 1200 },
													},
												],
											],
											// A Responses call whose failed terminal event arrived after its response.
											[
												"model-recovered-error",
												[
													{
														type: "openai_responses_failure",
														timestamp: 1_760_000_000_000,
														details: { kind: "RESPONSE_FAILED", phase: "response_body" },
													},
													{
														type: "openai_responses_call",
														timestamp: 1_760_000_000_000,
														details: { elapsedMs: 1200, responseMs: 200 },
													},
												],
											],
											// A malformed diagnostic claims nothing: the failure stays UNKNOWN.
											[
												"model-retry-failed",
												[
													{
														type: "openai_completions_failure",
														timestamp: "soon",
														details: { kind: "sk-orchestration-secret", status: 503 },
													},
												],
											],
										]).get(scenario),
									},
								});
								if (scenario === "model-recovered-error") {
									emit({ type: "auto_retry_end", success: true });
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(19),
											content: [],
											diagnostics: [
												{
													type: "openai_responses_call",
													timestamp: 1_760_000_001_000,
													details: { elapsedMs: 900, responseMs: 300 },
												},
											],
										},
									});
								}
								if (scenario === "model-retry-failed") {
									emit({
										type: "auto_retry_end",
										success: false,
										attempt: 3,
										finalError: "sk-orchestration-secret",
									});
								}
								await report.execute("o-1", notMet("Unsafe authentication call"));
								return;
							}
							if (scenario === "scope-cut-off") {
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "length",
										usage: callUsage(1),
										content: [],
									},
								});
							}
							if (scenario === "scope-recording-guard" && currentSlug === "second-practice") {
								for (let attempt = 0; attempt < 24; attempt += 1) {
									emit({
										type: "tool_execution_start",
										toolCallId: `invalid-${attempt}`,
										toolName: "submit_result",
										args: { invalidShape: attempt },
									});
									emit({
										type: "tool_execution_end",
										toolCallId: `invalid-${attempt}`,
										toolName: "submit_result",
										isError: true,
										// A tool's result can quote what it read; the runtime's stderr never does.
										result: {
											content: [
												{
													type: "text",
													text: "Invalid native schema: API_KEY=sk-orchestration-secret",
												},
											],
										},
									});
								}
								return;
							}
							if (scenario === "scope-repeat") {
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "toolUse",
										usage: callUsage(100),
										content: [
											{
												type: "toolCall",
												id: currentSlug,
												name: "read",
												arguments: { path: "work/change/diff.patch" },
											},
										],
									},
								});
								emit({
									type: "tool_execution_start",
									toolCallId: currentSlug,
									toolName: "read",
									args: { path: "work/change/diff.patch" },
								});
								await report.execute("own", notMet(`Own ${currentSlug}`));
								return;
							}
							if (scenario === "scope-ownership") {
								firstTool ??= report;
								const originalTool = firstTool;
								const sibling =
									currentSlug === "test-practice" ? "second-practice" : "test-practice";
								const before = stateText();
								if (firstTool !== report) {
									await assert.rejects(
										async () =>
											originalTool.execute(
												"stale-owner",
												notMet("Old callback must not borrow the next practice"),
											),
										/Measurement is closed/u,
									);
									assert.equal(stateText(), before);
								}
								for (let attempt = 0; attempt < 6; attempt += 1) {
									await assert.rejects(
										report.execute(
											`refused-${attempt}`,
											notMet(`Refused attempt ${attempt}`, {
												...changeCitation,
												quote: `+ notInTheDiff${attempt}();`,
											}),
										),
										/citation does not match/u,
									);
								}
								await assert.rejects(
									report.execute("foreign", {
										...notMet(`Own ${currentSlug}`),
										practiceSlug: sibling,
									}),
									/(?:unknown|Unrecognized).*practiceSlug/u,
								);
								const own = await report.execute("own", notMet(`Own ${currentSlug}`));
								assert.equal(stateText(), before);
								record(`own:${currentSlug}:${JSON.stringify(own)}`);
								return;
							}
							if (scenario === "scope-stop-failure" || scenario === "scope-stop-hangs") {
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "toolUse",
										usage: callUsage(55),
										content: [],
									},
								});
							}
							if (scenario === "scope-budget") {
								for (let call = 0; call < 3; call += 1) {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(100),
											content: [
												{
													type: "toolCall",
													id: `${currentSlug}-${call}`,
													name: call === 2 ? "submit_result" : "read",
													arguments: {},
												},
											],
										},
									});
									if (call === 2) {
										const result = await report.execute(
											`${currentSlug}-${call}`,
											notMet(`Own ${currentSlug}`),
										);
										emit({
											type: "tool_execution_end",
											toolCallId: `${currentSlug}-${call}`,
											toolName: "submit_result",
											isError: false,
											result,
										});
									}
									emit({ type: "turn_end" });
								}
								return;
							}
							if (
								(scenario === "batch" || scenario === "draft-commit") &&
								currentSlug === "second-practice"
							) {
								await report.execute(
									"own-second",
									notMet("Second practice submitted in its own session"),
								);
								return;
							}
							assert.match(report.description, /keeps the result for server admission/u);
							if (scenario === "repeat") {
								// A codemode script reading one file six times is the script's work: its nested
								// calls carry the script's call id and never trip the guard on the model's calls.
								for (let call = 1; call <= 6; call += 1) {
									emit({
										type: "tool_execution_start",
										toolCallId: `c-1/${call}`,
										parentToolCallId: "c-1",
										toolName: "read",
										args: { path: "work/change/diff.patch" },
									});
								}
								// The same bash call, six times: the SDK emits each start with its arguments, and
								// the runner nudges at the third and ends the turn at the sixth.
								for (let call = 1; call <= 6; call += 1) {
									emit({
										type: "tool_execution_start",
										toolCallId: `b-${call}`,
										toolName: "bash",
										args: { command: "git show abc --stat | head" },
									});
								}
								return;
							}
							if (scenario === "met-coverage" || scenario === "met-coverage-repair") {
								emit({
									type: "tool_execution_start",
									toolCallId: "r-cut",
									toolName: "read",
									args: { path: "work/change/diff.patch" },
								});
								emit({
									type: "tool_execution_end",
									toolCallId: "r-cut",
									toolName: "read",
									isError: false,
									result: {
										content: [{ type: "text", text: "[L10] + insecure();" }],
										details: { truncation: { truncated: true } },
									},
								});
								const strength = await report
									.execute("o-met", met("Added call goes through the checked helper"))
									.then(() => "stored")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`met-refused:${JSON.stringify(strength)}`);
								record(`met-notes:${JSON.stringify(notesText())}`);
								if (scenario === "met-coverage-repair") {
									const lines = readFileSync(
										nodePath.join(cwd, "work/change/diff.patch"),
										"utf8",
									).split("\n");
									for (let offset = 1; offset <= lines.length; offset += 2000) {
										const toolCallId = `r-page-${offset}`;
										emit({
											type: "tool_execution_start",
											toolCallId,
											toolName: "read",
											args: { path: "work/change/diff.patch", offset, limit: 2000 },
										});
										emit({
											type: "tool_execution_end",
											toolCallId,
											toolName: "read",
											isError: false,
											result: {
												content: [
													{ type: "text", text: lines.slice(offset - 1, offset + 1999).join("\n") },
												],
												details: {},
											},
										});
									}
									const repaired = await report.execute(
										"o-met-repaired",
										met("Added call goes through the checked helper"),
									);
									record(`met-repaired:${JSON.stringify(repaired)}`);
									return;
								}
								const problem = await report.execute(
									"o-not-met",
									notMet("Unsafe authentication call"),
								);
								record(`not-met-stored:${JSON.stringify(problem)}`);
								return;
							}
							if (scenario === "refusal-cap") {
								// The first item is sent three times as it was; the rest each differ, as a session's
								// attempts at a fix do.
								const wrong = (attempt: number) =>
									notMet("Wrong quote of the change", {
										...changeCitation,
										quote: `+ notInTheDiff${attempt <= 3 ? "" : String(attempt)}();`,
									});
								for (let attempt = 1; attempt <= 9; attempt += 1) {
									await report
										.execute(`o-${attempt}`, wrong(attempt))
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
								const quotedProblem = (summary: string, citation: SubmittedCitation) => {
									const candidate = notMet(summary, citation);
									return {
										...candidate,
										problems: candidate.problems.map((problem) => ({
											...problem,
											basis: {
												type: "duty",
												event: 0,
												criterionQuote: "Added authentication calls use checked(), not insecure().",
												explanation: "The citation witnesses the required authentication check.",
											},
										})),
									};
								};
								const cite = async (
									path: string,
									quote: string,
									summary = "Unsafe authentication call",
								) =>
									report.execute(
										"o-1",
										quotedProblem(summary, {
											sourceKind: "scm.repository.tree",
											sourcePath: "repos/primary/.git/HEAD",
											path,
											startLine: 2,
											quote,
										}),
									);
								await assert.rejects(
									cite("src/Auth.java", "insecure(user);"),
									/\[L2\] reads " {2}insecure\(\);", not "insecure\(user\);"/u,
								);
								await assert.rejects(cite("src/Missing.java", "insecure();"), /no such file/u);
								await assert.rejects(
									cite("src/logo.png", "PNG"),
									/binary and has no lines to quote; cite the commit that adds it in evidence\/commits\.json/u,
								);
								await assert.rejects(cite("../task.json", "schemaVersion"), /no such file/u);
								record("citation:refused");
								await cite("src/Auth.java", "insecure();");
								record("citation:stored");
								// A citation at a commit in the history is read through .git; a wrong line is
								// corrected there too, and an unknown commit is refused.
								const historySha = readFileSync(nodePath.join(cwd, "history-sha"), "utf8");
								const atRevision = async (commit: string, startLine: number) =>
									report.execute(
										"o-2",
										quotedProblem(`Cited at commit line ${startLine}`, {
											sourceKind: "scm.repository.tree",
											sourcePath: "repos/primary/.git/HEAD",
											path: "src/Auth.java",
											commit,
											startLine,
											quote: "insecure();",
										}),
									);
								const relocated: unknown = await atRevision(historySha, 3);
								record(
									`citation:history:${typeof relocated === "object" && relocated !== null && "details" in relocated && typeof relocated.details === "object" && relocated.details !== null && "revised" in relocated.details ? String(relocated.details.revised) : "?"}`,
								);
								await assert.rejects(atRevision("b".repeat(40), 2), /no such file at commit/u);
								record("citation:history-refused");
								await assert.rejects(cite("src/Auth.java", "", "Cited by line alone"), /quote/u);
								record("citation:no-quote-refused");
								writeFileSync(nodePath.join(cwd, "out", "stray.txt"), "left by a session");
								return;
							}
							if (scenario === "replacement-witness") {
								await report.execute(
									"provisional-diff",
									notMet("Retained changed authentication evidence"),
								);
								const beforeReplacement = [stateText(), notesText()];
								assert.match(notesText(), /Retained changed authentication evidence/u);
								await assert.rejects(
									report.execute("replacement-without-diff", undecided(["scm.pull-request.core"])),
									/must show it read the change/u,
								);
								assert.deepEqual([stateText(), notesText()], beforeReplacement);
								record("replacement-witness:preserved");
								return;
							}
							if (scenario === "argument-repairs") {
								const encoded = (quote: string, endLine = "[L10]") => {
									const [problem] = notMet("Unsafe authentication call").problems;
									return {
										...notMet("Unsafe authentication call"),
										problems: JSON.stringify([
											{
												...problem,
												citations: JSON.stringify([
													{ ...changeCitation, startLine: "[L10]", endLine, quote },
												]),
												basis: JSON.stringify({ ...problem?.basis, head: "0" }),
											},
										]),
										evidence: JSON.stringify({}),
									};
								};
								writeFileSync(
									nodePath.join(cwd, "result-call.json"),
									JSON.stringify({
										parameters: report.parameters,
										arguments: encoded("+ insecure();"),
									}),
								);
								await assert.rejects(
									report.execute("invalid-quote", encoded("invented();")),
									/citation does not match/u,
								);
								await assert.rejects(
									report.execute("invalid-range", encoded("insecure();", "[L999]")),
									/citation does not match/u,
								);
								await assert.rejects(
									report.execute(
										"uncaptured-sourceFile",
										notMet("Unsafe authentication call", {
											...changeCitation,
											sourcePath: "evidence/never-captured.json",
										}),
									),
									/sourceFile 'evidence\/never-captured\.json' was not staged by 'scm\.pull-request\.diff'/u,
								);
								assert.match(notesText(), /Nothing recorded yet/u);
								const nullableCitation = {
									...changeCitation,
									side: null,
									commit: null,
									endLine: null,
									quote: null,
								};
								const nullableEvidence = {
									search: null,
									inapplicability: null,
									openQuestions: null,
								};
								await assert.rejects(
									report.execute("incompatible-warrant", {
										...met("Added call goes through the checked helper"),
										evidence: {
											...nullableEvidence,
											inapplicability: {
												consulted: ["scm.pull-request.diff"],
												prerequisite: "none",
												ruledOutBy: "none",
											},
										},
									}),
									/evidence\.inapplicability is permitted only/u,
								);
								assert.match(notesText(), /Nothing recorded yet/u);
								const [problem] = notMet("Unsafe authentication call").problems;
								const reply = await report.execute("valid-transport", {
									...notMet("Unsafe authentication call"),
									problems: [
										{ ...problem, citations: [nullableCitation], key: null, nextStep: null },
									],
									severity: null,
									example: null,
									evidence: nullableEvidence,
								});
								assert.ok(isRecord(reply) && isRecord(reply.details));
								assert.equal(reply.details.inserted, 1);
								assert.equal(reply.details.refused, 0);
								return;
							}
							if (scenario === "draft-commit") {
								const positive = met("Authentication call goes through the helper");
								const negative = notMet("Authentication call is insecure");
								await assert.rejects(
									report.execute("invalid-first", {
										...positive,
										citations: [{ ...changeCitation, quote: "notInTheDiff();" }],
									}),
									/citation does not match/u,
								);
								assert.match(notesText(), /Nothing recorded yet/u);
								const first = await report.execute("first", positive);
								record(`draft-first:${JSON.stringify(first)}`);
								assert.ok(isRecord(first) && isRecord(first.details));
								assert.equal(first.details.inserted, 1);
								assert.equal(first.details.revised, 0);
								assert.equal(stateText(), null);
								assert.match(notesText(), /test-practice: MET/u);
								// A resend that changes nothing stores nothing: an error, or it reads as done.
								const duplicate = await report
									.execute("retry", positive)
									.then((result) => `accepted ${JSON.stringify(result)}`)
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`draft-duplicate:${duplicate}`);
								const drafted = notesText();
								await assert.rejects(
									report.execute("invalid", {
										...negative,
										problems: negative.problems.map((item) => ({
											...item,
											citations: [{ ...changeCitation, quote: "+ notInTheDiff();" }],
										})),
									}),
									/citation does not match/u,
								);
								assert.equal(notesText(), drafted);
								const corrected = await report.execute("correct", negative);
								assert.ok(isRecord(corrected) && isRecord(corrected.details));
								assert.equal(corrected.details.revised, 1);
								assert.equal(corrected.details.inserted, 0);
								record(`draft-corrected:${JSON.stringify(corrected)}`);
								assert.match(notesText(), /test-practice: NOT_MET/u);
								assert.doesNotMatch(notesText(), /test-practice: MET/u);
								return;
							}
							if (scenario === "comment-undecided") {
								// A practice that reads only what people wrote owes the change nothing; one that
								// reads the change still grounds a decision of nothing in it.
								const metadataOnly = undecided(["scm.pull-request.core"]);
								const said = async (id: string, result: unknown) => {
									try {
										await report.execute(id, result);
										return "stored";
									} catch (error) {
										return error instanceof Error ? error.message : String(error);
									}
								};
								if (currentSlug === "review-tone") {
									record(
										`tone-undecided:${await said("tone", {
											outcome: "COULD_NOT_DETERMINE",
											summary: "The tone of the reviewer's note is ambiguous",
											explanation: "The note can be read as a question or as an order.",
											citations: metadataOnly.citations,
											evidence: {
												openQuestions: [
													{
														openQuestion: "Is the note a question or an order?",
														wouldSettleIt: "The reviewer's next note on the thread.",
													},
												],
											},
										})}`,
									);
									return;
								}
								record(`code-undecided:${await said("code-na", metadataOnly)}`);
								await report.execute("code", notMet("Unsafe authentication call"));
								return;
							}
							if (scenario !== "batch") {
								if (scenario === "finish" && currentSlug === "second-practice") {
									for (let call = 1; call <= 6; call += 1) {
										emit({
											type: "tool_execution_start",
											toolCallId: `b-${call}`,
											toolName: "bash",
											args: { command: "git show abc --stat | head" },
										});
									}
									return;
								}
								if (scenario === "finish") {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(1200),
											content: [
												{ type: "toolCall", id: "o-1", name: "submit_result", arguments: {} },
											],
										},
									});
								}
								const measured = await report.execute("o-1", notMet("Unsafe authentication call"));
								if (scenario === "finish") {
									record(`batch:${JSON.stringify(measured)}`);
									emit({
										type: "tool_execution_end",
										toolCallId: "o-1",
										toolName: "submit_result",
										isError: false,
										result: measured,
									});
								}
								return;
							}
							// This session received the complete change in its opening.
							await report.execute("o-na", undecided(["scm.pull-request.core"]));
							const revise = (summary: string, citation: SubmittedCitation = changeCitation) =>
								notMet(summary, citation);
							const before = notesText();
							await assert.rejects(
								report.execute("non-root", { results: [revise("A wrapped correction")] }),
							);
							assert.equal(notesText(), before);
							record("non-root:unchanged");
							const corrected = await report.execute(
								"typed-correction",
								revise("A typed correction"),
							);
							assert.ok(isRecord(corrected) && isRecord(corrected.details));
							assert.equal(corrected.details.revised, 1);
							assert.match(notesText(), /test-practice: NOT_MET — A typed correction/u);
							const correctedCitation = changeCitation;
							const reply = await report.execute(
								"o-1",
								revise("Unsafe authentication call", correctedCitation),
							);
							record(`batch:${JSON.stringify(reply)}`);
							const outside = await report
								.execute("o-outside", {
									...revise("A result that names another practice"),
									practiceSlug: "second-practice",
								})
								.then((result) => JSON.stringify(result))
								.catch((error: unknown) =>
									error instanceof Error ? error.message : String(error),
								);
							record(`outside:${outside}`);
							await assert.rejects(
								report.execute("o-long", revise(OVERLONG_SUMMARY)),
								/summary.*(?:at most|Too big)/u,
							);
							const kind = await report.execute(
								"o-kind",
								revise("Cited under the wrong source kind", {
									...changeCitation,
									sourceKind: "scm.pull-request.core",
								}),
							);
							record(`corrected-kind:${JSON.stringify(kind)}`);
							// A record cited under the diff's source kind, with a diff side: recorded as the
							// record's own source, without the side admission would refuse.
							await assert.rejects(
								report.execute(
									"o-side",
									revise("A record cited with a diff side", {
										sourceKind: "scm.pull-request.core",
										sourcePath: "evidence/metadata.json",
										path: "evidence/metadata.json",
										side: "NEW",
										commit: "c".repeat(40),
										startLine: 1,
										quote: '"title": "Add login"',
									}),
								),
								/forbidden by the source catalog/u,
							);
							record("corrected-side:refused by the source catalog");
							const path = await report.execute(
								"o-path",
								revise("A record quoted under the change", {
									sourceKind: "scm.pull-request.core",
									sourcePath: "evidence/metadata.json",
									path: "evidence/metadata.json",
									startLine: 1,
									endLine: 1,
									quote: '"title": "Add login"',
								}),
							);
							record(`corrected-path:${JSON.stringify(path)}`);
							await report.execute("o-2", revise("The login change calls an insecure helper"));
							const resent = await report
								.execute("o-3", revise("The login change calls an insecure helper"))
								.then((answer) => JSON.stringify(answer))
								.catch((error: unknown) =>
									error instanceof Error ? error.message : String(error),
								);
							record(`resent:${resent}`);
						},
					},
				};
			},
		},
		(options) => {
			reviewExtensions = options.extensionFactories ?? [];
			assert.ok(cwd !== "");
			if (!existsSync(nodePath.join(cwd, "system-prompt.md"))) {
				writeFileSync(nodePath.join(cwd, "system-prompt.md"), options.systemPrompt);
			}
			assert.ok(options.agentsFilesOverride);
			assert.deepEqual(options.agentsFilesOverride(), { agentsFiles: [] });
		},
		() => {
			if (scenario === "setup") {
				now += PAST_SAFETY_MS;
			}
		},
	);
	const { runReviewJob } = await import("../../../main/resources/agent/pi-runner.ts");
	await runReviewJob();
} else {
	for (const stage of [
		"scope-native-problem-drafts",
		"scope-native-problem-repair",
		"scope-native-problem-isolation",
		"scope-native-problem-no-submit",
		"scope-native-problem-compaction",
		"scope-native-completion",
		"scope-native-commit",
		"scope-native-batch-correction",
		"scope-native-read-correction",
		"scope-native-submission-repair",
		"scope-native-submit-nudge",
		"scope-native-schema-cap",
		"scope-native-evidence-correction",
		"scope-native-evidence-cap",
		"scope-cut-off",
		"scope-recording-guard",
		"scope-repeat",
		"scope-ownership",
		"scope-budget",
		"scope-stop-failure",
		"scope-stop-hangs",
		"late-usage",
		"setup",
		"context-unfit",
		"measure-context-compact",
		"measure-context-missing",
		"session-init",
		"work-budget",
		"cut-off",
		"stall",
		"settle-safety",
		"request-caps",
		"provider-error",
		"provider-reply-unanswered-stall",
		"provider-reply-refused-error",
		"provider-reply-refused-stall",
		"provider-reply-empty-error",
		"provider-reply-partial-error",
		"provider-reply-partial-abort",
		"provider-reply-blank-error",
		"provider-reply-abort-only",
		"provider-reply-stored-error",
		"batch",
		"replacement-witness",
		"comment-undecided",
		"draft-commit",
		"argument-repairs",
		"finish",
		"natural-stop",
		"natural-stop-twice",
		"refusal-cap",
		"repeat",
		"met-coverage",
		"met-coverage-repair",
		"tree-citation",
		"model-error",
		"model-recovered-error",
		"model-retry-failed",
		"admission-unknown-outcome",
		"admission-null-outcome",
	]) {
		void test(
			{
				"scope-native-problem-drafts":
					"native keyed problem drafts edit, remove and select ordered problems without admitting late edits",
				"scope-native-problem-repair":
					"native refused problem corrections and key selections keep the earlier valid draft repairable",
				"scope-native-problem-compaction":
					"native compaction restores only the owning problem inventory and read_problem recovers a full body for correction",
				"scope-native-problem-no-submit":
					"native unsubmitted problem drafts stay non-results while a healthy sibling still reaches admission",
				"scope-native-problem-isolation":
					"native sibling sessions cannot select each other's drafts and a later MET leaks no staged problems",
				"scope-native-completion":
					"native mixed report and read ends each recorded practice before another model request",
				"scope-native-commit":
					"native refused commit stays open until corrected while same-batch valid commit settles",
				"scope-native-batch-correction":
					"native same-batch correction closes after the final successful report",
				"scope-native-read-correction":
					"native refused commit stays unresolved through a read-only turn until corrected",
				"scope-native-submit-nudge":
					"native prose-only review is nudged into a required submit_result request",
				"scope-native-submission-repair":
					"native review repairs an out-of-range citation and retired evidence fields using the exact tool errors",
				"scope-native-schema-cap":
					"native schema refusals stop at the same per-practice limit as candidate refusals",
				"scope-native-evidence-correction":
					"native evidence refusals spend the allowance once each, so a correction within the limit is recorded",
				"scope-native-evidence-cap":
					"native evidence refusals still stop at the per-practice limit",
				"scope-cut-off":
					"a truncated response in a prior practice does not consume the next session's correction chance",
				"scope-recording-guard":
					"a prior practice's submitted result does not disable another session's schema-refusal loop guard",
				"scope-repeat":
					"identical evidence reads in fresh practice sessions do not become a repeated-call loop",
				"scope-ownership":
					"a session's submissions bind to its own practice: they change no other practice's result and spend none of its refusal allowance",
				"scope-budget":
					"fresh practice sessions share one group budget and accumulate nonzero native usage once",
				"scope-stop-hangs":
					"a session that never settles fails within the existing deadline, refuses a late submission and keeps its usage without admission",
				"scope-stop-failure":
					"a native stop failure ends the run with its usage and starts no later practice session",
				"late-usage":
					"a practice session's settlement remains subscribed for its final native usage event",
				"request-caps":
					"grants each practice request no more output than its reservation has left, and no request once it is spent",
				"model-error":
					"traces a failed model call with its stated status and timing, without the provider's text",
				"model-recovered-error":
					"clears a failure a later successful call recovers, keeping its count and timing",
				"model-retry-failed":
					"traces a malformed failure diagnostic as unknown and marks the retry that ended unsuccessfully",
				"admission-unknown-outcome": "refuses an admitted outcome outside the vocabulary",
				"admission-null-outcome": "refuses an admitted result without an outcome",
				setup: "does not start a session when setup reaches the safety ceiling",
				"context-unfit":
					"leaves essential oversized criteria not reached without creating results or repeating compaction",
				"session-init": "fails cleanly when the session cannot be created",
				"work-budget":
					"nudges a turn two calls before its work budget and ends it after the call that spends it",
				"cut-off":
					"tells the model a call was cut off at the output limit, and ends a review that does it twice without a second session",
				stall:
					"aborts a turn that shows no sign of life, ends its compaction and waits before the next turn",
				"settle-safety": "does not start a measuring turn once settling reaches the safety line",
				"provider-reply-unanswered-stall":
					"retries only when no model or tool answer preceded a stall",
				"provider-reply-refused-error":
					"does not retry the whole review after a refused submission and a provider error",
				"provider-reply-refused-stall":
					"does not retry the whole review after a refused submission and a stall",
				"provider-reply-empty-error":
					"counts a normal empty assistant stop as a received reply before a provider error",
				"provider-reply-partial-error":
					"counts meaningful partial text on an error as a received reply",
				"provider-reply-partial-abort":
					"counts a partial tool call on an abort as a received reply",
				"provider-reply-blank-error":
					"does not count blank text on a provider error as a received reply",
				"provider-reply-abort-only": "does not count an empty abort as a received reply",
				"provider-reply-stored-error": "admits a stored result despite a later provider error",
				"provider-error":
					"a provider error the SDK does not retry is a failure of the provider, not a review that found nothing",
				"argument-repairs":
					"repairs result transport without accepting a false quote or an overlong range",
				batch:
					"normalizes corrections, refuses a non-root result and binds a result naming another practice to the session's own",
				"draft-commit":
					"replaces a draft only with a later valid complete submission, and refuses a submission after measurement closed",
				"replacement-witness":
					"refuses a quiet replacement when its session received no change content",
				"comment-undecided":
					"accepts an undecided result without the change only for a practice that does not read it",
				finish:
					"finishes practices no turn reached in a fresh session under one shared finish budget, and never reviews a cut-short practice twice",
				"natural-stop":
					"asks a session that ended in prose once, in the same session, to submit its practice's result",
				"natural-stop-twice":
					"leaves a practice not reached when its session ends in prose again after failed reads",
				"refusal-cap": "stops accepting a practice after eight refused submissions",
				repeat: "nudges a turn that repeats one call and ends it when the call keeps coming",
				"met-coverage":
					"refuses a MET whose session cut a read short, with the coverage it lacks, and stores the NOT_MET submitted after it",
				"met-coverage-repair":
					"accepts the same MET after its missing source content is read completely",
				"tree-citation":
					"verifies a HEAD repository citation against the checkout and finalizes out/",
				"measure-context-compact":
					"restores the exact criterion before illustrations and leads when native turn preparation compacts",
				"measure-context-missing":
					"keeps a missing criterion explicit without changing the recording contract",
			}[stage] ?? stage,
			async () => {
				const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-orchestration-")));
				try {
					mkdirSync(nodePath.join(cwd, "catalog/practices"), { recursive: true });
					mkdirSync(nodePath.join(cwd, "evidence"), { recursive: true });
					mkdirSync(nodePath.join(cwd, "work/change"), { recursive: true });
					writeFileSync(
						nodePath.join(cwd, "AGENTS.md"),
						readFileSync(
							new URL("../../../main/resources/agent/pi-orchestrator.md", import.meta.url),
							"utf8",
						),
					);
					writeFileSync(nodePath.join(cwd, "events"), "");
					writeFileSync(nodePath.join(cwd, "evidence/metadata.json"), JSON.stringify(WORK_RECORD));
					writeFileSync(
						nodePath.join(cwd, "evidence/change.json"),
						JSON.stringify({ base_sha: "b".repeat(40), head_sha: "a".repeat(40) }),
					);
					// The change view the container derives from the checkout before the runner starts.
					writeFileSync(
						nodePath.join(cwd, "work/change/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					if (stage === "met-coverage" || stage === "met-coverage-repair") {
						const changePath = nodePath.join(cwd, "work/change/diff.patch");
						writeFileSync(
							changePath,
							readFileSync(changePath, "utf8") + " unchanged context\n".repeat(4000),
						);
					}
					if (stage === "replacement-witness" || stage === "comment-undecided") {
						const changePath = nodePath.join(cwd, "work/change/diff.patch");
						writeFileSync(
							changePath,
							readFileSync(changePath, "utf8") + " unchanged context".repeat(5000),
						);
					}
					let practiceCriteria = "# Test practice\nCriteria.";
					if (stage.startsWith("provider-reply-")) {
						practiceCriteria =
							"# Uses secure authentication\n\n## Standard\nAdded authentication calls use checked(), not insecure().\n\n## applicability\nAn authentication call was added.\n\n## Judge\nMET for checked(); NOT_MET for insecure().\n\n## Grounding\nCite the added call in the captured change.\n\n## Severity\nMAJOR for an added insecure() call.\n\n## Defer\nOther code is outside this practice.";
					}
					if (stage === "measure-context-compact") {
						practiceCriteria =
							"    let answer = qualified(work)\n\n# Standard\nBounded criterion.  \n\n## applicability\nOnly captured work.\n\n## Judge\nQualify the outcome.\n\n## Defer\nUnknown remains unknown.  \n\n";
					} else if (stage === "context-unfit") {
						practiceCriteria = "Essential criteria. ".repeat(30_000);
					}
					if (stage !== "measure-context-missing") {
						writeFileSync(
							nodePath.join(cwd, "catalog/practices/test-practice.md"),
							practiceCriteria,
						);
					}
					mkdirSync(nodePath.join(cwd, PRACTICES_DIR), { recursive: true });
					if (stage === "batch" || stage.startsWith("measure-context-")) {
						writeFileSync(nodePath.join(cwd, "package.json"), '{"type":"module"}\n');
						symlinkSync(LIB, nodePath.join(cwd, nodePath.dirname(PRACTICES_DIR), "lib"));
						writeFileSync(nodePath.join(cwd, PRACTICES_DIR, "test-practice.ts"), LEADS_PROGRAM);
					}
					if (stage === "tree-citation") {
						// A real checkout with one commit in its history, so a citation at a commit is read
						// through .git like admission reads it.
						mkdirSync(nodePath.join(cwd, "repos/primary/src"), { recursive: true });
						writeFileSync(
							nodePath.join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n}\n",
						);
						const git = (...args: string[]) =>
							spawnSync("git", ["-C", nodePath.join(cwd, "repos/primary"), ...args], {
								encoding: "utf8",
								env: environmentForGitFixture({
									GIT_AUTHOR_NAME: "t",
									GIT_AUTHOR_EMAIL: "t@t",
									GIT_COMMITTER_NAME: "t",
									GIT_COMMITTER_EMAIL: "t@t",
								}),
							});
						writeFileSync(
							nodePath.join(cwd, "repos/primary/src/logo.png"),
							Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2]),
						);
						git("init", "-q");
						git("add", ".");
						git("commit", "-q", "-m", "first");
						writeFileSync(
							nodePath.join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n  more();\n}\n",
						);
						git("commit", "-q", "-am", "second");
						writeFileSync(
							nodePath.join(cwd, "history-sha"),
							git("rev-parse", "HEAD~1").stdout.trim(),
						);
					}
					writeFileSync(
						nodePath.join(cwd, "evidence/manifest.json"),
						JSON.stringify({
							itemKind: "scm.pull_request",
							files: [
								{ kind: "scm.pull-request.core", file: { path: "evidence/metadata.json" } },
								{ kind: "scm.pull-request.diff", file: { path: "evidence/change.json" } },
								{ kind: "scm.repository.tree", file: { path: "repos/primary/.git/HEAD" } },
								{
									kind: "scm.repository.tree",
									file: { path: "repos/primary/.git/hephaestus-captured-refs" },
								},
								// A capture this large must not make the reporter's declaration uncompilable.
								...(stage === "argument-repairs"
									? Array.from({ length: 5000 }, (_, index) => ({
											kind: "scm.pull-request.core",
											file: { path: `evidence/captured/${index}.json` },
										}))
									: []),
							],
							sources: [
								{
									kind: "scm.pull-request.core",
									citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
									state: { availability: "AVAILABLE" },
									files: [{ path: "evidence/metadata.json" }],
								},
								{
									kind: "scm.pull-request.diff",
									citation: { side: "REQUIRED", commit: "OPTIONAL" },
									state: { availability: "AVAILABLE" },
									files: [{ path: "evidence/change.json" }],
								},
								{
									kind: "scm.repository.tree",
									citation: { side: "FORBIDDEN", commit: "OPTIONAL" },
									state: { availability: "AVAILABLE" },
									files: [
										{ path: "repos/primary/.git/HEAD" },
										{ path: "repos/primary/.git/hephaestus-captured-refs" },
									],
								},
							],
						}),
					);
					let index = [practice("test-practice")];
					if (
						stage.startsWith("scope-") ||
						stage === "finish" ||
						stage === "settle-safety" ||
						stage === "session-init" ||
						stage === "batch" ||
						stage === "draft-commit"
					) {
						index = [
							practice("test-practice"),
							practice("second-practice"),
							...(stage === "finish" ? [practice("third-practice")] : []),
						];
					} else if (stage === "stall") {
						index = [
							practice("test-practice"),
							practice("second-practice", undefined, undefined, "docs"),
						];
					} else if (stage === "comment-undecided") {
						index = [practice("test-practice"), practice("review-tone", ["scm.pull-request.core"])];
					}
					if (stage === "scope-repeat") {
						index = Array.from({ length: 6 }, (_, i) =>
							practice(i === 0 ? "test-practice" : `practice-${i}`),
						);
						for (const item of index.slice(1)) {
							writeFileSync(
								nodePath.join(cwd, `catalog/practices/${item.slug}.md`),
								`Complete own criterion for ${item.slug}.`,
							);
						}
					}
					writeFileSync(nodePath.join(cwd, "catalog/practices/index.json"), JSON.stringify(index));
					for (const { slug } of index) {
						writeFileSync(
							nodePath.join(cwd, PRACTICES_DIR, `${slug}.inputs.json`),
							JSON.stringify({ ...GOLDEN_INPUTS, practice: { ...GOLDEN_INPUTS.practice, slug } }),
						);
					}
					writeFileSync(
						nodePath.join(cwd, "catalog/practices/second-practice.md"),
						"Complete second criterion. Its own independent question.",
					);
					writeFileSync(
						nodePath.join(cwd, "pi-provider.json"),
						JSON.stringify({
							apiProtocol: "openai-completions",
							modelId: "test-model",
							forcedToolChoice: true,
						}),
					);
					writeFileSync(
						nodePath.join(cwd, "task.json"),
						JSON.stringify({
							schemaVersion: 7,
							reviewId: "11111111-1111-4111-8111-111111111111",
							jobId: "22222222-2222-4222-8222-222222222222",
							attempt: 1,
							workspaceId: "1",
							contextRoot: "evidence",
							repositoryRoot: "repos/primary",
							manifest: "evidence/manifest.json",
							practiceIndex: "catalog/practices/index.json",
							preparedFeedback: "history/prepared.json",
							practiceScripts: PRACTICES_DIR,
							prompt: "Review the practice.",
							repositoryFullName: "group/repo",
							pullRequestNumber: 3,
						}),
					);
					const budgetMs = stage === "stall" || stage.endsWith("-stall") ? "3600000" : "60000";
					// The fixture's runner clock excludes the native SDK's startup time.
					const child = spawnSync(
						process.execPath,
						[
							"--experimental-test-module-mocks",
							"--experimental-import-meta-resolve",
							import.meta.filename,
						],
						{
							env: {
								...(stage.startsWith("scope-native-") ? { PATH: process.env.PATH } : process.env),
								PI_ORCHESTRATION_SCENARIO: stage,
								PI_RUNNER_CWD: cwd,
								PI_CODING_AGENT_DIR: cwd,
								AGENT_BUDGET_MS: budgetMs,
								PI_PRACTICE_MODEL_CALLS:
									new Map([
										["scope-native-schema-cap", "12"],
										["scope-native-evidence-correction", "12"],
										["scope-native-evidence-cap", "12"],
										["scope-native-problem-repair", "8"],
										["scope-native-problem-isolation", "4"],
										["scope-native-problem-compaction", "5"],
										["scope-native-submission-repair", "4"],
										["scope-native-read-correction", "4"],
									]).get(stage) ?? "2",
								PI_PRACTICE_CONCURRENCY:
									stage === "scope-native-problem-isolation" || stage === "request-caps"
										? "2"
										: "1",
								PI_PRACTICE_OUTPUT_TOKENS: "8000",
								PI_DIRECT_MODEL_TOKENS: "1000",
								LLM_PROXY_URL: "https://unused.invalid",
								LLM_PROXY_TOKEN: "test-token",
							},
							encoding: "utf8",
						},
					);
					assert.equal(child.error, undefined);
					// A scenario's runtime lines are all ones the worker keeps, with no field left out, and never quote a
					// tool, a provider or a model: some scenarios put the canary in a tool result and in provider errors.
					assert.deepEqual(
						child.stderr
							.split("\n")
							.filter(
								(line) =>
									line.startsWith("[pi-") &&
									(!isDiagnosticLine(line) || line.includes(" invalid=")),
							),
						[],
					);
					assert.ok(!child.stderr.includes("sk-orchestration-secret"), child.stderr);
					// Read production output from the native orchestration fixture.
					if (stage === "scope-native-completion") {
						const debug: unknown = JSON.parse(
							readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8"),
						);
						assert.ok(isRecord(debug) && Array.isArray(debug.turns));
						assert.deepEqual(
							debug.turns.map((turn: unknown) => {
								assert.ok(isRecord(turn));
								assert.ok(
									typeof turn.durationMs === "number" &&
										Number.isSafeInteger(turn.durationMs) &&
										turn.durationMs >= 0,
								);
								return turn.phase;
							}),
							["practice"],
						);
					}

					const events = readFileSync(nodePath.join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
					assert.ok(existsSync(nodePath.join(cwd, "out/practice-coverage.json")), child.stderr);
					const coverage: unknown = JSON.parse(
						readFileSync(nodePath.join(cwd, "out/practice-coverage.json"), "utf8"),
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
					const slugs = index.map(({ slug }) => slug).toSorted();
					assert.ok(events.includes("derive-change"), child.stderr);
					const reportPath = nodePath.join(cwd, "out/practice-scripts.json");
					assert.ok(existsSync(reportPath), child.stderr);
					const report = recordOf(JSON.parse(readFileSync(reportPath, "utf8")));
					assert.equal(report.schemaVersion, 2);
					const executions = new Map(
						listOf(report.practices).map((entry) => [
							String(recordOf(entry).practiceSlug),
							recordOf(entry),
						]),
					);
					assert.deepEqual([...executions.keys()], slugs, child.stderr);
					const ran = (expected: Record<string, string>) =>
						assert.deepEqual(
							Object.fromEntries(
								[...executions].map(([slug, entry]) => [
									slug,
									typeof entry.stopReason === "string"
										? `${String(entry.status)} (${entry.stopReason})`
										: String(entry.status),
								]),
							),
							expected,
							child.stderr,
						);
					const admissionPath = nodePath.join(cwd, "admission.json");
					const admissions = events.filter((event) => event === "admit").length;
					assert.equal(admissions, existsSync(admissionPath) ? 1 : 0);
					if (existsSync(admissionPath)) {
						const posted = readResults(admissionPath).map((entry) => String(entry.practiceSlug));
						assert.deepEqual(posted, posted.toSorted());
						assert.ok(posted.length > 0);
						for (const file of ["out/result.json", "out/review-state.json"]) {
							assert.deepEqual(
								JSON.parse(readFileSync(nodePath.join(cwd, file), "utf8")),
								JSON.parse(readFileSync(admissionPath, "utf8")),
								file,
							);
						}
					}
					const measuringSessions = events.filter(
						(event) => event.startsWith("create:session") && event.endsWith(",submit_result"),
					);
					switch (stage) {
						case "scope-native-problem-compaction": {
							assert.equal(child.status, 0, child.stderr);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							for (const [session, slug] of [
								[1, "test-practice"],
								[2, "second-practice"],
							] as const) {
								assert.ok(events.includes(`native-problem-compacted:${session}`));
								const problems = listOf(candidates.get(slug)?.problems).map(recordOf);
								assert.equal(problems.length, 1);
								assert.equal(problems[0]?.summary, `Recovered gap of ${slug}`);
							}
							assert.equal(events.filter((event) => event === "admit").length, 1);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "scope-native-problem-no-submit": {
							assert.equal(child.status, 0, child.stderr);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.equal(candidates.has("test-practice"), false);
							assert.equal(candidates.get("second-practice")?.outcome, "NOT_MET");
							const request = recordOf(
								JSON.parse(readFileSync(nodePath.join(cwd, "admission.json"), "utf8")),
							);
							assert.deepEqual(request.nonResults, [
								{ practiceSlug: "test-practice", reason: "EXECUTION_FAILED" },
							]);
							assert.equal(events.filter((event) => event === "admit").length, 1);
							reached({ "test-practice": "NOT_REACHED", "second-practice": "EVALUATED" });
							break;
						}
						case "scope-native-problem-drafts":
						case "scope-native-problem-repair":
						case "scope-native-problem-isolation": {
							assert.equal(child.status, 0, child.stderr);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							for (const [session, slug] of [
								[1, "test-practice"],
								[2, "second-practice"],
							] as const) {
								const candidate = candidates.get(slug);
								assert.ok(candidate);
								const replies = events
									.filter((event) => event.startsWith(`native-problem-reply:${session}:`))
									.map((event) =>
										recordOf(JSON.parse(event.slice(`native-problem-reply:${session}:`.length))),
									);
								assertDraftCandidate(stage, session, slug, candidate, replies);
							}
							assert.equal(events.filter((event) => event === "admit").length, 1);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "scope-native-submit-nudge": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("native-request:")),
								[
									"native-request:1:1",
									"native-request:1:2",
									"native-request:2:1",
									"native-request:2:2",
								],
							);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.equal(candidates.get("test-practice")?.summary, "Recorded test-practice");
							assert.equal(candidates.get("second-practice")?.summary, "Recorded second-practice");
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "scope-native-submission-repair": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("native-request:")),
								[
									"native-request:1:1",
									"native-request:1:2",
									"native-request:1:3",
									"native-request:2:1",
									"native-request:2:2",
									"native-request:2:3",
								],
							);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.equal(candidates.get("test-practice")?.summary, "Corrected test-practice");
							assert.equal(candidates.get("second-practice")?.summary, "Corrected second-practice");
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "scope-native-schema-cap": {
							assert.equal(child.status, 1, child.stderr);
							for (const session of [1, 2]) {
								assert.equal(
									events.filter((event) => event.startsWith(`native-request:${session}:`)).length,
									8,
								);
							}
							reached({ "test-practice": "NOT_REACHED", "second-practice": "NOT_REACHED" });
							break;
						}
						case "scope-native-evidence-correction":
						case "scope-native-evidence-cap": {
							const corrected = stage === "scope-native-evidence-correction";
							assert.equal(child.status, corrected ? 0 : 1, child.stderr);
							for (const [session, slug] of [
								[1, "test-practice"],
								[2, "second-practice"],
							] as const) {
								assert.equal(
									events.filter((event) => event.startsWith(`native-request:${session}:`)).length,
									8,
								);
								assert.equal(
									child.stderr.split(
										`[pi-runner] practice.result_refused practice=${practiceIndexOf(cwd).indexOf(slug)} `,
									).length - 1,
									corrected ? 7 : 8,
								);
							}
							if (corrected) {
								const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
								assert.equal(candidates.get("test-practice")?.summary, "Corrected test-practice");
								assert.equal(
									candidates.get("second-practice")?.summary,
									"Corrected second-practice",
								);
								reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							} else {
								reached({ "test-practice": "NOT_REACHED", "second-practice": "NOT_REACHED" });
							}
							break;
						}
						case "scope-native-completion":
						case "scope-native-commit":
						case "scope-native-batch-correction":
						case "scope-native-read-correction": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("native-request:")),
								[
									"native-request:1:1",
									"native-request:2:1",
									...(stage === "scope-native-commit" || stage === "scope-native-read-correction"
										? ["native-request:2:2"]
										: []),
									...(stage === "scope-native-read-correction" ? ["native-request:2:3"] : []),
								],
							);
							const candidates = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.equal(candidates.get("test-practice")?.summary, "Revised within the batch");
							assert.equal(
								candidates.get("second-practice")?.summary,
								stage === "scope-native-completion"
									? "Own second-practice"
									: "Corrected second-practice",
							);
							for (const session of [1, 2]) {
								assert.ok(events.includes(`native-queued:${session}:2`));
								assert.ok(events.includes(`native-pending:${session}:0`));
							}
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "model-error":
						case "model-recovered-error":
						case "model-retry-failed": {
							assert.equal(child.status, 0, child.stderr);
							const failures: Record<string, [unknown, number, unknown]> = {
								// The call failed with a stated native status, before any response.
								"model-error": [
									{
										kind: "HTTP_ERROR",
										source: "ADAPTER",
										phase: "request",
										status: 503,
										at: 1_760_000_000_000,
										retryEndedUnsuccessfully: false,
									},
									0,
									timing([[1200]], 1200),
								],
								// A later successful call clears the earlier failure; its count and time stay.
								// Responses diagnostics are read like chat-completions ones.
								"model-recovered-error": [
									null,
									0,
									timing(
										[
											[1200, 200],
											[900, 300],
										],
										1200,
									),
								],
								// A malformed diagnostic is UNKNOWN and unattributed; the native unsuccessful retry-end
								// event marks it. A call without a span is not timed, not timed as zero.
								"model-retry-failed": [
									{
										kind: "UNKNOWN",
										source: "INVALID",
										phase: null,
										status: null,
										at: null,
										retryEndedUnsuccessfully: true,
									},
									1,
									timing([], 0),
								],
							};
							const expected = failures[stage];
							assert.ok(expected !== undefined);
							const [final, unattributed, callTiming] = expected;
							const debugText = readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8");
							assert.ok(!debugText.includes("sk-orchestration-secret"));
							const debug: unknown = JSON.parse(debugText);
							assert.ok(isRecord(debug) && Array.isArray(debug.turns));
							const turn: unknown = debug.turns.find(
								(entry: unknown) => isRecord(entry) && entry.phase === "practice",
							);
							assert.ok(isRecord(turn));
							assert.deepEqual(turn.finalModelFailure, final, stage);
							assert.equal(turn.unattributedModelFailures, unattributed, stage);
							assert.deepEqual(turn.modelCallTiming, callTiming, stage);
							assert.ok(isRecord(turn.modelFailures));
							assert.equal(
								Object.values(turn.modelFailures).reduce(
									(sum: number, count: unknown) => sum + (typeof count === "number" ? count : 0),
									0,
								),
								1,
							);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "scope-cut-off": {
							assert.equal(child.status, 0, child.stderr);
							assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 2);
							assert.equal(events.filter((event) => event === "steer").length, 2);
							assert.doesNotMatch(child.stderr, /turn\.stopped/u);
							ran({ "second-practice": "ok", "test-practice": "ok" });
							break;
						}
						case "scope-recording-guard": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(child.stderr, /turn\.stopped .*stopped_by=loop/u);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 2);
							assert.deepEqual(
								[...candidatesIn(nodePath.join(cwd, "out/result.json")).keys()],
								["test-practice"],
							);
							ran({ "second-practice": "failed (off-format)", "test-practice": "ok" });
							reached({ "test-practice": "EVALUATED", "second-practice": "NOT_REACHED" });
							break;
						}
						case "scope-repeat": {
							assert.equal(child.status, 0, child.stderr);
							assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 6);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 6);
							assert.doesNotMatch(child.stderr, /turn\.stopped|review\.finishing/u);
							assert.match(child.stderr, /calls=6 call_budget=12/u);
							break;
						}
						case "scope-ownership":
						case "scope-budget": {
							assert.equal(child.status, 0, child.stderr);
							const result = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.deepEqual(
								[...result].map(([slug, candidate]) => [slug, candidate.summary]),
								[
									["second-practice", "Own second-practice"],
									["test-practice", "Own test-practice"],
								],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 2);
							assert.ok(events.indexOf("dispose") < events.indexOf("prompt:2"));
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(first, /# Test practice\nCriteria\./u);
							assert.doesNotMatch(first, /Complete second criterion/u);
							assert.match(second, /Complete second criterion\. Its own independent question\./u);
							assert.doesNotMatch(second, /# Test practice|Own test-practice/u);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							ran({ "second-practice": "ok", "test-practice": "ok" });
							if (stage === "scope-ownership") {
								for (const slug of ["test-practice", "second-practice"]) {
									assert.match(
										events.find((event) => event.startsWith(`own:${slug}:`)) ?? "",
										new RegExp(`${slug}: stored \\(negative\\)`, "u"),
									);
								}
								assert.doesNotMatch(child.stderr, /refusal limit/u);
							}
							if (stage === "scope-budget") {
								assert.match(
									child.stderr,
									/turn\.finished .*calls=6 call_budget=6 output_tokens=600 output_token_budget=24000 stopped_by=budget/u,
								);
								assert.match(child.stderr, /measurement\.finished .*calls=6/u);
								assert.doesNotMatch(child.stderr, /review\.finishing/u);
							}
							break;
						}
						case "scope-stop-hangs":
						case "scope-stop-failure": {
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							assert.deepEqual(readResults(nodePath.join(cwd, "out/review-state.json")), []);
							// The double's failed stop, or a stop that never settles, ends the run by its class.
							assert.match(child.stderr, /review\.fatal error=(?:Error|SessionUnsettledError)$/mu);
							if (stage === "scope-stop-hangs") {
								assert.ok(events.includes("late-record-closed"));
							}
							assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
							const usage: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/usage.json"), "utf8"),
							);
							assert.ok(isRecord(usage));
							assert.equal(usage.totalCalls, 1);
							assert.equal(usage.outputTokens, 55);
							ran({ "second-practice": "not-reached (error)", "test-practice": "failed (error)" });
							break;
						}
						case "late-usage": {
							assert.equal(child.status, 0, child.stderr);
							const usage: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/usage.json"), "utf8"),
							);
							assert.ok(isRecord(usage));
							assert.equal(usage.totalCalls, 1);
							assert.equal(usage.outputTokens, 77);
							break;
						}
						case "context-unfit": {
							assert.equal(child.status, 1, child.stderr);
							assert.equal(events.filter((event) => event === "compact").length, 0);
							assert.ok(!events.some((event) => event.startsWith("prompt:")));
							assert.ok(!existsSync(nodePath.join(cwd, "out/result.json")));
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (too-large)" });
							break;
						}

						case "replacement-witness": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("replacement-witness:preserved"), child.stderr);
							assert.equal(
								candidatesIn(nodePath.join(cwd, "out/result.json")).get("test-practice")?.summary,
								"Retained changed authentication evidence",
							);
							break;
						}

						case "settle-safety": {
							assert.equal(child.status, 1, child.stderr);
							assert.ok(!events.some((event) => event.startsWith("prompt:")), events.join("\n"));
							reached({ "test-practice": "NOT_REACHED", "second-practice": "NOT_REACHED" });
							ran({
								"second-practice": "not-reached (deadline)",
								"test-practice": "failed (deadline)",
							});
							break;
						}
						case "request-caps": {
							// A practice request is bounded by its reservation: never more output than the turn has left,
							// and none once the turn is spent.
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("stream-request")),
								[
									"stream-request:16384",
									"stream-request:7617",
									"stream-request:1",
									"stream-request-refused",
								],
								child.stderr,
							);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "setup": {
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(events, ["derive-change"]);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "not-reached (deadline)" });
							break;
						}
						case "session-init": {
							assert.equal(child.status, 1, child.stderr);
							assert.equal(events.filter((event) => event.startsWith("create:session")).length, 2);
							ran({ "second-practice": "failed (error)", "test-practice": "failed (error)" });
							for (const entry of executions.values()) {
								assert.equal(entry.error, "session initialization failed");
							}
							break;
						}
						case "cut-off": {
							assert.equal(child.status, 1, child.stderr);
							assert.match(child.stderr, /\[pi-runner\] turn\.output_limit /u);
							assert.match(child.stderr, /turn\.stopped .*stopped_by=loop/u);
							const order = events.filter((event) => ["steer", "abort"].includes(event));
							assert.ok(order.indexOf("steer") < order.indexOf("abort"));
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:session")).length, 1);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (off-format)" });
							break;
						}
						case "work-budget": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events
									.filter(
										(event) =>
											event.startsWith("prompt:") ||
											event.startsWith("recorded:") ||
											event === "steer" ||
											event === "abort" ||
											event === "dispose",
									)
									.map((event) => (event.startsWith("recorded:") ? "recorded" : event)),
								["prompt:1", "steer", "recorded", "abort", "abort", "dispose"],
							);
							assert.match(events.find((event) => event.startsWith("recorded:")) ?? "", /stored/u);
							const [nudge = ""] = readFileSync(nodePath.join(cwd, "steered"), "utf8")
								.trim()
								.split("\n")
								.map((line): unknown => JSON.parse(line))
								.map(String);
							assert.match(
								nudge,
								/Submit what the inspected evidence supports[^.]*with submit_result/u,
							);
							assert.match(
								child.stderr,
								/turn\.nudged .*calls=4 call_budget=6 output_tokens=400 output_token_budget=24000/u,
							);
							assert.match(child.stderr, /turn\.stopped .*stopped_by=budget/u);
							assert.match(
								child.stderr,
								/turn\.finished .*calls=6 call_budget=6 output_tokens=600 output_token_budget=24000 stopped_by=budget/u,
							);
							reached({ "test-practice": "EVALUATED" });
							ran({ "test-practice": "ok" });
							break;
						}
						case "provider-reply-unanswered-stall":
						case "provider-reply-refused-error":
						case "provider-reply-refused-stall":
						case "provider-reply-empty-error":
						case "provider-reply-partial-error":
						case "provider-reply-partial-abort":
						case "provider-reply-blank-error":
						case "provider-reply-abort-only":
						case "provider-reply-stored-error": {
							const stored = stage === "provider-reply-stored-error";
							const unanswered = [
								"provider-reply-unanswered-stall",
								"provider-reply-blank-error",
								"provider-reply-abort-only",
							].includes(stage);
							const answeredExit = stored ? 0 : 1;
							assert.equal(child.status, unanswered ? 76 : answeredExit, child.stderr);
							reached({ "test-practice": stored ? "EVALUATED" : "NOT_REACHED" });
							if (stored) {
								assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 1);
								assert.equal(readResults(nodePath.join(cwd, "admission.json")).length, 1);
							} else {
								assert.equal(existsSync(nodePath.join(cwd, "out/result.json")), false);
								assert.equal(existsSync(nodePath.join(cwd, "admission.json")), false);
							}
							if (unanswered) {
								assert.match(child.stderr, /review\.unreachable/u);
							} else {
								assert.doesNotMatch(child.stderr, /review\.unreachable/u);
							}
							if (stage.includes("refused")) {
								assert.ok(events.includes("answered-refusal"));
							}
							break;
						}
						case "provider-error": {
							// Exit 76: the server queues the review again instead of recording a failure.
							assert.equal(child.status, 76, child.stderr);
							assert.match(child.stderr, /review\.unreachable unanswered_calls=\d+/u);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (unavailable)" });
							break;
						}
						case "stall": {
							assert.equal(child.status, 0, child.stderr);
							const order = events.filter((event) =>
								["prompt:1", "steer", "abort-compaction", "abort", "prompt:2"].includes(event),
							);
							assert.ok(order.indexOf("abort-compaction") < order.indexOf("abort"));
							assert.ok(events.indexOf("dispose") < events.indexOf("prompt:2"));
							assert.match(child.stderr, /turn\.stopped .*stopped_by=stall/u);
							assert.doesNotMatch(child.stderr, /already processing/u);
							assert.deepEqual(
								[...candidatesIn(nodePath.join(cwd, "out/result.json")).keys()],
								["second-practice"],
							);
							reached({ "test-practice": "NOT_REACHED", "second-practice": "EVALUATED" });
							ran({ "second-practice": "ok", "test-practice": "failed (unavailable)" });
							break;
						}
						case "argument-repairs": {
							assert.equal(child.status, 0, child.stderr);
							const result = candidatesIn(nodePath.join(cwd, "out/result.json")).get(
								"test-practice",
							);
							assert.ok(result !== undefined);
							assert.equal(result.outcome, "NOT_MET");
							const [problem] = listOf(result.problems).map(recordOf);
							assert.deepEqual(problem?.citations, [{ ...changeCitation, quote: " insecure();" }]);
							const native = spawnSync(
								process.execPath,
								[
									"--experimental-import-meta-resolve",
									"--input-type=module",
									"-e",
									`import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { validateToolArguments } = await import(import.meta.resolve("@earendil-works/pi-ai", import.meta.resolve("@earendil-works/pi-coding-agent")));
const { stream } = await import(import.meta.resolve("@earendil-works/pi-ai/api/openai-completions", import.meta.resolve("@earendil-works/pi-coding-agent")));
const { stream: responsesStream } = await import(import.meta.resolve("@earendil-works/pi-ai/api/openai-responses", import.meta.resolve("@earendil-works/pi-coding-agent")));
const { convertResponsesTools } = await import(import.meta.resolve("@earendil-works/pi-ai/api/openai-responses-shared", import.meta.resolve("@earendil-works/pi-coding-agent")));
const { prepareResultArguments, validateResultEvidence } = await import(process.argv[2]);
const captured = JSON.parse(readFileSync(process.argv[1], "utf8"));
const tool = { name: "submit_result", parameters: captured.parameters };
const validate = (args) => {
  const prepared = prepareResultArguments(args);
  validateResultEvidence(prepared, ["scm.pull-request.core", "scm.pull-request.diff"]);
  return validateToolArguments(tool, { name: tool.name, arguments: prepared });
};
assert.deepEqual(validate(captured.arguments), prepareResultArguments(captured.arguments));
// The result is the root: an encoded root, a list or a wrapper refuses the call.
const base = prepareResultArguments(captured.arguments);
const encoded = JSON.stringify(base);
for (const sent of [encoded, encoded + '"', [base], { results: [base] }]) {
  assert.throws(() => validate(sent), JSON.stringify(sent).slice(0, 160));
}
// The result reaches the SDK whole: nested shapes, required fields and vocabularies refuse the call, and the
// keys the host binds are not the session's to send.
const [problem] = base.problems;
const [citation] = problem.citations;
const without = (record, key) => Object.fromEntries(Object.entries(record).filter(([name]) => name !== key));
for (const item of [
  { ...base, outcome: "PASSED" },
  { ...base, severity: "SEVERE" },
  { ...base, confidence: 0.9 },
  { ...base, practiceSlug: "test-practice" },
  { ...base, revises: null },
  { ...base, coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true } },
  { ...base, summary: "x".repeat(10000) },
  without(base, "evidence"),
  without(base, "explanation"),
  { ...base, problems: null },
  { ...base, problems: [] },
  { ...base, problems: [without(problem, "basis")] },
  { ...base, problems: [{ ...problem, basis: { ...problem.basis, type: "unknown" } }] },
  { ...base, problems: [{ ...problem, citations: [] }] },
  { ...base, problems: [{ ...problem, citations: [without(citation, "quote")] }] },
  { ...base, problems: [{ ...problem, citations: [{ ...citation, sourcePath: "" }] }] },
  { ...base, problems: [{ ...problem, citations: [{ ...citation, sourceKind: "hephaestus.00000000-0000-4000-8000-000000000010" }] }] },
  { ...base, problems: [{ ...problem, citations: [{ ...citation, side: "MIDDLE" }] }] },
  { ...base, problems: [{ ...problem, citations: [{ ...citation, startLine: 0 }] }] },
]) {
  assert.throws(() => validate(item), JSON.stringify(item).slice(0, 160));
}
// Optional fields stay optional: their absence is accepted as it is, not filled in.
const decided = { outcome: "MET", summary: base.summary, explanation: base.explanation, citations: [citation] };
const inapplicability = { consulted: [citation.sourceKind], prerequisite: "Runtime behavior", ruledOutBy: "Only documentation changed." };
const question = { openQuestion: "Was the described test run?", wouldSettleIt: "The referenced test report." };
for (const item of [
  { ...base, problems: [{ ...problem, citations: [without(without(without(citation, "side"), "endLine"), "sourcePath")] }] },
  { ...decided, evidence: {} },
  { ...decided, outcome: "DOES_NOT_APPLY", evidence: { inapplicability } },
  { ...decided, outcome: "COULD_NOT_DETERMINE", evidence: { openQuestions: [question] } },
]) {
  assert.deepEqual(validate(item), item);
}
// Nullable absence is valid for every outcome, including a provider that requires all declared fields.
const absent = { search: null, inapplicability: null, openQuestions: null };
const nullableCitation = { ...citation, sourceKind: null, sourcePath: null, side: null, commit: null, endLine: null };
const nulls = { problems: null, example: null };
const nullableCases = [
  { ...base, example: null, problems: [{ ...problem, key: null, nextStep: null, citations: [nullableCitation] }], evidence: absent },
  { ...decided, ...nulls, citations: [nullableCitation], evidence: absent },
  { ...decided, ...nulls, outcome: "DOES_NOT_APPLY", citations: [nullableCitation], evidence: { ...absent, inapplicability } },
  { ...decided, ...nulls, outcome: "COULD_NOT_DETERMINE", citations: [nullableCitation], evidence: { ...absent, openQuestions: [{ ...question, citation: null }] } },
];
const responseDeclaration = convertResponsesTools([{ ...tool, description: "Submit a result." }])[0];
assert.equal(responseDeclaration.strict, false);
for (const item of nullableCases) {
  assert.deepEqual(validate(item), prepareResultArguments(item));
  assert.deepEqual(validateToolArguments({ ...tool, parameters: responseDeclaration.parameters }, { name: tool.name, arguments: item }), item);
}
// Optional empty lists are absent; invalid primitives still reach outcome and citation validation.
for (const value of ["", false, true, 0, []]) {
  for (const branch of Object.keys(absent)) {
    const sent = { ...base, evidence: { [branch]: value } };
    if ((branch === "inapplicability" && (value === "" || Array.isArray(value))) ||
        (branch === "openQuestions" && Array.isArray(value))) {
      assert.deepEqual(validate(sent), { ...base, evidence: {} });
    } else {
      assert.throws(() => validate(sent));
    }
  }
}
for (const [field, invalid] of [
  ["side", ["", false]], ["commit", ["", false]], ["endLine", ["", false]], ["quote", [false, 0, null, ""]],
]) {
  for (const value of invalid) {
    assert.throws(() => validate({ ...base, problems: [{ ...problem, citations: [{ ...citation, [field]: value }] }] }));
  }
}
for (const item of [
  { ...decided, outcome: "DOES_NOT_APPLY", evidence: { inapplicability: {} } },
  { ...decided, outcome: "COULD_NOT_DETERMINE", evidence: {} },
  { ...decided, outcome: "COULD_NOT_DETERMINE", evidence: { openQuestions: null } },
  { ...decided, outcome: "COULD_NOT_DETERMINE", evidence: { openQuestions: [] } },
  { ...decided, outcome: "COULD_NOT_DETERMINE", evidence: { openQuestions: [{}] } },
  { ...base, evidence: { search: {} } },
]) {
  assert.throws(() => validate(item));
}
// The declaration leaves the SDK unchanged for an OpenAI-compatible endpoint: same model, no forced strict mode.
let payload;
const model = {
  id: "configured-model",
  name: "configured-model",
  api: "openai-completions",
  provider: "hephaestus",
  baseUrl: "http://127.0.0.1:9/v1",
  reasoning: false,
  input: ["text"],
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  contextWindow: 128000,
  maxTokens: 1000,
};
const answer = await stream(
  model,
  { messages: [{ role: "system", content: "Submit a result.", toolsAdded: [{ name: tool.name, description: "Submit a result.", parameters: tool.parameters }], timestamp: 0 }, { role: "user", content: "Submit it.", timestamp: 0 }] },
  { apiKey: "test-key", onPayload: (params) => { payload = params; throw new Error("stopped before any request left"); } },
).result();
assert.equal(answer.stopReason, "error");
assert.equal(payload.model, model.id);
const outgoing = payload.tools.find((entry) => entry.function?.name === tool.name);
assert.deepEqual(outgoing.function.parameters, tool.parameters);
assert.notEqual(outgoing.function.strict, true);
let responsesPayload;
const responsesAnswer = await responsesStream(
  { ...model, api: "openai-responses" },
  { messages: [{ role: "system", content: "Submit a result.", toolsAdded: [{ name: tool.name, description: "Submit a result.", parameters: tool.parameters }], timestamp: 0 }, { role: "user", content: "Submit it.", timestamp: 0 }] },
  { apiKey: "test-key", onPayload: (params) => { responsesPayload = params; throw new Error("stopped before any request left"); } },
).result();
assert.equal(responsesAnswer.stopReason, "error");
const responsesTool = responsesPayload.tools.find((entry) => entry.name === tool.name);
assert.deepEqual(responsesTool.parameters, tool.parameters);
for (const item of nullableCases) {
  assert.deepEqual(validateToolArguments({ ...tool, parameters: responsesTool.parameters }, { name: tool.name, arguments: item }), item);
}`,
									nodePath.join(cwd, "result-call.json"),
									new URL("../../../main/resources/agent/pi-tool-arguments.ts", import.meta.url)
										.href,
								],
								{ encoding: "utf8", cwd: process.cwd() },
							);
							assert.equal(native.status, 0, native.stderr);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "draft-commit": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("draft-first:")) ?? "",
								/test-practice: stored\. This is a local result awaiting server admission/u,
								child.stderr,
							);
							assert.match(
								events.find((event) => event.startsWith("draft-duplicate:")) ?? "",
								/test-practice: already submitted; this result changed nothing, so do not send it again/u,
							);
							assert.match(
								events.find((event) => event.startsWith("draft-corrected:")) ?? "",
								/test-practice: revised \(negative\)/u,
								child.stderr,
							);
							const result = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.deepEqual(
								[...result].map(([slug, candidate]) => [
									slug,
									candidate.outcome,
									candidate.summary,
								]),
								[
									["second-practice", "NOT_MET", "Second practice submitted in its own session"],
									["test-practice", "NOT_MET", "Authentication call is insecure"],
								],
							);
							const admission = recordOf(JSON.parse(readFileSync(admissionPath, "utf8")));
							assert.deepEqual(Object.keys(admission), [
								"contractVersion",
								"nonResults",
								"reviewId",
								"jobId",
								"attempt",
								"results",
							]);
							assert.ok(!JSON.stringify(admission).includes("revises"));
							assert.ok(!JSON.stringify(admission).includes("Authentication call goes through"));
							assert.match(
								events.find((event) => event.startsWith("draft-closed:")) ?? "",
								/Measurement is closed; result and notes unchanged/u,
							);
							const notes = readFileSync(nodePath.join(cwd, "work/notes/review.md"), "utf8");
							assert.match(notes, /test-practice: NOT_MET/u);
							assert.doesNotMatch(notes, /test-practice: MET —/u);
							// The next practice receives no earlier draft or superseded positive result.
							const next = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(next, /### Practice `second-practice`/u);
							assert.doesNotMatch(next, /Recorded so far/u);
							assert.doesNotMatch(next, /test-practice: MET —/u);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							ran({ "second-practice": "ok", "test-practice": "ok" });
							break;
						}
						case "comment-undecided": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("tone-undecided:stored"), events.join("\n"));
							assert.match(
								(events.find((event) => event.startsWith("code-undecided:")) ?? "").replaceAll(
									String.raw`\"`,
									'"',
								),
								/must show it read the change: [^]*Call read\(\{"path":"work\/change\/diff\.patch"\}\)/u,
							);
							assert.deepEqual(
								[...candidatesIn(nodePath.join(cwd, "out/result.json"))].map(
									([slug, candidate]) => [slug, candidate.outcome],
								),
								[
									["review-tone", "COULD_NOT_DETERMINE"],
									["test-practice", "NOT_MET"],
								],
							);
							reached({ "test-practice": "EVALUATED", "review-tone": "EVALUATED" });
							break;
						}
						case "measure-context-compact":
						case "measure-context-missing": {
							assert.equal(child.status, 0, child.stderr);
							const turn = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const criterion =
								stage === "measure-context-missing" ? "(criteria file missing)" : practiceCriteria;
							const criterionAt = turn.indexOf(criterion);
							const illustrationsAt = turn.indexOf("## How submit_result takes a result");
							const leadsAt = turn.indexOf("#### Leads from the practice script");
							const submitAt = turn.lastIndexOf("Submit one result for test-practice");
							assert.ok(criterionAt !== -1 && turn.includes("### `evidence/metadata.json`"), turn);
							assert.ok(criterionAt > turn.indexOf("### `evidence/metadata.json`"), turn);
							assert.ok(illustrationsAt > criterionAt + criterion.length, turn);
							assert.ok(leadsAt > illustrationsAt && submitAt > leadsAt, turn);
							assert.equal(turn.split("## How submit_result takes a result").length, 2);
							assert.ok(!turn.includes("Complete second criterion"));
							assert.deepEqual(
								events.filter((event) => event === "compact"),
								stage === "measure-context-compact" ? ["compact"] : [],
							);
							assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 1);
							reached({ "test-practice": "EVALUATED" });
							ran({ "test-practice": "ok" });
							assert.equal(recordOf(executions.get("test-practice")?.program).kind, "custom");
							break;
						}
						case "batch": {
							assert.equal(child.status, 0, child.stderr);
							const system = readFileSync(nodePath.join(cwd, "system-prompt.md"), "utf8");
							assert.doesNotMatch(
								system,
								/<(?:contextRoot|repositoryRoot|manifest|practiceIndex|practiceRoot|historyRoot)>/u,
							);
							for (const path of [
								"evidence/commits.json",
								"repos/primary/",
								"evidence/manifest.json",
								"catalog/practices/index.json",
								"catalog/practices",
								"history/problems.json",
							]) {
								assert.ok(system.includes(path), path);
							}
							assert.doesNotMatch(system, /`(?:write|edit)`|tools\.(?:write|edit)\(/u);
							assert.match(system, /Each fresh session names one practice/u);
							assert.match(system, /Call it directly: a codemode script cannot call it/u);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8"),
								/### Practice `test-practice`/u,
							);
							assert.match(
								events.find((event) => event.startsWith("resent:")) ?? "",
								/already submitted; this result changed nothing, so do not send it again/u,
							);
							assert.ok(events.includes("non-root:unchanged"), events.join("\n"));
							const reply = events.find((event) => event.startsWith("batch:")) ?? "";
							assert.match(reply, /test-practice: revised \(negative\)/u, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("outside:")) ?? "",
								/(?:unknown|Unrecognized).*practiceSlug/u,
							);
							assert.match(reply, /"terminate":true/u);
							assert.deepEqual(measuringSessions, [
								"create:session tools=read,grep,find,ls,bash,codemode,upsert_problem,remove_problem,read_problem,submit_result",
								"create:session tools=read,grep,find,ls,bash,codemode,upsert_problem,remove_problem,read_problem,submit_result",
							]);
							assert.ok(events.includes("exposure:submit_result=model-only"), events.join("\n"));
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.ok(first.indexOf("## Turn") > 0 && second.indexOf("## Turn") > 0);
							assert.equal(
								first.slice(0, first.indexOf("## Turn")),
								second.slice(0, second.indexOf("## Turn")),
							);
							assert.ok(
								first.indexOf(practiceCriteria) <
									first.indexOf("## How submit_result takes a result"),
							);
							assert.ok(
								first.indexOf("## How submit_result takes a result") <
									first.indexOf("#### Leads from the practice script"),
							);
							for (const turn of [first, second]) {
								assert.equal(turn.split("## How submit_result takes a result").length, 2);
							}
							assert.match(
								first,
								/#### Leads from the practice script for `test-practice` — starting points to check against the criteria, not verdicts\n[\s\S]*An added insecure call\./u,
							);
							assert.doesNotMatch(second, /#### Leads from the practice script/u);
							assert.match(
								events.find((event) => event.startsWith("corrected-kind:")) ?? "",
								/recorded as scm\.pull-request\.diff/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-side:")) ?? "",
								/refused by the source catalog/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-path:")) ?? "",
								/revised/u,
							);
							const result = candidatesIn(nodePath.join(cwd, "out/result.json"));
							assert.deepEqual([...result.keys()], ["second-practice", "test-practice"]);
							const own = result.get("test-practice");
							assert.ok(own !== undefined);
							assert.equal(own.summary, "The login change calls an insecure helper");
							assert.equal(
								result.get("second-practice")?.summary,
								"Second practice submitted in its own session",
								child.stderr,
							);
							const [problem] = listOf(own.problems).map(recordOf);
							assert.ok(problem !== undefined);
							const [citation] = listOf(problem.citations).map(recordOf);
							assert.ok(citation !== undefined);
							assert.equal(citation.quote, " insecure();");
							assert.equal(citation.side, "NEW");
							assert.ok(!JSON.stringify([...result]).includes(OVERLONG_SUMMARY));
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							ran({ "second-practice": "ok", "test-practice": "ok" });
							assert.deepEqual(
								[...executions].map(([slug, entry]) => [slug, recordOf(entry.program).kind]),
								[
									["second-practice", "builtin"],
									["test-practice", "custom"],
								],
							);
							break;
						}
						case "finish": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 3);
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const third = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							// Each fresh session receives the stable brief and its own complete criterion.
							assert.ok(first.includes("### `evidence/metadata.json`"), first.slice(0, 300));
							assert.match(
								first,
								/## How submit_result takes a result[\s\S]*"sourcePath": "evidence\/change\.json"/u,
							);
							assert.ok(third.includes("### `evidence/metadata.json`"));
							assert.doesNotMatch(third, /### Practice `test-practice`|test-practice: NOT_MET/u);
							const batch: unknown = JSON.parse(
								(events.find((event) => event.startsWith("batch:")) ?? "batch:{}").slice(
									"batch:".length,
								),
							);
							const details: unknown =
								typeof batch === "object" && batch !== null ? Reflect.get(batch, "details") : null;
							assert.ok(typeof details === "object" && details !== null);
							const written = ["inserted", "duplicates", "refused"]
								.map((key): unknown => Reflect.get(details, key))
								.reduce((sum: number, count) => sum + (typeof count === "number" ? count : 0), 0);
							const pace = Math.round((1500 + 1200 / written) / 2);
							assert.match(
								child.stderr,
								new RegExp(
									`turn\\.started phase=finish practices=1 call_budget=6 output_token_budget=24000 tokens_per_result=${pace}`,
									"u",
								),
							);
							// It evaluates the unfinished practice from its criteria, not from memory.
							assert.match(third, /## Unfinished practices\n[\s\S]*### Practice `third-practice`/u);
							assert.match(
								events.find((event) => event.startsWith("finish:third-practice:")) ?? "",
								/third-practice: stored/u,
							);
							assert.match(
								events.find((event) => event.startsWith("batch:")) ?? "",
								/"terminate":true/u,
							);
							reached({
								"test-practice": "EVALUATED",
								"second-practice": "NOT_REACHED",
								"third-practice": "EVALUATED",
							});
							ran({
								"second-practice": "failed (off-format)",
								"test-practice": "ok",
								"third-practice": "ok",
							});
							break;
						}
						case "repeat": {
							assert.equal(child.status, 1, child.stderr);
							assert.match(
								child.stderr,
								/turn\.repeated_call phase=review turn=1 (?:practice=0 )?tool=bash repeats=3/u,
							);
							assert.match(
								child.stderr,
								/turn\.stopped phase=review turn=1 (?:practice=0 )?stopped_by=loop/u,
							);
							assert.match(child.stderr, /tool\.called phase=review tool=read codemode=true/u);
							assert.equal(
								events.filter((event) => event === "steer").length,
								1,
								events.join("\n"),
							);
							assert.match(
								readFileSync(nodePath.join(cwd, "steered"), "utf8"),
								/Still owed: test-practice\. Submit what the evidence you have read supports for these, in one submit_result call/u,
							);
							assert.ok(events.includes("abort"), events.join("\n"));
							assert.equal(events.filter((event) => event.startsWith("create:session")).length, 1);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (off-format)" });
							break;
						}
						case "refusal-cap": {
							assert.equal(child.status, 1, child.stderr);
							const refusals = events.filter((event) => event.startsWith("refusal-"));
							assert.equal(refusals.length, 9);
							// An identical resend is answered at once with the reason it already had.
							assert.match(
								refusals[1] ?? "",
								/identical to a result this turn already refused, so the answer is the same: citation does not match/u,
							);
							// The third identical send is a loop, and the circuit breaker ends the turn.
							assert.match(child.stderr, /turn\.stopped .*stopped_by=loop/u);
							assert.match(refusals[7] ?? "", /refused/u);
							const afterCap = events.slice(
								events.findIndex((event) => event.startsWith("refusal-9:")),
							);
							assert.ok(
								afterCap.includes("No longer accepted (refusal limit): test-practice."),
								afterCap.join("\n"),
							);
							assert.match(
								refusals[8] ?? "",
								/8 submissions for 'test-practice' were refused; no more are accepted/u,
							);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (off-format)" });
							break;
						}
						case "natural-stop": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("natural-prompt:")),
								["natural-prompt:1:session-1", "natural-prompt:2:session-1"],
							);
							assert.deepEqual(
								events.filter((event) => event.startsWith("create:session")),
								[
									"create:session tools=read,grep,find,ls,bash,codemode,upsert_problem,remove_problem,read_problem,submit_result",
								],
							);
							assert.ok(events.includes("exposure:submit_result=model-only"), events.join("\n"));
							const request = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(request, /in one submit_result call/u);
							assert.match(request, /test-practice/u);
							assert.doesNotMatch(request, /### Practice `|## Unfinished practices/u);
							assert.match(
								events.find((event) => event.startsWith("natural-recorded:")) ?? "",
								/test-practice: stored \(negative\)/u,
							);
							assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 1);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "natural-stop-twice": {
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("natural-prompt:")),
								["natural-prompt:1:session-1", "natural-prompt:2:session-1"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:session")).length, 1);
							assert.doesNotMatch(child.stderr, /review\.finishing/u);
							assert.deepEqual(readResults(nodePath.join(cwd, "out/review-state.json")), []);
							assert.equal(existsSync(nodePath.join(cwd, "out/result.json")), false);
							reached({ "test-practice": "NOT_REACHED" });
							ran({ "test-practice": "failed (off-format)" });
							break;
						}
						case "met-coverage":
						case "met-coverage-repair": {
							assert.equal(child.status, 0, child.stderr);
							const refused = events.find((event) => event.startsWith("met-refused:")) ?? "";
							assert.match(
								refused,
								/a MET result needs complete coverage of its required and cited sources: required or cited sources with unread ranges: 1/u,
							);
							// The cut read returned no counted lines, so every file line of the 4006-line view is unread.
							assert.match(
								refused.replaceAll(String.raw`\"`, '"'),
								/read\(\{"path":"work\/change\/diff\.patch","offset":1,"limit":4006\}\)/u,
							);
							assert.match(
								events.find((event) => event.startsWith("met-notes:")) ?? "",
								/Nothing recorded yet/u,
							);
							if (stage === "met-coverage-repair") {
								assert.match(
									events.find((event) => event.startsWith("met-repaired:")) ?? "",
									/test-practice: stored/u,
								);
								const result = candidatesIn(nodePath.join(cwd, "out/result.json")).get(
									"test-practice",
								);
								assert.equal(result?.outcome, "MET");
								const repairedCoverage = recordOf(result.coverage);
								assert.equal(repairedCoverage.complete, true);
								assert.equal(repairedCoverage.reads, 4);
								assert.equal(repairedCoverage.truncatedReads, 0);
								reached({ "test-practice": "EVALUATED" });
								break;
							}
							assert.match(
								events.find((event) => event.startsWith("not-met-stored:")) ?? "",
								/test-practice: stored \(negative\)/u,
							);
							const result = candidatesIn(nodePath.join(cwd, "out/result.json")).get(
								"test-practice",
							);
							assert.equal(result?.outcome, "NOT_MET");
							assert.deepEqual(result.coverage, {
								source: "host",
								reads: 1,
								truncatedReads: 1,
								failedReads: 0,
								receipts: [],
								complete: false,
								omitted: [
									{
										what: "required or cited sources with unread ranges",
										count: 1,
										reason: "truncated",
										items: ["work/change/diff.patch"],
									},
								],
							});
							assert.deepEqual(recordOf(executions.get("test-practice")?.coverageReached), {
								reads: 1,
								truncatedReads: 1,
								failedReads: 0,
							});
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "tree-citation": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("citation:")),
								[
									"citation:refused",
									"citation:stored",
									"citation:history:1",
									"citation:history-refused",
									"citation:no-quote-refused",
								],
								child.stderr,
							);
							assert.ok(!existsSync(nodePath.join(cwd, "out/stray.txt")));
							const [problem] = listOf(
								candidatesIn(nodePath.join(cwd, "out/result.json")).get("test-practice")?.problems,
							).map(recordOf);
							assert.deepEqual(problem?.citations, [
								{
									sourceKind: "scm.repository.tree",
									sourcePath: "repos/primary/.git/HEAD",
									path: "src/Auth.java",
									commit: readFileSync(nodePath.join(cwd, "history-sha"), "utf8"),
									startLine: 2,
									endLine: 2,
									quote: "insecure();",
								},
							]);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "admission-unknown-outcome":
						case "admission-null-outcome": {
							assert.equal(child.status, 2, child.stderr);
							// The admission answer fails its schema, which names the run's end by its class.
							assert.match(child.stderr, /review\.fatal error=ZodError$/mu);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							break;
						}
						default: {
							assert.fail(`unknown stage ${stage}`);
						}
					}
				} finally {
					rmSync(cwd, { recursive: true, force: true });
				}
			},
		);
	}
}

void test("model timing leaves an overflowing call untimed without changing prior totals", () => {
	const result = newModelCallTiming();
	addModelCall(
		result,
		{
			diagnostics: [
				{
					type: "openai_completions_call",
					details: { elapsedMs: Number.MAX_SAFE_INTEGER, responseMs: Number.MAX_SAFE_INTEGER },
				},
			],
		},
		true,
	);
	const prior = { ...result };
	assert.equal(prior.timedCalls, 1);
	assert.equal(prior.respondedCalls, 1);
	assert.equal(prior.elapsedMs, Number.MAX_SAFE_INTEGER);
	assert.equal(prior.failedElapsedMs, Number.MAX_SAFE_INTEGER);
	assert.equal(prior.responseMs, Number.MAX_SAFE_INTEGER);
	addModelCall(
		result,
		{
			diagnostics: [{ type: "openai_completions_call", details: { elapsedMs: 1, responseMs: 1 } }],
		},
		true,
	);
	assert.deepEqual(result, prior);
});
