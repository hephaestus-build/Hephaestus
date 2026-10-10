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
import { STANDARD_REFERENCE } from "../../../main/resources/agent/pi-runner-composition.ts";

// The runner reads /workspace and the environment at module scope, so each scenario is a child
// process: this file re-enters itself with the SDK mocked and drives one review through it.

const supportOccurrence = (artifactKind: string, artifactId: number) => ({
	observationId: `support-${artifactKind}-${artifactId}`,
	artifactKind,
	artifactId,
	outcome: "NOT_MET",
	origin: "LIVE",
	summary: "A selected negative occurrence",
	observedAt: "2026-10-07T08:00:00Z",
});

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

function reviewPracticesOf(text: string): unknown {
	const practices = [...text.matchAll(/```json\n(?<context>[\s\S]*?)\n```/gu)].flatMap((block) => {
		const entry: unknown = JSON.parse(block.groups?.context ?? "null");
		return isRecord(entry) && isRecord(entry.practice) ? [entry.practice] : [];
	});
	assert.ok(practices.length > 0, text);
	return { practices };
}

function readToolText(path: string): string {
	const result: unknown = JSON.parse(readFileSync(path, "utf8"));
	if (typeof result === "string") {
		return result;
	}
	assert.ok(isRecord(result) && Array.isArray(result.content));
	const parts: unknown[] = result.content;
	const [content] = parts;
	assert.ok(isRecord(content) && content.type === "text" && typeof content.text === "string");
	return content.text;
}

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

/** What the staged history establishes, as the server writes it on both history files. */
const HISTORY_COVERAGE = {
	scope: "AUTHORIZED_REVIEW_RECORDS_FOR_SUBJECT",
	workChronology: "NOT_ESTABLISHED",
	communicationHistory: "NOT_ESTABLISHED",
};

/** The person's prepared feedback, as the server stages it for the private lanes, byte for byte. */
const STAGED_PREPARED_HISTORY = JSON.stringify({
	coverage: HISTORY_COVERAGE,
	recordRole: "PREPARED_NOT_DELIVERED",
	prepared: [
		{
			threadKey: "queued-current",
			practiceSlug: "test-practice",
			channel: "IN_CHAT",
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "MENTOR_NOTES",
			body: "Earlier prepared mentor wording.",
		},
		{
			threadKey: "queued-withdrawn",
			practiceSlug: "test-practice",
			channel: "IN_CHAT",
			withdrawn: true,
			recordedClaimCurrentness: "STALE",
		},
	],
});

/** The person's delivered feedback, as the server stages it for the private lanes, byte for byte. */
const STAGED_FEEDBACK_HISTORY = JSON.stringify({
	coverage: HISTORY_COVERAGE,
	recordRole: "RECORDED_DELIVERY",
	feedback: [
		{
			id: "feedback-same-work",
			channel: "IN_CONTEXT",
			publicEligible: true,
			artifact: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/3",
			},
			deliveredAt: "2026-09-01T10:00:00Z",
			reviewedRevision: "c".repeat(40),
			basedOn: [
				{
					id: "earlier-1",
					practiceSlug: "test-practice",
					practiceRevision: null,
					outcome: "NOT_MET",
				},
			],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "COMPOSED_GUIDANCE",
			body: "An earlier comment on this same change.",
		},
		{
			id: "feedback-earlier-work",
			channel: "IN_APP",
			artifact: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/1",
			},
			deliveredAt: "2026-08-01T10:00:00Z",
			reviewedRevision: null,
			basedOn: [],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "COMPOSED_GUIDANCE",
			body: PRIVATE_HISTORY_SENTENCE,
		},
		{
			id: "feedback-withdrawn",
			channel: "IN_APP",
			artifact: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/2",
			},
			deliveredAt: "2026-08-15T10:00:00Z",
			reviewedRevision: "d".repeat(40),
			basedOn: [
				{
					id: "earlier-2",
					practiceSlug: "test-practice",
					practiceRevision: null,
					outcome: "NOT_MET",
				},
			],
			recordedClaimCurrentness: "STALE",
			withdrawn: true,
		},
		{
			id: "feedback-legacy",
			channel: null,
			artifact: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/4",
			},
			deliveredAt: "2026-07-01T10:00:00Z",
			reviewedRevision: null,
			basedOn: [],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "UNKNOWN",
			body: "A legacy delivered note without a recorded channel.",
		},
	],
});

/** A delivered record as the server stages it, on one channel, resting on one recorded NOT_MET observation. */
const deliveredRecord = (id: string, channel: string, practiceSlug: string, overrides = {}) => ({
	id,
	channel,
	artifact: {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/1",
	},
	deliveredAt: "2026-08-01T10:00:00Z",
	reviewedRevision: null,
	basedOn: [{ id: `earlier-${id}`, practiceSlug, practiceRevision: null, outcome: "NOT_MET" }],
	publicEligible: false,
	recordedClaimCurrentness: "CURRENT",
	bodyRole: channel === "IN_CHAT" ? "MENTOR_NOTES" : "COMPOSED_GUIDANCE",
	body: `Earlier ${channel} feedback about ${practiceSlug}.`,
	...overrides,
});

/**
 * One delivered IN_APP card about each practice. On IN_CHAT, second-practice has current notes, and test-practice
 * has only records that do not count: malformed, MET-based, stale, withdrawn and undated.
 */
const ALREADY_SAID_HISTORY = JSON.stringify({
	coverage: HISTORY_COVERAGE,
	recordRole: "RECORDED_DELIVERY",
	feedback: [
		deliveredRecord("app-test", "IN_APP", "test-practice"),
		deliveredRecord("app-second", "IN_APP", "second-practice"),
		deliveredRecord("context-test", "IN_CONTEXT", "test-practice"),
		deliveredRecord("chat-second", "IN_CHAT", "second-practice"),
		deliveredRecord("chat-other", "IN_CHAT", "other-practice"),
		deliveredRecord("chat-test-malformed", "IN_CHAT", "test-practice", { withdrawn: "false" }),
		deliveredRecord("chat-test-met", "IN_CHAT", "test-practice", {
			basedOn: [{ id: "earlier-met", practiceSlug: "test-practice", outcome: "MET" }],
		}),
		deliveredRecord("chat-test-stale", "IN_CHAT", "test-practice", {
			recordedClaimCurrentness: "STALE",
			bodyRole: undefined,
			body: undefined,
		}),
		deliveredRecord("chat-test-withdrawn", "IN_CHAT", "test-practice", {
			withdrawn: true,
			bodyRole: undefined,
			body: undefined,
		}),
		deliveredRecord("chat-test-undated", "IN_CHAT", "test-practice", { deliveredAt: null }),
	],
});

/** Prepared IN_CHAT notes about test-practice: queued, never delivered. */
const ALREADY_SAID_PREPARED = JSON.stringify({
	coverage: HISTORY_COVERAGE,
	recordRole: "PREPARED_NOT_DELIVERED",
	prepared: [
		{
			threadKey: "queued-test",
			practiceSlug: "test-practice",
			channel: "IN_CHAT",
			preparedAt: "2026-09-01T10:00:00Z",
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "MENTOR_NOTES",
			body: "Prepared mentor notes about test-practice.",
		},
	],
});

/** The review composition's whole summary, as the composer wrote it, paragraphs and all. */
const REVIEW_SUMMARY =
	"The login change calls `insecure()` on the path every sign-in takes.\n\n" +
	"Route it through the checked helper so a bad token cannot reach it.";

function observation(slug: string, summary: string, citation: unknown = changeCitation) {
	return {
		revises: null,
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
	revises: null,
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
function practice(
	slug: string,
	readsSources = ["scm.pull-request.core", "scm.pull-request.diff"],
	revisionId?: unknown,
) {
	return { slug, group: "code", readsSources, ...(revisionId === undefined ? {} : { revisionId }) };
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

const withhold = (
	channel: string,
	practiceSlug: string,
	withholdReason: string,
	basedOn = [practiceSlug === "test-practice" ? "observation-1" : "observation-2"],
) => ({
	channel,
	practiceSlug,
	basedOn,
	action: "WITHHOLD",
	withholdReason,
});

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
const unavailableContext = process.env.PI_UNAVAILABLE_CONTEXT === "true";
const counterevidence = process.env.PI_COUNTEREVIDENCE === "true";
const reviewCompacted = process.env.PI_REVIEW_COMPACTED === "true";
const blankStandard = process.env.PI_BLANK_STANDARD === "true";
const reviewerOnly = process.env.PI_REVIEWER_ONLY === "true";
const loopReject = process.env.PI_LOOP_REJECT === "true";
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
			case "compose-already-said": {
				// Two NOT_MET practices, and MET counterevidence of another practice without delivered history.
				return [
					admittedObservation,
					{ ...admittedObservation, id: "observation-2", practiceSlug: "second-practice" },
					{
						...admittedObservation,
						id: "observation-3",
						practiceSlug: "counter-practice",
						outcome: "MET",
						severity: null,
					},
				];
			}
			case "compose-repeat-select":
			case "compose-reselect": {
				return [
					admittedObservation,
					{
						...admittedObservation,
						id: "observation-2",
						practiceSlug: "second-practice",
						outcome: "MET",
						severity: null,
					},
				];
			}
			case "compose-partial-error":
			case "compose-qualify":
			case "compose-fold": {
				return [
					{ ...admittedObservation, publicEligible: !reviewerOnly },
					{
						...admittedObservation,
						publicEligible: !reviewerOnly,
						id: "observation-2",
						practiceSlug:
							counterevidence && scenario === "compose-qualify"
								? "test-practice"
								: "second-practice",
						...(counterevidence
							? {
									outcome: "MET",
									severity: null,
									evidenceRationale:
										"The helper path is present in the source; its runtime behavior was not executed.",
									evidence: {
										citations: [changeCitation],
										search: {
											consulted: ["scm.pull-request.diff"],
											lookedFor: "A recorded relaunch result",
											boundary: "Source only; no relaunch result recorded.",
										},
									},
								}
							: {}),
					},
					...(scenario === "compose-fold"
						? [
								{
									...admittedObservation,
									id: "qualification-und",
									practiceSlug: "request-scope-practice",
									citations: [
										{
											...admittedObservation.citations[0],
											path: "src/RequestScope.java",
											startLine: 41,
											endLine: 42,
											quote: "+ unresolvedCurrentRequest();",
										},
									],
									outcome: "UNDETERMINED",
									severity: null,
									summary: "Unwarranted conformance summary",
									evidenceRationale: "Unwarranted conformance rationale",
									evidence: {
										undecidability: {
											openQuestion: "Which current request governs?",
											wouldSettleIt:
												"An authored resolution of the conflicting current statements.",
										},
									},
								},
								{
									...admittedObservation,
									id: "qualification-na",
									practiceSlug: "external-interface-practice",
									citations: [
										{
											...admittedObservation.citations[0],
											path: "src/ExternalInterface.java",
											startLine: 73,
											endLine: 73,
											quote: "+ unchangedInterface();",
										},
									],
									outcome: "NOT_APPLICABLE",
									severity: null,
									evidence: {
										inapplicability: {
											consulted: ["scm.pull-request.diff"],
											subject: "A changed external interface",
											ruledOutBy: "The captured interface is unchanged.",
										},
									},
								},
							]
						: []),
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
			case "compose-complete-error":
			case "compose-quiet":
			case "compose-empty": {
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
	let publicHistoryReads = 0;
	mock.method(globalThis, "fetch", async (input: unknown, init?: RequestInit) => {
		if (String(input).endsWith("/public-feedback-history")) {
			const mode = process.env.PI_PUBLIC_HISTORY_MODE;
			publicHistoryReads += 1;
			if (mode === "body-transport" || mode === "malformed-json") {
				record("public-history-read");
			}
			if (mode === "body-transport" && publicHistoryReads === 1) {
				return new Response(
					new ReadableStream({
						start(controller) {
							controller.error(new TypeError("Response body stream interrupted"));
						},
					}),
				);
			}
			if (mode === "malformed-json") {
				return new Response('{"schemaVersion":');
			}
			if (mode === "invalid" || mode === "invalid-link") {
				return Response.json({
					schemaVersion: 1,
					readAt: "2026-10-07T11:00:00Z",
					history: {
						feedback: [
							{
								id: "00000000-0000-4000-8000-000000000001",
								channel: "IN_CONTEXT",
								publicEligible: true,
								artifact: {
									kind: "scm.pull_request",
									url:
										mode === "invalid-link"
											? 42
											: "https://gitlab.example/group/repo/-/merge_requests/3",
								},
								deliveredAt: mode === "invalid" ? "invalid" : "2026-10-07T10:00:00Z",
								recordedClaimCurrentness: "CURRENT",
								body: "Malformed non-null delivery time",
							},
						],
					},
				});
			}
			if (mode === "refused") {
				return new Response("Attempt no longer owns the job", { status: 409 });
			}
			if (mode === "fresh" || mode === "body-transport") {
				return Response.json({
					schemaVersion: 1,
					readAt: "2026-10-07T11:00:00Z",
					history: {
						feedback: [
							{
								id: "00000000-0000-4000-8000-000000000001",
								channel: "IN_CONTEXT",
								publicEligible: true,
								artifact: {
									kind: "scm.pull_request",
									url: "https://gitlab.example/group/repo/-/merge_requests/3",
								},
								deliveredAt: "2026-10-07T10:00:00Z",
								recordedClaimCurrentness: "CURRENT",
								body: "Fresh delivered communication",
							},
							{
								id: "00000000-0000-4000-8000-000000000002",
								channel: "IN_CONTEXT",
								publicEligible: true,
								artifact: {
									kind: "scm.pull_request",
									url: "https://gitlab.example/group/repo/-/merge_requests/3",
								},
								deliveredAt: null,
								recordedClaimCurrentness: "CURRENT",
								body: "Delivered with unknown time",
							},
							...[{ kind: "scm.pull_request" }, { kind: "scm.pull_request", url: null }].map(
								(artifact, index) => ({
									id: `00000000-0000-4000-8000-00000000000${index + 3}`,
									channel: "IN_CONTEXT",
									publicEligible: true,
									artifact,
									deliveredAt: "2026-10-07T08:00:00Z",
									recordedClaimCurrentness: "CURRENT",
									body: "Delivery with unknown work link",
								}),
							),
						],
					},
				});
			}
			const path = nodePath.join(cwd, "history/feedback.json");
			const history: unknown = existsSync(path)
				? JSON.parse(readFileSync(path, "utf8"))
				: { feedback: [] };
			assert.ok(isRecord(history) && Array.isArray(history.feedback));
			return Response.json({
				schemaVersion: 1,
				readAt: "2026-10-07T11:00:00Z",
				history: {
					...history,
					feedback: history.feedback.filter(
						(entry: unknown) =>
							isRecord(entry) && entry.channel === "IN_CONTEXT" && entry.publicEligible === true,
					),
				},
			});
		}
		if (
			scenario.startsWith("provider-reply-") ||
			scenario === "draft-revision" ||
			scenario === "compose-abstention"
		) {
			assert.ok(typeof init?.body === "string");
			writeFileSync(nodePath.join(cwd, "admission.json"), init.body);
		}
		const rows = admitted();
		const supportMode = process.env.PI_IN_APP_SUPPORT_MODE;

		let selected: ReturnType<typeof supportOccurrence>[] = [];
		if (
			scenario === "compose" ||
			scenario === "compose-foreign-provider" ||
			supportMode === "selected"
		) {
			selected = [supportOccurrence("scm.pull_request", 3), supportOccurrence("scm.issue", 3)];
		} else if (supportMode === "duplicate") {
			selected = [
				supportOccurrence("scm.pull_request", 3),
				supportOccurrence("scm.pull_request", 3),
			];
		}

		const complete = {
			state: "COMPLETE",
			readAt: "2026-10-07T10:00:00Z",
			practices: [
				...new Set(
					rows
						.filter((row) => isRecord(row) && row.outcome === "NOT_MET")
						.map((row) => {
							assert.ok(isRecord(row));
							return row.practiceSlug;
						}),
				),
			].map((practiceSlug) => ({ practiceSlug, occurrences: selected })),
		};
		let support: unknown = complete;
		if (supportMode === "missing") {
			support = undefined;
		} else if (supportMode === "unavailable") {
			support = { state: "UNAVAILABLE", readAt: complete.readAt };
		} else if (supportMode === "refused" || supportMode === "refused-only") {
			support = { state: "REFUSED", readAt: complete.readAt };
		} else if (supportMode === "malformed") {
			support = { ...complete, practices: [] };
		} else if (supportMode === "invalid-optional") {
			support = {
				...complete,
				practices: [
					{
						practiceSlug: "test-practice",
						occurrences: [{ ...supportOccurrence("scm.issue", 1), practiceRevisionId: "unknown" }],
					},
				],
			};
		}

		return Response.json({
			schemaVersion: 1,
			admissionDigest: "admitted-digest",
			observations: rows,
			inAppSupport: support,
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
	let prompts = 0;
	let measuredTool: CustomTool | undefined;
	/** What the runner steered the session with, in order. */
	const steered: string[] = [];
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
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			defineTool: (tool: unknown) => tool,
			estimateTokens: (message: { content: string }) => Math.ceil(message.content.length / 4),
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
					const privateComposition = options.systemPrompt.includes(
						"Compose from admitted observations.",
					);
					let promptFile = "system-prompt.md";
					if (!first) {
						promptFile = privateComposition
							? "private-system-prompt.md"
							: "review-system-prompt.md";
					}
					writeFileSync(nodePath.join(cwd, promptFile), options.systemPrompt);
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
						getModel: () => ({ id: "test-model", contextWindow: 128_000, maxTokens: 16_384 }),
					};
				},
			},
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
					now += 20_000;
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
				return {
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						agent: {
							state: { tools: [] },
							finishTurn: async () => {
								record("native-finish-turn");
								return { action: "continue" };
							},
						},
						systemPrompt: "",
						model: { contextWindow: 128_000, maxTokens: 16_384 },
						settingsManager: { getCompactionSettings: () => ({ reserveTokens: 16_384 }) },
						sessionManager: manager,
						subscribe(handler: (event: unknown) => void) {
							handlers.add(handler);
							return async () => {
								handlers.delete(handler);
								if (scenario === "scope-stop-hangs") {
									const statePath = nodePath.join(cwd, "out/review-state.json");
									const before = readFileSync(statePath, "utf8");
									const reply = await tool("report_observation").execute("late", {
										...observation("test-practice", "Late overwrite"),
										revises: "test-practice",
									});
									assert.ok(isRecord(reply));
									assert.ok(isRecord(reply.details));
									assert.equal(reply.details.inserted, 0);
									assert.equal(reply.details.revised, 0);
									assert.equal(reply.details.refused, 0);
									assert.equal(readFileSync(statePath, "utf8"), before);
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
							tokens:
								((scenario === "compose-overflow" && prompts >= 2) ||
									scenario === "measure-context-compact") &&
								!wasCompacted
									? 120_000
									: 1000,
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
							if (
								scenario === "compose-late-usage" &&
								options.tools.includes("report_review") &&
								!settlementUsageEmitted
							) {
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
							if (scenario.startsWith("provider-reply-")) {
								const mode = scenario.slice("provider-reply-".length);
								if (prompts === 1) {
									if (mode === "refused-error" || mode === "refused-stall") {
										emit({
											type: "tool_execution_start",
											toolCallId: "refused",
											toolName: "report_observation",
											args: {},
										});
										await assert.rejects(tool("report_observation").execute("refused", {}));
										record("answered-refusal");
									} else if (mode === "stored-error") {
										await tool("report_observation").execute(
											"stored",
											observation("test-practice", "Recorded before the provider failed"),
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
																	name: "report_observation",
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
													name: recording ? "report_observation" : "read",
													arguments: {},
												},
											],
										},
									});
									if (recording) {
										const reply = await tool("report_observation").execute(
											"w-6",
											observation("test-practice", "Recorded in the call that spent the budget"),
										);
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
								text.includes("The review leaves these NOT_MET observations undecided") ||
								text.includes("The review on this work is not final yet")
							) {
								// The review composition, in its own session: choosing and storing the review is all it
								// can do.
								// read_practice is offered only when a public MET observation has a reference to show.
								const offered = admitted().some(
									(row) => isRecord(row) && row.publicEligible === true && row.outcome === "MET",
								)
									? ["read_practice", "report_review"]
									: ["report_review"];
								assert.deepEqual(options.tools, offered);
								assert.deepEqual(
									options.customTools.map((custom) => custom.name),
									offered,
								);
								if (scenario === "compose-public-error" || scenario === "compose-recovered-error") {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "error",
											usage: callUsage(17),
											content: [],
											errorMessage: "503 upstream body sk-orchestration-secret",
											diagnostics: [
												{
													type: "openai_completions_failure",
													timestamp: 1_760_000_000_000,
													details: { kind: "HTTP_ERROR", phase: "request", status: 503 },
												},
											],
										},
									});
									if (scenario === "compose-public-error") {
										return;
									}
									emit({ type: "auto_retry_end", success: true });
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "toolUse",
											usage: callUsage(19),
											content: [],
										},
									});
								}
								const review = tool("report_review");
								if (
									text.includes("The review leaves") ||
									text.includes("The review on this work is not final yet")
								) {
									record("review-retry");
									return;
								}
								const read = async (id: string, args: unknown) => {
									try {
										return JSON.stringify(await tool("read_practice").execute(id, args));
									} catch (error) {
										return JSON.stringify(error instanceof Error ? error.message : String(error));
									}
								};
								if (scenario === "compose-settle-deadline") {
									// A whole review is refused, and then the response ends while its auto-compaction
									// remains busy until the deadline: no final review is ever stored.
									const refused = await review
										.execute("r-d", {})
										.catch((error: unknown) =>
											error instanceof Error ? error.message : String(error),
										);
									record(`refused-only:${JSON.stringify(refused)}`);
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
									if (loopReject) {
										throw new Error("Expected native loop abort");
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
								if (scenario === "compose-repeat-select" || scenario === "compose-invalid-select") {
									// Each call as the SDK runs it: started and ended as events, so the runner's repeat
									// guard sees it, and executed by the native tool.
									const native = async (name: string, id: string, args: unknown) => {
										emit({ type: "tool_execution_start", toolCallId: id, toolName: name, args });
										try {
											const result = await tool(name).execute(id, args);
											emit({
												type: "tool_execution_end",
												toolCallId: id,
												toolName: name,
												isError: false,
												result,
											});
											return JSON.stringify(result);
										} catch (error) {
											const errorMessage = error instanceof Error ? error.message : String(error);
											emit({
												type: "tool_execution_end",
												toolCallId: id,
												toolName: name,
												isError: true,
												result: { content: [{ type: "text", text: errorMessage }] },
											});
											return JSON.stringify(errorMessage);
										}
									};
									/** The tools a steering message tells the session to call. */
									const toolsNamed = (from: number) =>
										steered
											.slice(from)
											.map((message) =>
												["read_practice", "report_review"].filter((name) => message.includes(name)),
											);
									const said = {
										decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
										summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
									};
									// A practice that has no MET observation here: its standard is not the tool's to show.
									const unreadable = { practiceSlug: "test-practice" };
									if (scenario === "compose-repeat-select") {
										record(`repeat-refused:${await native("read_practice", "s-1", unreadable)}`);
										const before = steered.length;
										record(`repeat-again:${await native("read_practice", "s-2", unreadable)}`);
										record(`repeat-nudge:${JSON.stringify(toolsNamed(before))}`);
										record(`repeat-final:${await native("report_review", "r-2", said)}`);
										return;
									}
									const decidesNothing = {};
									record(`invalid-report:${await native("report_review", "r-1", decidesNothing)}`);
									const before = steered.length;
									record(`invalid-again:${await native("report_review", "r-1b", decidesNothing)}`);
									record(`invalid-nudge:${JSON.stringify(toolsNamed(before))}`);
									record(`invalid-final:${await native("report_review", "r-2", said)}`);
									record(`invalid-after-final:${await native("report_review", "r-3", said)}`);
									return;
								}
								if (scenario === "compose-recover") {
									// A whole review is refused and the response ends without a final one.
									record(`recover-refused:${await attempt("r-r", {})}`);
									return;
								}
								if (scenario === "compose-qualify" && reviewCompacted) {
									// The session compacts after the review turn, and again after the standard is read.
									const compacted = () =>
										emit({
											type: "compaction_end",
											reason: "threshold",
											result: undefined,
											aborted: false,
										});
									const said = {
										summary: {
											body: "Moving the check into its own helper keeps every caller on the same path.",
											basedOn: ["observation-2"],
										},
										decisions: [{ observationId: "observation-1", disposition: "BELOW_BAR" }],
									};
									record(`mixed-unread:${await attempt("r-unread", said)}`);
									compacted();
									// The strength's standard is read after a compaction: the answer carries the whole
									// review turn back as well.
									writeFileSync(
										nodePath.join(cwd, "compacted-read.txt"),
										await read("s-c", { practiceSlug: "test-practice" }),
									);

									writeFileSync(
										nodePath.join(cwd, "same-batch-refused.txt"),
										await attempt("r-batch", said),
									);
									emit({ type: "turn_start" });
									compacted();
									writeFileSync(
										nodePath.join(cwd, "compacted-refused.txt"),
										await attempt("r-c1", said),
									);
									writeFileSync(
										nodePath.join(cwd, "restore-batch-refused.txt"),
										await attempt("r-c2", said),
									);
									emit({ type: "turn_start" });
									record(`compacted-stored:${await attempt("r-c3", said)}`);
									return;
								}
								if (scenario === "compose-qualify") {
									const before = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
									// A review that leaves the second problem undecided is refused whole; the corrected
									// whole review is stored.
									writeFileSync(
										nodePath.join(cwd, "qualification-refused.txt"),
										await attempt("r-q1", {
											decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
											summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
										}),
									);
									record(
										`qualification-stored:${await attempt("r-q", {
											summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
											decisions: [
												{ observationId: "observation-1", disposition: "RAISE" },
												{ observationId: "observation-2", disposition: "BELOW_BAR" },
											],
										})}`,
									);
									assert.equal(
										readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
										before,
									);
									record("qualification:canonical-unchanged");
									return;
								}
								if (scenario === "compose-reselect") {
									// The composer leaves a problem undecided, then withholds it naming no witness, and then
									// sends the corrected whole review.
									const said = {
										decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
										summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
									};
									await this.steer();
									record(`reselect-incomplete:${await attempt("r-0", {})}`);
									record(`reselect-before-final:${JSON.stringify(await this.agent.finishTurn())}`);
									record(`reselect-retained:${this.pendingMessageCount}`);
									record(
										`reselect-unwitnessed:${await attempt("r-1", {
											decisions: [{ observationId: "observation-1", disposition: "ALREADY_SAID" }],
										})}`,
									);
									record(`reselect-final:${await attempt("r-2", said)}`);
									record(`reselect-terminal:${JSON.stringify(await this.agent.finishTurn())}`);
									record(`reselect-drained:${this.pendingMessageCount}`);
									// One response can carry more calls after the final one; none of them is accepted.
									record(
										`reselect-after-read:${await read("s-5", { practiceSlug: "second-practice" })}`,
									);
									record(`reselect-after-review:${await attempt("r-3", said)}`);
									return;
								}
								if (scenario === "compose-empty" || scenario === "compose-complete-error") {
									// Only a routine strength was measured, and nothing on the work earns a comment.
									record(`empty-final:${await attempt("r-e", { decisions: [] })}`);
									record(`empty-terminal:${JSON.stringify(await this.agent.finishTurn())}`);
									if (scenario === "compose-complete-error") {
										emit({
											type: "message_end",
											message: { role: "assistant", stopReason: "error", content: [] },
										});
									}
									return;
								}
								if (scenario === "compose-quiet") {
									// Only a strength was measured: the review may say so, specifically, once its standard is
									// in view. A read answered in this batch is in view only from the next turn.
									const strength = {
										decisions: [],
										summary: {
											body: "Moving the check into its own helper keeps every caller on the same path.",
											basedOn: ["observation-1"],
										},
									};
									if (blankStandard) {
										// The strength's standard is staged blank before this composition first reads it:
										// the read shows it as staged and puts nothing in view, in this turn or the next.
										writeFileSync(
											nodePath.join(cwd, "catalog/practices/test-practice.md"),
											" \n\t\n",
										);
										record(`blank-read:${await read("s-b", { practiceSlug: "test-practice" })}`);
										emit({ type: "turn_start" });
										record(`blank-strength:${await attempt("r-b1", strength)}`);
										record(`blank-quiet:${await attempt("r-b2", { decisions: [] })}`);
										return;
									}
									record(`strength-unread:${await attempt("r-q0", strength)}`);
									record(`strength-unknown:${await read("s-u", { practiceSlug: "elsewhere" })}`);
									record(`strength-read:${await read("s-q", { practiceSlug: "test-practice" })}`);
									record(`strength-same-batch:${await attempt("r-q1", strength)}`);
									emit({ type: "turn_start" });
									record(`review-strength:${await attempt("r-q", strength)}`);
									return;
								}
								if (scenario === "compose-abstention") {
									// Only the strength can be acknowledged: an abstention is not among what the review rests on.
									record(
										`review-abstention:${await attempt("r-a", {
											decisions: [],
											summary: { body: "Nothing to settle here.", basedOn: ["observation-2"] },
										})}`,
									);
									record(`met-read:${await read("s-m", { practiceSlug: "test-practice" })}`);
									emit({ type: "turn_start" });
									record(
										`review-met:${await attempt("r-m", {
											decisions: [],
											summary: {
												body: "The helper you added keeps the login flow on one path.",
												basedOn: ["observation-1"],
											},
										})}`,
									);
									return;
								}
								if (scenario === "compose" || scenario === "compose-foreign-provider") {
									// The practice has only a problem here and its MET-history row is private, so nothing is
									// offered to read.
									record(
										`review-read:${options.customTools.some((custom) => custom.name === "read_practice") ? "offered" : "not offered"}`,
									);
									record(
										`review-refused:${await attempt("r-1", {
											decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
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
											decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
											summary: { body: "A sentence.", basedOn: ["observation-history"] },
										})}`,
									);
									record(
										`review-stored:${await attempt("r-2", {
											decisions: [{ observationId: "observation-1", disposition: "RAISE" }],
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
								// Every other composing scenario decides the review's problems by withholding them.
								const ids =
									(scenario === "compose-fold" ||
										scenario === "compose-partial-error" ||
										scenario === "compose-already-said") &&
									!counterevidence
										? ["observation-1", "observation-2"]
										: ["observation-1"];
								const decisions = ids.map((observationId) => ({
									observationId,
									disposition: "BELOW_BAR",
								}));
								if (scenario === "compose-fold" && !reviewerOnly) {
									record(
										`qualification-support:${await attempt("r-limits", {
											decisions,
											summary: {
												body: "A limit is not a concern.",
												basedOn: ["qualification-und"],
											},
										})}`,
									);
									emit({
										type: "compaction_end",
										reason: "threshold",
										result: undefined,
										aborted: false,
									});
									record(`qualification-restored:${await attempt("r-restored", { decisions })}`);
									emit({ type: "turn_start" });
								}
								record(`review-withheld:${await attempt("r-w", { decisions })}`);
								if (unavailableContext) {
									rmSync(nodePath.join(cwd, "catalog/practices/test-practice.md"));
									writeFileSync(nodePath.join(cwd, "catalog/practices/second-practice.md"), "");
									writeFileSync(
										nodePath.join(cwd, "history/feedback.json"),
										JSON.stringify({ unexpected: "Unknown historical prose" }),
									);
								}
								return;
							}
							if (text.includes("## Undecided")) {
								if (scenario === "compose-no-decision") {
									return;
								}
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
								if (scenario === "compose-support") {
									const feedback = tool("report_feedback");
									const reply = await feedback.execute("support", {
										units: [
											{
												channel: "IN_APP",
												practiceSlug: "test-practice",
												basedOn: ["observation-1"],
												action: "NEW",
												title: "A repeated problem",
												body: "The recurring problem",
												nextStep: "Check the boundary",
											},
											{
												channel: "IN_CHAT",
												practiceSlug: "test-practice",
												basedOn: ["observation-1"],
												action: "NEW",
												title: "Discuss the boundary",
												notes: {
													situation: "Current problem",
													capability: "Check the boundary",
													evidenceSummary: "The admitted occurrence",
													inConversationSignal: "A boundary discussion",
												},
											},
										],
									});
									record(`support-units:${JSON.stringify(reply)}`);
									if (
										process.env.PI_IN_APP_SUPPORT_MODE === "empty" ||
										process.env.PI_IN_APP_SUPPORT_MODE === "duplicate"
									) {
										await feedback.execute("below", {
											units: [
												{
													channel: "IN_APP",
													practiceSlug: "test-practice",
													basedOn: ["observation-1"],
													action: "WITHHOLD",
													withholdReason: "BELOW_BAR",
												},
											],
										});
									}
									return;
								}
								if (scenario === "draft-revision") {
									assert.ok(
										!options.customTools.some((item) => item.name === "report_observation"),
									);
									assert.ok(measuredTool);
									const closed = await measuredTool.execute("after-admission", {
										...observation("test-practice", "Forbidden late replacement"),
										revises: "test-practice",
									});
									record(`draft-closed:${JSON.stringify(closed)}`);
									return;
								}
								// Private composition has its own session.
								if (scenario === "compose-private-budget") {
									for (let call = 0; call < 6; call += 1) {
										emit({
											type: "message_end",
											message: {
												role: "assistant",
												stopReason: "toolUse",
												usage: callUsage(100),
												content: [],
											},
										});
										emit({ type: "turn_end" });
									}
									return;
								}
								if (scenario === "compose-private-complete-error") {
									await tool("report_feedback").execute("complete", {
										units: [
											{
												channel: "IN_APP",
												practiceSlug: "test-practice",
												basedOn: ["observation-1"],
												action: "WITHHOLD",
												withholdReason: "BELOW_BAR",
											},
										],
									});
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "error",
											usage: callUsage(23),
											content: [],
										},
									});
									return;
								}
								if (scenario === "compose-no-decision") {
									return;
								}
								if (scenario === "compose-partial-error") {
									await tool("report_feedback").execute("partial", {
										units: [
											{
												channel: "IN_APP",
												practiceSlug: "test-practice",
												basedOn: ["observation-1"],
												action: "WITHHOLD",
												withholdReason: "BELOW_BAR",
											},
										],
									});
								}
								if (scenario === "compose-private-error" || scenario === "compose-partial-error") {
									emit({
										type: "message_end",
										message: {
											role: "assistant",
											stopReason: "error",
											usage: callUsage(23),
											content: [],
											// A malformed diagnostic claims nothing: the failure stays UNKNOWN.
											diagnostics: [
												{
													type: "openai_completions_failure",
													timestamp: "soon",
													details: { kind: "sk-orchestration-secret", status: 503 },
												},
											],
										},
									});
									if (scenario === "compose-private-error") {
										emit({
											type: "auto_retry_end",
											success: false,
											attempt: 3,
											finalError: "sk-orchestration-secret",
										});
									}
									return;
								}
								if (scenario === "compose-runtime-error") {
									throw new Error("Synthetic settled prompt failure");
								}
								if (scenario === "compose-public-error" || scenario === "compose-recovered-error") {
									await tool("report_feedback").execute("paid", {
										units: [
											{
												channel: "IN_APP",
												practiceSlug: "test-practice",
												basedOn: ["observation-1"],
												action: "WITHHOLD",
												withholdReason: "BELOW_BAR",
											},
										],
									});
									return;
								}
								if (scenario === "compose-settle-deadline") {
									// The response ended, but its auto-compaction remains busy until the deadline.
									compacting = true;
									return;
								}
								if (scenario === "compose-already-said") {
									// A withholding that names earlier feedback needs a delivered record on its own channel
									// for every NOT_MET practice it folds in; a refused one is stored nowhere and the lane
									// can still be decided.
									const feedback = tool("report_feedback");
									const both = ["observation-1", "observation-2"];
									const first = await feedback.execute("said", {
										units: [
											withhold("IN_CHAT", "second-practice", "NO_MATERIAL_CHANGE", [
												"observation-2",
												"observation-1",
											]),
											withhold("IN_CHAT", "test-practice", "ALREADY_SAID"),
											withhold("IN_APP", "test-practice", "ALREADY_SAID", both),
										],
									});
									record(`said-first:${JSON.stringify(first)}`);
									const second = await feedback.execute("said-again", {
										units: [
											withhold("IN_CHAT", "test-practice", "NO_MATERIAL_CHANGE"),
											withhold("IN_CHAT", "second-practice", "NO_MATERIAL_CHANGE", [
												"observation-2",
												"observation-3",
											]),
											withhold("IN_CHAT", "test-practice", "BELOW_BAR"),
										],
									});
									record(`said-second:${JSON.stringify(second)}`);
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
												basedOn: [
													"observation-1",
													"observation-2",
													"qualification-und",
													"qualification-na",
												],
												action: "WITHHOLD",
												withholdReason: "BELOW_BAR",
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
								const historical = await feedback
									.execute("historical", {
										units: [{ ...card, basedOn: ["support-scm.issue-3"] }],
									})
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-historical:${historical}`);
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
							if (scenario === "natural-stop" || scenario === "natural-stop-twice") {
								// The model answers in prose and the session ends on its own: prose records nothing.
								record(`natural-prompt:${prompts}:session-${sessionNumber}`);
								if (scenario === "natural-stop" && prompts > 1) {
									const reply = await tool("report_observation").execute(
										"o-natural",
										observation("test-practice", "Unsafe authentication call"),
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
							if (text.includes("## Unfinished practices")) {
								const unfinished =
									scenario === "stall" || scenario === "repeat" || scenario === "cut-off"
										? "test-practice"
										: "second-practice";
								const reply = await tool("report_observation").execute(
									"o-3",
									observation(unfinished, "Recorded on the finishing turn"),
								);
								record(`finish:${JSON.stringify(reply)}`);
								return;
							}
							const report = tool("report_observation");
							measuredTool = report;
							const currentSlug = /### Practice `(?<slug>[^`]+)`/u.exec(text)?.groups?.slug;
							assert.ok(currentSlug !== undefined && currentSlug !== "");
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
										toolName: "report_observation",
										args: { invalidShape: attempt },
									});
									emit({
										type: "tool_execution_end",
										toolCallId: `invalid-${attempt}`,
										toolName: "report_observation",
										isError: true,
										result: { content: [{ type: "text", text: "Invalid native schema" }] },
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
								await report.execute("own", observation(currentSlug, `Own ${currentSlug}`));
								return;
							}
							if (scenario === "scope-ownership") {
								const sibling =
									currentSlug === "test-practice" ? "second-practice" : "test-practice";
								const before = existsSync(nodePath.join(cwd, "out/review-state.json"))
									? readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8")
									: null;
								// More attempts than the refusal limit: an item outside the session spends none of it.
								for (let attempt = 0; attempt < 9; attempt += 1) {
									await assert.rejects(
										report.execute(`inactive-${attempt}`, observation(sibling, "Inactive result")),
										/this session/u,
									);
								}
								await assert.rejects(
									report.execute("inactive-revision", {
										...observation(sibling, "Overwrite sibling"),
										revises: sibling,
									}),
									/this session/u,
								);
								assert.equal(
									existsSync(nodePath.join(cwd, "out/review-state.json"))
										? readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8")
										: null,
									before,
								);
								await report.execute("own", observation(currentSlug, `Own ${currentSlug}`));
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
													name: call === 2 ? "report_observation" : "read",
													arguments: {},
												},
											],
										},
									});
									if (call === 2) {
										const result = await report.execute(
											`${currentSlug}-${call}`,
											observation(currentSlug, `Own ${currentSlug}`),
										);
										emit({
											type: "tool_execution_end",
											toolCallId: `${currentSlug}-${call}`,
											toolName: "report_observation",
											isError: false,
											result,
										});
									}
									emit({ type: "turn_end" });
								}
								return;
							}
							if (
								(scenario === "batch" || scenario === "draft-revision") &&
								currentSlug === "second-practice"
							) {
								await report.execute(
									"own-second",
									observation(currentSlug, "Second practice recorded in its own session"),
								);
								return;
							}
							assert.match(report.description, /local review state/u);
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
								let hasDraft = false;
								const cite = async (
									path: string,
									quote: string,
									summary = "Unsafe authentication call",
								) =>
									report.execute("o-1", {
										...observation("test-practice", summary, {
											sourceKind: "scm.repository.tree",
											artifactPath: "repos/primary/.git/HEAD",
											path,
											startLine: 2,
											quote,
										}),
										...(hasDraft ? { revises: "test-practice" } : {}),
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
										...observation("test-practice", `At revision ${startLine}`, {
											sourceKind: "scm.repository.tree",
											artifactPath: "repos/primary/.git/HEAD",
											path: "src/Auth.java",
											revision,
											startLine,
											quote: "insecure();",
										}),
										revises: "test-practice",
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
								await report.execute("provisional-diff", provisional);
								const beforeReplacement = readFileSync(
									nodePath.join(cwd, "out/review-state.json"),
									"utf8",
								);
								await assert.rejects(
									report.execute("replacement-without-diff", {
										...undecided(["scm.pull-request.core"]),
										revises: "test-practice",
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
									...observation("test-practice", "Unsafe authentication call"),
									evidence: JSON.stringify({
										citations: JSON.stringify([
											{ ...changeCitation, startLine: "[L10]", endLine, quote },
										]),
									}),
								});
								writeFileSync(
									nodePath.join(cwd, "observation-call.json"),
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
								// The declaration names no artifact; recording still refuses one the capture did not stage.
								await assert.rejects(
									report.execute(
										"uncaptured-artifact",
										observation("test-practice", "Unsafe authentication call", {
											...changeCitation,
											artifactPath: "evidence/never-captured.json",
										}),
									),
									/artifact 'evidence\/never-captured\.json' was not staged by 'scm\.pull-request\.diff'/u,
								);
								assert.equal(existsSync(nodePath.join(cwd, "out/review-state.json")), false);
								const nullableEvidence = {
									citations: [
										{ ...changeCitation, side: null, revision: null, endLine: null, quote: null },
									],
									search: null,
									inapplicability: null,
									undecidability: null,
								};
								for (const [branch, warrant] of [
									[
										"inapplicability",
										{ consulted: ["scm.pull-request.diff"], subject: "none", ruledOutBy: "none" },
									],
									["undecidability", { openQuestion: "none", wouldSettleIt: "none" }],
								] as const) {
									await assert.rejects(
										report.execute("incompatible-warrant", {
											...observation("test-practice", "Unsafe authentication call"),
											evidence: { ...nullableEvidence, [branch]: warrant },
										}),
										new RegExp(`evidence\\.${branch} is permitted only`, "u"),
									);
								}
								assert.equal(existsSync(nodePath.join(cwd, "out/review-state.json")), false);
								const reply = await report.execute("valid-transport", {
									...observation("test-practice", "Unsafe authentication call"),
									evidence: nullableEvidence,
								});
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
								// Before the first draft, even the practice's own slug is not an issued reference.
								for (const revises of ["second-practice", "test-practice", ""]) {
									await assert.rejects(
										report.execute("unissued-draft", { ...positive, revises }),
										/No draft reference has been issued[\s\S]*revises: null/u,
									);
								}
								await assert.rejects(
									report.execute("invalid-first", {
										...positive,
										revises: null,
										evidence: {
											citations: [{ ...changeCitation, quote: "notInTheDiff();" }],
										},
									}),
									/citation does not match/u,
								);
								assert.equal(existsSync(nodePath.join(cwd, "out/review-state.json")), false);
								const first = await report.execute("first", {
									...positive,
									revises: null,
								});
								record(`draft-first:${JSON.stringify(first)}`);
								assert.ok(isRecord(first) && isRecord(first.details));
								assert.equal(first.details.inserted, 1);
								assert.equal(first.details.revised, 0);
								// A resend that changes nothing stores nothing: an error, or it reads as done.
								const duplicate = await report
									.execute("retry", positive)
									.then((result) => `accepted ${JSON.stringify(result)}`)
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`draft-duplicate:${duplicate}`);
								assert.equal(readState().length, 1);
								// A foreign reference stays refused once a draft exists, and the draft stays as it was.
								const recorded = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
								await assert.rejects(
									report.execute("foreign-draft", { ...negative, revises: "second-practice" }),
									/revises must name this practice's draft reference, 'test-practice'/u,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
									recorded,
								);
								await assert.rejects(
									report.execute("implicit", negative),
									/resend the complete observation with revises/u,
								);
								await assert.rejects(
									report.execute("invalid", {
										...negative,
										revises: "test-practice",
										evidence: {
											citations: [{ ...changeCitation, quote: "+ notInTheDiff();" }],
										},
									}),
									/citation does not match/u,
								);
								assert.equal(readState()[0]?.outcome, "MET");
								const corrected = await report.execute("correct", {
									...negative,
									revises: "test-practice",
								});
								assert.ok(isRecord(corrected) && isRecord(corrected.details));
								assert.equal(corrected.details.revised, 1);
								assert.equal(corrected.details.inserted, 0);
								assert.equal(corrected.details.totalObservations, 1);
								record(`draft-corrected:${JSON.stringify(corrected)}`);
								const { revises: noCorrection, ...omittedReference } = positive;
								assert.equal(noCorrection, null);
								const correctedState = readFileSync(
									nodePath.join(cwd, "out/review-state.json"),
									"utf8",
								);
								await assert.rejects(
									report.execute("omitted-reference", omittedReference),
									/resend the complete observation with revises/u,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
									correctedState,
								);
								const before = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
								await assert.rejects(
									report.execute(
										"another-practice",
										observation("second-practice", "Another result"),
									),
									/this session/u,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
									before,
								);
								return;
							}
							if (scenario === "compose-abstention") {
								const own = [
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
								].find((item) => item.practiceSlug === currentSlug);
								assert.ok(own);
								await report.execute("o-mixed", own);
								return;
							}
							if (scenario === "comment-undecided") {
								// A practice that reads only what people wrote owes the change nothing; one that
								// reads the change still grounds a decision of nothing in it.
								const metadataOnly = undecided(["scm.pull-request.core"]);
								const tone = await report
									.execute("tone", {
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
									})
									.then(() => "stored")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`tone-undecided:${tone}`);
								const code = await report
									.execute("code-na", metadataOnly)
									.then(() => "stored")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`code-undecided:${code}`);
								await report.execute(
									"code",
									observation(currentSlug, "Unsafe authentication call"),
								);
								return;
							}
							if (scenario !== "batch") {
								if (scenario === "finish" && currentSlug === "second-practice") {
									// The loop guard, not the model, ends this session: finishing it is the finish scope's.
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
								const measured = await report.execute(
									"o-1",
									observation(currentSlug, "Unsafe authentication call"),
								);
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
								report.execute("o-na", undecided(["scm.pull-request.core"])),
								/must show it read the change/u,
							);
							await report.execute(
								"o-na2",
								undecided(["scm.pull-request.core", "scm.pull-request.diff"]),
							);
							const revise = (summary: string, citation: unknown = changeCitation) => ({
								...observation("test-practice", summary, citation),
								revises: "test-practice",
							});
							const before = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
							await assert.rejects(
								report.execute("non-root", { observations: [revise("A wrapped correction")] }),
							);
							assert.equal(
								readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8"),
								before,
							);
							record("non-root:unchanged");
							const corrected = await report.execute(
								"typed-correction",
								revise("A typed correction"),
							);
							assert.ok(isRecord(corrected) && isRecord(corrected.details));
							assert.equal(corrected.details.revised, 1);
							const revised = readObservations(nodePath.join(cwd, "out/review-state.json"))[0];
							assert.ok(isRecord(revised));
							const { evidence } = revised;
							assert.ok(isRecord(evidence));
							assert.ok(Array.isArray(evidence.citations));
							const quoted: unknown = evidence.citations[0];
							assert.ok(isRecord(quoted));
							assert.equal(quoted.quote, " insecure();");
							const { side: _side, ...sideless } = changeCitation;
							const reply = await report.execute(
								"o-1",
								revise("Unsafe authentication call", sideless),
							);
							record(`batch:${JSON.stringify(reply)}`);
							const outside = await report
								.execute("o-outside", {
									...observation("second-practice", "A quote that is not in the change", {
										...changeCitation,
										quote: "+ somethingElse();",
									}),
									revises: "second-practice",
								})
								.then(() => "accepted")
								.catch((error: unknown) =>
									error instanceof Error ? error.message : String(error),
								);
							record(`outside:${outside}`);
							await assert.rejects(
								report.execute("o-long", revise(OVERLONG_SUMMARY)),
								/summary must be at most/u,
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
							const side = await report.execute(
								"o-side",
								revise("A record cited with a diff side", {
									sourceKind: "scm.pull-request.diff",
									artifactPath: "evidence/metadata.json",
									path: "evidence/metadata.json",
									side: "NEW",
									revision: "c".repeat(40),
									startLine: 1,
									quote: '"title": "Add login"',
								}),
							);
							record(`corrected-side:${JSON.stringify(side)}`);
							const withSide = readObservations(nodePath.join(cwd, "out/review-state.json")).find(
								(item) => item.practiceSlug === "test-practice",
							);
							record(`side-recorded:${JSON.stringify(withSide)}`);
							const path = await report.execute(
								"o-path",
								revise("A record quoted under the change", {
									...changeCitation,
									path: "evidence/metadata.json",
									startLine: 1,
									endLine: 1,
									quote: '"title": "Add login"',
								}),
							);
							record(`corrected-path:${JSON.stringify(path)}`);
							await report.execute("o-2", revise("The login change calls an insecure helper"));
							// A resend of what is already recorded stores nothing: an error, or it reads as done.
							const resent = await report
								.execute("o-3", revise("The login change calls an insecure helper"))
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
	for (const fixture of [
		"scope-cut-off",
		"scope-recording-guard",
		"scope-repeat",
		"scope-ownership",
		"scope-budget",
		"scope-stop-failure",
		"scope-stop-hangs",
		"compose-late-usage",
		"setup",
		"context-unfit",
		"measure-context-compact",
		"measure-context-missing",
		"session-init",
		"work-budget",
		"cut-off",
		"stall",
		"settle-safety",
		"compose-settle-deadline",
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
		"draft-revision",
		"argument-repairs",
		"finish",
		"natural-stop",
		"natural-stop-twice",
		"refusal-cap",
		"repeat",
		"tree-citation",
		"compose-private-complete-error",
		"compose-private-budget",
		"compose-partial-error",
		"compose-no-decision",
		"compose-public-error",
		"compose-private-error",
		"compose-runtime-error",
		"compose-recovered-error",
		"compose-complete-error",
		"compose-support-selected",
		"compose-support-empty",
		"compose-support-duplicate",
		"compose-support-missing",
		"compose-support-malformed",
		"compose-support-invalid-optional",
		"compose-support-unavailable",
		"compose-support-refused",
		"compose-support-refused-only",
		"compose",
		"compose-fresh-history",
		"compose-history-invalid",
		"compose-history-invalid-link",
		"compose-history-refused",
		"compose-history-body-transport",
		"compose-history-malformed-json",
		"compose-foreign-provider",
		"compose-overflow",
		"compose-silent",
		"compose-loop-rejection",
		"compose-loop",
		"compose-quiet",
		"compose-quiet-blank",
		"compose-empty",
		"compose-reselect",
		"compose-qualify",
		"compose-compacted",
		"compose-recover",
		"compose-repeat-select",
		"compose-invalid-select",
		"compose-fold",
		"compose-already-said",
		"compose-context-unavailable",
		"compose-counterevidence",
		"compose-reviewer-only",
		"compose-abstention",
		"compose-unknown-outcome",
		"compose-null-outcome",
	]) {
		const stage =
			new Map([
				["compose-support-selected", "compose-support"],
				["compose-support-empty", "compose-support"],
				["compose-support-duplicate", "compose-support"],
				["compose-support-missing", "compose-support"],
				["compose-support-malformed", "compose-support"],
				["compose-support-invalid-optional", "compose-support"],
				["compose-support-unavailable", "compose-support"],
				["compose-support-refused", "compose-support"],
				["compose-support-refused-only", "compose-support"],
				["compose-loop-rejection", "compose-loop"],
				["compose-compacted", "compose-qualify"],
				["compose-quiet-blank", "compose-quiet"],
				["compose-fresh-history", "compose"],
				["compose-history-invalid", "compose"],
				["compose-history-invalid-link", "compose"],
				["compose-history-refused", "compose"],
				["compose-history-body-transport", "compose"],
				["compose-history-malformed-json", "compose"],
				["compose-context-unavailable", "compose-fold"],
				["compose-counterevidence", "compose-fold"],
				["compose-reviewer-only", "compose-fold"],
			]).get(fixture) ?? fixture;
		void test(
			{
				"scope-cut-off":
					"a truncated response in a prior practice does not consume the next session's correction chance",
				"scope-recording-guard":
					"a prior practice's stored result does not disable another session's schema-refusal loop guard",
				"scope-repeat":
					"identical evidence reads in fresh practice sessions do not become a repeated-call loop",
				"scope-ownership":
					"inactive reports and inactive revisions cannot mutate drafts or spend another practice's refusal allowance",
				"scope-budget":
					"fresh practice sessions share one group budget and accumulate nonzero native usage once",
				"scope-stop-hangs":
					"a session that never settles fails within the existing deadline and retains drafts and usage without admission",
				"scope-stop-failure":
					"a native stop failure preserves drafts and starts no later practice session",
				"compose-late-usage":
					"public session settlement remains subscribed for its final native usage event",
				setup: "does not start a session when setup reaches the safety ceiling",
				"context-unfit":
					"leaves essential oversized criteria not reached without creating observations or repeating compaction",
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
				"provider-reply-unanswered-stall":
					"retries only when no model or tool answer preceded a stall",
				"provider-reply-refused-error":
					"does not retry the whole review after a refused report and a provider error",
				"provider-reply-refused-stall":
					"does not retry the whole review after a refused report and a stall",
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
					"repairs observation transport without accepting a false quote or an overlong range",
				batch:
					"normalizes corrections, refuses a non-root observation and answers another practice's item",
				"draft-revision":
					"replaces only an explicitly corrected valid draft and refuses another practice's item without changing drafts",
				"replacement-witness": "refuses a replacement that relies on its superseded diff witness",
				"comment-undecided":
					"accepts an undecided result without the change only for a practice that does not read it",
				finish:
					"finishes practices the runner cut short in fresh sessions under one shared finish budget",
				"natural-stop":
					"asks a session that ended in prose once, in the same session, to record its practice",
				"natural-stop-twice":
					"leaves a practice not reached when its session ends in prose again after failed reads",
				"refusal-cap": "stops accepting a practice after eight refused submissions",
				repeat: "nudges a turn that repeats one call and ends it when the call keeps coming",
				"tree-citation":
					"verifies a HEAD repository citation against the checkout and finalizes out/",
				compose:
					"uses selected native issue and merge request identities while retaining current-only unit grounding",
				"compose-foreign-provider":
					"keeps distinct native work identities separate from provider URL text",
				"compose-overflow":
					"compacts the session before a composition prompt that would not fit beside what it holds",
				"compose-silent":
					"asks once more when the composition ended without a recording call and negatives await a decision",
				"compose-loop":
					"ends a composition that keeps calling a recording tool without recording anything",
				"compose-quiet":
					"stores an acknowledgement only from the turn after its standard is read, and asks no more",
				"compose-quiet-blank":
					"never stores an acknowledgement whose staged standard is blank, and stores the quiet review instead",
				"compose-empty":
					"ends the review composition on an intentionally empty final review of positive results",
				"measure-context-compact":
					"restores the exact criterion before illustrations and leads when native turn preparation compacts",
				"measure-context-missing":
					"keeps a missing criterion explicit without changing the recording contract",
				"compose-qualify":
					"refuses a whole review that leaves a problem undecided and stores the corrected one, canonical rows unchanged",
				"compose-compacted":
					"restores the review turn and every standard read after a compaction, and stores a review only from the next turn",
				"compose-reselect":
					"stores only the corrected whole review after refusals, and nothing after it",
				"compose-recover": "asks once more for a review left unfinished after a refused one",
				"compose-repeat-select":
					"points a repeated refused standard read at the final review, which is then stored without an abort",
				"compose-invalid-select":
					"refuses a repeated whole review that decides nothing until a corrected one is stored",
				"compose-reviewer-only":
					"reviewer-only concerns start no public writer alongside public abstentions and remain available to private feedback",
				"compose-counterevidence":
					"private counterevidence retains MET source qualifications and coordinates without quoted or verified-runtime claims",
				"compose-context-unavailable":
					"keeps missing and empty standards and unknown history explicit without inventing their contents",
				"compose-fold":
					"counts a NOT_MET practice folded into another practice's unit as decided and asks no more",
				"compose-already-said":
					"withholds as already said only on the channel and practice that captured delivered feedback records",
				"compose-abstention":
					"composes from admitted NOT_APPLICABLE and UNDETERMINED observations without basing feedback on them",
				"compose-unknown-outcome":
					"refuses an admitted outcome outside the vocabulary before composing",
				"compose-null-outcome":
					"refuses an admitted observation without an outcome before composing",
			}[fixture] ?? fixture,
			async () => {
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
					writeFileSync(nodePath.join(cwd, "feedback-style.md"), "Write clear, useful feedback.");
					if (stage === "compose" || stage === "compose-foreign-provider") {
						// The person's history, staged as the server stages it for the measurement and private lanes.
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(nodePath.join(cwd, "history/feedback.json"), STAGED_FEEDBACK_HISTORY);
						writeFileSync(nodePath.join(cwd, "history/prepared.json"), STAGED_PREPARED_HISTORY);
					}
					if (fixture === "compose-context-unavailable") {
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
					}
					if (stage === "compose-already-said") {
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(nodePath.join(cwd, "history/feedback.json"), ALREADY_SAID_HISTORY);
						writeFileSync(nodePath.join(cwd, "history/prepared.json"), ALREADY_SAID_PREPARED);
					}
					if (stage === "compose-support") {
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(
							nodePath.join(cwd, "history/observations.json"),
							JSON.stringify({
								observations: [
									{
										practiceSlug: "test-practice",
										outcome: "NOT_MET",
										artifact: {
											kind: "scm.issue",
											url: "https://gitlab.example/group/repo/-/issues/1",
										},
									},
									{
										practiceSlug: "test-practice",
										outcome: "NOT_MET",
										artifact: {
											kind: "scm.issue",
											url: "https://gitlab.example/group/repo/-/issues/2",
										},
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
							repository_full_name: "group/repo",
							pr_number: 3,
							commit_sha: "a".repeat(40),
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
					let practiceCriteria = "# Test practice\nCriteria.";
					if (stage.startsWith("provider-reply-")) {
						practiceCriteria =
							"# Uses secure authentication\n\n## Standard\nAdded authentication calls use checked(), not insecure().\n\n## Occasion\nAn authentication call was added.\n\n## Judge\nMET for checked(); NOT_MET for insecure().\n\n## Grounding\nCite the added call in the captured change.\n\n## Severity\nMAJOR for an added insecure() call.\n\n## Defer\nOther code is outside this practice.";
					}
					if (stage === "measure-context-compact") {
						practiceCriteria =
							"    let answer = qualified(work)\n\n# Standard\nBounded criterion.  \n\n## Occasion\nOnly captured work.\n\n## Judge\nQualify the outcome.\n\n## Defer\nUnknown remains unknown.  \n\n";
					} else if (stage === "context-unfit") {
						practiceCriteria = "Essential criteria. ".repeat(30_000);
					} else if (stage === "compose-reselect" || stage === "compose-recover") {
						// Leading indentation is a Markdown code block; trimming it would change the standard.
						practiceCriteria =
							"    let reply = answer(request)\n\n# Engages with review\n" +
							"A review request is answered by a fix, a reasoned decline, or a clarification.  \n\n";
					}
					if (stage !== "measure-context-missing") {
						writeFileSync(
							nodePath.join(cwd, "catalog/practices/test-practice.md"),
							practiceCriteria,
						);
					}
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
									...(stage === "compose-support" || stage === "compose-already-said"
										? {
												IN_CHAT: {
													enabled: fixture !== "compose-support-refused-only",
													maxUnits: 1,
												},
											}
										: {}),
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
							...(fixture === "compose-fresh-history" ||
							fixture === "compose-history-body-transport"
								? { capturedAt: "2026-10-07T09:00:00Z" }
								: {}),
							artifacts: [
								{ kind: "scm.pull-request.core", artifact: { path: "evidence/metadata.json" } },
								{ kind: "scm.pull-request.diff", artifact: { path: "evidence/change.json" } },
								...(stage === "tree-citation"
									? [{ kind: "scm.repository.tree", artifact: { path: "repos/primary/.git/HEAD" } }]
									: []),
								// A capture this large must not make the reporter's declaration uncompilable.
								...(stage === "argument-repairs"
									? Array.from({ length: 5000 }, (_, index) => ({
											kind: "scm.pull-request.core",
											artifact: { path: `evidence/captured/${index}.json` },
										}))
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
					if (stage === "compose") {
						index = [practice("test-practice", undefined, 42)];
					} else if (stage === "compose-foreign-provider") {
						index = [practice("test-practice", undefined, "42")];
					}
					if (
						stage.startsWith("scope-") ||
						stage === "finish" ||
						stage === "batch" ||
						stage === "draft-revision" ||
						stage === "compose-fold" ||
						stage === "compose-already-said" ||
						stage === "compose-qualify" ||
						stage === "compose-repeat-select" ||
						stage === "compose-reselect" ||
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
					writeFileSync(
						nodePath.join(cwd, "catalog/practices/second-practice.md"),
						stage === "compose-fold"
							? "Long criterion. ".repeat(2500)
							: "Complete second criterion. Its own independent question.",
					);
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
					if (stage === "stall" || stage.endsWith("-stall")) {
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
								PI_IN_APP_SUPPORT_MODE: fixture.startsWith("compose-support-")
									? fixture.slice("compose-support-".length)
									: undefined,
								PI_UNAVAILABLE_CONTEXT: String(fixture === "compose-context-unavailable"),
								PI_PUBLIC_HISTORY_MODE: new Map([
									["compose-fresh-history", "fresh"],
									["compose-history-invalid", "invalid"],
									["compose-history-invalid-link", "invalid-link"],
									["compose-history-refused", "refused"],
									["compose-history-body-transport", "body-transport"],
									["compose-history-malformed-json", "malformed-json"],
								]).get(fixture),
								PI_COUNTEREVIDENCE: String(
									fixture === "compose-counterevidence" || fixture === "compose-compacted",
								),
								PI_REVIEW_COMPACTED: String(fixture === "compose-compacted"),
								PI_BLANK_STANDARD: String(fixture === "compose-quiet-blank"),
								PI_REVIEWER_ONLY: String(fixture === "compose-reviewer-only"),
								PI_LOOP_REJECT: String(fixture === "compose-loop-rejection"),
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
					if (stage.startsWith("compose") && existsSync(nodePath.join(cwd, "out/feedback.json"))) {
						const payload: unknown = JSON.parse(
							readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
						);
						assert.ok(isRecord(payload));
						if (
							[
								"compose-private-budget",
								"compose-private-complete-error",
								"compose-partial-error",
								"compose-no-decision",
								"compose-public-error",
								"compose-private-error",
								"compose-runtime-error",
								"compose-recovered-error",
								"compose-complete-error",
							].includes(stage)
						) {
							const failuresByStage: Record<string, { phase: string; reason: string }[]> = {
								"compose-private-budget": [{ phase: "PRIVATE_FEEDBACK", reason: "BUDGET" }],
								"compose-public-error": [{ phase: "PUBLIC_REVIEW", reason: "MODEL_ERROR" }],
								"compose-private-error": [{ phase: "PRIVATE_FEEDBACK", reason: "MODEL_ERROR" }],
								"compose-partial-error": [{ phase: "PRIVATE_FEEDBACK", reason: "MODEL_ERROR" }],
								"compose-no-decision": [{ phase: "PRIVATE_FEEDBACK", reason: "NO_DECISION" }],
								"compose-runtime-error": [{ phase: "PRIVATE_FEEDBACK", reason: "RUNTIME_ERROR" }],
							};
							const expected = failuresByStage[stage] ?? [];
							assert.deepEqual(payload.compositionFailures, expected);
							assert.equal(payload.admissionDigest, "admitted-digest");
							assert.equal(child.status, 0, child.stderr);
							const promptCounts: Record<string, number> = {
								"compose-complete-error": 2,
								"compose-no-decision": 4,
							};
							assert.equal(
								events.filter((event) => event.startsWith("prompt:")).length,
								promptCounts[stage] ?? 3,
							);
							if (stage !== "compose-public-error") {
								assert.ok(payload.review !== null);
							}
						}
						if (["compose-empty", "compose-fold", "compose-quiet"].includes(stage)) {
							assert.deepEqual(payload.compositionFailures, []);
						}
						if (stage === "compose-settle-deadline") {
							assert.deepEqual(payload.compositionFailures, [
								{ phase: "PUBLIC_REVIEW", reason: "SAFETY" },
								{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
							]);
						}
						if (stage === "compose-silent") {
							assert.deepEqual(payload.compositionFailures, []);
						}
						if (stage === "compose-loop") {
							assert.deepEqual(payload.compositionFailures, [
								{ phase: "PUBLIC_REVIEW", reason: "NO_DECISION" },
							]);
						}
					}
					if (fixture === "compose-reviewer-only") {
						assert.equal(child.status, 0, child.stderr);
						assert.equal(
							events.filter(
								(event) =>
									event.startsWith("create:") &&
									event.endsWith("tools=read_practice,report_review"),
							).length,
							0,
						);
						const feedback: unknown = JSON.parse(
							readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
						);
						assert.ok(isRecord(feedback));
						assert.ok(Array.isArray(feedback.observations));
						assert.equal(feedback.observations.length, 4);
						assert.deepEqual(
							feedback.observations.map((row: unknown) => (isRecord(row) ? row.id : null)),
							["observation-1", "observation-2", "qualification-und", "qualification-na"],
						);
						assert.ok(Array.isArray(feedback.units) && feedback.units.length > 0);
						assert.ok(
							feedback.units.every(
								(unit: unknown) => isRecord(unit) && unit.channel !== "IN_CONTEXT",
							),
						);
						return;
					}
					switch (stage) {
						case "compose-private-budget":
						case "compose-private-complete-error":
						case "compose-public-error":
						case "compose-private-error":
						case "compose-partial-error":
						case "compose-no-decision":
						case "compose-runtime-error":
						case "compose-recovered-error":
						case "compose-complete-error": {
							if (stage === "compose-partial-error") {
								const payload: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(payload) && Array.isArray(payload.units));
								assert.equal(payload.units.length, 1);
							}
							const failures: Record<string, [string, unknown][]> = {
								// The last call failed with a stated native status.
								"compose-public-error": [
									[
										"review composition",
										{
											kind: "HTTP_ERROR",
											phase: "request",
											status: 503,
											at: 1_760_000_000_000,
											retryEndedUnsuccessfully: false,
										},
									],
								],
								// A later successful call clears the earlier failure; its count stays.
								"compose-recovered-error": [["review composition", null]],
								// A malformed diagnostic is UNKNOWN; the native unsuccessful retry-end event marks it.
								"compose-private-error": [
									[
										"composition",
										{
											kind: "UNKNOWN",
											phase: null,
											status: null,
											at: null,
											retryEndedUnsuccessfully: true,
										},
									],
								],
							};
							const debugText = readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8");
							assert.ok(!debugText.includes("sk-orchestration-secret"));
							const debug: unknown = JSON.parse(debugText);
							assert.ok(isRecord(debug) && Array.isArray(debug.turns));
							for (const [label, final] of failures[stage] ?? []) {
								const turn: unknown = debug.turns.find(
									(entry: unknown) => isRecord(entry) && entry.label === label,
								);
								assert.ok(isRecord(turn), label);
								assert.deepEqual(turn.finalModelFailure, final, `${stage} ${label}`);
								assert.ok(isRecord(turn.modelFailures));
								assert.equal(
									Object.values(turn.modelFailures).reduce(
										(sum: number, count: unknown) => sum + (typeof count === "number" ? count : 0),
										0,
									),
									1,
								);
							}
							break;
						}
						case "scope-cut-off": {
							assert.equal(child.status, 0, child.stderr);
							assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 2);
							assert.equal(events.filter((event) => event === "steer").length, 2);
							assert.doesNotMatch(child.stderr, /2 calls in this session ran into/u);
							break;
						}
						case "scope-recording-guard": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								child.stderr,
								/24 recording calls without a record — aborting this turn/u,
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 3);
							assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 2);
							break;
						}
						case "scope-repeat": {
							assert.equal(child.status, 0, child.stderr);
							assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 6);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 6);
							assert.doesNotMatch(child.stderr, /aborting this turn|Finishing/u);
							assert.match(child.stderr, /calls=6\/12/u);
							break;
						}
						case "scope-ownership":
						case "scope-budget": {
							const result = readObservations(nodePath.join(cwd, "out/result.json"));
							assert.equal(child.status, 0, child.stderr);
							assert.equal(result.length, 2);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 2);
							assert.ok(events.indexOf("dispose") < events.indexOf("prompt:2"));
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(first, /# Test practice\nCriteria\./u);
							assert.doesNotMatch(first, /Complete second criterion/u);
							assert.match(second, /Complete second criterion\. Its own independent question\./u);
							assert.doesNotMatch(second, /# Test practice|Own test-practice/u);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							if (stage === "scope-budget") {
								assert.match(
									child.stderr,
									/calls=6\/6, outputTokens=600\/24000, stoppedBy=budget/u,
								);
								assert.match(child.stderr, /Measured: .*calls=6/u);
								assert.doesNotMatch(child.stderr, /Finishing/u);
							}
							break;
						}
						case "scope-stop-hangs":
						case "scope-stop-failure": {
							const result = readObservations(nodePath.join(cwd, "out/review-state.json"));
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							assert.equal(result.length, 1);
							assert.equal(result[0]?.practiceSlug, "test-practice");
							assert.match(child.stderr, /Native session did not settle/u);
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
							break;
						}
						case "compose-late-usage": {
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
							break;
						}

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
							// A refused review is not a review: nothing is left to deliver on the work.
							assert.match(
								events.find((event) => event.startsWith("refused-only:")) ?? "",
								/review refused, nothing was stored:[\s\S]*observation-1 has no decision/u,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.equal(feedback.review ?? null, null);
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
								/2 calls in this session ran into the 16384-token output limit — aborting this turn/u,
							);
							// One notice, then the stop; the finishing turn records the practice.
							const order = events.filter((event) =>
								["steer", "abort", "prompt:2"].includes(event),
							);
							assert.ok(order.indexOf("steer") < order.indexOf("abort"));
							assert.ok(events.indexOf("dispose") < events.indexOf("prompt:2"));
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
								assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 1);
								assert.equal(readObservations(nodePath.join(cwd, "admission.json")).length, 1);
							} else {
								assert.equal(existsSync(nodePath.join(cwd, "out/result.json")), false);
								assert.equal(existsSync(nodePath.join(cwd, "admission.json")), false);
							}
							if (unanswered) {
								assert.match(child.stderr, /UNREACHABLE/u);
							} else {
								assert.doesNotMatch(child.stderr, /UNREACHABLE/u);
							}
							if (stage.includes("refused")) {
								assert.ok(events.includes("answered-refusal"));
							}
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
							assert.ok(order.indexOf("abort-compaction") < order.indexOf("abort"));
							assert.ok(events.indexOf("dispose") < events.indexOf("prompt:2"));
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
							// Exercise Pi's public validator on the actual reporter declaration and prepared arguments.
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
const { prepareObservationArguments } = await import(process.argv[2]);
const captured = JSON.parse(readFileSync(process.argv[1], "utf8"));
const tool = { name: "report_observation", parameters: captured.parameters };
const validate = (args) => validateToolArguments(tool, { name: tool.name, arguments: prepareObservationArguments(args) });
assert.deepEqual(validate(captured.arguments), prepareObservationArguments(captured.arguments));
// The canonical observation is the root: an encoded root, a list or the old wrapper refuses the call.
const base = prepareObservationArguments(captured.arguments);
const encoded = JSON.stringify(base);
for (const sent of [encoded, encoded + '"', [base], { observations: [base] }]) {
  assert.throws(() => validate(sent), JSON.stringify(sent).slice(0, 160));
}
// The canonical observation reaches the SDK whole: nested shapes, required fields and vocabularies refuse the call.
const citation = base.evidence.citations[0];
const without = (record, key) => Object.fromEntries(Object.entries(record).filter(([name]) => name !== key));
for (const item of [
  { ...base, outcome: "PASSED" },
  { ...base, severity: "SEVERE" },
  { ...base, confidence: 0.9 },
  { ...base, summary: "x".repeat(10000) },
  without(base, "evidence"),
  without(base, "evidenceRationale"),
  { ...base, evidence: {} },
  { ...base, evidence: { citations: [] } },
  { ...base, evidence: { citations: [without(citation, "artifactPath")] } },
  { ...base, evidence: { citations: [{ ...citation, artifactPath: "" }] } },
  { ...base, evidence: { citations: [{ ...citation, sourceKind: "hephaestus.observation-history" }] } },
  { ...base, evidence: { citations: [{ ...citation, side: "MIDDLE" }] } },
  { ...base, evidence: { citations: [{ ...citation, startLine: 0 }] } },
]) {
  assert.throws(() => validate(item), JSON.stringify(item).slice(0, 160));
}
// Optional fields stay optional: their absence is accepted as it is, not filled in.
for (const item of [
  { ...base, evidence: { citations: [without(without(without(citation, "side"), "endLine"), "quote")] } },
  { ...base, outcome: "MET", severity: null },
  { ...base, outcome: "NOT_APPLICABLE", severity: null, evidence: { citations: [citation], inapplicability: { consulted: [citation.sourceKind], subject: "Runtime behavior", ruledOutBy: "Only documentation changed." } } },
  { ...base, outcome: "UNDETERMINED", severity: null, evidence: { citations: [citation], undecidability: { openQuestion: "Was the described test run?", wouldSettleIt: "The referenced test report." } } },
]) {
  assert.deepEqual(validate(item), item);
}
// Nullable absence is valid for every outcome, including a provider that requires all declared fields.
const absent = { search: null, inapplicability: null, undecidability: null };
const withoutQuote = { ...citation, side: null, revision: null, endLine: null, quote: null };
const nullableCases = [
  { ...base, evidence: { citations: [withoutQuote], ...absent } },
  { ...base, outcome: "MET", severity: null, evidence: { citations: [withoutQuote], ...absent } },
  { ...base, outcome: "NOT_APPLICABLE", severity: null, evidence: { citations: [withoutQuote], ...absent, inapplicability: { consulted: [citation.sourceKind], subject: "Runtime behavior", ruledOutBy: "Only documentation changed." } } },
  { ...base, outcome: "UNDETERMINED", severity: null, evidence: { citations: [withoutQuote], ...absent, undecidability: { openQuestion: "Was the described test run?", wouldSettleIt: "The referenced test report." } } },
];
const responseDeclaration = convertResponsesTools([{ ...tool, description: "Record observations." }])[0];
assert.equal(responseDeclaration.strict, false);
for (const item of nullableCases) {
  assert.deepEqual(validate(item), item);
  assert.deepEqual(validateToolArguments({ ...tool, parameters: responseDeclaration.parameters }, { name: tool.name, arguments: item }), item);
}
// Invalid primitives must not become null before outcome and citation validation sees them.
for (const value of ["", false, true, 0, []]) {
  for (const branch of Object.keys(absent)) {
    assert.throws(() => validate({ ...base, evidence: { citations: [citation], [branch]: value } }));
  }
}
for (const [field, invalid] of [
  ["side", ["", false]], ["revision", ["", false]], ["endLine", ["", false]], ["quote", [false, 0]],
]) {
  for (const value of invalid) {
    assert.throws(() => validate({ ...base, evidence: { citations: [{ ...citation, [field]: value }] } }));
  }
}
for (const item of [
  { ...base, evidence: { citations: [citation], inapplicability: {} } },
  { ...base, evidence: { citations: [citation], undecidability: {} } },
  { ...base, evidence: { citations: [citation], search: {} } },
]) {
  assert.throws(() => validate(item));
}
// Required nullable references survive native SDK preparation and validation unchanged.
assert.equal(validate({ ...base, revises: null }).revises, null);
assert.throws(() => validate(without(base, "revises")));
assert.throws(() => validate({ ...base, revises: "" }));
// Nonempty references are shape-valid; the recorder owns whether they were issued.
for (const revises of ["test-practice", "another-practice"]) {
  assert.deepEqual(validate({ ...base, revises }), { ...base, revises });
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
  { messages: [{ role: "system", content: "Record observations.", toolsAdded: [{ name: tool.name, description: "Record observations.", parameters: tool.parameters }], timestamp: 0 }, { role: "user", content: "Record it.", timestamp: 0 }] },
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
  { messages: [{ role: "system", content: "Record observations.", toolsAdded: [{ name: tool.name, description: "Record observations.", parameters: tool.parameters }], timestamp: 0 }, { role: "user", content: "Record it.", timestamp: 0 }] },
  { apiKey: "test-key", onPayload: (params) => { responsesPayload = params; throw new Error("stopped before any request left"); } },
).result();
assert.equal(responsesAnswer.stopReason, "error");
const responsesTool = responsesPayload.tools.find((entry) => entry.name === tool.name);
assert.deepEqual(responsesTool.parameters, tool.parameters);
for (const item of nullableCases) {
  assert.deepEqual(validateToolArguments({ ...tool, parameters: responsesTool.parameters }, { name: tool.name, arguments: item }), item);
}`,
									nodePath.join(cwd, "observation-call.json"),
									new URL("../../../main/resources/agent/pi-tool-arguments.ts", import.meta.url)
										.href,
								],
								{ encoding: "utf8", cwd: process.cwd() },
							);
							assert.equal(native.status, 0, native.stderr);
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
								/test-practice: already recorded; this item changed nothing, so do not send it again/u,
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
							// The next practice receives no earlier draft or superseded positive result.
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
						case "measure-context-compact":
						case "measure-context-missing": {
							assert.equal(child.status, 0, child.stderr);
							const turn = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const criterion =
								stage === "measure-context-missing" ? "(criteria file missing)" : practiceCriteria;
							const criterionAt = turn.indexOf(criterion);
							const illustrationsAt = turn.indexOf("## How report_observation takes observations");
							const leadsAt = turn.indexOf("#### Precomputed leads");
							const recordAt = turn.lastIndexOf("Record one observation for test-practice");
							assert.ok(criterionAt !== -1 && turn.includes("### `evidence/metadata.json`"), turn);
							assert.ok(criterionAt > turn.indexOf("### `evidence/metadata.json`"), turn);
							assert.ok(illustrationsAt > criterionAt + criterion.length, turn);
							assert.ok(leadsAt > illustrationsAt && recordAt > leadsAt, turn);
							assert.equal(turn.split("## How report_observation takes observations").length, 2);
							assert.ok(!turn.includes("Complete second criterion"));
							assert.deepEqual(
								events.filter((event) => event === "compact"),
								stage === "measure-context-compact" ? ["compact"] : [],
							);
							assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 1);
							reached({ "test-practice": "EVALUATED" });
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
							assert.match(system, /Each fresh session names one practice/u);
							// The reporter is a direct call only; scripts reach the evidence tools.
							assert.match(system, /Call it directly: a codemode script cannot call it/u);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8"),
								/### Practice `test-practice`/u,
							);
							assert.match(
								events.find((event) => event.startsWith("resent:")) ?? "",
								/already recorded; this item changed nothing, so do not send it again/u,
							);
							assert.ok(events.includes("non-root:unchanged"), events.join("\n"));
							const reply = events.find((event) => event.startsWith("batch:")) ?? "";
							assert.match(reply, /test-practice: revised \(negative\)/u, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("outside:")) ?? "",
								/second-practice: refused — this session assesses only 'test-practice'/u,
							);
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
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.ok(first.indexOf("## Turn") > 0 && second.indexOf("## Turn") > 0);
							assert.equal(
								first.slice(0, first.indexOf("## Turn")),
								second.slice(0, second.indexOf("## Turn")),
							);
							assert.ok(
								first.indexOf(practiceCriteria) <
									first.indexOf("## How report_observation takes observations"),
							);
							assert.ok(
								first.indexOf("## How report_observation takes observations") <
									first.indexOf("#### Precomputed leads"),
							);
							for (const turn of [first, second]) {
								assert.equal(turn.split("## How report_observation takes observations").length, 2);
							}
							assert.match(
								first,
								/#### Precomputed leads for `test-practice` — starting points to check against the criteria, not verdicts\n- `src\/Auth\.java` \[L10\] — insecure call/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-kind:")) ?? "",
								/recorded as scm\.pull-request\.diff/u,
							);
							assert.match(
								events.find((event) => event.startsWith("corrected-side:")) ?? "",
								/test-practice: revised \(negative\)\.[^#]*evidence\/metadata\.json is staged by scm\.pull-request\.core, not scm\.pull-request\.diff; recorded as scm\.pull-request\.core/u,
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
							assert.equal(
								result[1]?.summary,
								"Second practice recorded in its own session",
								child.stderr,
							);
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
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 3);
							const first = readFileSync(nodePath.join(cwd, "prompt-1.md"), "utf8");
							const second = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							// Each fresh session receives the stable brief and its own complete criterion.
							assert.ok(first.includes("### `evidence/metadata.json`"), first.slice(0, 300));
							assert.match(
								first,
								/## How report_observation takes observations[\s\S]*"artifactPath": "evidence\/change\.json"/u,
							);
							assert.ok(second.includes("### `evidence/metadata.json`"));
							assert.doesNotMatch(second, /### Practice `test-practice`|test-practice: NOT_MET/u);
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
								/"terminate":true/u,
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
							// A fresh finishing session records the practice the loop left unrecorded.
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
							// Exhaustion is not a recorded result, and the closed practice is not asked again.
							assert.ok(
								!events.includes("Every practice of this turn has a recorded result."),
								events.join("\n"),
							);
							assert.ok(
								afterCap.includes("No recorded result for: test-practice."),
								afterCap.join("\n"),
							);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "natural-stop": {
							assert.equal(child.status, 0, child.stderr);
							// One session holds what it read: the request to record goes to it, not to a fresh one.
							assert.deepEqual(
								events.filter((event) => event.startsWith("natural-prompt:")),
								["natural-prompt:1:session-1", "natural-prompt:2:session-1"],
							);
							assert.deepEqual(
								events.filter((event) => event.startsWith("create:session")),
								["create:session tools=read,grep,find,ls,bash,codemode,report_observation"],
							);
							assert.ok(
								events.includes("exposure:report_observation=model-only"),
								events.join("\n"),
							);
							const request = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
							assert.match(request, /report_observation/u);
							assert.match(request, /test-practice/u);
							assert.doesNotMatch(request, /### Practice `|## Unfinished practices/u);
							assert.match(
								events.find((event) => event.startsWith("natural-recorded:")) ?? "",
								/test-practice: stored \(negative\)/u,
							);
							assert.equal(readObservations(nodePath.join(cwd, "out/result.json")).length, 1);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "natural-stop-twice": {
							// Nothing recorded: the run fails as a review that reached no practice, not one that measured.
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("natural-prompt:")),
								["natural-prompt:1:session-1", "natural-prompt:2:session-1"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:session")).length, 1);
							assert.doesNotMatch(child.stderr, /Finishing 1 practice/u);
							assert.deepEqual(readObservations(nodePath.join(cwd, "out/review-state.json")), []);
							assert.equal(existsSync(nodePath.join(cwd, "out/result.json")), false);
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
						case "compose-support": {
							assert.equal(child.status, 0, child.stderr);
							const payload: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(payload) && Array.isArray(payload.units));
							assert.ok(payload.review !== null);
							assert.equal(payload.admissionDigest, "admitted-digest");
							assert.deepEqual(
								JSON.parse(
									readFileSync(nodePath.join(cwd, "work/composition/observations.json"), "utf8"),
								),
								{ observations: [admittedObservation] },
							);

							const mode = fixture.slice("compose-support-".length);
							assert.equal(
								payload.units.some(
									(unit: unknown) =>
										isRecord(unit) && unit.channel === "IN_CHAT" && unit.action === "NEW",
								),
								mode !== "refused-only",
							);
							assert.deepEqual(
								payload.units
									.filter((unit: unknown) => isRecord(unit) && unit.channel === "IN_APP")
									.map((unit: unknown) => {
										assert.ok(isRecord(unit));
										return unit.action;
									}),
								new Map<string, string[]>([
									["selected", ["NEW"]],
									["empty", ["WITHHOLD"]],
									["duplicate", ["WITHHOLD"]],
								]).get(mode) ?? [],
							);
							assert.deepEqual(
								payload.compositionFailures,
								["missing", "malformed", "unavailable", "invalid-optional"].includes(mode)
									? [{ phase: "PRIVATE_FEEDBACK", reason: "RUNTIME_ERROR" }]
									: [],
							);
							assert.equal(
								events.filter(
									(event) => event.includes("tools=") && event.includes("report_feedback"),
								).length,
								mode === "refused-only" ? 0 : 1,
							);
							break;
						}
						case "compose":
						case "compose-foreign-provider": {
							assert.equal(child.status, 0, child.stderr);
							if (
								fixture === "compose-history-invalid" ||
								fixture === "compose-history-invalid-link" ||
								fixture === "compose-history-refused" ||
								fixture === "compose-history-malformed-json"
							) {
								const payload: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(payload));
								assert.equal(payload.review, null);
								assert.deepEqual(payload.compositionFailures, [
									{ phase: "PUBLIC_REVIEW", reason: "RUNTIME_ERROR" },
								]);
								assert.ok(
									events.some((event) => event.startsWith("private-turn history=true")),
									events.join("\n"),
								);
								assert.equal(
									events.filter((event) => event.includes("tools=read_practice,report_review"))
										.length,
									0,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "history/feedback.json"), "utf8"),
									STAGED_FEEDBACK_HISTORY,
								);
								assert.equal(payload.admissionDigest, "admitted-digest");
								if (fixture === "compose-history-malformed-json") {
									assert.equal(events.filter((event) => event === "public-history-read").length, 1);
								}
								break;
							}
							if (
								fixture === "compose-fresh-history" ||
								fixture === "compose-history-body-transport"
							) {
								if (fixture === "compose-history-body-transport") {
									assert.equal(events.filter((event) => event === "public-history-read").length, 2);
								}
								const prompt = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
								assert.ok(prompt.includes("Fresh delivered communication"));
								assert.ok(!prompt.includes("Delivery with unknown work link"));
								assert.ok(!prompt.includes("An earlier comment on this same change."));
								const debug: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8"),
								);
								assert.ok(isRecord(debug) && isRecord(debug.publicHistory));
								assert.equal(debug.publicHistory.readAt, "2026-10-07T11:00:00Z");
								assert.equal(debug.publicHistory.workCapturedAt, "2026-10-07T09:00:00Z");
								assert.deepEqual(debug.publicHistory.entries, [
									{
										id: "00000000-0000-4000-8000-000000000001",
										deliveredAt: "2026-10-07T10:00:00Z",
										eligibleForAlreadySaid: true,
										eligibleForPriorAdvice: false,
									},
									{
										id: "00000000-0000-4000-8000-000000000002",
										deliveredAt: null,
										eligibleForAlreadySaid: false,
										eligibleForPriorAdvice: false,
									},
								]);
								assert.ok(
									!JSON.stringify(debug.publicHistory).includes("Fresh delivered communication"),
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "history/feedback.json"), "utf8"),
									STAGED_FEEDBACK_HISTORY,
								);
							}

							// Measurement, then the review on the work in a fresh session, then the private lanes in
							// a third fresh session.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.deepEqual(
								events
									.filter((event) => event.startsWith("create:"))
									.map((event) => event.endsWith("tools=report_review")),
								[false, true, false],
							);
							// A practice with only a problem here, and a private MET-history row, offer nothing to read.
							assert.equal(
								events.find((event) => event.startsWith("review-read:")),
								"review-read:not offered",
							);
							assert.ok(events.includes("review-loader extensions=0"), events.join("\n"));
							assert.equal(
								readFileSync(nodePath.join(cwd, "review-system-prompt.md"), "utf8"),
								"Write clear, useful feedback.\n\nWrite the review on the work.",
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
							if (
								fixture !== "compose-fresh-history" &&
								fixture !== "compose-history-body-transport"
							) {
								assert.match(reviewTurn, /An earlier comment on this same change\./u);
							}
							assert.match(reviewTurn, /"slug": "test-practice"/u);
							assert.deepEqual(reviewPracticesOf(reviewTurn), {
								practices: [
									{
										slug: "test-practice",
										name: "test-practice",
										knownLimitations: [],
										...(stage === "compose" ? { revisionId: 42 } : {}),
									},
								],
							});
							// The NOT_MET practice's standard comes whole, once, after the work and before the observations.
							const standard = reviewTurn.indexOf("```markdown\n# Test practice\nCriteria.\n```");
							assert.ok(
								record < standard && standard < reviewTurn.indexOf('"id": "observation-1"'),
								reviewTurn,
							);
							assert.equal(reviewTurn.split("# Test practice\nCriteria.").length, 2, reviewTurn);
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
								/Stored the review: a summary resting on 1 observation\(s\), 1 line note\(s\), 0 withholding decision\(s\)[\s\S]*"terminate":true/u,
							);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							// The private lanes keep the person's history, and see what the review said.
							assert.ok(
								events.includes("private-turn history=true review=true"),
								events.join("\n"),
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-historical:")) ?? "",
								/does not name an admitted observation from this run/u,
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
							// Private composition receives stable context anew, without measurement criteria or tool examples.
							assert.match(second, /### `evidence\/metadata\.json`/u);
							assert.doesNotMatch(
								second,
								/### Practice `test-practice`|How report_observation takes/u,
							);
							assert.equal(
								readFileSync(nodePath.join(cwd, "private-system-prompt.md"), "utf8"),
								"Write clear, useful feedback.\n\nCompose from admitted observations.",
							);
							assert.doesNotMatch(
								second,
								/Compose from admitted observations|How report_observation takes/u,
							);
							assert.match(second, /"id": "observation-1"/u);
							// The quoted lines and the verification records stay on disk, where the composer
							// can read them if needed; the search it recorded is still shown.
							assert.ok(second.includes("## This turn"));
							const composerTurn = second.slice(second.indexOf("## This turn"));
							// The NOT_MET practice's criteria come whole before the admitted rows, without its
							// measuring leads or a sibling's criteria; the staged files stay readable.
							const criteriaAt = composerTurn.indexOf("# Test practice\nCriteria.");
							assert.ok(criteriaAt !== -1, composerTurn);
							assert.ok(criteriaAt < composerTurn.indexOf('"id": "observation-1"'), composerTurn);
							assert.doesNotMatch(
								composerTurn,
								/Precomputed leads|insecure call: |Complete second criterion/u,
							);
							// Earlier feedback keeps every fact it was staged with and names where its words are.
							for (const fact of [
								'"id": "feedback-same-work"',
								'"deliveredAt": "2026-08-15T10:00:00Z"',
								`"reviewedRevision": "${"d".repeat(40)}"`,
								'"id": "earlier-2"',
								'"recordedClaimCurrentness": "STALE"',
								'"withdrawn": true',
							]) {
								assert.ok(composerTurn.includes(fact), fact);
							}
							assert.ok(!composerTurn.includes("An earlier comment on this same change."));
							assert.ok(!composerTurn.includes("Earlier prepared mentor wording."));
							assert.ok(!composerTurn.includes(REVIEW_SUMMARY));
							assert.match(composerTurn, /prepared\[0\]\.body/u);
							assert.doesNotMatch(composerTurn, /prepared\[1\]\.body/u);
							assert.match(composerTurn, /review\.summary\.body/u);
							assert.match(composerTurn, /review\.inline\[0\]\.body/u);
							// The native evidence reader can retrieve the original source at a displayed locator.
							const { createReadTool } = await import("@earendil-works/pi-coding-agent");
							const reader = createReadTool(cwd);
							for (const [path, expected] of [
								["history/feedback.json", "An earlier comment on this same change."],
								["history/prepared.json", "Earlier prepared mentor wording."],
								["out/feedback.json", JSON.stringify(REVIEW_SUMMARY).slice(1, -1)],
							]) {
								assert.ok(path !== undefined && expected !== undefined);
								const result = await reader.execute(`jit-${path}`, { path });
								const text = result.content
									.filter((block) => block.type === "text")
									.map((block) => block.text)
									.join("\n");
								assert.ok(text.includes(expected), text);
							}

							// Each history file's projection keeps every staged root and row fact; a body is replaced by
							// its locator in the unchanged file, and a row without one (withdrawn, stale) names none.
							const projections = [
								...composerTurn.matchAll(/```json\n(?<json>[\s\S]*?)\n```/gu),
							].map((match): unknown => JSON.parse(match.groups?.json ?? "null"));
							for (const [role, key, file, staged, bodyRoles] of [
								[
									"RECORDED_DELIVERY",
									"feedback",
									"history/feedback.json",
									STAGED_FEEDBACK_HISTORY,
									["COMPOSED_GUIDANCE", "COMPOSED_GUIDANCE", undefined, "UNKNOWN"],
								],
								[
									"PREPARED_NOT_DELIVERED",
									"prepared",
									"history/prepared.json",
									STAGED_PREPARED_HISTORY,
									["MENTOR_NOTES", undefined],
								],
							] as const) {
								assert.equal(readFileSync(nodePath.join(cwd, file), "utf8"), staged);
								const view = projections.find(
									(projection) => isRecord(projection) && projection.recordRole === role,
								);
								assert.ok(isRecord(view), role);
								assert.deepEqual(view.coverage, HISTORY_COVERAGE);
								const rows = view[key];
								assert.ok(Array.isArray(rows));
								assert.deepEqual(
									rows.map((row: unknown) => (isRecord(row) ? row.bodyRole : null)),
									bodyRoles,
								);
								const original: unknown = JSON.parse(staged);
								const sourceRows = isRecord(original) ? original[key] : null;
								assert.ok(Array.isArray(sourceRows) && sourceRows.length === rows.length);
								for (const [bodyIndex, source] of sourceRows.entries()) {
									assert.ok(isRecord(source));
									const { body, ...facts } = source;
									const row: unknown = rows[bodyIndex];
									assert.ok(isRecord(row));
									const { bodyAt, ...kept } = row;
									assert.deepEqual(kept, facts);
									assert.ok(!Object.hasOwn(row, "body"));
									const sourcePath = nodePath.join(cwd, file);
									assert.equal(
										bodyAt,
										typeof body === "string"
											? `${sourcePath} → ${key}[${bodyIndex}].body (${body.length} characters)`
											: undefined,
									);
									// The locator identifies the original bytes and the staged body index.
									const located = readFileSync(sourcePath, "utf8");
									assert.equal(located, staged);
									const reread: unknown = JSON.parse(located);
									const rereadRows = isRecord(reread) ? reread[key] : null;
									assert.ok(Array.isArray(rereadRows));
									const entry: unknown = rereadRows[bodyIndex];
									assert.ok(isRecord(entry) && entry.body === body);
								}
							}
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
							// private composition starts fresh and restores the complete authorized opening after compaction.
							const order = events.filter((event) =>
								["prompt:1", "prompt:2", "compact", "prompt:3"].includes(event),
							);
							assert.deepEqual(order, ["prompt:1", "prompt:2", "compact", "prompt:3"]);

							// The compaction took the brief from the context, so the composition turn carries it again
							// ahead of the composer's instructions — without the measuring examples and record, which
							// the composer does not use.
							const composition = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							assert.match(composition, /### `evidence\/metadata\.json`[\s\S]*## This turn/u);
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
							const said = (label: string) =>
								events.find((event) => event.startsWith(`${label}:`)) ?? "";
							if (fixture === "compose-quiet-blank") {
								// A blank standard is shown as staged and grants nothing, even in the turn after the read.
								assert.match(
									said("blank-read"),
									/### Criteria of `test-practice`[\s\S]*Without its standard, no acknowledgement of this practice can be stored/u,
								);
								// A read that shows no standard is offered as no reference.
								assert.ok(!said("blank-read").includes(STANDARD_REFERENCE), said("blank-read"));
								assert.match(
									said("blank-strength"),
									/review refused, nothing was stored:[\s\S]*rests on a MET observation of test-practice, whose complete MET reference this review was not written with/u,
								);
								assert.match(
									said("blank-quiet"),
									/Stored the review: no summary, 0 line note\(s\), 0 withholding decision\(s\)/u,
								);
								const quiet: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(quiet));
								assert.deepEqual(quiet.review, { summary: null, inline: [], withheld: [] });
								assert.deepEqual(
									readObservations(nodePath.join(cwd, "out/feedback.json")).map(
										(item) => item.outcome,
									),
									["MET"],
								);
								assert.doesNotMatch(child.stderr, /asking once more/u);
								break;
							}
							// Before its standard is read, and in the batch that reads it, the acknowledgement is refused
							// whole; an unknown practice shows nothing; from the next turn the same review is stored.
							const unread =
								/review refused, nothing was stored:[\s\S]*rests on a MET observation of test-practice, whose complete MET reference this review was not written with/u;
							assert.match(said("strength-unread"), unread);
							assert.match(
								said("strength-unknown"),
								/elsewhere is not a practice with a MET observation this review may acknowledge; read_practice shows only: test-practice/u,
							);
							assert.match(said("strength-read"), /### Criteria of `test-practice`/u);
							assert.match(
								said("strength-read"),
								/The recorded MET observations of `test-practice`:[\s\S]*observation-1/u,
							);
							assert.ok(
								!said("strength-read").includes('"id": "observation-2"'),
								said("strength-read"),
							);
							assert.ok(said("strength-read").includes(STANDARD_REFERENCE), said("strength-read"));
							assert.match(said("strength-same-batch"), unread);
							assert.match(
								said("review-strength"),
								/Stored the review: a summary resting on 1 observation\(s\)/u,
							);
							const stored: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(stored));
							assert.deepEqual(stored.review, {
								summary: {
									body: "Moving the check into its own helper keeps every caller on the same path.",
									basedOn: ["observation-1"],
								},
								inline: [],
								withheld: [],
							});
							assert.deepEqual(
								readObservations(nodePath.join(cwd, "out/feedback.json")).map(
									(item) => item.outcome,
								),
								["MET"],
							);
							assert.ok(
								!events.some((event) => event.startsWith("private-turn")),
								events.join("\n"),
							);
							assert.doesNotMatch(child.stderr, /asking once more/u);
							break;
						}
						case "compose-empty": {
							assert.equal(child.status, 0, child.stderr);
							// An empty final review is a decision: it ends the composition, and nobody asks again.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.match(
								events.find((event) => event.startsWith("empty-final:")) ?? "",
								/Stored the review: no summary, 0 line note\(s\), 0 withholding decision\(s\)[\s\S]*"terminate":true/u,
							);
							assert.ok(events.includes('empty-terminal:{"action":"end"}'), events.join("\n"));
							assert.equal(events.filter((event) => event === "native-finish-turn").length, 1);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.deepEqual(feedback.review, { summary: null, inline: [], withheld: [] });
							break;
						}
						case "compose-repeat-select":
						case "compose-invalid-select": {
							assert.equal(child.status, 0, child.stderr);
							const said = (label: string) =>
								events.find((event) => event.startsWith(`${label}:`)) ?? "";
							const prefix = stage === "compose-repeat-select" ? "repeat" : "invalid";
							// The nudge points at the final review, and at a read only where a strength needs one.
							const nudge = JSON.stringify([["read_practice", "report_review"]]);
							if (stage === "compose-repeat-select") {
								// Only the independent MET practice has a standard to show, however often this problem's is asked.
								for (const label of ["repeat-refused", "repeat-again"]) {
									assert.match(
										said(label),
										/test-practice is not a practice with a MET observation this review may acknowledge; read_practice shows only: second-practice/u,
									);
								}
								assert.equal(said("repeat-nudge"), `repeat-nudge:${nudge}`);
							} else {
								// A whole review that decides nothing is refused whole, each time it is sent.
								for (const label of ["invalid-report", "invalid-again"]) {
									assert.match(
										said(label),
										/review refused, nothing was stored:[\s\S]*observation-1 has no decision/u,
									);
								}
								assert.equal(said("invalid-nudge"), `invalid-nudge:${nudge}`);
								// The final review ends the composition; a later call stores nothing more.
								assert.match(said("invalid-after-final"), /The review on this work is final/u);
							}
							const repeated =
								stage === "compose-repeat-select" ? "read_practice" : "report_review";
							assert.match(
								child.stderr,
								new RegExp(`the same ${repeated} call 2 times — nudging to record`, "u"),
							);
							assert.doesNotMatch(
								child.stderr,
								new RegExp(`the same ${repeated} call 3 times`, "u"),
							);
							assert.match(said(`${prefix}-final`), /Stored the review[\s\S]*"terminate":true/u);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.deepEqual(feedback.review, {
								summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
								inline: [],
								withheld: [],
							});
							break;
						}
						case "compose-recover": {
							assert.equal(child.status, 0, child.stderr);
							const criteria = readFileSync(
								nodePath.join(cwd, "catalog/practices/test-practice.md"),
								"utf8",
							);
							assert.match(criteria, /^ {4}let reply[\s\S]*reasoned decline[\s\S]* {2}\n\n$/u);
							const staged = `markdown\n${criteria}\n`;
							const refused = events.find((event) => event.startsWith("recover-refused:")) ?? "";
							assert.match(refused, /review refused, nothing was stored/u);
							assert.ok(events.includes("review-retry"), events.join("\n"));
							const prompts = events
								.filter((event) => event.startsWith("prompt:"))
								.map((event) =>
									readFileSync(
										nodePath.join(cwd, `prompt-${event.slice("prompt:".length)}.md`),
										"utf8",
									),
								);
							// The review turn carries the NOT_MET standard whole, before the rows it is decided on; the
							// session still holds it, so neither the refusal nor the retry repeats it.
							const turn = prompts.find((prompt) => prompt.startsWith("## The review to write"));
							assert.ok(turn !== undefined, events.join("\n"));
							assert.ok(turn.includes(staged), turn);
							assert.ok(turn.indexOf(staged) < turn.indexOf('"observations": ['), turn);
							assert.ok(!refused.includes(JSON.stringify(staged).slice(1, -1)), refused);
							// The retry holds only what is owed and how to finish.
							const retry = prompts.find((prompt) => prompt.startsWith("## Undecided"));
							assert.ok(retry !== undefined, events.join("\n"));
							assert.ok(retry.includes("observation-1"), retry);
							assert.ok(!retry.includes(staged), retry);
							break;
						}
						case "compose-qualify": {
							assert.equal(child.status, 0, child.stderr);
							const standards = ["test-practice", "second-practice"].map(
								(slug) =>
									`markdown\n${readFileSync(nodePath.join(cwd, `catalog/practices/${slug}.md`), "utf8")}\n`,
							);
							const reviewTurn = events
								.filter((event) => event.startsWith("prompt:"))
								.map((event) =>
									readFileSync(
										nodePath.join(cwd, `prompt-${event.slice("prompt:".length)}.md`),
										"utf8",
									),
								)
								.find((prompt) => prompt.startsWith("## The review to write"));
							assert.ok(reviewTurn !== undefined, events.join("\n"));
							if (fixture === "compose-compacted") {
								const [notMet = "", met = ""] = standards;
								// The mixed practice standard is shown, but its full MET grounds still wait for a read.
								assert.ok(reviewTurn.includes(notMet), reviewTurn);
								assert.ok(!reviewTurn.includes(met), reviewTurn);
								const withheld = [{ basedOn: ["observation-1"], reason: "BELOW_BAR" }];
								// After a compaction, the read carries the whole review turn back with the MET standard.
								assert.match(
									events.find((event) => event.startsWith("mixed-unread:")) ?? "",
									/whose complete MET reference this review was not written with/u,
								);
								assert.ok(
									!reviewTurn.includes(
										"The helper path is present in the source; its runtime behavior was not executed.",
									),
									reviewTurn,
								);
								const read = readToolText(nodePath.join(cwd, "compacted-read.txt"));
								assert.ok(read.includes(reviewTurn) && !read.includes(met), read);
								// The read standard arrives as reference for its recorded observations, whole.
								assert.ok(
									read.includes("The whole standard of `test-practice` is shown with its concern"),
									read,
								);
								// A review sent in the same batch is refused: nothing restored is in view before the next
								// turn.
								assert.match(
									readFileSync(nodePath.join(cwd, "same-batch-refused.txt"), "utf8"),
									/review refused, nothing was stored/u,
								);
								// A review sent after another compaction is refused with the whole turn and every standard
								// read so far; one sent in the same batch is refused too; the next turn's is stored.
								const refused = readToolText(nodePath.join(cwd, "compacted-refused.txt"));
								assert.match(
									refused,
									/review refused, nothing was stored: the session's context was compacted/u,
								);
								assert.ok(refused.includes(notMet) && !refused.includes(met), refused);
								assert.match(
									refused,
									/The recorded MET observations of `test-practice`:[\s\S]*observation-2/u,
								);
								assert.ok(refused.includes(reviewTurn), refused);
								assert.ok(refused.includes(`"reviewedRevision": "${"a".repeat(40)}"`), refused);
								// A restored standard keeps what it is for.
								assert.ok(
									refused.includes(
										`### Standards read earlier in this session\n${STANDARD_REFERENCE}\nThe whole standard of \`test-practice\` is shown with its concern`,
									),
									refused,
								);
								assert.match(
									readFileSync(nodePath.join(cwd, "restore-batch-refused.txt"), "utf8"),
									/review refused, nothing was stored/u,
								);
								assert.match(
									events.find((event) => event.startsWith("compacted-stored:")) ?? "",
									/Stored the review: a summary resting on 1 observation\(s\)/u,
								);
								const stored: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(stored));
								assert.deepEqual(stored.review, {
									summary: {
										body: "Moving the check into its own helper keeps every caller on the same path.",
										basedOn: ["observation-2"],
									},
									inline: [],
									withheld,
								});
								reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
								break;
							}
							// Both practices have a NOT_MET observation: the review turn carries both standards whole,
							// and a refusal answered while the session holds them does not repeat them.
							for (const standard of standards) {
								assert.ok(reviewTurn.includes(standard), reviewTurn);
							}
							const refused = readFileSync(nodePath.join(cwd, "qualification-refused.txt"), "utf8");
							assert.match(
								refused,
								/review refused, nothing was stored:[\s\S]*observation-2 has no decision/u,
							);
							for (const standard of standards) {
								assert.ok(!refused.includes(standard), refused);
							}
							assert.match(
								events.find((event) => event.startsWith("qualification-stored:")) ?? "",
								/Stored the review/u,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.equal(feedback.admissionDigest, "admitted-digest");
							assert.deepEqual(feedback.review, {
								summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
								inline: [],
								withheld: [{ basedOn: ["observation-2"], reason: "BELOW_BAR" }],
							});
							assert.ok(events.includes("qualification:canonical-unchanged"), events.join("\n"));
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "compose-reselect": {
							assert.equal(child.status, 0, child.stderr);
							const said = (label: string) =>
								events.find((event) => event.startsWith(`${label}:`)) ?? "";
							assert.ok(
								events.includes('reselect-before-final:{"action":"continue"}'),
								events.join("\n"),
							);
							assert.ok(events.includes("reselect-retained:1"), events.join("\n"));
							assert.match(
								said("reselect-incomplete"),
								/review refused, nothing was stored:[\s\S]*observation-1 has no decision/u,
							);
							// A prior-advice reason with no statement to name is refused whole, not stored without it.
							assert.match(
								said("reselect-unwitnessed"),
								/review refused, nothing was stored:[\s\S]*ALREADY_SAID names in witnessIds/u,
							);
							const criteria = readFileSync(
								nodePath.join(cwd, "catalog/practices/test-practice.md"),
								"utf8",
							);
							assert.match(criteria, /^ {4}let reply[\s\S]*reasoned decline[\s\S]* {2}\n\n$/u);
							const staged = `markdown\n${criteria}\n`;
							// The staged bytes reach the review turn whole; refusals the session answers while it still
							// holds them do not repeat them.
							const reviewTurn = events
								.filter((event) => event.startsWith("prompt:"))
								.map((event) =>
									readFileSync(
										nodePath.join(cwd, `prompt-${event.slice("prompt:".length)}.md`),
										"utf8",
									),
								)
								.find((prompt) => prompt.startsWith("## The review to write"));
							assert.ok(reviewTurn?.includes(staged) === true, reviewTurn);
							assert.ok(
								!said("reselect-unwitnessed").includes(JSON.stringify(staged).slice(1, -1)),
								said("reselect-unwitnessed"),
							);
							assert.match(
								said("reselect-final"),
								/Stored the review: a summary resting on 1 observation\(s\)[\s\S]*"terminate":true/u,
							);
							assert.ok(events.includes('reselect-terminal:{"action":"end"}'), events.join("\n"));
							assert.ok(events.includes("reselect-drained:0"), events.join("\n"));
							assert.equal(events.filter((event) => event === "native-finish-turn").length, 2);
							assert.match(
								said("reselect-after-read"),
								/final; this composition accepts nothing more/u,
							);
							assert.match(
								said("reselect-after-review"),
								/final; this composition accepts nothing more/u,
							);
							// The final review was the one the composition owed, so it is not asked for again.
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							const reviewPrompts = events
								.filter((event) => event.startsWith("prompt:"))
								.map((event) =>
									readFileSync(
										nodePath.join(cwd, `prompt-${event.slice("prompt:".length)}.md`),
										"utf8",
									),
								)
								.filter((prompt) =>
									/^## (?:The review to write|Undecided|Unfinished)/u.test(prompt),
								);
							assert.equal(reviewPrompts.length, 1);
							const initialReview = reviewPrompts[0];
							assert.ok(initialReview !== undefined);
							assert.deepEqual(reviewPracticesOf(initialReview), {
								practices: [{ slug: "test-practice", name: "test-practice", knownLimitations: [] }],
							});
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.deepEqual(feedback.review, {
								summary: { body: REVIEW_SUMMARY, basedOn: ["observation-1"] },
								inline: [],
								withheld: [],
							});
							break;
						}
						case "compose-already-said": {
							assert.equal(child.status, 0, child.stderr);
							const first = events.find((event) => event.startsWith("said-first:")) ?? "";
							// The primary second-practice has IN_CHAT notes; the folded-in NOT_MET test-practice has none.
							assert.match(
								first,
								/#1: NO_MATERIAL_CHANGE rests on feedback recorded as delivered on IN_CHAT for test-practice in/u,
							);
							// Prepared notes, the IN_APP card, the public review, other practices, and malformed,
							// MET-based, stale, withdrawn and undated IN_CHAT records leave test-practice without one.
							assert.match(
								first,
								/#2: ALREADY_SAID rests on feedback recorded as delivered on IN_CHAT for test-practice in/u,
							);
							// Both NOT_MET practices have an IN_APP card; a withholding uses no slot on its lane.
							assert.match(
								first,
								/#3: stored a IN_APP unit for test-practice \(WITHHOLD\); 0\/1 used on that lane\./u,
							);
							const second = events.find((event) => event.startsWith("said-second:")) ?? "";
							assert.match(
								second,
								/#1: NO_MATERIAL_CHANGE rests on feedback recorded as delivered on IN_CHAT for test-practice in/u,
							);
							// The refused grouping left the pair open; MET counterevidence owes no history.
							assert.match(
								second,
								/#2: stored a IN_CHAT unit for second-practice \(WITHHOLD\); 0\/1 used on that lane\./u,
							);
							assert.match(
								second,
								/#3: stored a IN_CHAT unit for test-practice \(WITHHOLD\); 0\/1 used on that lane\./u,
							);
							const payload: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(payload) && Array.isArray(payload.units));
							assert.deepEqual(
								payload.units.map((unit: unknown) => {
									assert.ok(isRecord(unit));
									assert.ok(Array.isArray(unit.basedOn));
									return `${String(unit.channel)}:${String(unit.practiceSlug)}:${String(unit.withholdReason)}:${unit.basedOn.join("+")}`;
								}),
								[
									"IN_APP:test-practice:ALREADY_SAID:observation-1+observation-2",
									"IN_CHAT:second-practice:NO_MATERIAL_CHANGE:observation-2+observation-3",
									"IN_CHAT:test-practice:BELOW_BAR:observation-1",
								],
							);
							assert.deepEqual(payload.compositionFailures, []);
							break;
						}
						case "compose-fold": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("feedback-fold:")) ?? "",
								/stored/u,
							);
							// The turn, the finishing turn, the review and the private composition; the review's one
							// decisions cover both observations, so neither composition asks again.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3", "prompt:4"],
							);
							assert.match(
								events.find((event) => event.startsWith("review-withheld:")) ?? "",
								fixture === "compose-counterevidence"
									? /0 line note\(s\), 1 withholding decision\(s\)/u
									: /0 line note\(s\), 2 withholding decision\(s\)/u,
							);
							assert.doesNotMatch(child.stderr, /asking once more/u);
							// An oversized criterion is named with where to read it, never cut; the other is shown whole.
							const privateTurn = ["prompt-1.md", "prompt-2.md", "prompt-3.md", "prompt-4.md"]
								.map((name) => readFileSync(nodePath.join(cwd, name), "utf8"))
								.find((text) => text.includes("## This turn"));
							assert.ok(privateTurn !== undefined);
							if (fixture === "compose-counterevidence") {
								assert.ok(
									privateTurn.includes(
										"The helper path is present in the source; its runtime behavior was not executed.",
									),
								);
								assert.ok(privateTurn.includes("Source only; no relaunch result recorded."));
								assert.match(privateTurn, /"startLine": 10/u);
								assert.doesNotMatch(
									privateTurn,
									/"quote"|"verification"|Criteria of `second-practice`/u,
								);
								break;
							}

							if (fixture === "compose-context-unavailable") {
								assert.match(privateTurn, /Criteria of `test-practice` — not staged/u);
								assert.match(privateTurn, /Criteria of `second-practice`[^\n]*is empty/u);
								assert.match(
									privateTurn,
									/read `[^`]*history\/feedback\.json` for the recorded history/u,
								);
								assert.doesNotMatch(
									privateTurn,
									/Unknown historical prose|# Test practice|Long criterion|review\.summary\.body/u,
								);
								break;
							}
							assert.match(
								privateTurn,
								/### Criteria of `second-practice` — too large to show here; read `[^`]*catalog\/practices\/second-practice\.md`/u,
							);
							assert.ok(!privateTurn.includes("Long criterion. Long criterion."));
							assert.ok(privateTurn.includes("# Test practice\nCriteria."));
							if (fixture === "compose-fold") {
								const publicTurn = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
								const limits =
									/### Recorded limits of this review[\s\S]*?```json\n(?<limits>[\s\S]*?)\n```/u.exec(
										publicTurn,
									)?.groups?.limits;
								assert.ok(limits !== undefined);
								for (const text of [limits, privateTurn]) {
									assert.ok(text.includes("Which current request governs?"));
									assert.ok(
										text.includes("An authored resolution of the conflicting current statements."),
									);
									assert.ok(text.includes("The captured interface is unchanged."));
									assert.match(text, /"path": "src\/RequestScope\.java"/u);
									assert.match(text, /"startLine": 41/u);
									assert.match(text, /"endLine": 42/u);
									assert.match(text, /"path": "src\/ExternalInterface\.java"/u);
									assert.match(text, /"startLine": 73/u);
									assert.match(text, /"endLine": 73/u);
									assert.doesNotMatch(
										text,
										/Unwarranted conformance summary|Unwarranted conformance rationale/u,
									);
								}
								assert.ok(limits.includes("+ unresolvedCurrentRequest();"));
								assert.ok(limits.includes("+ unchangedInterface();"));
								assert.ok(!publicTurn.includes("qualification-und"));
								assert.ok(privateTurn.includes('"id": "qualification-und"'));
								assert.doesNotMatch(privateTurn, /"quote"|"verification"/u);
								assert.match(
									events.find((event) => event.startsWith("qualification-support:")) ?? "",
									/qualification-und, which is not one of the observations this review may rest on/u,
								);
								const restored =
									events.find((event) => event.startsWith("qualification-restored:")) ?? "";
								assert.ok(restored.includes("Which current request governs?"));
								assert.ok(restored.includes("The captured interface is unchanged."));
								const feedback: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(feedback) && Array.isArray(feedback.units));
								const unit: unknown = feedback.units[0];
								assert.ok(isRecord(unit));
								assert.deepEqual(unit.basedOn, [
									"observation-1",
									"observation-2",
									"qualification-und",
									"qualification-na",
								]);
							}
							break;
						}
						case "compose-abstention": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3", "prompt:4"],
							);
							// An abstention cannot carry a claim about the work; the strength beside it can, once its
							// whole standard was read in an earlier turn.
							assert.match(
								events.find((event) => event.startsWith("met-read:")) ?? "",
								/### Criteria of `test-practice`[\s\S]*This standard is in view from your next turn/u,
							);
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
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3", "prompt:4", "prompt:5"],
							);
							assert.ok(events.includes("review-retry"));
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
