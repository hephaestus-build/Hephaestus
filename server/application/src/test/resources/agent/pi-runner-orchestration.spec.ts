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
import nodePath from "node:path";
import { mock, test } from "node:test";

import { isRecord } from "../../../main/resources/agent/pi-observation-normalize.ts";

// The runner reads /workspace and the environment at module scope, so each scenario is a child
// process: this file re-enters itself with the SDK mocked and drives one review through it.

const admittedObservation = {
	outcome: "NOT_MET",
	id: "observation-1",
	practiceSlug: "test-practice",
	severity: "MAJOR",
	anchorable: true,
	publicEligible: true,
	citations: [
		{
			index: 0,
			sourceKind: "scm.pull-request.diff",
			path: "src/Auth.java",
			side: "NEW",
			startLine: 10,
			endLine: 10,
			quote: "+ insecure();",
			verification: { status: "VERIFIED", scope: "EXACT_LOCATION" },
			anchorable: true,
		},
	],
	evidence: {
		citations: [{ path: "src/Auth.java", quote: "+ insecure();" }],
		search: { consulted: ["scm.pull-request.diff"], lookedFor: "x", boundary: "y" },
	},
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

const OVERLONG_SUMMARY = "A summary that runs on ".repeat(8).trim();

/** Words only the person's history holds: they may reach the private lanes and never the review on the work. */
const PRIVATE_HISTORY_SENTENCE = "Earlier reviews told this person about the insecure call twice.";

/** The review composition's whole summary, as the composer wrote it, paragraphs and all. */
const REVIEW_SUMMARY =
	"The login change calls `insecure()` on the path every sign-in takes.\n\n" +
	"Route it through the checked helper so a bad token cannot reach it.";

function observation(slug: string, summary: string, citation: unknown = changeCitation) {
	return {
		practiceSlug: slug,
		summary,
		outcome: "NOT_MET",
		severity: "MAJOR",
		evidenceRationale: "The changed authentication code calls insecure().",
		evidence: { citations: [citation] },
	};
}

/** An observation that decides nothing; for a practice that reads the change, it must show it read it. */
const undecided = (consulted: string[]) => ({
	practiceSlug: "test-practice",
	summary: "Nothing to assess in this change",
	outcome: "NOT_APPLICABLE",
	severity: null,
	evidenceRationale: "The change touches only metadata.",
	evidence: {
		citations: [
			{
				sourceKind: "scm.pull-request.core",
				artifactPath: "evidence/metadata.json",
				path: "evidence/metadata.json",
				startLine: 1,
				quote: '"title": "Add login"',
			},
		],
		inapplicability: {
			consulted,
			subject: "authentication calls",
			ruledOutBy: "no code changed",
		},
	},
});

/** A practice index entry as the server writes it: the sources its evidence requirements name. */
function practice(slug: string, readsSources = ["scm.pull-request.core", "scm.pull-request.diff"]) {
	return { slug, group: "code", readsSources };
}

function readObservations(path: string) {
	const payload: unknown = JSON.parse(readFileSync(path, "utf8"));
	assert.ok(isRecord(payload));
	assert.ok(Array.isArray(payload.observations));
	const observations: unknown[] = payload.observations;
	return observations.map((item) => {
		assert.ok(isRecord(item));
		return item;
	});
}

const noHandler = (): undefined => undefined;
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
	parameters: unknown;
	prepareArguments?: (args: unknown) => unknown;
	execute: (id: string, input: unknown) => Promise<unknown>;
}

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
if (scenario !== undefined && scenario !== "") {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd !== undefined && cwd !== "");
	let now = 1_000_000;
	mock.method(Date, "now", () => now);
	const record = (event: string) => appendFileSync(nodePath.join(cwd, "events"), `${event}\n`);
	const admitted = (): unknown[] => {
		switch (scenario) {
			case "compose-abstention": {
				// Admission answers with every recorded outcome, abstentions included, as it was measured.
				return readObservations(nodePath.join(cwd, "admission.json")).map((posted, index) => ({
					...posted,
					id: `observation-${index + 1}`,
					citations: [],
					anchorable: false,
					publicEligible: true,
				}));
			}
			case "compose-fold": {
				return [
					admittedObservation,
					{ ...admittedObservation, id: "observation-2", practiceSlug: "second-practice" },
				];
			}
			case "compose":
			case "compose-foreign-provider": {
				// Admission marks an observation drawn from the person's history as not for the review on the work.
				return [
					admittedObservation,
					{
						...admittedObservation,
						id: "observation-history",
						practiceSlug: "test-practice",
						summary: PRIVATE_HISTORY_SENTENCE,
						evidenceRationale: PRIVATE_HISTORY_SENTENCE,
						publicEligible: false,
						anchorable: false,
						citations: [
							{
								index: 0,
								sourceKind: "hephaestus.feedback-history",
								path: "history/feedback.json",
								anchorable: false,
							},
						],
					},
				];
			}
			case "compose-quiet": {
				return [{ ...admittedObservation, outcome: "MET", severity: null }];
			}
			case "compose-unknown-outcome": {
				return [{ ...admittedObservation, outcome: "PASSED" }];
			}
			case "compose-null-outcome": {
				return [{ ...admittedObservation, outcome: null }];
			}
			default: {
				return [admittedObservation];
			}
		}
	};
	mock.method(globalThis, "fetch", async (_input: unknown, init?: RequestInit) => {
		if (scenario === "draft-revision" || scenario === "compose-abstention") {
			assert.ok(typeof init?.body === "string");
			writeFileSync(nodePath.join(cwd, "admission.json"), init.body);
		}
		return Response.json({
			schemaVersion: 1,
			admissionDigest: "admitted-digest",
			observations: admitted(),
		});
	});
	const manager = { getSessionFile: () => undefined, getSessionId: () => "test-session" };
	let prompts = 0;
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
	const waitForIdle = async () => {
		if (scenario === "settle-safety" && compacting) {
			now += 20_000;
			compacting = false;
		}
		const { promise, resolve } = Promise.withResolvers<undefined>();
		idleWaiters.push(() => resolve(undefined));
		settleIdle();
		await promise;
	};
	/** The session's event handler, so a scenario can emit what the SDK would. */
	let emit: (event: unknown) => void = noHandler;
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			defineTool: (tool: unknown) => tool,
			createCodemodeExtension: () => () => undefined,
			getAgentDir: () => cwd,
			DefaultResourceLoader: class {
				constructor(options: {
					systemPrompt: string;
					agentsFilesOverride: () => unknown;
					extensionFactories?: unknown[];
				}) {
					assert.ok(cwd !== undefined && cwd !== "");
					// The measurement session's loader comes first; the review composition's own follows it.
					const first = !existsSync(nodePath.join(cwd, "system-prompt.md"));
					writeFileSync(
						nodePath.join(cwd, first ? "system-prompt.md" : "review-system-prompt.md"),
						options.systemPrompt,
					);
					if (!first) {
						record(`review-loader extensions=${String(options.extensionFactories?.length)}`);
					}
					assert.deepEqual(options.agentsFilesOverride(), { agentsFiles: [] });
				}
				readonly loaded = Promise.resolve();
				async reload() {
					await this.loaded;
				}
			},
			SettingsManager: { create: () => ({}) },
			SessionManager: { create: () => manager, inMemory: () => manager, open: () => manager },
			ModelRuntime: {
				async create() {
					if (scenario === "setup") {
						now += 20_000;
					}
					return {
						registerProvider: () => undefined,
						getModel: () => ({ contextWindow: 128_000, maxTokens: 16_384 }),
					};
				},
			},
			async createAgentSession(options: { tools: string[]; customTools: CustomTool[] }) {
				record(`create:session tools=${options.tools.join(",")}`);
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
				return {
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe(handler: (event: unknown) => void) {
							emit = handler;
							return () => undefined;
						},
						clearQueue: () => undefined,
						getContextUsage: () => ({
							tokens: scenario === "compose-overflow" ? 120_000 : 1000,
							contextWindow: 128_000,
							percent: 0,
						}),
						compact: async () => {
							record("compact");
							emit({ type: "compaction_end", reason: "manual", result: undefined, aborted: false });
							return {};
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
							// The aborted call ends a moment later, and abort() settles once the session is
							// idle — which a compaction still in flight keeps it from being.
							setTimeout(() => releasePrompt?.(), 50);
							return waitForIdle();
						},
						dispose: () => record("dispose"),
						steer: async () => {
							record("steer");
						},
						async prompt(text: string) {
							prompts += 1;
							record(`prompt:${prompts}`);
							writeFileSync(nodePath.join(cwd, `prompt-${prompts}.md`), text);
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
													name: recording ? "report_observation" : "read",
													arguments: {},
												},
											],
										},
									});
									if (recording) {
										const reply = await tool("report_observation").execute("w-6", {
											observations: [
												observation("test-practice", "Recorded in the call that spent the budget"),
											],
										});
										record(`recorded:${JSON.stringify(reply)}`);
										emit({
											type: "tool_execution_end",
											toolCallId: "w-6",
											toolName: "report_observation",
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
							if (
								text.includes("## The review to write") ||
								text.includes("The review leaves these NOT_MET observations undecided")
							) {
								// The review composition, in its own session: report_review is all it can call.
								assert.deepEqual(options.tools, ["report_review"]);
								assert.deepEqual(
									options.customTools.map((custom) => custom.name),
									["report_review"],
								);
								const review = tool("report_review");
								if (text.includes("The review leaves")) {
									record("review-retry");
									return;
								}
								if (scenario === "compose-settle-deadline") {
									// The response ended, but its auto-compaction remains busy until the deadline.
									compacting = true;
									return;
								}
								if (scenario === "compose-loop") {
									// SDK validation failures emit tool events without executing the tool.
									for (let call = 1; call <= 24; call += 1) {
										emit({
											type: "tool_execution_start",
											toolCallId: `s-${call}`,
											toolName: "report_review",
											args: {},
										});
										emit({
											type: "tool_execution_end",
											toolCallId: `s-${call}`,
											toolName: "report_review",
											isError: true,
											result: {
												content: [
													{
														type: "text",
														text: 'Validation failed for tool "report_review":\n  - /summary: Expected object',
													},
												],
											},
										});
									}
									return;
								}
								const attempt = async (id: string, args: unknown) => {
									try {
										return JSON.stringify(await review.execute(id, args));
									} catch (error) {
										return JSON.stringify(error instanceof Error ? error.message : String(error));
									}
								};
								if (scenario === "compose-quiet") {
									// Only a strength was measured: the review may say so, specifically.
									record(
										`review-strength:${await attempt("r-q", {
											summary: {
												body: "Moving the check into its own helper keeps every caller on the same path.",
												basedOn: ["observation-1"],
											},
										})}`,
									);
									return;
								}
								if (scenario === "compose-abstention") {
									record(
										`review-abstention:${await attempt("r-a", {
											summary: { body: "Nothing to settle here.", basedOn: ["observation-2"] },
										})}`,
									);
									record(
										`review-met:${await attempt("r-m", {
											summary: {
												body: "The helper you added keeps the login flow on one path.",
												basedOn: ["observation-1"],
											},
										})}`,
									);
									return;
								}
								if (scenario === "compose" || scenario === "compose-foreign-provider") {
									record(
										`review-refused:${await attempt("r-1", {
											summary: { body: "Fine. <!-- marker -->", basedOn: ["observation-1"] },
											inline: [
												{
													body: "On this line.",
													basedOn: ["observation-1"],
													anchor: { observationId: "observation-2", citationIndex: 0 },
												},
											],
										})}`,
									);
									record(
										`review-history:${await attempt("r-h", {
											summary: { body: "A sentence.", basedOn: ["observation-history"] },
										})}`,
									);
									record(
										`review-stored:${await attempt("r-2", {
											summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
											inline: [
												{
													body: "`insecure()` runs here before the token is checked; call the checked helper instead.",
													basedOn: ["observation-1"],
													anchor: { observationId: "observation-1", citationIndex: 0 },
												},
											],
										})}`,
									);
									return;
								}
								// Every other composing scenario decides the review's one problem by withholding it.
								const ids =
									scenario === "compose-fold"
										? ["observation-1", "observation-2"]
										: ["observation-1"];
								record(
									`review-withheld:${await attempt("r-w", {
										withheld: [{ basedOn: ids, reason: "ALREADY_SAID" }],
									})}`,
								);
								return;
							}
							if (text.includes("## Undecided")) {
								// The composer's finishing prompt: the runner asks once more for the practices
								// with a NOT_MET observation, and a WITHHOLD is a recorded decision.
								// Sent without basedOn: a WITHHOLD rests on its practice's NOT_MET observations,
								// found under the slug as any unit's slug is read.
								const withheld = await tool("report_feedback").execute("f-9", {
									units: [
										{
											channel: "IN_APP",
											practiceSlug: " Test_Practice ",
											action: "WITHHOLD",
											withholdReason: "below_bar",
										},
									],
								});
								record(`feedback-finish:${JSON.stringify(withheld)}`);
								return;
							}
							if (text.includes("## This turn")) {
								if (scenario === "draft-revision") {
									const closed = await tool("report_observation").execute("after-admission", {
										observations: [
											{
												...observation("test-practice", "Forbidden late replacement"),
												revises: "test-practice",
											},
										],
									});
									record(`draft-closed:${JSON.stringify(closed)}`);
									return;
								}
								// The composition turn, in the same session.
								if (scenario === "compose-settle-deadline") {
									// The response ended, but its auto-compaction remains busy until the deadline.
									compacting = true;
									return;
								}
								if (scenario === "compose-fold") {
									// Two practices saw one event: one private unit names the practice that best names it
									// and folds the other's observation into basedOn, which decides both.
									const folded = await tool("report_feedback").execute("f-fold", {
										units: [
											{
												channel: "IN_APP",
												practiceSlug: "test-practice",
												basedOn: ["observation-1", "observation-2"],
												action: "WITHHOLD",
												withholdReason: "ALREADY_SAID",
											},
										],
									});
									record(`feedback-fold:${JSON.stringify(folded)}`);
									return;
								}
								if (scenario === "compose-loop") {
									return;
								}
								if (scenario === "compose-silent") {
									// A composer that reads its way through the practice files: at the twelfth call
									// without a recording call it is told to persist, once.
									for (let call = 1; call <= 13; call += 1) {
										emit({
											type: "tool_execution_start",
											toolCallId: `r-${call}`,
											toolName: "read",
											args: { path: `catalog/practices/${call}.md` },
										});
									}
									return;
								}
								// The private composition sees the person's history, and the review planned for the work as a draft.
								record(
									`private-turn history=${String(text.includes(PRIVATE_HISTORY_SENTENCE))} review=${String(text.includes("The review planned for this work (a draft, not delivered)"))}`,
								);
								const card = {
									channel: "IN_APP",
									practiceSlug: "test-practice",
									basedOn: ["observation-1"],
									action: "NEW",
									title: "Insecure call",
									body: "The pattern across your work.",
									nextStep: "Check the call before pushing.",
								};
								const feedback = tool("report_feedback");
								// The schema carries the shape and the vocabulary and no rule: a rule is applied
								// per unit, by the tool, so one wrong unit never discards the others in the call.
								const schema = JSON.stringify(feedback.parameters);
								for (const keyword of ["maxLength", "additionalProperties", "minItems", "oneOf"]) {
									assert.ok(
										!schema.includes(`"${keyword}"`),
										`${keyword} in ${schema.slice(0, 200)}`,
									);
								}
								assert.match(schema, /One of: IN_APP\./u);
								assert.match(schema, /Required: channel, practiceSlug, basedOn, action\./u);
								assert.match(
									schema,
									/SUPERSEDE to replace a message that is queued and unread; WITHHOLD/u,
								);
								// One occurrence is not a pattern: with no history, the card is refused, and a
								// call that stored nothing is an error the session must correct.
								const alone = await feedback
									.execute("f-0", { units: [card] })
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-alone:${alone}`);
								mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
								// An earlier review of this same merge request is the same piece of work, not a second.
								writeFileSync(
									nodePath.join(cwd, "history/observations.json"),
									JSON.stringify({
										observations: [
											{
												practiceSlug: "test-practice",
												outcome: "NOT_MET",
												artifact: {
													kind: "scm.pull_request",
													container: "group/repo",
													number: 3,
													url: "https://gitlab.example/group/repo/-/merge_requests/3",
													title: "This change, reviewed before",
												},
											},
										],
									}),
								);
								const sameWork = await feedback
									.execute("f-s", { units: [card] })
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-same-work:${sameWork}`);
								writeFileSync(
									nodePath.join(cwd, "history/observations.json"),
									JSON.stringify({
										observations: [
											{
												practiceSlug: "test-practice",
												outcome: "NOT_MET",
												artifact: { kind: "scm.issue", number: 7, title: "Unresolved work" },
											},
										],
									}),
								);
								const unresolved = await feedback
									.execute("f-u", { units: [card] })
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-unresolved:${unresolved}`);
								writeFileSync(
									nodePath.join(cwd, "history/observations.json"),
									JSON.stringify({
										observations: [
											{
												practiceSlug: "test-practice",
												outcome: "NOT_MET",
												artifact: {
													kind:
														scenario === "compose-foreign-provider"
															? "scm.pull_request"
															: "scm.issue",
													container: "group/repo",
													number: 3,
													url:
														scenario === "compose-foreign-provider"
															? "https://github.com/group/repo/pull/3"
															: "https://gitlab.example/group/repo/-/issues/3",
													title: "Earlier work",
												},
											},
										],
									}),
								);
								// Trying to break it: every unit but the first is wrong in its own way, and each
								// is answered on its own while the first is stored.
								const reply = await feedback.execute("f-1", {
									units: [
										{ ...card, channel: "in_app", basedOn: "observation-1" },
										{
											channel: "IN_APP",
											practiceSlug: "test-practice",
											action: "NEW",
											basedOn: [],
										},
										{ ...card, verdict: "BAD" },
										{ ...card, body: "x".repeat(8001) },
										{ ...card, channel: "IN_CHAT", withholdReason: "NOT_A_REASON" },
										{ ...card, practiceSlug: "other-practice" },
										{ ...card, channel: "IN_CONTEXT", body: undefined },
										"a unit as a string",
									],
								});
								record(`feedback:${JSON.stringify(reply)}`);
								// A single unit sent bare where the list was asked for is the list of one.
								const bare = await feedback
									.execute("f-2", {
										units: {
											...card,
											channel: "IN_CHAT",
											body: undefined,
											nextStep: undefined,
											notes: {
												situation: "s",
												capability: "c",
												evidenceSummary: "e",
												inConversationSignal: "i",
											},
										},
									})
									.then((result) => JSON.stringify(result))
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-bare:${bare}`);
								return;
							}
							if (text.includes("## Unfinished practices")) {
								const unfinished =
									scenario === "stall" || scenario === "repeat" || scenario === "cut-off"
										? "test-practice"
										: "second-practice";
								const reply = await tool("report_observation").execute("o-3", {
									observations: [observation(unfinished, "Recorded on the finishing turn")],
								});
								record(`finish:${JSON.stringify(reply)}`);
								return;
							}
							const report = tool("report_observation");
							assert.match(report.description, /local review state/u);
							// The SDK checks a call against this schema all or nothing, so it carries the shape and
							// the vocabulary and no rule: a rule is applied per observation, by the tool.
							const schema = JSON.stringify(report.parameters);
							for (const keyword of ["maxLength", "additionalProperties", "enum", "pattern"]) {
								assert.ok(
									!schema.includes(`"${keyword}"`),
									`${keyword} in ${schema.slice(0, 200)}`,
								);
							}
							assert.match(schema, /One of: evidence\/change\.json, evidence\/metadata\.json/u);
							assert.match(schema, /One of: OLD, NEW/u);
							// Container types go, so items are answered one by one; scalar types stay as the model's hint.
							const parameters: unknown = report.parameters;
							assert.ok(typeof parameters === "object" && parameters !== null);
							const properties: unknown = Reflect.get(parameters, "properties");
							assert.ok(typeof properties === "object" && properties !== null);
							const items = JSON.stringify(Reflect.get(properties, "observations"));
							assert.ok(!items.includes('"type":"object"'), items.slice(0, 200));
							assert.ok(!items.includes('"type":"array"'), items.slice(0, 200));
							assert.match(items, /"startLine":\{"description":"[^"]*","type":"integer"\}/u);
							assert.match(items, /Required: practiceSlug, summary/u);
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
							if (scenario === "refusal-cap") {
								// The first item is sent three times as it was; the rest each differ, as a session's
								// attempts at a fix do.
								const wrong = (attempt: number) =>
									observation("test-practice", "Wrong quote", {
										...changeCitation,
										quote: `+ notInTheDiff${attempt <= 3 ? "" : String(attempt)}();`,
									});
								for (let attempt = 1; attempt <= 9; attempt += 1) {
									await report
										.execute(`o-${attempt}`, { observations: [wrong(attempt)] })
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
								let hasDraft = false;
								const cite = async (
									path: string,
									quote: string,
									summary = "Unsafe authentication call",
								) =>
									report.execute("o-1", {
										observations: [
											{
												...observation("test-practice", summary, {
													sourceKind: "scm.repository.tree",
													artifactPath: "repos/primary/.git/HEAD",
													path,
													startLine: 2,
													quote,
												}),
												...(hasDraft ? { revises: "test-practice" } : {}),
											},
										],
									});
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
								hasDraft = true;
								record("citation:stored");
								// A citation at a revision in the history is read through .git; a wrong line is
								// corrected there too, and an unknown revision is refused.
								const historySha = readFileSync(nodePath.join(cwd, "history-sha"), "utf8");
								const atRevision = async (revision: string, startLine: number) =>
									report.execute("o-2", {
										observations: [
											{
												...observation("test-practice", `At revision ${startLine}`, {
													sourceKind: "scm.repository.tree",
													artifactPath: "repos/primary/.git/HEAD",
													path: "src/Auth.java",
													revision,
													startLine,
													quote: "insecure();",
												}),
												revises: "test-practice",
											},
										],
									});
								const relocated: unknown = await atRevision(historySha, 3);
								record(
									`citation:history:${typeof relocated === "object" && relocated !== null && "details" in relocated && typeof relocated.details === "object" && relocated.details !== null && "revised" in relocated.details ? String(relocated.details.revised) : "?"}`,
								);
								await assert.rejects(atRevision("b".repeat(40), 2), /no such file at revision/u);
								record("citation:history-refused");
								// Coordinates alone: the runner records the line and echoes what it recorded.
								const echoed: unknown = await cite("src/Auth.java", "", "Cited by line alone");
								const firstContent: unknown =
									typeof echoed === "object" &&
									echoed !== null &&
									"content" in echoed &&
									Array.isArray(echoed.content)
										? echoed.content[0]
										: undefined;
								const echoedText =
									typeof firstContent === "object" &&
									firstContent !== null &&
									"text" in firstContent &&
									typeof firstContent.text === "string"
										? firstContent.text
										: "";
								record(`citation:echo:${echoedText.split("\n")[1] ?? ""}`);
								writeFileSync(nodePath.join(cwd, "out", "stray.txt"), "left by a session");
								return;
							}
							if (scenario === "replacement-witness") {
								const provisional = observation(
									"test-practice",
									"Retained changed authentication evidence",
								);
								await report.execute("provisional-diff", { observations: [provisional] });
								const beforeReplacement = readFileSync(
									nodePath.join(cwd, "out/review-state.json"),
									"utf8",
								);
								await assert.rejects(
									report.execute("replacement-without-diff", {
										observations: [
											{ ...undecided(["scm.pull-request.core"]), revises: "test-practice" },
										],
									}),
									/must show it read the change/u,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
									beforeReplacement,
								);
								record("replacement-witness:preserved");
								return;
							}
							if (scenario === "argument-repairs") {
								const encoded = (quote: string, endLine = "[L10]") => ({
									observations: JSON.stringify([
										{
											...observation("test-practice", "Unsafe authentication call"),
											evidence: JSON.stringify({
												citations: JSON.stringify([
													{ ...changeCitation, startLine: "[L10]", endLine, quote },
												]),
											}),
										},
									]),
								});
								await assert.rejects(
									report.execute("invalid-quote", encoded("invented();")),
									/citation does not match/u,
								);
								await assert.rejects(
									report.execute("invalid-range", encoded("insecure();", "[L999]")),
									/citation does not match/u,
								);
								assert.equal(existsSync(nodePath.join(cwd, "out/review-state.json")), false);
								const reply = await report.execute("valid-transport", encoded("+ insecure();"));
								assert.ok(isRecord(reply) && isRecord(reply.details));
								assert.equal(reply.details.inserted, 1);
								assert.equal(reply.details.refused, 0);
								return;
							}
							if (scenario === "draft-revision") {
								const positive = {
									...observation("test-practice", "Authentication call"),
									outcome: "MET",
									severity: null,
								};
								const negative = observation("test-practice", "Authentication call");
								const readState = () =>
									readObservations(nodePath.join(cwd, "out/review-state.json"));
								await assert.rejects(
									report.execute("wrong-draft", {
										observations: [{ ...positive, revises: "second-practice" }],
									}),
									/revises must name/u,
								);
								await assert.rejects(
									report.execute("invalid-first", {
										observations: [
											{
												...positive,
												revises: "test-practice",
												evidence: {
													citations: [{ ...changeCitation, quote: "notInTheDiff();" }],
												},
											},
										],
									}),
									/citation does not match/u,
								);
								assert.equal(existsSync(nodePath.join(cwd, "out/review-state.json")), false);
								const first = await report.execute("first", {
									observations: [{ ...positive, revises: "test-practice" }],
								});
								record(`draft-first:${JSON.stringify(first)}`);
								assert.ok(isRecord(first) && isRecord(first.details));
								assert.equal(first.details.inserted, 1);
								assert.equal(first.details.revised, 0);
								// A resend that changes nothing stores nothing: an error, or it reads as done.
								const duplicate = await report
									.execute("retry", { observations: [positive] })
									.then((result) => `accepted ${JSON.stringify(result)}`)
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`draft-duplicate:${duplicate}`);
								assert.equal(readState().length, 1);
								await assert.rejects(
									report.execute("implicit", { observations: [negative] }),
									/resend the complete observation with revises/u,
								);
								await assert.rejects(
									report.execute("invalid", {
										observations: [
											{
												...negative,
												revises: "test-practice",
												evidence: {
													citations: [{ ...changeCitation, quote: "+ notInTheDiff();" }],
												},
											},
										],
									}),
									/citation does not match/u,
								);
								assert.equal(readState()[0]?.outcome, "MET");
								const corrected = await report.execute("correct", {
									observations: [{ ...negative, revises: "test-practice" }],
								});
								assert.ok(isRecord(corrected) && isRecord(corrected.details));
								assert.equal(corrected.details.revised, 1);
								assert.equal(corrected.details.inserted, 0);
								assert.equal(corrected.details.totalObservations, 1);
								record(`draft-corrected:${JSON.stringify(corrected)}`);
								const before = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
								await assert.rejects(
									report.execute("ambiguous", {
										observations: [
											observation("second-practice", "Another result"),
											{ ...positive, revises: "test-practice" },
											{ ...negative, practiceSlug: "TEST_PRACTICE", revises: "test-practice" },
										],
									}),
									/without changing any drafts/u,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
									before,
								);
								return;
							}
							if (scenario === "compose-abstention") {
								await report.execute("o-mixed", {
									observations: [
										{
											...observation("test-practice", "Authentication call"),
											outcome: "MET",
											severity: null,
										},
										{
											...undecided(["scm.pull-request.core", "scm.pull-request.diff"]),
											practiceSlug: "second-practice",
										},
										{
											...observation("third-practice", "Whether the call is reachable"),
											outcome: "UNDETERMINED",
											severity: null,
											evidence: {
												citations: [changeCitation],
												undecidability: {
													openQuestion: "Is insecure() reachable from the login flow?",
													wouldSettleIt: "The callers of Auth outside this change.",
												},
											},
										},
									],
								});
								return;
							}
							if (scenario === "comment-undecided") {
								// A practice that reads only what people wrote owes the change nothing; one that
								// reads the change still grounds a decision of nothing in it.
								const metadataOnly = undecided(["scm.pull-request.core"]);
								const tone = await report
									.execute("tone", {
										observations: [
											{
												...metadataOnly,
												practiceSlug: "review-tone",
												summary: "The tone of the reviewer's note is ambiguous",
												outcome: "UNDETERMINED",
												evidence: {
													citations: metadataOnly.evidence.citations,
													undecidability: {
														openQuestion: "Is the note a question or an order?",
														wouldSettleIt: "The reviewer's next note on the thread.",
													},
												},
											},
										],
									})
									.then(() => "stored")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`tone-undecided:${tone}`);
								const code = await report
									.execute("code-na", { observations: [metadataOnly] })
									.then(() => "stored")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`code-undecided:${code}`);
								await report.execute("code", {
									observations: [observation("test-practice", "Unsafe authentication call")],
								});
								return;
							}
							if (scenario !== "batch") {
								if (scenario === "finish") {
									// The message carrying this recording spent 1200 output tokens; the pace is read from it.
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(1200),
											content: [
												{ type: "toolCall", id: "o-1", name: "report_observation", arguments: {} },
											],
										},
									});
								}
								const measured = await report.execute("o-1", {
									observations: [observation("test-practice", "Unsafe authentication call")],
								});
								if (scenario === "finish") {
									record(`batch:${JSON.stringify(measured)}`);
									emit({
										type: "tool_execution_end",
										toolCallId: "o-1",
										toolName: "report_observation",
										isError: false,
										result: measured,
									});
								}
								return;
							}
							await assert.rejects(
								report.execute("o-na", { observations: [undecided(["scm.pull-request.core"])] }),
								/must show it read the change/u,
							);
							await report.execute("o-na2", {
								observations: [undecided(["scm.pull-request.core", "scm.pull-request.diff"])],
							});
							const revise = (summary: string, citation: unknown = changeCitation) => ({
								revises: "test-practice",
								...observation("test-practice", summary, citation),
							});
							const oneBraceTooMany = `${JSON.stringify([revise("Sent as a string with an extra brace")]).slice(0, -1)}}]`;
							await report.execute("o-00", { observations: oneBraceTooMany });
							const intact = JSON.stringify([revise("Sent as a string closed one brace early")]);
							const early = intact.replace(',"evidence":{', '},"evidence":{');
							assert.notEqual(early, intact);
							await report.execute("o-01", { observations: early });
							const two = JSON.stringify([
								revise("First of two, its evidence left open"),
								observation("second-practice", "Second of two, starting inside the first"),
							]);
							const unclosed = two.replace('}]}},{"practiceSlug"', '}]},{"practiceSlug"');
							assert.notEqual(unclosed, two);
							await report.execute("o-02", { observations: unclosed });
							// The evidence object left open, so the item's own keys land inside it: closed before them.
							// Keys in the order the model writes them, so evidence comes before the item's own keys.
							const sorted = Object.fromEntries(
								Object.entries(revise("Evidence left open before the item's own keys")).toSorted(
									([a], [b]) => a.localeCompare(b),
								),
							);
							const whole = JSON.stringify([sorted]);
							const evidenceOpen = whole.replace(/\}(?<next>,"evidenceRationale")/u, "$<next>");
							assert.notEqual(evidenceOpen, whole);
							const evidenceOpenReply = await report.execute("o-03", {
								observations: evidenceOpen,
							});
							record(`repaired-evidence-open:${JSON.stringify(evidenceOpenReply)}`);
							await report
								.execute("o-0", { observations: "[{not json" })
								.then(() => record("unparsed:accepted"))
								.catch((error: unknown) =>
									record(`unparsed:${error instanceof Error ? error.message : String(error)}`),
								);
							const { side: _side, ...sideless } = changeCitation;
							const reply = await report.execute("o-1", {
								observations: [
									revise("Unsafe authentication call", sideless),
									{
										...observation("second-practice", "A quote that is not in the change", {
											...changeCitation,
											quote: "+ somethingElse();",
										}),
										revises: "second-practice",
									},
								],
							});
							record(`batch:${JSON.stringify(reply)}`);
							await assert.rejects(
								report.execute("o-long", { observations: [revise(OVERLONG_SUMMARY)] }),
								/summary must be at most/u,
							);
							const kind = await report.execute("o-kind", {
								observations: [
									revise("Cited under the wrong source kind", {
										...changeCitation,
										sourceKind: "scm.pull-request.core",
									}),
								],
							});
							record(`corrected-kind:${JSON.stringify(kind)}`);
							// A record cited under the diff's source kind, with a diff side: recorded as the
							// record's own source, without the side admission would refuse.
							const side = await report.execute("o-side", {
								observations: [
									revise("A record cited with a diff side", {
										sourceKind: "scm.pull-request.diff",
										artifactPath: "evidence/metadata.json",
										path: "evidence/metadata.json",
										side: "NEW",
										revision: "c".repeat(40),
										startLine: 1,
										quote: '"title": "Add login"',
									}),
								],
							});
							record(`corrected-side:${JSON.stringify(side)}`);
							const withSide = readObservations(nodePath.join(cwd, "out/review-state.json")).find(
								(item) => item.practiceSlug === "test-practice",
							);
							record(`side-recorded:${JSON.stringify(withSide)}`);
							const path = await report.execute("o-path", {
								observations: [
									revise("A record quoted under the change", {
										...changeCitation,
										path: "evidence/metadata.json",
										startLine: 1,
										endLine: 1,
										quote: '"title": "Add login"',
									}),
								],
							});
							record(`corrected-path:${JSON.stringify(path)}`);
							await report.execute("o-2", {
								observations: [revise("The login change calls an insecure helper")],
							});
							// A resend of what is already recorded stores nothing: an error, or it reads as done.
							const resent = await report
								.execute("o-3", {
									observations: [revise("The login change calls an insecure helper")],
								})
								.then(() => "accepted")
								.catch((error: unknown) =>
									error instanceof Error ? error.message : String(error),
								);
							record(`resent:${resent}`);
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
		"session-init",
		"work-budget",
		"cut-off",
		"stall",
		"settle-safety",
		"compose-settle-deadline",
		"provider-error",
		"batch",
		"replacement-witness",
		"comment-undecided",
		"draft-revision",
		"argument-repairs",
		"finish",
		"refusal-cap",
		"repeat",
		"tree-citation",
		"compose",
		"compose-foreign-provider",
		"compose-overflow",
		"compose-silent",
		"compose-loop",
		"compose-quiet",
		"compose-fold",
		"compose-abstention",
		"compose-unknown-outcome",
		"compose-null-outcome",
	]) {
		void test(
			{
				setup: "does not start a session when setup reaches the safety ceiling",
				"session-init": "fails cleanly when the session cannot be created",
				"work-budget":
					"nudges a turn two calls before its work budget and ends it after the call that spends it",
				"cut-off":
					"tells the model a call was cut off at the output limit, and ends a turn that does it twice",
				stall:
					"aborts a turn that shows no sign of life, ends its compaction and waits before the next turn",
				"settle-safety": "does not start a measuring turn once settling reaches the safety line",
				"compose-settle-deadline":
					"does not ask the composer once more after the run reaches its safety ceiling",
				"provider-error":
					"a provider error the SDK does not retry is a failure of the provider, not a review that found nothing",
				"argument-repairs":
					"repairs observation transport without accepting a false quote or an overlong range",
				batch: "normalizes corrections and answers distinct practices per item",
				"draft-revision":
					"replaces only an explicitly corrected valid draft and refuses an ambiguous batch atomically",
				"replacement-witness": "refuses a replacement that relies on its superseded diff witness",
				"comment-undecided":
					"accepts an undecided result without the change only for a practice that does not read it",
				finish: "asks once more, in the same session, for the practices no turn recorded",
				"refusal-cap": "stops accepting a practice after eight refused submissions",
				repeat: "nudges a turn that repeats one call and ends it when the call keeps coming",
				"tree-citation":
					"verifies a HEAD repository citation against the checkout and finalizes out/",
				compose: "counts an issue and a merge request with the same number as distinct work",
				"compose-foreign-provider":
					"counts matching work numbers at different providers as distinct work",
				"compose-overflow":
					"compacts the session before a composition prompt that would not fit beside what it holds",
				"compose-silent":
					"asks once more when the composition ended without a recording call and negatives await a decision",
				"compose-loop":
					"ends a composition that keeps calling a recording tool without recording anything",
				"compose-quiet": "skips a WITHHOLD on a practice with nothing to withhold and asks no more",
				"compose-fold":
					"counts a NOT_MET practice folded into another practice's unit as decided and asks no more",
				"compose-abstention":
					"composes from admitted NOT_APPLICABLE and UNDETERMINED observations without basing feedback on them",
				"compose-unknown-outcome":
					"refuses an admitted outcome outside the vocabulary before composing",
				"compose-null-outcome":
					"refuses an admitted observation without an outcome before composing",
			}[stage] ?? stage,
			() => {
				const cwd = mkdtempSync(nodePath.join(tmpdir(), "pi-orchestration-"));
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
					writeFileSync(
						nodePath.join(cwd, "feedback-composer.md"),
						"Compose from admitted observations.",
					);
					writeFileSync(nodePath.join(cwd, "review-composer.md"), "Write the review on the work.");
					if (stage === "compose" || stage === "compose-foreign-provider") {
						// The person's history, staged as the server stages it for the measurement and private lanes.
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(
							nodePath.join(cwd, "history/feedback.json"),
							JSON.stringify({
								feedback: [
									{
										channel: "IN_CONTEXT",
										artifact: {
											kind: "scm.pull_request",
											url: "https://gitlab.example/group/repo/-/merge_requests/3",
										},
										body: "An earlier comment on this same change.",
									},
									{
										channel: "IN_APP",
										artifact: {
											kind: "scm.pull_request",
											url: "https://gitlab.example/group/repo/-/merge_requests/1",
										},
										body: PRIVATE_HISTORY_SENTENCE,
									},
								],
							}),
						);
					}
					writeFileSync(nodePath.join(cwd, "events"), "");
					writeFileSync(
						nodePath.join(cwd, "evidence/metadata.json"),
						JSON.stringify({
							title: "Add login",
							pr_url: "https://gitlab.example/group/repo/-/merge_requests/3",
						}),
					);
					writeFileSync(
						nodePath.join(cwd, "evidence/change.json"),
						JSON.stringify({ base_sha: "b".repeat(40), head_sha: "a".repeat(40) }),
					);
					// The change view the container derives from the checkout before the runner starts.
					writeFileSync(
						nodePath.join(cwd, "work/change/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					writeFileSync(
						nodePath.join(cwd, "catalog/practices/test-practice.md"),
						"# Test practice\nCriteria.",
					);
					// What the practice's precompute script derived, as the precompute runner writes it.
					mkdirSync(nodePath.join(cwd, "work/precompute-out"), { recursive: true });
					writeFileSync(
						nodePath.join(cwd, "work/precompute-out/test-practice.md"),
						"- `src/Auth.java` [L10] — insecure call: `insecure();`\n",
					);
					if (stage.startsWith("compose") || stage === "draft-revision") {
						writeFileSync(
							nodePath.join(cwd, "evidence/composition.json"),
							JSON.stringify({
								enabled: true,
								channels: {
									IN_APP: { enabled: true, maxUnits: 1 },
									IN_CONTEXT: { enabled: true, maxUnits: 1 },
								},
								inContextPlacementKinds:
									stage === "compose" || stage === "compose-foreign-provider"
										? ["ARTIFACT", "DIFF"]
										: ["ARTIFACT"],
							}),
						);
					}
					if (stage === "tree-citation") {
						// A real checkout with one commit in its history, so a citation at a revision is read
						// through .git like admission reads it.
						mkdirSync(nodePath.join(cwd, "repos/primary/src"), { recursive: true });
						writeFileSync(
							nodePath.join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n}\n",
						);
						const git = (...args: string[]) =>
							spawnSync("git", ["-C", nodePath.join(cwd, "repos/primary"), ...args], {
								encoding: "utf8",
								env: {
									...process.env,
									GIT_AUTHOR_NAME: "t",
									GIT_AUTHOR_EMAIL: "t@t",
									GIT_COMMITTER_NAME: "t",
									GIT_COMMITTER_EMAIL: "t@t",
								},
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
							artifactKind: "scm.pull_request",
							artifacts: [
								{ kind: "scm.pull-request.core", artifact: { path: "evidence/metadata.json" } },
								{ kind: "scm.pull-request.diff", artifact: { path: "evidence/change.json" } },
								...(stage === "tree-citation"
									? [{ kind: "scm.repository.tree", artifact: { path: "repos/primary/.git/HEAD" } }]
									: []),
							],
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
					let index = [practice("test-practice")];
					if (
						stage === "finish" ||
						stage === "batch" ||
						stage === "draft-revision" ||
						stage === "compose-fold" ||
						stage === "compose-abstention"
					) {
						index = [
							practice("test-practice"),
							practice("second-practice"),
							...(stage === "compose-abstention" ? [practice("third-practice")] : []),
						];
					} else if (stage === "comment-undecided") {
						index = [practice("test-practice"), practice("review-tone", ["scm.pull-request.core"])];
					}
					writeFileSync(nodePath.join(cwd, "catalog/practices/index.json"), JSON.stringify(index));
					writeFileSync(
						nodePath.join(cwd, "pi-provider.json"),
						JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
					);
					writeFileSync(
						nodePath.join(cwd, "task.json"),
						JSON.stringify({
							schemaVersion: 3,
							contextRoot: "evidence",
							repositoryRoot: "repos/primary",
							manifest: "evidence/manifest.json",
							practiceIndex: "catalog/practices/index.json",
							compositionRequest: "evidence/composition.json",
							preparedFeedback: "history/prepared.json",
							precomputeScripts: "scripts/practices",
							prompt: "Review the practice.",
							repositoryFullName: "group/repo",
							pullRequestNumber: 3,
						}),
					);
					let budgetMs = "10000";
					if (stage === "stall") {
						budgetMs = "3600000";
					} else if (stage === "compose-settle-deadline") {
						budgetMs = "200";
					}
					const child = spawnSync(
						process.execPath,
						["--experimental-test-module-mocks", import.meta.filename],
						{
							env: {
								...process.env,
								PI_ORCHESTRATION_SCENARIO: stage,
								PI_RUNNER_CWD: cwd,
								PI_CODING_AGENT_DIR: cwd,
								AGENT_BUDGET_MS: budgetMs,
								PI_PRACTICE_MODEL_CALLS: "2",
								PI_PRACTICE_OUTPUT_TOKENS: "8000",
								LLM_PROXY_URL: "https://unused.invalid",
								LLM_PROXY_TOKEN: "test-token",
							},
							encoding: "utf8",
							timeout: 10_000,
						},
					);
					assert.equal(child.error, undefined);
					const events = readFileSync(nodePath.join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
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
					switch (stage) {
						case "replacement-witness": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("replacement-witness:preserved"), child.stderr);
							break;
						}

						case "settle-safety": {
							assert.equal(child.status, 1, child.stderr);
							assert.ok(!events.some((event) => event.startsWith("prompt:")), events.join("\n"));
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "compose-settle-deadline": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.match(
								child.stderr,
								/the run is near its safety ceiling — preserving observations and composed feedback/u,
							);
							// The review composition was cut off by the safety line, so the private one never started.
							assert.ok(
								!events.some((event) => event.startsWith("private-turn")),
								events.join("\n"),
							);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "setup": {
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(events, []);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "session-init": {
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.length, 1);
							break;
						}
						case "cut-off": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(child.stderr, /a call ran into the output limit — telling the model/u);
							assert.match(
								child.stderr,
								/2 calls in this turn ran into the 16384-token output limit — aborting this turn/u,
							);
							// One notice, then the stop; the finishing turn records the practice.
							const order = events.filter((event) =>
								["steer", "abort", "prompt:2"].includes(event),
							);
							assert.deepEqual(order.slice(0, 3), ["steer", "abort", "prompt:2"]);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "work-budget": {
							assert.equal(child.status, 0, child.stderr);
							// One nudge, the recording, then the stop; the practice is recorded, so no turn follows
							// and the next abort is the session's own stop.
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
							assert.match(
								child.stderr,
								/4 of 6 calls and 400 of 24000 output tokens spent — nudging to record/u,
							);
							assert.match(
								child.stderr,
								/work budget spent \(6 calls, 600 output tokens\) — ending this turn/u,
							);
							assert.match(child.stderr, /calls=6\/6, outputTokens=600\/24000, stoppedBy=budget/u);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "provider-error": {
							// Exit 76: the server queues the review again instead of recording a failure.
							assert.equal(child.status, 76, child.stderr);
							assert.match(
								child.stderr,
								/UNREACHABLE: this review reached no practice, and \d+ model call\(s\)/u,
							);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "stall": {
							assert.equal(child.status, 0, child.stderr);
							const order = events.filter((event) =>
								["prompt:1", "steer", "abort-compaction", "abort", "prompt:2"].includes(event),
							);
							assert.deepEqual(order.slice(0, 4), [
								"prompt:1",
								"abort-compaction",
								"abort",
								"prompt:2",
							]);
							assert.match(events.find((event) => event.startsWith("finish:")) ?? "", /stored/u);
							assert.match(child.stderr, /no model or tool event for 300s — aborting this turn/u);
							assert.doesNotMatch(child.stderr, /already processing/u);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "argument-repairs": {
							assert.equal(child.status, 0, child.stderr);
							const result = readObservations(nodePath.join(cwd, "out/result.json"));
							assert.equal(result.length, 1);
							assert.equal(result[0]?.outcome, "NOT_MET");
							assert.ok(isRecord(result[0].evidence));
							assert.deepEqual(result[0].evidence.citations, [
								{ ...changeCitation, quote: " insecure();" },
							]);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "draft-revision": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("draft-first:")) ?? "",
								/stored/u,
								child.stderr,
							);
							assert.match(
								events.find((event) => event.startsWith("draft-duplicate:")) ?? "",
								/#1 test-practice: already recorded; this item changed nothing, so do not send it again/u,
							);
							assert.match(
								events.find((event) => event.startsWith("draft-corrected:")) ?? "",
								/revised/u,
								child.stderr,
							);
							const result = readObservations(nodePath.join(cwd, "out/result.json"));
							const state = readObservations(nodePath.join(cwd, "out/review-state.json"));
							assert.deepEqual(result, state);
							assert.equal(
								result.filter((item) => item.practiceSlug === "test-practice").length,
								1,
							);
							assert.equal(result[0]?.outcome, "NOT_MET");
							const admission = readObservations(nodePath.join(cwd, "admission.json"));
							assert.deepEqual(admission, result);
							assert.ok(!JSON.stringify(admission).includes("revises"));
							assert.match(
								events.find((event) => event.startsWith("draft-closed:")) ?? "",
								/Measurement is closed/u,
							);
							const notes = readFileSync(nodePath.join(cwd, "work/notes/review.md"), "utf8");
							assert.match(notes, /test-practice: NOT_MET/u);
							assert.doesNotMatch(notes, /test-practice: MET —/u);
							// The finishing turn continues the session, which holds the current drafts: it repeats no
							// record, so the replaced draft cannot reappear in it.
							const finishing = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.doesNotMatch(finishing, /Recorded so far/u);
							assert.doesNotMatch(finishing, /test-practice: MET —/u);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "comment-undecided": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("tone-undecided:stored"), events.join("\n"));
							assert.match(
								events.find((event) => event.startsWith("code-undecided:")) ?? "",
								/must show it read the change/u,
							);
							reached({ "test-practice": "EVALUATED", "review-tone": "EVALUATED" });
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
								"history/observations.json",
							]) {
								assert.ok(system.includes(path), path);
							}
							assert.doesNotMatch(system, /`(?:write|edit)`|tools\.(?:write|edit)\(/u);
							assert.match(system, /up to three observations per call/u);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8"),
								/up to three observations per call/u,
							);
							assert.match(
								events.find((event) => event.startsWith("resent:")) ?? "",
								/already recorded; this item changed nothing, so do not send it again/u,
							);
							assert.match(
								events.find((event) => event.startsWith("repaired-evidence-open:")) ?? "",
								/#1 test-practice: revised/u,
							);
							assert.match(
								events.find((event) => event.startsWith("unparsed:")) ?? "",
								/observations refused — the list arrived as a string that is not valid JSON \(.*\); it breaks here: "\[\{" ⟵ "not json\}\]"/u,
							);
							const reply = events.find((event) => event.startsWith("batch:")) ?? "";
							assert.match(reply, /#1 test-practice: revised \(negative\)/u, child.stderr);
							assert.match(reply, /#2 second-practice: refused/u);
							assert.match(reply, /Every practice of this turn has a recorded result/u);
							// Nothing is left for the turn to record, so the run ends with this call.
							assert.match(reply, /"terminate":true/u);
							assert.match(
								events.find((event) => event.startsWith("create:session")) ?? "",
								/tools=read,grep,find,ls,bash,codemode,report_observation$/u,
							);
							assert.ok(
								events.includes("exposure:report_observation=model-only"),
								events.join("\n"),
							);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8"),
								/### Practice `test-practice`\n[\s\S]*# Test practice\nCriteria\.\n\n#### Precomputed leads for `test-practice` — starting points to check against the criteria, not verdicts\n- `src\/Auth\.java` \[L10\] — insecure call/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-kind:")) ?? "",
								/recorded as scm\.pull-request\.diff/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-side:")) ?? "",
								/#1 test-practice: revised \(negative\)\.[^#]*evidence\/metadata\.json is staged by scm\.pull-request\.core, not scm\.pull-request\.diff; recorded as scm\.pull-request\.core/u,
							);
							const recordedSide = events.find((event) => event.startsWith("side-recorded:")) ?? "";
							assert.match(recordedSide, /"summary":"A record cited with a diff side"/u);
							assert.match(recordedSide, /"sourceKind":"scm\.pull-request\.core"/u);
							assert.doesNotMatch(recordedSide, /"side"/u);
							// A revision selects a commit of the checkout; on a record it says nothing, and is dropped.
							assert.doesNotMatch(recordedSide, /"revision"/u);
							assert.match(
								events.find((event) => event.startsWith("corrected-side:")) ?? "",
								/side dropped — only a line of the change has a side[\s\S]*revision dropped — only a repository file is read at a revision/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-path:")) ?? "",
								/recorded against it/u,
							);
							const result = readObservations(nodePath.join(cwd, "out/result.json"));
							assert.equal(result.length, 2);
							assert.equal(result[0]?.summary, "The login change calls an insecure helper");
							assert.equal(result[1]?.summary, "Second of two, starting inside the first");
							const { evidence } = result[0];
							assert.ok(isRecord(evidence));
							assert.ok(Array.isArray(evidence.citations));
							const citation: unknown = evidence.citations[0];
							assert.ok(isRecord(citation));
							assert.equal(citation.quote, " insecure();");
							assert.equal(citation.side, "NEW");
							assert.ok(!JSON.stringify(result).includes(OVERLONG_SUMMARY));
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "finish": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							// The first turn carries the brief and shows how an observation is written, with this review's own
							// artifact paths; the finishing turn continues the same session, which still holds both.
							assert.ok(first.includes("### `evidence/metadata.json`"), first.slice(0, 300));
							assert.match(
								first,
								/## How report_observation takes observations[\s\S]*"artifactPath": "evidence\/change\.json"/u,
							);
							assert.doesNotMatch(
								second,
								/### `evidence\/metadata\.json`|How report_observation takes|Recorded so far/u,
							);
							// The next turn keeps back what recording costs at the pace the first one showed: 1200
							// output tokens over the items it wrote, averaged with the prior of 1500.
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
									`finish: 1 practice\\(s\\), up to 6 calls and 24000 output tokens \\(${pace} tokens per observation so far\\)`,
									"u",
								),
							);
							// It evaluates the unfinished practice from its criteria, not from memory.
							assert.match(
								second,
								/## Unfinished practices\n[\s\S]*### Practice `second-practice`/u,
							);
							assert.match(
								events.find((event) => event.startsWith("batch:")) ?? "",
								/"terminate":false/u,
							);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "repeat": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								child.stderr,
								/turn 1\/1 \(code\): the same bash call 3 times — nudging to record/u,
							);
							assert.match(
								child.stderr,
								/turn 1\/1 \(code\): the same bash call 6 times — aborting this turn/u,
							);
							assert.doesNotMatch(child.stderr, /the same read call/u);
							assert.match(child.stderr, /review tool: codemode → read/u);
							assert.equal(
								events.filter((event) => event === "steer").length,
								1,
								events.join("\n"),
							);
							assert.ok(events.includes("abort"), events.join("\n"));
							// The finishing turn, in the same session, records the practice the loop left unrecorded.
							assert.match(events.find((event) => event.startsWith("finish:")) ?? "", /stored/u);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "refusal-cap": {
							assert.equal(child.status, 1, child.stderr);
							const refusals = events.filter((event) => event.startsWith("refusal-"));
							assert.equal(refusals.length, 9);
							// An identical resend is answered at once with the reason it already had.
							assert.match(
								refusals[1] ?? "",
								/identical to an item this turn already refused, so the answer is the same: citation does not match/u,
							);
							// The third identical send is a loop, and the circuit breaker ends the turn.
							assert.match(
								child.stderr,
								/the same refused test-practice observation sent 3 times — aborting this turn/u,
							);
							assert.match(refusals[7] ?? "", /refused/u);
							// The practice past its limit is not listed as owed: nothing is left to resend for it.
							const afterCap = events.slice(
								events.findIndex((event) => event.startsWith("refusal-9:")),
							);
							assert.ok(
								afterCap.includes("No longer accepted (refusal limit): test-practice."),
								afterCap.join("\n"),
							);
							assert.ok(
								!afterCap.includes("No recorded result yet for: test-practice."),
								afterCap.join("\n"),
							);
							assert.match(
								refusals[8] ?? "",
								/8 submissions for 'test-practice' were refused; no more are accepted/u,
							);
							reached({ "test-practice": "NOT_REACHED" });
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
									'citation:echo:   src/Auth.java:2-2 recorded "  insecure();"',
								],
								child.stderr,
							);
							assert.ok(!existsSync(nodePath.join(cwd, "out/stray.txt")));
							const result: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/result.json"), "utf8"),
							);
							assert.ok(
								typeof result === "object" && result !== null && "admissionDigest" in result,
							);
							assert.equal(result.admissionDigest, "admitted-digest");
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "compose":
						case "compose-foreign-provider": {
							assert.equal(child.status, 0, child.stderr);
							// Measurement, then the review on the work in a fresh session, then the private lanes in
							// the measurement session.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.deepEqual(
								events
									.filter((event) => event.startsWith("create:"))
									.map((event) => event.includes("tools=report_review") && !event.includes(",")),
								[false, true],
							);
							assert.ok(events.includes("review-loader extensions=0"), events.join("\n"));
							assert.equal(
								readFileSync(nodePath.join(cwd, "review-system-prompt.md"), "utf8"),
								"Write the review on the work.",
							);
							assert.ok(!events.includes("compact"), child.stderr);
							// What the review composition was given: this work, and nothing about the person.
							const reviewTurn = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(reviewTurn, /^## The review to write/u);
							// The captured record of this same work comes first, and the measurement task never.
							const record = reviewTurn.indexOf('"title": "Add login"');
							assert.ok(
								record !== -1 && record < reviewTurn.indexOf('"id": "observation-1"'),
								reviewTurn,
							);
							assert.match(reviewTurn, /linked_work_items\.json[^\n]*not part of this capture/u);
							assert.match(reviewTurn, /"id": "observation-1"/u);
							assert.match(reviewTurn, /"quote": "\+ insecure\(\);"/u);
							assert.match(reviewTurn, /An earlier comment on this same change\./u);
							assert.match(reviewTurn, /"slug": "test-practice"/u);
							assert.doesNotMatch(reviewTurn, /Criteria\./u);
							assert.ok(!reviewTurn.includes(PRIVATE_HISTORY_SENTENCE), reviewTurn);
							assert.ok(!reviewTurn.includes("observation-history"), reviewTurn);
							assert.ok(!reviewTurn.includes("Review the practice."), reviewTurn);
							assert.match(
								events.find((event) => event.startsWith("review-refused:")) ?? "",
								/review refused, nothing was stored:[\s\S]*summary: body may not contain an HTML comment[\s\S]*inline #1: anchor names observation-2, which this note's basedOn does not/u,
							);
							assert.match(
								events.find((event) => event.startsWith("review-history:")) ?? "",
								/observation-history, which is not one of the observations this review may rest on/u,
							);
							assert.match(
								events.find((event) => event.startsWith("review-stored:")) ?? "",
								/Stored the review: a summary resting on 1 observation\(s\), 1 line note\(s\), 0 withholding decision\(s\)/u,
							);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							// The private lanes keep the person's history, and see what the review said.
							assert.ok(
								events.includes("private-turn history=true review=true"),
								events.join("\n"),
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-alone:")) ?? "",
								/IN_APP needs a pattern across at least 2 pieces of work, and test-practice is NOT_MET on 1/u,
								child.stderr,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-same-work:")) ?? "",
								/IN_APP needs a pattern across at least 2 pieces of work, and test-practice is NOT_MET on 1/u,
								child.stderr,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-unresolved:")) ?? "",
								/IN_APP needs a pattern across at least 2 pieces of work, and test-practice is NOT_MET on 1/u,
							);
							const reply = events.find((event) => event.startsWith("feedback:")) ?? "";
							// The composition turn goes on to its summary; only the retry ends on its last decision.
							assert.match(reply, /"terminate":false/u);
							assert.match(reply, /#1: stored a IN_APP unit for test-practice \(NEW\); 1\/1 used/u);
							assert.match(reply, /#2: basedOn is required/u);
							assert.match(reply, /#3: unknown unit field\(s\): verdict — a unit takes channel/u);
							assert.match(reply, /#4: body must be at most 8000 characters; this one is 8001/u);
							assert.match(
								reply,
								/#5: withholdReason must be one of NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR \(received 'NOT_A_REASON'\)/u,
							);
							assert.match(
								reply,
								/#6: practiceSlug 'other-practice' is not a practice with an admitted observation in this run \(those are: test-practice\)/u,
							);
							assert.match(
								reply,
								/#7: the review on the work is written in its own turn, not as a unit here; skipped\./u,
							);
							assert.match(
								reply,
								/#8: each item of units is one unit object .*\(received string\)/u,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-bare:")) ?? "",
								/#1: IN_CHAT is not a lane this run may write for/u,
							);
							const second = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							// Composition continues the session, which still holds the brief.
							assert.doesNotMatch(second, /### `evidence\/metadata\.json`/u);
							assert.match(second, /Compose from admitted observations\./u);
							assert.match(second, /"id": "observation-1"/u);
							// The quoted lines and the verification records stay on disk, where the composer
							// can read them if it must; the search it recorded is still shown. (The shared opening
							// above the composer's turn carries the observation example, quotes and all.)
							const composerTurn = second.slice(
								second.indexOf("Compose from admitted observations."),
							);
							assert.doesNotMatch(composerTurn, /"quote"/u);
							assert.doesNotMatch(composerTurn, /"verification"/u);
							assert.match(second, /"lookedFor": "x"/u);
							assert.match(
								readFileSync(nodePath.join(cwd, "work/composition/observations.json"), "utf8"),
								/"quote": "\+ insecure\(\);"/u,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							assert.equal(Reflect.get(feedback, "admissionDigest"), "admitted-digest");
							assert.equal(Reflect.get(feedback, "contractVersion"), 2);
							assert.equal(Reflect.get(feedback, "lead"), undefined);
							const units: unknown = Reflect.get(feedback, "units");
							assert.ok(Array.isArray(units) && units.length === 1, JSON.stringify(units));
							assert.ok(isRecord(units[0]) && units[0].channel === "IN_APP");
							// The review as stored: the summary verbatim, the note on its cited line.
							assert.deepEqual(Reflect.get(feedback, "review"), {
								summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
								inline: [
									{
										body: "`insecure()` runs here before the token is checked; call the checked helper instead.",
										basedOn: ["observation-1"],
										anchor: { observationId: "observation-1", citationIndex: 0 },
									},
								],
								withheld: [],
							});
							assert.match(child.stderr, /\] composition: .*stored=1/u);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "compose-overflow": {
							// 120k held, a prompt of a few thousand and 16k of output do not fit in 128k: the
							// session is compacted before the prompt, and the prompt itself is sent whole.
							assert.equal(child.status, 0, child.stderr);
							// The review composition runs in its own fresh session, which holds nothing to compact; the
							// private composition continues the measurement session and makes room first.
							const order = events.filter((event) =>
								["prompt:1", "prompt:2", "compact", "prompt:3"].includes(event),
							);
							assert.deepEqual(order, ["prompt:1", "prompt:2", "compact", "prompt:3"]);
							assert.match(
								child.stderr,
								/composition: 120000 tokens held and \d+ needed exceed the 128000 window — compacting first/u,
							);
							// The compaction took the brief from the context, so the composition turn carries it again
							// ahead of the composer's instructions — without the measuring examples and record, which
							// the composer does not use.
							const composition = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							assert.match(
								composition,
								/### `evidence\/metadata\.json`[\s\S]*Compose from admitted observations\./u,
							);
							assert.doesNotMatch(composition, /## Recorded so far|How report_observation takes/u);
							break;
						}
						case "compose-silent": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3", "prompt:4"],
							);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-4.md"), "utf8"),
								/## Undecided[\s\S]*NOT_MET observation: test-practice/u,
							);
							// The retry knows the room each lane has left, so it writes no unit that is skipped.
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-4.md"), "utf8"),
								/Room left for new units: IN_APP 1 of 1/u,
							);
							assert.match(
								child.stderr,
								/composition left 1 NOT_MET practice\(s\) undecided — asking once more/u,
							);
							assert.equal(
								(
									child.stderr.match(
										/composition: 12 calls without a recording call — nudging to persist/gu,
									) ?? []
								).length,
								1,
								child.stderr,
							);
							assert.equal(events.filter((event) => event === "steer").length, 1);
							assert.match(
								events.find((event) => event.startsWith("feedback-finish:")) ?? "",
								/#1: stored a IN_APP unit for test-practice \(WITHHOLD\)/u,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-finish:")) ?? "",
								/basedOn filled in with test-practice's NOT_MET observation\(s\) observation-1\./u,
							);
							// The retry exists only to decide what was left undecided: once nothing is, it ends.
							assert.match(
								events.find((event) => event.startsWith("feedback-finish:")) ?? "",
								/"terminate":true/u,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							const units: unknown = Reflect.get(feedback, "units");
							assert.ok(Array.isArray(units) && units.length === 1);
							const unit: unknown = units[0];
							assert.ok(typeof unit === "object" && unit !== null);
							assert.equal(Reflect.get(unit, "withholdReason"), "BELOW_BAR");
							break;
						}
						case "compose-quiet": {
							assert.equal(child.status, 0, child.stderr);
							// Only a strength was measured: the review may acknowledge it specifically, and with no
							// problem the private lanes, which rest on problems, are not composed at all.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.match(
								events.find((event) => event.startsWith("review-strength:")) ?? "",
								/Stored the review: a summary resting on 1 observation\(s\)/u,
							);
							assert.ok(
								!events.some((event) => event.startsWith("private-turn")),
								events.join("\n"),
							);
							assert.doesNotMatch(child.stderr, /asking once more/u);
							break;
						}
						case "compose-fold": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("feedback-fold:")) ?? "",
								/stored/u,
							);
							// The turn, the finishing turn, the review and the private composition; the review's one
							// withholding decision covers both observations, so neither composition asks again.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3", "prompt:4"],
							);
							assert.match(
								events.find((event) => event.startsWith("review-withheld:")) ?? "",
								/0 line note\(s\), 1 withholding decision\(s\)/u,
							);
							assert.doesNotMatch(child.stderr, /asking once more/u);
							break;
						}
						case "compose-abstention": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							// An abstention cannot carry a claim about the work; the strength beside it can.
							assert.match(
								events.find((event) => event.startsWith("review-abstention:")) ?? "",
								/observation-2, which is not one of the observations this review may rest on/u,
							);
							assert.match(
								events.find((event) => event.startsWith("review-met:")) ?? "",
								/Stored the review: a summary resting on 1 observation\(s\)/u,
							);
							for (const file of ["out/result.json", "out/feedback.json"]) {
								const payload: unknown = JSON.parse(readFileSync(nodePath.join(cwd, file), "utf8"));
								assert.ok(isRecord(payload));
								assert.equal(payload.admissionDigest, "admitted-digest");
								assert.deepEqual(
									readObservations(nodePath.join(cwd, file)).map((item) => item.outcome),
									["MET", "NOT_APPLICABLE", "UNDETERMINED"],
								);
							}
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback) && isRecord(feedback.review));
							assert.deepEqual(feedback.review.summary, {
								body: "The helper you added keeps the login flow on one path.",
								basedOn: ["observation-1"],
							});
							reached({
								"test-practice": "EVALUATED",
								"second-practice": "EVALUATED",
								"third-practice": "EVALUATED",
							});
							break;
						}
						case "compose-unknown-outcome":
						case "compose-null-outcome": {
							assert.equal(child.status, 2, child.stderr);
							assert.match(child.stderr, /observation admission returned an invalid contract/u);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							assert.ok(!existsSync(nodePath.join(cwd, "out/feedback.json")));
							break;
						}
						case "compose-loop": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("abort"), child.stderr);
							assert.match(
								child.stderr,
								/composition: 24 recording calls without a record — aborting this turn/u,
							);
							// A recording call answers the same to the same arguments: the second identical call is
							// nudged and the third ends the turn.
							assert.match(
								child.stderr,
								/the same report_review call 2 times — nudging to record/u,
							);
							assert.match(
								child.stderr,
								/the same report_review call 3 times — aborting this turn/u,
							);
							// Every refused call is in the transcript with the SDK's reason, and in the trace.
							assert.match(
								child.stderr,
								/composer tool error: report_review — Validation failed for tool "report_review": - \/summary: Expected object/u,
							);
							// A review composition stopped by the loop guard is asked once more for what it left.
							assert.ok(events.includes("review-retry"), events.join("\n"));
							const debug: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8"),
							);
							assert.ok(typeof debug === "object" && debug !== null);
							const turns: unknown = Reflect.get(debug, "turns");
							assert.ok(Array.isArray(turns));
							const composition: unknown = turns.find(
								(turn: unknown) =>
									typeof turn === "object" &&
									turn !== null &&
									Reflect.get(turn, "label") === "review composition",
							);
							assert.ok(typeof composition === "object" && composition !== null);
							assert.equal(Reflect.get(composition, "toolErrors"), 24);
							assert.equal(Reflect.get(composition, "recordingCalls"), 24);
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
