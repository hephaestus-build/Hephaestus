import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
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

import {
	CANDIDATE_LIMITS,
	compositionRequestSchema,
} from "../../../main/resources/agent/work/practice-scripts/lib/core/contract.ts";

import type {
	AgentSession,
	ExtensionFactory,
	AgentToolResult,
	ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import { isJsonObject as isRecord } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import {
	addModelCall,
	newModelCallTiming,
} from "../../../main/resources/agent/pi-model-failure.ts";
import { z } from "../../../main/resources/agent/work/practice-scripts/lib/core/practice-script.ts";
import { GOLDEN_INPUTS, PRACTICES_DIR } from "./golden-review-workspace.ts";

// Each child process mocks the SDK and drives one isolated review through runReviewJob.

import { mockChange, mockPiCodingAgent } from "./program-host-runner.ts";

const LIB = nodePath.resolve(
	import.meta.dirname,
	"../../../../../../docker/agents/practice-scripts/lib",
);

const SERVER_COMPOSITION_REQUEST = compositionRequestSchema.parse(
	JSON.parse(readFileSync(new URL("feedback-composition-request.json", import.meta.url), "utf8")),
);

const supportOccurrence = (itemKind: string, itemId: number) => ({
	resultId: `support-${itemKind}-${itemId}`,
	itemKind,
	itemId: String(itemId),
	outcome: "NOT_MET",
	origin: "EVENT",
	summary: "A selected negative occurrence",
	recordedAt: "2026-10-07T08:00:00Z",
});

const admittedCitation = {
	index: 0,
	sourceKind: "scm.pull-request.diff",
	sourcePath: "evidence/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
	verification: { status: "VERIFIED", scope: "EXACT_LOCATION" },
	anchorable: true,
};

const admittedChild = (problemId: string, citation: unknown = admittedCitation) => ({
	problemId,
	position: 0,
	severity: "MAJOR",
	summary: "Login calls insecure() on every sign-in",
	explanation: "The added line calls insecure() where the criteria ask for checked().",
	nextStep: null,
	basis: { type: "introduced", head: 0, explanation: "The change adds the call." },
	citations: [citation],
	anchorable: true,
});

const admittedResult = {
	resultId: "00000000-0000-4000-8000-000000000001",
	practiceSlug: "test-practice",
	outcome: "NOT_MET",
	summary: "Added authentication call is insecure",
	explanation: "The changed authentication code calls insecure().",
	summaryOnly: false,
	publicEligible: true,
	citations: [],
	evidence: {
		citations: [],
		search: { consulted: ["scm.pull-request.diff"], lookedFor: "x", boundary: "y" },
	},
	example: null,
	coverage: {
		source: "host",
		reads: 1,
		truncatedReads: 0,
		failedReads: 0,
		receipts: [],
		complete: true,
	},
	problems: [admittedChild("problem-1")],
};

const metResult = (resultId: string, practiceSlug: string) => ({
	...admittedResult,
	resultId,
	practiceSlug,
	outcome: "MET",
	citations: [{ ...admittedCitation, anchorable: false }],
	problems: [],
});

function admissionDto(value: unknown) {
	const row = recordOf(value);
	const { citations, problems, severity: _severity, ...fields } = row;
	return {
		...fields,
		evidence: { ...recordOf(row.evidence), citations },
		problems: listOf(problems).map((child) => {
			const { problemId, ...problem } = recordOf(child);
			return { ...problem, id: problemId };
		}),
	};
}

const PUBLICATION_CONTEXT = `v1:7:${"c".repeat(64)}`;

function historyDto(value: unknown) {
	const response = recordOf(value);
	const history = recordOf(response.history);
	const entries = listOf(history.feedback)
		.map(recordOf)
		.filter(
			(entry) =>
				!["fresh", "body-transport"].includes(process.env.PI_PUBLIC_HISTORY_MODE ?? "") ||
				entry.id === "00000000-0000-4000-8000-000000000001",
		);
	const first = entries[0];
	const item = {
		id: "1",
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
		...(first !== undefined && isRecord(first.item) ? first.item : {}),
	};
	return {
		contractVersion: 4,
		readAt: response.readAt,
		publicationContext:
			typeof response.publicationContext === "string"
				? response.publicationContext
				: PUBLICATION_CONTEXT,
		history: {
			item,
			feedback: entries.map((entry) => ({
				id: entry.id,
				destination: entry.destination,
				state: "DELIVERED",
				deliveredAt: entry.deliveredAt ?? "2026-10-07T10:00:00Z",
				itemVersion: null,
				body: entry.body ?? null,
				bodyUnavailable: entry.wordsUnavailable === "COPY_AUTHORITY_UNAVAILABLE",
				currentness:
					process.env.PI_PUBLIC_HISTORY_MODE === "missing-currentness"
						? undefined
						: (entry.recordedClaimCurrentness ?? "UNVERIFIABLE"),
				withdrawn: entry.withdrawn ?? false,
				basedOn: entry.basedOn ?? [],
				copies: [],
			})),
		},
	};
}

const timing = (calls: number[][], failedElapsedMs: number) => ({
	timedCalls: calls.length,
	respondedCalls: calls.filter((call) => call.length > 1).length,
	elapsedMs: calls.reduce((sum, [elapsed = 0]) => sum + elapsed, 0),
	failedElapsedMs,
	maxElapsedMs: Math.max(0, ...calls.map(([elapsed = 0]) => elapsed)),
	responseMs: calls.reduce((sum, [, response = 0]) => sum + response, 0),
});

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
	sourcePath: "evidence/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

const OVERLONG_SUMMARY = "A summary that runs on ".repeat(8).trim();

/** Words only the person's history holds: they may reach the private destinations and never the review on the work. */
const PRIVATE_HISTORY_SENTENCE = "Earlier reviews told this person about the insecure call twice.";

/** What the staged history establishes, as the server writes it on both history files. */
const HISTORY_COVERAGE = {
	scope: "AUTHORIZED_REVIEW_RECORDS_FOR_SUBJECT",
	workChronology: "NOT_ESTABLISHED",
	communicationHistory: "NOT_ESTABLISHED",
};

const historyBasis = (practiceSlug: string, outcome = "NOT_MET") => [
	{
		resultId: "00000000-0000-4000-8000-000000000301",
		practiceSlug,
		practiceVersionId: null,
		outcome,
	},
];

/** The server's read of this developer's earlier private feedback, answered with the admission. */
const PRIVATE_HISTORY = {
	readAt: "2026-10-07T10:00:00Z",
	coverage: { maxEntries: 30, hasMore: false, omittedWithoutCopyAuthority: 1 },
	delivered: [
		{
			id: "00000000-0000-4000-8000-000000000201",
			destination: "PRACTICE_PAGE",
			deliveredAt: "2026-08-01T10:00:00Z",
			recordedClaimCurrentness: "CURRENT",
			withdrawn: false,
			basedOn: historyBasis("test-practice"),
			bodyRole: "PRACTICE_PAGE_CARD",
			body: "An earlier card about the insecure call.",
		},
		{
			id: "00000000-0000-4000-8000-000000000202",
			destination: "PRACTICE_PAGE",
			deliveredAt: "2026-08-15T10:00:00Z",
			recordedClaimCurrentness: "STALE",
			withdrawn: true,
			basedOn: historyBasis("test-practice"),
			bodyRole: null,
			body: null,
		},
	],
	queued: [
		{
			threadKey: "00000000-0000-4000-8000-000000000101",
			feedbackId: "00000000-0000-4000-8000-000000000101",
			destination: "CONVERSATION",
			practiceSlug: "test-practice",
			preparedAt: "2026-09-01T10:00:00Z",
			recordedClaimCurrentness: "CURRENT",
			basedOn: historyBasis("test-practice"),
			bodyRole: "MENTOR_NOTES",
			body: "Earlier prepared mentor wording.",
		},
	],
};

const STAGED_FEEDBACK_HISTORY = JSON.stringify({
	coverage: HISTORY_COVERAGE,
	recordRole: "RECORDED_DELIVERY",
	feedback: [
		{
			id: "feedback-same-work",
			destination: "ON_WORK",
			publicEligible: true,
			item: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/3",
			},
			deliveredAt: "2026-09-01T10:00:00Z",
			itemVersion: "c".repeat(40),
			basedOn: [
				{
					id: "earlier-1",
					practiceSlug: "test-practice",
					practiceVersion: null,
					outcome: "NOT_MET",
				},
			],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "COMPOSED_GUIDANCE",
			body: "An earlier comment on this same change.",
		},
		{
			id: "feedback-earlier-work",
			destination: "PRACTICE_PAGE",
			item: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/1",
			},
			deliveredAt: "2026-08-01T10:00:00Z",
			itemVersion: null,
			basedOn: [],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "COMPOSED_GUIDANCE",
			body: PRIVATE_HISTORY_SENTENCE,
		},
		{
			id: "feedback-withdrawn",
			destination: "PRACTICE_PAGE",
			item: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/2",
			},
			deliveredAt: "2026-08-15T10:00:00Z",
			itemVersion: "d".repeat(40),
			basedOn: [
				{
					id: "earlier-2",
					practiceSlug: "test-practice",
					practiceVersion: null,
					outcome: "NOT_MET",
				},
			],
			recordedClaimCurrentness: "STALE",
			withdrawn: true,
		},
		{
			id: "feedback-legacy",
			destination: null,
			item: {
				kind: "scm.pull_request",
				url: "https://gitlab.example/group/repo/-/merge_requests/4",
			},
			deliveredAt: "2026-07-01T10:00:00Z",
			itemVersion: null,
			basedOn: [],
			recordedClaimCurrentness: "CURRENT",
			bodyRole: "UNKNOWN",
			body: "A legacy delivered note without a recorded destination.",
		},
	],
});

/** Delivered-feedback IDs the already-said composer names as witnesses; `chat-test` names none on record. */
const WITNESS: Record<string, string> = {
	"app-test": "00000000-0000-4000-8000-000000000211",
	"app-second": "00000000-0000-4000-8000-000000000212",
	"chat-test": "00000000-0000-4000-8000-000000000221",
	"chat-second": "00000000-0000-4000-8000-000000000222",
	"chat-other": "00000000-0000-4000-8000-000000000223",
};

const deliveredRecord = (
	id: string,
	destination: string,
	practiceSlug: string,
	overrides = {},
) => ({
	id,
	destination,
	deliveredAt: "2026-08-01T10:00:00Z",
	recordedClaimCurrentness: "CURRENT",
	withdrawn: false,
	basedOn: historyBasis(practiceSlug),
	bodyRole: destination === "CONVERSATION" ? "CONVERSATION_WORDS" : "PRACTICE_PAGE_CARD",
	body: `Earlier ${destination} feedback about ${practiceSlug}.`,
	...overrides,
});

/** Delivered feedback for every practice but test-practice on CONVERSATION, where only unusable records exist. */
const ALREADY_SAID_HISTORY = {
	readAt: "2026-10-07T10:00:00Z",
	coverage: { maxEntries: 30, hasMore: false, omittedWithoutCopyAuthority: 0 },
	delivered: [
		deliveredRecord(WITNESS["app-test"] ?? "", "PRACTICE_PAGE", "test-practice"),
		deliveredRecord(WITNESS["app-second"] ?? "", "PRACTICE_PAGE", "second-practice"),
		deliveredRecord(WITNESS["chat-second"] ?? "", "CONVERSATION", "second-practice"),
		deliveredRecord(WITNESS["chat-other"] ?? "", "CONVERSATION", "other-practice"),
		deliveredRecord("00000000-0000-4000-8000-000000000231", "CONVERSATION", "test-practice", {
			basedOn: historyBasis("test-practice", "MET"),
		}),
		deliveredRecord("00000000-0000-4000-8000-000000000232", "CONVERSATION", "test-practice", {
			recordedClaimCurrentness: "STALE",
			bodyRole: null,
			body: null,
		}),
		deliveredRecord("00000000-0000-4000-8000-000000000233", "CONVERSATION", "test-practice", {
			withdrawn: true,
			bodyRole: null,
			body: null,
		}),
	],
	queued: [
		{
			threadKey: "00000000-0000-4000-8000-000000000103",
			feedbackId: "00000000-0000-4000-8000-000000000103",
			destination: "CONVERSATION",
			practiceSlug: "test-practice",
			preparedAt: "2026-09-01T10:00:00Z",
			recordedClaimCurrentness: "CURRENT",
			basedOn: historyBasis("test-practice"),
			bodyRole: "MENTOR_NOTES",
			body: "Prepared mentor notes about test-practice.",
		},
	],
};

/** The review composition's whole summary, as the composer wrote it, paragraphs and all. */
const REVIEW_SUMMARY =
	"The login change calls `insecure()` on the path every sign-in takes.\n\n" +
	"Route it through the checked helper so a bad token cannot reach it.";

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

function readFeedbackResults(path: string) {
	const payload: unknown = JSON.parse(readFileSync(path, "utf8"));
	assert.ok(isRecord(payload) && Array.isArray(payload.results));
	const results: unknown[] = payload.results;
	return results.map(recordOf);
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

const withhold = (
	destination: string,
	practiceSlug: string,
	withholdReason: string,
	basedOn = [
		practiceSlug === "test-practice"
			? "00000000-0000-4000-8000-000000000001"
			: "00000000-0000-4000-8000-000000000002",
	],
) => ({
	destination,
	practiceSlug,
	basedOn,
	action: "WITHHOLD",
	withholdReason,
	witnessIds:
		withholdReason === "BELOW_BAR"
			? []
			: basedOn.map((id) => {
					let suffix = "other";
					if (id.endsWith("1")) {
						suffix = "test";
					} else if (id.endsWith("2")) {
						suffix = "second";
					}
					return WITNESS[`${destination === "PRACTICE_PAGE" ? "app" : "chat"}-${suffix}`] ?? "";
				}),
});

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
const unavailableContext = process.env.PI_UNAVAILABLE_CONTEXT === "true";
/** The private composer's response ends, but its compaction stays busy until the run's own deadline. */
const privateHold = process.env.PI_PRIVATE_HOLD === "true";
/** The review composer's response ends, but its compaction stays busy until the run's own deadline. */
const publicHold = process.env.PI_PUBLIC_HOLD === "true";
const counterevidence = process.env.PI_COUNTEREVIDENCE === "true";
const reviewCompacted = process.env.PI_REVIEW_COMPACTED === "true";
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
	await mockChange({ writeChangeView: () => record("derive-change") });
	const secondNotMet = {
		...admittedResult,
		resultId: "00000000-0000-4000-8000-000000000002",
		practiceSlug: "second-practice",
		problems: [admittedChild("problem-2")],
	};
	const abstention = (
		resultId: string,
		practiceSlug: string,
		path: string,
		startLine: number,
		endLine: number,
		quote: string,
	) => ({
		...admittedResult,
		resultId,
		practiceSlug,
		severity: null,
		citations: [{ ...admittedCitation, path, startLine, endLine, quote }],
		problems: [],
	});
	const admitted = (): unknown[] => {
		switch (scenario) {
			case "compose-abstention": {
				const ids = ["test-practice", "second-practice", "third-practice"];
				return readResults(nodePath.join(cwd, "admission.json")).map((posted) => {
					const candidate = recordOf(posted.candidate);
					return {
						...admittedResult,
						...candidate,
						resultId: `00000000-0000-4000-8000-${String(ids.indexOf(String(posted.practiceSlug)) + 1).padStart(12, "0")}`,
						practiceSlug: posted.practiceSlug,
						severity: null,
						citations: listOf(candidate.citations).map((citation, index) => ({
							...recordOf(citation),
							index,
							anchorable: false,
						})),
						problems: [],
					};
				});
			}
			case "compose-already-said": {
				// Two NOT_MET practices, and MET counterevidence of another practice without delivered history.
				return [
					admittedResult,
					secondNotMet,
					metResult("00000000-0000-4000-8000-000000000003", "counter-practice"),
				];
			}
			case "compose-reselect": {
				return [
					admittedResult,
					metResult("00000000-0000-4000-8000-000000000002", "second-practice"),
				];
			}
			case "compose-partial-error":
			case "compose-qualify":
			case "compose-fold": {
				return [
					{ ...admittedResult, publicEligible: !reviewerOnly },
					counterevidence
						? {
								...metResult(
									"00000000-0000-4000-8000-000000000002",
									scenario === "compose-qualify" ? "test-practice" : "second-practice",
								),
								publicEligible: !reviewerOnly,
								explanation:
									"The helper path is present in the source; its runtime behavior was not executed.",
								evidence: {
									search: {
										consulted: ["scm.pull-request.diff"],
										lookedFor: "A recorded relaunch result",
										boundary: "Source only; no relaunch result recorded.",
									},
								},
							}
						: { ...secondNotMet, publicEligible: !reviewerOnly },
					...(scenario === "compose-fold"
						? [
								{
									...abstention(
										"00000000-0000-4000-8000-000000000011",
										"request-scope-practice",
										"src/RequestScope.java",
										41,
										42,
										"+ unresolvedCurrentRequest();",
									),
									outcome: "COULD_NOT_DETERMINE",
									summary: "Unwarranted conformance summary",
									explanation: "Unwarranted conformance rationale",
									evidence: {
										openQuestions: [
											{
												openQuestion: "Which current request governs?",
												wouldSettleIt:
													"An authored resolution of the conflicting current statements.",
											},
										],
									},
								},
								{
									...abstention(
										"00000000-0000-4000-8000-000000000012",
										"external-interface-practice",
										"src/ExternalInterface.java",
										73,
										73,
										"+ unchangedInterface();",
									),
									outcome: "DOES_NOT_APPLY",
									evidence: {
										inapplicability: {
											consulted: ["scm.pull-request.diff"],
											prerequisite: "A changed external interface",
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
				return [
					{
						...admittedResult,
						problems: [admittedChild("problem-1"), admittedChild("problem-1b")],
					},
					{
						...admittedResult,
						resultId: "00000000-0000-4000-8000-000000000010",
						summary: PRIVATE_HISTORY_SENTENCE,
						explanation: PRIVATE_HISTORY_SENTENCE,
						publicEligible: false,
						problems: [
							{
								...admittedChild("problem-history", {
									index: 0,
									sourceKind: "hephaestus.feedback-history",
									path: "history/feedback.json",
									anchorable: false,
								}),
								anchorable: false,
							},
						],
					},
				];
			}
			case "compose-empty": {
				return [metResult("00000000-0000-4000-8000-000000000001", "test-practice")];
			}
			case "compose-unknown-outcome": {
				return [{ ...admittedResult, outcome: "PASSED" }];
			}
			case "compose-null-outcome": {
				return [{ ...admittedResult, outcome: null }];
			}
			default: {
				return [admittedResult];
			}
		}
	};
	let publicHistoryReads = 0;
	let publicCompletionAttempts = 0;
	mock.method(globalThis, "fetch", async (input: unknown, init?: RequestInit) => {
		if (nativeSdk) {
			assert.equal(String(input), "https://unused.invalid/admit-results");
		}
		if (String(input).endsWith("/prepare-feedback")) {
			publicCompletionAttempts += 1;
			const preparationBody = init?.body;
			assert.ok(typeof preparationBody === "string");
			const preparation: unknown = JSON.parse(preparationBody);
			assert.ok(isRecord(preparation));
			assert.equal(typeof preparation.publicComposition, "boolean");
			record(`prepare-public:${String(preparation.publicComposition)}`);
			const mode = process.env.PI_PUBLIC_COMPLETION_MODE;
			if (mode !== undefined) {
				const body = init?.body;
				assert.ok(typeof body === "string");
				record(
					`public-completion:${publicCompletionAttempts}:${createHash("sha256").update(body).digest("hex")}`,
				);
				const submitted: unknown = JSON.parse(body);
				assert.ok(isRecord(submitted));
				assert.equal(submitted.reviewId, "11111111-1111-4111-8111-111111111111");
				assert.ok(Array.isArray(submitted.composition));
				if (submitted.publicComposition === true) {
					record(`public-context:${String(submitted.publicationContext)}`);
				}
			}
			if (mode === "stale" && publicCompletionAttempts === 1) {
				return Response.json({ status: 409, code: "PUBLIC_HISTORY_CHANGED" }, { status: 409 });
			}
			if (mode === "unresolved" && publicCompletionAttempts === 1) {
				return Response.json({ status: 409, code: "PUBLIC_DELIVERY_UNRESOLVED" }, { status: 409 });
			}
			if (mode === "refused") {
				return Response.json(
					{ schemaVersion: 1, accepted: false, reasons: ["Refused"] },
					{ status: 422 },
				);
			}
			if (mode === "public-unavailable" && preparation.publicComposition === true) {
				throw new DOMException("The preparation response was not received", "TimeoutError");
			}
			if (mode === "lost-ack" && publicCompletionAttempts === 1) {
				throw new TypeError("fetch failed");
			}
			return Response.json({
				feedbackIds: [],
				preparedAt: "2026-10-14T12:00:00Z",
				contentDigest: "a".repeat(64),
			});
		}
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
				return Response.json(
					historyDto({
						schemaVersion: 1,
						readAt: "2026-10-07T11:00:00Z",
						history: {
							feedback: [
								{
									id: "00000000-0000-4000-8000-000000000001",
									destination: "ON_WORK",
									publicEligible: true,
									item: {
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
					}),
				);
			}
			if (mode === "unavailable-words" || mode === "missing-currentness") {
				return Response.json(
					historyDto({
						schemaVersion: 1,
						readAt: "2026-10-07T11:00:00Z",
						history: {
							wordsCoverage: "PARTIAL",
							feedback: ["CURRENT", "STALE"].map((currentness, index) => ({
								id: `00000000-0000-4000-8000-00000000000${index + 1}`,
								destination: "ON_WORK",
								publicEligible: true,
								item: {
									kind: "scm.pull_request",
									url: "https://gitlab.example/group/repo/-/merge_requests/3",
								},
								deliveredAt: "2026-10-07T10:00:00Z",
								...(mode === "missing-currentness"
									? {}
									: { recordedClaimCurrentness: currentness }),
								...(index === 1 ? { withdrawn: true } : {}),
								wordsUnavailable: "COPY_AUTHORITY_UNAVAILABLE",
							})),
						},
					}),
				);
			}
			if (mode === "refused") {
				return new Response("Attempt no longer owns the job", { status: 409 });
			}
			if (mode === "fresh" || mode === "body-transport") {
				return Response.json(
					historyDto({
						schemaVersion: 1,
						readAt: "2026-10-07T11:00:00Z",
						history: {
							feedback: [
								{
									id: "00000000-0000-4000-8000-000000000001",
									destination: "ON_WORK",
									publicEligible: true,
									item: {
										kind: "scm.pull_request",
										url: "https://gitlab.example/group/repo/-/merge_requests/3",
									},
									deliveredAt: "2026-10-07T10:00:00Z",
									recordedClaimCurrentness: "CURRENT",
									body: "Fresh delivered communication",
								},
								{
									id: "00000000-0000-4000-8000-000000000002",
									destination: "ON_WORK",
									publicEligible: true,
									item: {
										kind: "scm.pull_request",
										url: "https://gitlab.example/group/repo/-/merge_requests/3",
									},
									deliveredAt: null,
									recordedClaimCurrentness: "CURRENT",
									body: "Delivered with unknown time",
								},
								...[{ kind: "scm.pull_request" }, { kind: "scm.pull_request", url: null }].map(
									(item, index) => ({
										id: `00000000-0000-4000-8000-00000000000${index + 3}`,
										destination: "ON_WORK",
										publicEligible: true,
										item,
										deliveredAt: "2026-10-07T08:00:00Z",
										recordedClaimCurrentness: "CURRENT",
										body: "Delivery with unknown work link",
									}),
								),
							],
						},
					}),
				);
			}
			const path = nodePath.join(cwd, "history/feedback.json");
			const history: unknown = existsSync(path)
				? JSON.parse(readFileSync(path, "utf8"))
				: { feedback: [] };
			assert.ok(isRecord(history) && Array.isArray(history.feedback));
			return Response.json(
				historyDto({
					schemaVersion: 1,
					readAt: "2026-10-07T11:00:00Z",
					// Each read names its own response, so a stale package must come back with the newer one.
					...(process.env.PI_PUBLIC_COMPLETION_MODE === "stale"
						? { publicationContext: `v1:7:${(publicHistoryReads === 1 ? "c" : "d").repeat(64)}` }
						: {}),
					history: {
						...history,
						feedback: history.feedback.filter(
							(entry: unknown) =>
								isRecord(entry) && entry.destination === "ON_WORK" && entry.publicEligible === true,
						),
					},
				}),
			);
		}
		assert.ok(String(input).endsWith("/admit-results"), String(input));
		record("admit");
		assert.ok(typeof init?.body === "string");
		writeFileSync(nodePath.join(cwd, "admission.json"), init.body);
		if (scenario === "compose-settle-deadline") {
			now = 1_000_000 + Number(process.env.AGENT_BUDGET_MS) - 200;
		}
		if (scenario === "compose-private-safety") {
			// Room for the review on the work, which this mock answers at once, before the private composition.
			now = 1_000_000 + Number(process.env.AGENT_BUDGET_MS) - 2000;
		}
		const rows = admitted();
		const supportMode = process.env.PI_PRACTICE_PAGE_SUPPORT_MODE;

		let selected: ReturnType<typeof supportOccurrence>[] = [];
		if (
			scenario === "compose" ||
			scenario === "compose-foreign-provider" ||
			supportMode === "selected" ||
			supportMode === "mentor-unavailable" ||
			supportMode === "routing-missing"
		) {
			selected = [supportOccurrence("scm.pull_request", 3), supportOccurrence("scm.issue", 3)];
		} else if (supportMode === "duplicate") {
			selected = [
				supportOccurrence("scm.pull_request", 3),
				supportOccurrence("scm.pull_request", 3),
			];
		}

		const pageAdmission = new Map([
			["cooldown", { decision: "RECENTLY_SURFACED", stopReason: "NOTHING_NEW" }],
			["inline", { decision: "UNCORROBORATED", stopReason: "UNCORROBORATED" }],
		]).get(supportMode ?? "");
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
			].map((practiceSlug) =>
				pageAdmission === undefined
					? { practiceSlug, decision: "ADMIT", stopReason: null, occurrences: selected }
					: { practiceSlug, ...pageAdmission, occurrences: [] },
			),
		};
		const chatRouting =
			supportMode === "routing-missing"
				? undefined
				: {
						state: "COMPLETE",
						readAt: complete.readAt,
						results: rows.flatMap((row) => {
							if (!isRecord(row) || row.outcome !== "NOT_MET") {
								return [];
							}
							const inline =
								supportMode === "inline" ||
								(supportMode === "first-inline" && row.practiceSlug === "test-practice");
							let decision = "ADMIT";
							let stopReason: string | null = null;
							if (supportMode === "mentor-unavailable") {
								decision = "MENTOR_UNAVAILABLE";
								stopReason = "RECIPIENT_UNAVAILABLE";
							} else if (inline) {
								decision = "HAS_INLINE_ANCHOR";
								stopReason = "NOTHING_NEW";
							}
							return [
								{
									resultId: row.resultId,
									decision,
									stopReason,
								},
							];
						}),
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
						decision: "ADMIT",
						stopReason: null,
						occurrences: [{ ...supportOccurrence("scm.issue", 1), practiceVersionId: "unknown" }],
					},
				],
			};
		}

		// The server's private history read for this fixture, if it made one.
		const privateHistoryPath = nodePath.join(cwd, "private-history.json");
		const privateHistory: unknown = existsSync(privateHistoryPath)
			? JSON.parse(readFileSync(privateHistoryPath, "utf8"))
			: null;
		return Response.json({
			contractVersion: 4,
			admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			publicReviewed: false,
			privateProgress: {
				decidedResultIds: [],
				practicePageDeliveries: 0,
				conversationDeliveries: 0,
			},
			admitted: rows.map(admissionDto),
			withheld: [],
			practicePageSupport: support ?? null,
			conversationEligibility: chatRouting ?? null,
			privateHistory,
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
							if (
								scenario === "compose-request-caps" &&
								(options.tools.includes("report_review") ||
									options.tools.includes("report_feedback"))
							) {
								// Each request asks for the model's ceiling; the turn grants only what it has left.
								const phase = options.tools.includes("report_review") ? "public" : "private";
								const request = () => {
									try {
										agent.streamFunction({ maxTokens: 16_384 }, {}, { maxTokens: 16_384 });
									} catch {
										record(`stream-request-refused:${phase}`);
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
							if (
								text.includes("## The review to write") ||
								text.includes("The review leaves these NOT_MET results undecided") ||
								text.includes("The review on this work is not final yet")
							) {
								assert.ok(
									admitted().some(
										(row) =>
											isRecord(row) && row.publicEligible === true && row.outcome === "NOT_MET",
									),
								);
								assert.deepEqual(options.tools, ["report_review"]);
								assert.deepEqual(
									options.customTools.map((custom) => custom.name),
									["report_review"],
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
											// The public error keeps chat-completions names; the recovered call fails as a
											// Responses call whose failed terminal event arrived after its response.
											diagnostics:
												scenario === "compose-public-error"
													? [
															{
																type: "openai_completions_failure",
																timestamp: 1_760_000_000_000,
																details: { kind: "HTTP_ERROR", phase: "request", status: 503 },
															},
															// The call never got its response, so it states no response time.
															{
																type: "openai_completions_call",
																timestamp: 1_760_000_000_000,
																details: { elapsedMs: 1200 },
															},
														]
													: [
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
								const review = tool("report_review");
								if (
									text.includes("The review leaves") ||
									text.includes("The review on this work is not final yet")
								) {
									record("review-retry");
									return;
								}
								if (scenario === "compose-settle-deadline" || publicHold) {
									// A whole review is refused, and then the response ends while its auto-compaction
									// remains busy until the deadline: no final review is ever stored.
									const refused = await review
										.execute("r-d", { decisions: [] })
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
								if (scenario === "compose-invalid-select") {
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
											.map((message) => ["report_review"].filter((name) => message.includes(name)));
									const said = {
										decisions: [
											{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
										],
										summary: {
											body: REVIEW_SUMMARY,
											basedOn: ["00000000-0000-4000-8000-000000000001"],
										},
									};
									const decidesNothing = { decisions: [] };
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
									record(`recover-refused:${await attempt("r-r", { decisions: [] })}`);
									return;
								}
								if (scenario === "compose-qualify" && reviewCompacted) {
									const raised = {
										decisions: [
											{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
										],
										summary: {
											body: REVIEW_SUMMARY,
											basedOn: ["00000000-0000-4000-8000-000000000001"],
										},
									};
									record(
										`mixed-met:${await attempt("r-met", {
											...raised,
											summary: {
												body: REVIEW_SUMMARY,
												basedOn: [
													"00000000-0000-4000-8000-000000000001",
													"00000000-0000-4000-8000-000000000002",
												],
											},
										})}`,
									);
									emit({
										type: "compaction_end",
										reason: "threshold",
										result: undefined,
										aborted: false,
									});
									writeFileSync(
										nodePath.join(cwd, "compacted-refused.txt"),
										await attempt("r-c1", raised),
									);
									writeFileSync(
										nodePath.join(cwd, "restore-batch-refused.txt"),
										await attempt("r-c2", raised),
									);
									emit({ type: "turn_start" });
									record(`compacted-stored:${await attempt("r-c3", raised)}`);
									return;
								}
								if (scenario === "compose-qualify") {
									const before = readFileSync(nodePath.join(cwd, "out/review-state.json"), "utf8");
									// A review that leaves the second problem undecided is refused whole; the corrected
									// whole review is stored.
									writeFileSync(
										nodePath.join(cwd, "qualification-refused.txt"),
										await attempt("r-q1", {
											decisions: [
												{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
											],
											summary: {
												body: REVIEW_SUMMARY,
												basedOn: ["00000000-0000-4000-8000-000000000001"],
											},
										}),
									);
									record(
										`qualification-stored:${await attempt("r-q", {
											summary: {
												body: REVIEW_SUMMARY,
												basedOn: ["00000000-0000-4000-8000-000000000001"],
											},
											decisions: [
												{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
												{
													resultId: "00000000-0000-4000-8000-000000000002",
													disposition: "BELOW_BAR",
												},
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
										decisions: [
											{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
										],
										summary: {
											body: REVIEW_SUMMARY,
											basedOn: ["00000000-0000-4000-8000-000000000001"],
										},
									};
									await this.steer();
									record(`reselect-incomplete:${await attempt("r-0", { decisions: [] })}`);
									record(`reselect-before-final:${JSON.stringify(await this.agent.finishTurn())}`);
									record(`reselect-retained:${this.pendingMessageCount}`);
									record(
										`reselect-unwitnessed:${await attempt("r-1", {
											decisions: [
												{
													resultId: "00000000-0000-4000-8000-000000000001",
													disposition: "ALREADY_SAID",
												},
											],
										})}`,
									);
									record(
										`reselect-met:${await attempt("r-met", {
											...said,
											summary: {
												body: REVIEW_SUMMARY,
												basedOn: [
													"00000000-0000-4000-8000-000000000001",
													"00000000-0000-4000-8000-000000000002",
												],
											},
										})}`,
									);
									record(`reselect-final:${await attempt("r-2", said)}`);
									record(`reselect-terminal:${JSON.stringify(await this.agent.finishTurn())}`);
									record(`reselect-drained:${this.pendingMessageCount}`);
									// One response can carry more calls after the final one; none of them is accepted.
									record(`reselect-after-review:${await attempt("r-3", said)}`);
									return;
								}
								if (scenario === "compose-complete-error") {
									record(
										`complete-final:${await attempt("r-e", {
											decisions: [
												{
													resultId: "00000000-0000-4000-8000-000000000001",
													disposition: "BELOW_BAR",
												},
											],
										})}`,
									);
									emit({
										type: "message_end",
										message: { role: "assistant", stopReason: "error", content: [] },
									});
									return;
								}
								if (scenario === "compose" || scenario === "compose-foreign-provider") {
									record(
										`review-refused:${await attempt("r-1", {
											decisions: [
												{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
											],
											summary: {
												body: "Fine. <!-- marker -->",
												basedOn: ["00000000-0000-4000-8000-000000000001"],
											},
											inline: [
												{
													body: "On this line.",
													basedOn: ["problem-1"],
													anchor: { problemId: "problem-1b", citationIndex: 0 },
												},
											],
										})}`,
									);
									record(
										`review-history:${await attempt("r-h", {
											decisions: [
												{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
											],
											summary: {
												body: "A sentence.",
												basedOn: ["00000000-0000-4000-8000-000000000010"],
											},
										})}`,
									);
									record(
										`review-stored:${await attempt("r-2", {
											decisions: [
												{ resultId: "00000000-0000-4000-8000-000000000001", disposition: "RAISE" },
											],
											summary: {
												body: REVIEW_SUMMARY,
												basedOn: ["00000000-0000-4000-8000-000000000001"],
											},
											inline: [
												{
													body: "`insecure()` runs here before the token is checked; call the checked helper instead.",
													basedOn: ["problem-1"],
													anchor: { problemId: "problem-1", citationIndex: 0 },
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
										? [
												"00000000-0000-4000-8000-000000000001",
												"00000000-0000-4000-8000-000000000002",
											]
										: ["00000000-0000-4000-8000-000000000001"];
								const decisions = ids.map((resultId) => ({
									resultId,
									disposition: "BELOW_BAR",
								}));
								if (scenario === "compose-fold" && !reviewerOnly) {
									record(
										`qualification-support:${await attempt("r-limits", {
											decisions,
											summary: {
												body: "A limit is not a concern.",
												basedOn: ["00000000-0000-4000-8000-000000000011"],
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
									// A file a session leaves where the runner stages history is not history.
									mkdirSync(nodePath.join(cwd, "work/composition/history"), { recursive: true });
									writeFileSync(
										nodePath.join(cwd, "work/composition/history/feedback.json"),
										JSON.stringify({ unexpected: "Unknown historical prose" }),
									);
								}
								return;
							}
							if (text.includes("## Undecided")) {
								if (scenario === "compose-no-decision") {
									return;
								}
								const withheld = await tool("report_feedback").execute("f-9", {
									feedback: [
										{
											destination: "PRACTICE_PAGE",
											practiceSlug: "test-practice",
											action: "WITHHOLD",
											withholdReason: "BELOW_BAR",
										},
										{
											destination: "PRACTICE_PAGE",
											practiceSlug: "test-practice",
											basedOn: ["00000000-0000-4000-8000-000000000001"],
											action: "WITHHOLD",
											withholdReason: "BELOW_BAR",
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
										feedback: [
											{
												destination: "PRACTICE_PAGE",
												practiceSlug: "test-practice",
												basedOn: ["problem-1"],
												action: "NEW",
												title: "A repeated problem",
												body: "The recurring problem",
												nextStep: "Check the boundary",
											},
											{
												destination: "CONVERSATION",
												practiceSlug: "test-practice",
												basedOn: ["problem-1"],
												action: "NEW",
												title: "Discuss the boundary",
												notes: {
													situation: "Current problem",
													capability: "Check the boundary",
													evidenceSummary: "The admitted occurrence",
													inConversationLearning: "A boundary discussion",
												},
											},
										],
									});
									record(`support-items:${JSON.stringify(reply)}`);
									if (
										process.env.PI_PRACTICE_PAGE_SUPPORT_MODE === "empty" ||
										process.env.PI_PRACTICE_PAGE_SUPPORT_MODE === "duplicate"
									) {
										await feedback.execute("below", {
											feedback: [
												{
													destination: "PRACTICE_PAGE",
													practiceSlug: "test-practice",
													basedOn: ["problem-1"],
													action: "WITHHOLD",
													withholdReason: "BELOW_BAR",
												},
											],
										});
									}
									return;
								}
								if (scenario === "draft-commit") {
									assert.ok(!options.customTools.some((item) => item.name === "submit_result"));
									assert.ok(measuredTool);
									const closedTool = measuredTool;
									const before = [stateText(), notesText()];
									await assert.rejects(
										async () =>
											closedTool.execute("after-admission", notMet("Forbidden late replacement")),
										/Measurement is closed/u,
									);
									assert.deepEqual([stateText(), notesText()], before);
									record("draft-closed:Measurement is closed; result and notes unchanged");
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
										feedback: [
											{
												destination: "PRACTICE_PAGE",
												practiceSlug: "test-practice",
												basedOn: ["problem-1"],
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
										feedback: [
											{
												destination: "PRACTICE_PAGE",
												practiceSlug: "test-practice",
												basedOn: ["problem-1"],
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
								if (
									scenario === "compose-public-error" ||
									scenario === "compose-recovered-error" ||
									scenario === "compose-complete-error"
								) {
									await tool("report_feedback").execute("paid", {
										feedback: [
											{
												destination: "PRACTICE_PAGE",
												practiceSlug: "test-practice",
												basedOn: ["problem-1"],
												action: "WITHHOLD",
												withholdReason: "BELOW_BAR",
											},
										],
									});
									return;
								}
								if (
									scenario === "compose-settle-deadline" ||
									scenario === "compose-private-safety" ||
									privateHold
								) {
									// The response ended, but its auto-compaction remains busy until the deadline.
									compacting = true;
									return;
								}
								if (scenario === "compose-already-said") {
									const feedback = tool("report_feedback");
									const first = await feedback.execute("said", {
										feedback: [
											withhold("CONVERSATION", "second-practice", "NO_MATERIAL_CHANGE", [
												"00000000-0000-4000-8000-000000000002",
												"00000000-0000-4000-8000-000000000001",
											]),
											withhold("CONVERSATION", "test-practice", "ALREADY_SAID"),
											withhold("PRACTICE_PAGE", "test-practice", "ALREADY_SAID"),
										],
									});
									record(`said-first:${JSON.stringify(first)}`);
									const second = await feedback.execute("said-again", {
										feedback: [
											withhold("CONVERSATION", "test-practice", "NO_MATERIAL_CHANGE"),
											withhold("CONVERSATION", "second-practice", "NO_MATERIAL_CHANGE", [
												"00000000-0000-4000-8000-000000000002",
											]),
											withhold("CONVERSATION", "test-practice", "BELOW_BAR"),
										],
									});
									record(`said-second:${JSON.stringify(second)}`);
									return;
								}
								if (scenario === "compose-fold") {
									const pieces = admitted()
										.map(recordOf)
										.filter((row) => row.outcome === "NOT_MET")
										.map((row) => ({
											destination: "PRACTICE_PAGE",
											practiceSlug: row.practiceSlug,
											basedOn: [row.resultId],
											action: "WITHHOLD",
											withholdReason: "BELOW_BAR",
										}));
									const folded = await tool("report_feedback").execute("f-fold", {
										feedback: pieces,
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
								// The private composition sees the person's history and the accepted public review.
								record(
									`private-turn history=${String(text.includes(PRIVATE_HISTORY_SENTENCE))} review=${String(text.includes("The review composed for this work"))}`,
								);
								const card = {
									destination: "PRACTICE_PAGE",
									practiceSlug: "test-practice",
									basedOn: ["problem-1"],
									action: "NEW",
									title: "Insecure call",
									body: "The pattern across your work.",
									nextStep: "Check the call before pushing.",
								};
								const feedback = tool("report_feedback");
								const schema = JSON.stringify(feedback.parameters);
								for (const keyword of ["maxLength", "additionalProperties", "minItems", "anyOf"]) {
									assert.ok(
										schema.includes(`"${keyword}"`),
										`${keyword} in ${schema.slice(0, 200)}`,
									);
								}
								assert.match(schema, /"enum":\["PRACTICE_PAGE"\]/u);
								assert.match(
									schema,
									/"required":\["destination","practiceSlug","basedOn","action"/u,
								);
								const historical = await feedback
									.execute("historical", {
										feedback: [{ ...card, basedOn: ["support-scm.issue-3"] }],
									})
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-historical:${historical}`);
								const reply = await feedback.execute("f-1", {
									feedback: [
										{ ...card, destination: "PRACTICE_PAGE", basedOn: ["problem-1"] },
										{
											destination: "PRACTICE_PAGE",
											practiceSlug: "test-practice",
											action: "NEW",
											basedOn: [],
										},
										{ ...card, verdict: "BAD" },
										{ ...card, body: "x".repeat(8001) },
										{ ...card, destination: "CONVERSATION", withholdReason: "NOT_A_REASON" },
										{ ...card, practiceSlug: "other-practice" },
										{ ...card, destination: "ON_WORK", body: undefined },
										"a piece as a string",
									],
								});
								record(`feedback:${JSON.stringify(reply)}`);
								const bare = await feedback
									.execute("f-2", {
										feedback: {
											...card,
											destination: "CONVERSATION",
											body: undefined,
											nextStep: undefined,
											notes: {
												situation: "s",
												capability: "c",
												evidenceSummary: "e",
												inConversationLearning: "i",
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
							if (scenario === "compose-abstention") {
								const own = new Map<string, unknown>([
									["test-practice", met("Authentication call goes through the helper")],
									[
										"second-practice",
										undecided(["scm.pull-request.core", "scm.pull-request.diff"]),
									],
									[
										"third-practice",
										{
											outcome: "COULD_NOT_DETERMINE",
											summary: "Whether the call is reachable",
											explanation: "The change adds the call; its callers are outside the change.",
											citations: [changeCitation],
											evidence: {
												openQuestions: [
													{
														openQuestion: "Is insecure() reachable from the login flow?",
														wouldSettleIt: "The callers of Auth outside this change.",
													},
												],
											},
										},
									],
								]).get(currentSlug);
								assert.ok(own !== undefined);
								await report.execute("o-mixed", own);
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
			const first = !existsSync(nodePath.join(cwd, "system-prompt.md"));
			const privateComposition = options.systemPrompt.includes("Compose from admitted problems.");
			if (first) {
				writeFileSync(nodePath.join(cwd, "system-prompt.md"), options.systemPrompt);
			} else {
				writeFileSync(
					nodePath.join(
						cwd,
						privateComposition ? "private-system-prompt.md" : "review-system-prompt.md",
					),
					options.systemPrompt,
				);
				record(`review-loader extensions=${String(options.extensionFactories?.length)}`);
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
	for (const fixture of [
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
		"compose-private-safety",
		"compose-private-only-safety",
		"compose-private-unacknowledged-public",
		"compose-request-caps",
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
		"compose-support-mentor-unavailable",
		"compose-support-empty",
		"compose-support-duplicate",
		"compose-support-missing",
		"compose-support-malformed",
		"compose-support-invalid-optional",
		"compose-support-unavailable",
		"compose-support-refused",
		"compose-support-refused-only",
		"compose-support-cooldown",
		"compose-support-inline",
		"compose-support-routing-missing",
		"compose",
		"compose-completion-accepted",
		"compose-completion-refused",
		"compose-completion-lost-ack",
		"compose-completion-stale",
		"compose-completion-unresolved",
		"compose-continuation",
		"compose-continuation-reviewed",
		"compose-continuation-private",
		"compose-continuation-private-prepared",
		"compose-continuation-private-safety",
		"compose-continuation-public-safety",
		"compose-continuation-stale",
		"compose-continuation-unresolved",
		"compose-fresh-history",
		"compose-history-unavailable-words",
		"compose-history-missing-currentness",
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
		"compose-empty",
		"compose-reselect",
		"compose-qualify",
		"compose-compacted",
		"compose-recover",
		"compose-invalid-select",
		"compose-fold",
		"compose-already-said",
		"compose-already-said-inline",
		"compose-context-unavailable",
		"compose-counterevidence",
		"compose-reviewer-only",
		"compose-abstention",
		"compose-unknown-outcome",
		"compose-null-outcome",
	]) {
		const stage =
			new Map([
				["compose-continuation-reviewed", "compose-continuation"],
				["compose-continuation-private", "compose-continuation"],
				["compose-continuation-private-prepared", "compose-continuation"],
				["compose-continuation-private-safety", "compose-continuation"],
				["compose-continuation-public-safety", "compose-continuation"],
				["compose-private-only-safety", "compose-private-safety"],
				["compose-private-unacknowledged-public", "compose-private-safety"],
				["compose-continuation-stale", "compose-continuation"],
				["compose-continuation-unresolved", "compose-continuation"],
				["compose-completion-accepted", "compose"],
				["compose-completion-refused", "compose"],
				["compose-completion-lost-ack", "compose"],
				["compose-completion-stale", "compose"],
				["compose-completion-unresolved", "compose"],
				["compose-support-selected", "compose-support"],
				["compose-support-mentor-unavailable", "compose-support"],
				["compose-support-empty", "compose-support"],
				["compose-support-duplicate", "compose-support"],
				["compose-support-missing", "compose-support"],
				["compose-support-malformed", "compose-support"],
				["compose-support-invalid-optional", "compose-support"],
				["compose-support-unavailable", "compose-support"],
				["compose-support-refused", "compose-support"],
				["compose-support-refused-only", "compose-support"],
				["compose-support-cooldown", "compose-support"],
				["compose-support-inline", "compose-support"],
				["compose-support-routing-missing", "compose-support"],
				["compose-already-said-inline", "compose-already-said"],
				["compose-loop-rejection", "compose-loop"],
				["compose-compacted", "compose-qualify"],
				["compose-fresh-history", "compose"],
				["compose-history-unavailable-words", "compose"],
				["compose-history-missing-currentness", "compose"],
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
				"compose-late-usage":
					"public session settlement remains subscribed for its final native usage event",
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
				"compose-settle-deadline":
					"does not ask the composer once more after the run reaches its safety ceiling",
				"compose-private-safety":
					"owes private feedback the run stopped at its safety ceiling after the public review was prepared",
				"compose-private-unacknowledged-public":
					"leaves a lost public preparation response for the server to decide after a private safety stop",
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
				compose:
					"uses selected native issue and merge request identities while retaining current-only piece grounding",
				"compose-foreign-provider":
					"keeps distinct native work identities separate from provider URL text",
				"compose-continuation-reviewed":
					"an accepted public review is never recomposed by its continuation",
				"compose-continuation-private":
					"continues private feedback after an accepted public review when no private piece is prepared",
				"compose-continuation":
					"continues an admitted review by composing the review on the work and then its private feedback, measuring and admitting nothing",
				"compose-continuation-private-prepared":
					"continues an admitted review whose private practices are all decided by composing only the review on the work, with no private failure",
				"compose-continuation-unresolved":
					"continues an admitted review whose public delivery stays unresolved without composing it again",
				"compose-continuation-private-safety":
					"owes the private feedback a continuation's own ceiling stopped after the review it composed was prepared",
				"compose-continuation-public-safety":
					"ends a continuation whose own ceiling stopped the review on the work, without another attempt",
				"compose-private-only-safety":
					"ends a run without a requested review on the work whose own ceiling stopped private feedback",
				"compose-request-caps":
					"grants each composer request no more output than its turn has left, and no request once it is spent",
				"compose-overflow":
					"compacts the session before a composition prompt that would not fit beside what it holds",
				"compose-silent":
					"asks once more when the composition ended without a recording call and negatives await a decision",
				"compose-loop":
					"ends a composition that keeps calling a recording tool without recording anything",
				"compose-empty":
					"composes no review on the work when its only public result is MET, and records no failure",
				"measure-context-compact":
					"restores the exact criterion before illustrations and leads when native turn preparation compacts",
				"measure-context-missing":
					"keeps a missing criterion explicit without changing the recording contract",
				"compose-qualify":
					"refuses a whole review that leaves a problem undecided and stores the corrected one, final results unchanged",
				"compose-compacted":
					"refuses a text on a MET result, restores the review turn after a compaction, and stores a review only from the next turn",
				"compose-reselect":
					"stores only the corrected whole review after refusals, a text on a MET result among them, and nothing after it",
				"compose-recover": "asks once more for a review left unfinished after a refused one",
				"compose-invalid-select":
					"refuses a repeated whole review that decides nothing until a corrected one is stored",
				"compose-reviewer-only":
					"reviewer-only concerns start no public writer alongside public abstentions and remain available to private feedback",
				"compose-counterevidence":
					"private counterevidence retains MET source qualifications and coordinates without quoted or verified-runtime claims",
				"compose-context-unavailable":
					"keeps missing and empty standards and unknown history explicit without inventing their contents",
				"compose-fold": "keeps one practice per card and leaves non-NOT_MET records as context",
				"compose-already-said":
					"withholds as already said only on the destination and practice that captured delivered feedback records",
				"compose-already-said-inline":
					"refuses even a withholding on an open destination for a practice the server routed elsewhere",
				"compose-support-cooldown":
					"composes conversation notes for an unanchored problem while the page's cooldown closes its destination",
				"compose-support-inline":
					"opens no private session when every destination refuses the practice, keeping the review and every result",
				"compose-support-routing-missing":
					"closes only the conversation destination, as a failure, when its routing read is missing",
				"compose-abstention":
					"composes no review on the work from a MET result and abstentions, and keeps every admitted result",
				"compose-unknown-outcome":
					"refuses an admitted outcome outside the vocabulary before composing",
				"compose-null-outcome": "refuses an admitted result without an outcome before composing",
			}[fixture] ?? fixture,
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
					writeFileSync(
						nodePath.join(cwd, "feedback-composer.md"),
						"Compose from admitted problems.",
					);
					writeFileSync(nodePath.join(cwd, "review-composer.md"), "Write the review on the work.");
					writeFileSync(nodePath.join(cwd, "feedback-style.md"), "Write clear, useful feedback.");
					// What the mocked server answers: the private history with the admission (private-history.json), and
					// the public history it reads from history/feedback.json.
					if (stage === "compose" || stage === "compose-foreign-provider") {
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(nodePath.join(cwd, "history/feedback.json"), STAGED_FEEDBACK_HISTORY);
						writeFileSync(
							nodePath.join(cwd, "private-history.json"),
							JSON.stringify(PRIVATE_HISTORY),
						);
					}
					if (fixture === "compose-context-unavailable") {
						// A read that found nothing: present and empty, unlike a read never made.
						writeFileSync(
							nodePath.join(cwd, "private-history.json"),
							JSON.stringify({ ...PRIVATE_HISTORY, delivered: [], queued: [] }),
						);
					}
					if (stage === "compose-already-said") {
						writeFileSync(
							nodePath.join(cwd, "private-history.json"),
							JSON.stringify(ALREADY_SAID_HISTORY),
						);
					}
					if (stage === "compose-support") {
						mkdirSync(nodePath.join(cwd, "history"), { recursive: true });
						writeFileSync(
							nodePath.join(cwd, "history/problems.json"),
							JSON.stringify({
								problems: [
									{
										practiceSlug: "test-practice",
										outcome: "NOT_MET",
										item: {
											kind: "scm.issue",
											url: "https://gitlab.example/group/repo/-/issues/1",
										},
									},
									{
										practiceSlug: "test-practice",
										outcome: "NOT_MET",
										item: {
											kind: "scm.issue",
											url: "https://gitlab.example/group/repo/-/issues/2",
										},
									},
								],
							}),
						);
					}
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
					mkdirSync(nodePath.join(cwd, PRACTICES_DIR), { recursive: true });
					if (stage === "batch" || stage.startsWith("measure-context-")) {
						writeFileSync(nodePath.join(cwd, "package.json"), '{"type":"module"}\n');
						symlinkSync(LIB, nodePath.join(cwd, nodePath.dirname(PRACTICES_DIR), "lib"));
						writeFileSync(nodePath.join(cwd, PRACTICES_DIR, "test-practice.ts"), LEADS_PROGRAM);
					}
					if (stage.startsWith("compose") || stage === "draft-commit") {
						const onWorkCopyForms = stage === "compose-continuation" ? [] : ["FILE_NOTE"];
						if (stage === "compose" || stage === "compose-foreign-provider") {
							onWorkCopyForms.push("LINE_NOTE");
						}
						writeFileSync(
							nodePath.join(cwd, "evidence/composition.json"),
							JSON.stringify(
								fixture === "compose"
									? SERVER_COMPOSITION_REQUEST
									: {
											...SERVER_COMPOSITION_REQUEST,
											destinations: {
												PRACTICE_PAGE: { maxPieces: 1 },
												...(fixture === "compose-private-only-safety"
													? {}
													: { ON_WORK: { maxPieces: 1 } }),
												...((stage === "compose-support" || stage === "compose-already-said") &&
												fixture !== "compose-support-refused-only"
													? {
															CONVERSATION: {
																maxPieces: 1,
															},
														}
													: {}),
											},
											onWorkCopyForms,
										},
							),
						);
					}
					if (stage === "compose-continuation") {
						// What an earlier attempt's admission answered, staged by the server beside the request.
						const continued = admissionDto(admittedResult);
						writeFileSync(
							nodePath.join(cwd, "evidence/composition-continuation.json"),
							JSON.stringify({
								contractVersion: 4,
								admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
								publicReviewed: [
									"compose-continuation-reviewed",
									"compose-continuation-private",
								].includes(fixture),
								// An earlier attempt's accepted card decides the one NOT_MET practice and uses the page's room.
								privateProgress: [
									"compose-continuation-reviewed",
									"compose-continuation-private-prepared",
								].includes(fixture)
									? {
											decidedResultIds: [admittedResult.resultId],
											practicePageDeliveries: 1,
											conversationDeliveries: 0,
										}
									: { decidedResultIds: [], practicePageDeliveries: 0, conversationDeliveries: 0 },
								admitted: [continued],
								withheld: [],
								// The server's reread for this attempt: the page is routed, the conversation is not.
								practicePageSupport:
									fixture === "compose-continuation-reviewed"
										? null
										: {
												state: "COMPLETE",
												readAt: "2026-10-07T10:00:00Z",
												practices: [
													{
														practiceSlug: admittedResult.practiceSlug,
														decision: "ADMIT",
														stopReason: null,
														occurrences: [
															supportOccurrence("scm.pull_request", 3),
															supportOccurrence("scm.issue", 3),
														],
													},
												],
											},
								conversationEligibility:
									fixture === "compose-continuation-reviewed"
										? null
										: {
												state: "COMPLETE",
												readAt: "2026-10-07T10:00:00Z",
												results: [
													{
														resultId: admittedResult.resultId,
														decision: "GATE_REFUSED",
														stopReason: "NO_AI",
													},
												],
											},
							}),
						);
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
							itemKind: "scm.pull_request",
							// NO_MATERIAL_CHANGE needs advice delivered before this capture time; without one it has no witness.
							...(fixture === "compose-fresh-history" ||
							fixture === "compose-history-body-transport" ||
							stage === "compose-already-said"
								? { capturedAt: "2026-10-07T09:00:00Z" }
								: {}),
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
					if (stage === "compose") {
						index = [practice("test-practice", undefined, "42")];
					} else if (stage === "compose-foreign-provider") {
						index = [practice("test-practice", undefined, "42")];
					}
					if (
						stage.startsWith("scope-") ||
						stage === "finish" ||
						stage === "settle-safety" ||
						stage === "session-init" ||
						stage === "batch" ||
						stage === "draft-commit" ||
						stage === "compose-fold" ||
						stage === "compose-already-said" ||
						stage === "compose-qualify" ||
						stage === "compose-reselect" ||
						stage === "compose-abstention"
					) {
						index = [
							practice("test-practice"),
							practice("second-practice"),
							...(stage === "compose-abstention" || stage === "finish"
								? [practice("third-practice")]
								: []),
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
							schemaVersion: 6,
							reviewId: "11111111-1111-4111-8111-111111111111",
							jobId: "22222222-2222-4222-8222-222222222222",
							attempt: 1,
							workspaceId: "1",
							contextRoot: "evidence",
							repositoryRoot: "repos/primary",
							manifest: "evidence/manifest.json",
							practiceIndex: "catalog/practices/index.json",
							compositionRequest: "evidence/composition.json",
							preparedFeedback: "history/prepared.json",
							practiceScripts: PRACTICES_DIR,
							prompt: "Review the practice.",
							repositoryFullName: "group/repo",
							pullRequestNumber: 3,
						}),
					);
					// A continuation measures nothing: its whole budget is composition, and this one ends inside it.
					const budgetMs =
						new Map([
							["compose-continuation-private-safety", "3000"],
							["compose-continuation-public-safety", "3000"],
						]).get(fixture) ??
						(stage === "stall" || stage.endsWith("-stall") ? "3600000" : "60000");
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
								PI_PUBLIC_COMPLETION_MODE: fixture.startsWith("compose-completion-")
									? fixture.slice("compose-completion-".length)
									: new Map([
											["compose-continuation-stale", "stale"],
											["compose-continuation-unresolved", "unresolved"],
											["compose-private-unacknowledged-public", "public-unavailable"],
										]).get(fixture),
								PI_PRACTICE_PAGE_SUPPORT_MODE: fixture.startsWith("compose-support-")
									? fixture.slice("compose-support-".length)
									: new Map([["compose-already-said-inline", "first-inline"]]).get(fixture),
								PI_UNAVAILABLE_CONTEXT: String(fixture === "compose-context-unavailable"),
								PI_PRIVATE_HOLD: String(fixture === "compose-continuation-private-safety"),
								PI_PUBLIC_HOLD: String(fixture === "compose-continuation-public-safety"),
								PI_PUBLIC_HISTORY_MODE: new Map([
									["compose-fresh-history", "fresh"],
									["compose-history-unavailable-words", "unavailable-words"],
									["compose-history-missing-currentness", "missing-currentness"],
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
								PI_REVIEWER_ONLY: String(fixture === "compose-reviewer-only"),
								PI_LOOP_REJECT: String(fixture === "compose-loop-rejection"),
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
								PI_PRACTICE_CONCURRENCY: stage === "scope-native-problem-isolation" ? "2" : "1",
								PI_PRACTICE_OUTPUT_TOKENS: "8000",
								PI_DIRECT_MODEL_TOKENS: "1000",
								LLM_PROXY_URL: "https://unused.invalid",
								LLM_PROXY_TOKEN: "test-token",
								PI_COMPOSITION_PHASES: "PUBLIC_REVIEW,PRIVATE_FEEDBACK",
							},
							encoding: "utf8",
							timeout: 10_000,
						},
					);
					assert.equal(child.error, undefined);
					// Read production output from the native orchestration fixtures, including both retry helpers.
					const expectedPhases = new Map([
						["scope-native-completion", ["practice"]],
						["compose", ["practice", "public-review", "private-feedback"]],
						["compose-recover", ["practice", "public-review", "public-review", "private-feedback"]],
						[
							"compose-no-decision",
							["practice", "public-review", "private-feedback", "private-feedback"],
						],
						["compose-recovered-error", ["practice", "public-review", "private-feedback"]],
						["compose-private-error", ["practice", "public-review", "private-feedback"]],
					]).get(fixture);
					if (expectedPhases !== undefined) {
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
							expectedPhases,
						);
					}

					const events = readFileSync(nodePath.join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
					// A continuing writer evaluates no practice and reports no coverage of its own.
					assert.equal(
						existsSync(nodePath.join(cwd, "out/practice-coverage.json")),
						stage !== "compose-continuation",
						child.stderr,
					);
					const coverage: unknown =
						stage === "compose-continuation"
							? null
							: JSON.parse(readFileSync(nodePath.join(cwd, "out/practice-coverage.json"), "utf8"));
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
					assert.equal(
						events.includes("derive-change"),
						stage !== "compose-continuation",
						child.stderr,
					);
					const reportPath = nodePath.join(cwd, "out/practice-scripts.json");
					assert.equal(existsSync(reportPath), stage !== "compose-continuation", child.stderr);
					const executions =
						stage === "compose-continuation"
							? new Map<string, Record<string, unknown>>()
							: new Map(
									listOf(recordOf(JSON.parse(readFileSync(reportPath, "utf8"))).practices).map(
										(entry) => [String(recordOf(entry).practiceSlug), recordOf(entry)],
									),
								);
					if (stage !== "compose-continuation") {
						const report = recordOf(JSON.parse(readFileSync(reportPath, "utf8")));
						assert.equal(report.schemaVersion, 2);
						assert.deepEqual([...executions.keys()], slugs, child.stderr);
					}
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
					if (stage.startsWith("compose")) {
						assert.equal(
							measuringSessions.length,
							stage === "compose-continuation" ? 0 : index.length,
							events.join("\n"),
						);
					}
					if (stage.startsWith("compose") && existsSync(nodePath.join(cwd, "out/feedback.json"))) {
						const payload: unknown = JSON.parse(
							readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
						);
						assert.ok(isRecord(payload));
						if (fixture.startsWith("compose-completion-")) {
							assert.equal(child.status, 0, child.stderr);
							const attempts = events.filter((event) => event.startsWith("public-completion:"));
							assert.equal(
								attempts.length,
								["compose-completion-lost-ack", "compose-completion-stale"].includes(fixture)
									? 3
									: 2,
							);
							const privateIndex = events.findIndex((event) => event.startsWith("private-turn"));
							const lastAttempt = attempts.at(-1);
							assert.ok(lastAttempt !== undefined);
							assert.ok(privateIndex < events.indexOf(lastAttempt));
							assert.ok(Array.isArray(payload.feedback));
							assert.equal(payload.feedback.length > 0, fixture !== "compose-completion-refused");
							if (fixture === "compose-completion-refused") {
								assert.equal(payload.review, null);
								assert.deepEqual(payload.compositionFailures, [
									{ phase: "PUBLIC_REVIEW", reason: "SERVER_REFUSED" },
									{ phase: "PRIVATE_FEEDBACK", reason: "SERVER_REFUSED" },
								]);
							} else if (fixture === "compose-completion-unresolved") {
								// Only a readback can resolve an unknown delivery: no fresh session, the exact reason,
								// and private composition still runs.
								assert.equal(payload.review, null);
								assert.deepEqual(payload.compositionFailures, [
									{ phase: "PUBLIC_REVIEW", reason: "PUBLIC_DELIVERY_UNRESOLVED" },
								]);
								assert.equal(
									events.filter(
										(event) => event.startsWith("create:") && event.endsWith("tools=report_review"),
									).length,
									1,
									events.join("\n"),
								);
								assert.equal(events.filter((event) => event === "admit").length, 1);
							} else {
								assert.ok(isRecord(payload.review));
								assert.deepEqual(payload.compositionFailures, []);
							}
							assert.ok(
								events.includes(
									`private-turn history=true review=${String(
										!["compose-completion-refused", "compose-completion-unresolved"].includes(
											fixture,
										),
									)}`,
								),
							);
							const publicPreparation = events.indexOf("prepare-public:true");
							assert.ok(publicPreparation !== -1 && publicPreparation < privateIndex);
							if (fixture === "compose-completion-lost-ack") {
								const [first, second] = attempts;
								assert.ok(first !== undefined && second !== undefined);
								assert.equal(first.split(":")[2], second.split(":")[2]);
							}
							if (fixture === "compose-completion-stale") {
								// The refused words were composed from the first read; the accepted ones from a fresh
								// session over the second read, in the same attempt and with no new assessment.
								assert.deepEqual(
									events.filter((event) => event.startsWith("public-context:")),
									[
										`public-context:v1:7:${"c".repeat(64)}`,
										`public-context:v1:7:${"d".repeat(64)}`,
									],
								);
								assert.equal(
									events.filter(
										(event) => event.startsWith("create:") && event.endsWith("tools=report_review"),
									).length,
									2,
									events.join("\n"),
								);
								assert.equal(events.filter((event) => event === "admit").length, 1);
							}
							return;
						}

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
							assert.equal(
								payload.admissionDigest,
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.equal(child.status, 0, child.stderr);
							const promptCounts: Record<string, number> = {
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
						if (["compose-empty", "compose-fold"].includes(stage)) {
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
						assert.ok(events.includes("prepare-public:false"), events.join("\n"));
						assert.equal(
							events.filter(
								(event) => event.startsWith("create:") && event.endsWith("tools=report_review"),
							).length,
							0,
						);
						const feedback: unknown = JSON.parse(
							readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
						);
						assert.ok(isRecord(feedback));
						assert.deepEqual(
							listOf(feedback.results).map((row) => recordOf(row).resultId),
							[
								"00000000-0000-4000-8000-000000000001",
								"00000000-0000-4000-8000-000000000002",
								"00000000-0000-4000-8000-000000000011",
								"00000000-0000-4000-8000-000000000012",
							],
						);
						assert.ok(Array.isArray(feedback.feedback) && feedback.feedback.length > 0);
						assert.ok(
							feedback.feedback.every(
								(piece: unknown) => isRecord(piece) && piece.destination !== "ON_WORK",
							),
						);
						return;
					}
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
									child.stderr.split(`result refused for ${slug}:`).length - 1,
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
								assert.ok(isRecord(payload) && Array.isArray(payload.feedback));
								assert.equal(payload.feedback.length, 1);
							}

							const failures: Record<string, [string, unknown, number, unknown][]> = {
								// The last call failed with a stated native status, before any response.
								"compose-public-error": [
									[
										"public-review",
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
								],
								// A later successful call clears the earlier failure; its count and time stay.
								// Responses diagnostics are read like chat-completions ones.
								"compose-recovered-error": [
									[
										"public-review",
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
								],
								// A malformed diagnostic is UNKNOWN and unattributed; the native unsuccessful retry-end
								// event marks it. A call without a span is not timed, not timed as zero.
								"compose-private-error": [
									[
										"private-feedback",
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
								],
							};
							const debugText = readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8");
							assert.ok(!debugText.includes("sk-orchestration-secret"));
							const debug: unknown = JSON.parse(debugText);
							assert.ok(isRecord(debug) && Array.isArray(debug.turns));
							for (const [phase, final, unattributed, callTiming] of failures[stage] ?? []) {
								const turn: unknown = debug.turns.find(
									(entry: unknown) => isRecord(entry) && entry.phase === phase,
								);
								assert.ok(isRecord(turn), phase);
								assert.deepEqual(turn.finalModelFailure, final, `${stage} ${phase}`);
								assert.equal(turn.unattributedModelFailures, unattributed, `${stage} ${phase}`);
								assert.deepEqual(turn.modelCallTiming, callTiming, `${stage} ${phase}`);
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
							assert.equal(readResults(nodePath.join(cwd, "out/result.json")).length, 2);
							assert.equal(events.filter((event) => event === "steer").length, 2);
							assert.doesNotMatch(child.stderr, /2 calls in this session ran into/u);
							ran({ "second-practice": "ok", "test-practice": "ok" });
							break;
						}
						case "scope-recording-guard": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								child.stderr,
								/24 recording calls without a record — aborting this turn/u,
							);
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
							assert.doesNotMatch(child.stderr, /aborting this turn|Finishing/u);
							assert.match(child.stderr, /calls=6\/12/u);
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
									/calls=6\/6, outputTokens=600\/24000, stoppedBy=budget/u,
								);
								assert.match(child.stderr, /Measured: .*calls=6/u);
								assert.doesNotMatch(child.stderr, /Finishing/u);
							}
							break;
						}
						case "scope-stop-hangs":
						case "scope-stop-failure": {
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							assert.deepEqual(readResults(nodePath.join(cwd, "out/review-state.json")), []);
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
							ran({ "second-practice": "not-reached (error)", "test-practice": "failed (error)" });
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
						case "compose-private-safety": {
							if (fixture === "compose-private-only-safety") {
								// No review on the work was requested, so no review exists for private feedback to follow:
								// the run's own ceiling ends it, with no further attempt.
								assert.equal(child.status, 0, child.stderr);
								assert.ok(
									!events.some(
										(event) =>
											event.startsWith("create:session") && event.includes("report_review"),
									),
									events.join("\n"),
								);
								assert.ok(!events.includes("prepare-public:true"), events.join("\n"));
								const stopped: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(stopped));
								assert.deepEqual(stopped.compositionFailures, [
									{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
								]);
								break;
							}
							if (fixture === "compose-private-unacknowledged-public") {
								const stopped: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(stopped));
								assert.notEqual(stopped.review, null);
								assert.equal(stopped.preparation, null);
								assert.deepEqual(stopped.compositionFailures, [
									{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
								]);
								assert.ok(events.includes("prepare-public:true"), events.join("\n"));
								const privateSession = events.findIndex(
									(event) =>
										event.startsWith("create:session") && event.includes("report_feedback"),
								);
								assert.ok(privateSession !== -1, events.join("\n"));
								assert.ok(
									events.slice(privateSession + 1).some((event) => event.startsWith("prompt:")),
									events.join("\n"),
								);
								assert.match(child.stderr, /public review preparation: UNAVAILABLE/u);
								assert.equal(child.status, 77, child.stderr);
								break;
							}
							// The public review is prepared; the run's own ceiling stopped private composition.
							assert.equal(child.status, 77, child.stderr);
							assert.ok(events.includes("prepare-public:true"), events.join("\n"));
							const owed: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(owed));
							assert.deepEqual(owed.compositionFailures, [
								{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
							]);
							break;
						}
						case "compose-request-caps": {
							// A composer request is bounded like a practice's: never more output than its turn has left,
							// and none once the turn is spent. Each phase keeps its own allowance.
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("stream-request")),
								[
									"stream-request:16384",
									"stream-request:7617",
									"stream-request:1",
									"stream-request-refused:public",
									"stream-request:16384",
									"stream-request:7617",
									"stream-request:1",
									"stream-request-refused:private",
								],
								child.stderr,
							);
							const capped: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(capped));
							// A spent work budget is the work's outcome, not one more attempt.
							assert.deepEqual(capped.compositionFailures, [
								{ phase: "PUBLIC_REVIEW", reason: "BUDGET" },
								{ phase: "PRIVATE_FEEDBACK", reason: "BUDGET" },
							]);
							break;
						}
						case "compose-settle-deadline": {
							// The admitting attempt's own ceiling stopped the review on the work: it is owed to one more
							// attempt, which composes it from the same admitted results.
							assert.equal(child.status, 77, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.match(
								child.stderr,
								/the run is near its safety ceiling — preserving results and composed feedback/u,
							);
							// The review composition was cut off by the safety line, so the private one never started.
							assert.ok(
								!events.some((event) => event.startsWith("private-turn")),
								events.join("\n"),
							);
							// A refused review is not a review: nothing is left to deliver on the work.
							assert.match(
								events.find((event) => event.startsWith("refused-only:")) ?? "",
								/review refused, nothing was stored:[\s\S]*00000000-0000-4000-8000-000000000001 has no decision/u,
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
							assert.match(child.stderr, /a call ran into the output limit — telling the model/u);
							assert.match(
								child.stderr,
								/2 calls in this session ran into the 16384-token output limit — aborting this turn/u,
							);
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
								/4 of 6 calls and 400 of 24000 output tokens spent — nudging to record/u,
							);
							assert.match(
								child.stderr,
								/work budget spent \(6 calls, 600 output tokens\) — ending this turn/u,
							);
							assert.match(child.stderr, /calls=6\/6, outputTokens=600\/24000, stoppedBy=budget/u);
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
							assert.match(child.stderr, /no model or tool event for 300s — aborting this turn/u);
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
									`finish: 1 practice\\(s\\), up to 6 calls and 24000 output tokens \\(${pace} tokens per result so far\\)`,
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
							assert.match(
								child.stderr,
								/the same refused test-practice result sent 3 times — aborting this turn/u,
							);
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
							assert.doesNotMatch(child.stderr, /Finishing 1 practice/u);
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
						case "compose-support": {
							assert.equal(child.status, 0, child.stderr);
							const payload: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(payload) && Array.isArray(payload.feedback));
							assert.ok(payload.review !== null);
							assert.equal(
								payload.admissionDigest,
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.deepEqual(
								JSON.parse(
									readFileSync(nodePath.join(cwd, "work/composition/results.json"), "utf8"),
								),
								{ results: [admittedResult] },
							);

							const mode = fixture.slice("compose-support-".length);
							assert.equal(
								payload.feedback.some(
									(piece: unknown) =>
										isRecord(piece) &&
										piece.destination === "CONVERSATION" &&
										piece.action === "NEW",
								),
								!["refused-only", "inline", "routing-missing", "mentor-unavailable"].includes(mode),
							);
							assert.deepEqual(
								payload.feedback
									.filter(
										(piece: unknown) => isRecord(piece) && piece.destination === "PRACTICE_PAGE",
									)
									.map((piece: unknown) => {
										assert.ok(isRecord(piece));
										return piece.action;
									}),
								new Map<string, string[]>([
									["selected", ["NEW"]],
									["mentor-unavailable", ["NEW"]],
									["routing-missing", ["NEW"]],
									["empty", ["WITHHOLD"]],
									["duplicate", ["WITHHOLD"]],
								]).get(mode) ?? [],
							);
							// A destination the server refuses is decided; only an unknown read is a failure.
							assert.deepEqual(
								payload.compositionFailures,
								[
									"missing",
									"malformed",
									"unavailable",
									"invalid-optional",
									"routing-missing",
								].includes(mode)
									? [{ phase: "PRIVATE_FEEDBACK", reason: "RUNTIME_ERROR" }]
									: [],
							);
							assert.equal(
								events.filter(
									(event) => event.includes("tools=") && event.includes("report_feedback"),
								).length,
								mode === "refused-only" || mode === "inline" ? 0 : 1,
							);
							const pieceReply = events.find((event) => event.startsWith("support-items:")) ?? "";
							if (mode === "cooldown") {
								assert.match(
									pieceReply,
									/#1: PRACTICE_PAGE is not a destination this run may write for/u,
								);
								assert.match(
									pieceReply,
									/#2: stored a CONVERSATION piece for test-practice \(NEW\)/u,
								);
								assert.match(
									child.stderr,
									/PRACTICE_PAGE closed for this run: no undecided practice is routed to it/u,
								);
							}
							if (mode === "routing-missing") {
								assert.match(
									pieceReply,
									/#2: CONVERSATION is not a destination this run may write for/u,
								);
								assert.match(
									child.stderr,
									/CONVERSATION closed for this run: routing missing or malformed/u,
								);
							}
							if (mode === "mentor-unavailable") {
								assert.match(
									pieceReply,
									/#2: CONVERSATION is not a destination this run may write for/u,
								);
								assert.match(
									child.stderr,
									/CONVERSATION closed for this run: no undecided practice is routed to it/u,
								);
							}
							if (mode === "inline") {
								assert.equal(pieceReply, "");
								assert.equal(payload.feedback.length, 0);
								assert.match(
									child.stderr,
									/PRACTICE_PAGE closed for this run: no undecided practice is routed to it/u,
								);
								assert.match(
									child.stderr,
									/CONVERSATION closed for this run: no undecided practice is routed to it/u,
								);
							}
							break;
						}
						case "compose":
						case "compose-foreign-provider": {
							assert.equal(child.status, 0, child.stderr);
							assert.equal(existsSync(nodePath.join(cwd, "work/review-input")), false);
							ran({ "test-practice": "ok" });
							assert.deepEqual(executions.get("test-practice")?.program, {
								kind: "builtin",
								digest: null,
							});
							assert.deepEqual(executions.get("test-practice")?.calls, {
								review: 1,
								chat: 0,
								decision: 0,
								embedding: 0,
								reranking: 0,
							});
							if (
								fixture === "compose-history-missing-currentness" ||
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
									events.filter((event) => event.includes("tools=report_review")).length,
									0,
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "history/feedback.json"), "utf8"),
									STAGED_FEEDBACK_HISTORY,
								);
								assert.equal(
									payload.admissionDigest,
									"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
								);
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
								]);
								assert.ok(
									!JSON.stringify(debug.publicHistory).includes("Fresh delivered communication"),
								);
								assert.equal(
									readFileSync(nodePath.join(cwd, "history/feedback.json"), "utf8"),
									STAGED_FEEDBACK_HISTORY,
								);
							}

							if (fixture === "compose-history-unavailable-words") {
								const prompt = readFileSync(nodePath.join(cwd, "prompt-2.md"), "utf8");
								assert.match(prompt, /"wordsUnavailable": "COPY_AUTHORITY_UNAVAILABLE"/u);
								assert.ok(!prompt.includes("An earlier comment on this same change."));
								const debug: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/runner-debug.json"), "utf8"),
								);
								assert.ok(isRecord(debug) && isRecord(debug.publicHistory));
								assert.deepEqual(
									debug.publicHistory.entries,
									[1, 2].map((entryNumber) => ({
										id: `00000000-0000-4000-8000-00000000000${entryNumber}`,
										deliveredAt: "2026-10-07T10:00:00Z",
										eligibleForAlreadySaid: false,
										eligibleForPriorAdvice: false,
									})),
								);
								const answer: unknown = JSON.parse(
									readFileSync(
										nodePath.join(cwd, "work/composition/public-feedback-history.json"),
										"utf8",
									),
								);
								assert.ok(isRecord(answer) && isRecord(answer.history));
								assert.ok(
									listOf(answer.history.feedback).every(
										(entry) => recordOf(entry).bodyUnavailable === true,
									),
								);
							}

							// Measurement, then the review on the work in a fresh session, then the private destinations in
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
								record !== -1 &&
									record < reviewTurn.indexOf('"resultId": "00000000-0000-4000-8000-000000000001"'),
								reviewTurn,
							);
							assert.match(reviewTurn, /linked_work_items\.json[^\n]*not part of this capture/u);
							assert.match(reviewTurn, /"resultId": "00000000-0000-4000-8000-000000000001"/u);
							assert.match(reviewTurn, /"problemId": "problem-1"/u);
							assert.match(reviewTurn, /"quote": "\+ insecure\(\);"/u);
							if (
								fixture !== "compose-fresh-history" &&
								fixture !== "compose-history-unavailable-words" &&
								fixture !== "compose-history-body-transport"
							) {
								assert.match(reviewTurn, /An earlier comment on this same change\./u);
							}
							assert.match(reviewTurn, /"slug": "test-practice"/u);
							assert.deepEqual(reviewPracticesOf(reviewTurn), {
								practices: [
									{
										slug: "test-practice",
										name: "Test practice",
										knownLimitations: [],
										...(["compose", "compose-foreign-provider"].includes(stage)
											? { versionId: "42" }
											: {}),
									},
								],
							});
							const standard = reviewTurn.indexOf("```markdown\n# Test practice\nCriteria.\n```");
							assert.ok(
								record < standard &&
									standard <
										reviewTurn.indexOf('"resultId": "00000000-0000-4000-8000-000000000001"'),
								reviewTurn,
							);
							assert.equal(reviewTurn.split("# Test practice\nCriteria.").length, 2, reviewTurn);
							assert.ok(!reviewTurn.includes(PRIVATE_HISTORY_SENTENCE), reviewTurn);
							// The private history the admission brought stays out of the review on the work.
							assert.doesNotMatch(
								reviewTurn,
								/work\/composition\/history|00000000-0000-4000-8000-000000000201|00000000-0000-4000-8000-000000000101/u,
							);
							assert.ok(!reviewTurn.includes("problem-history"), reviewTurn);
							assert.ok(!reviewTurn.includes("00000000-0000-4000-8000-000000000010"), reviewTurn);
							assert.ok(!reviewTurn.includes("Review the practice."), reviewTurn);
							assert.match(
								events.find((event) => event.startsWith("review-refused:")) ?? "",
								/review refused, nothing was stored:[\s\S]*summary: body may not contain an HTML comment[\s\S]*inline #1: anchor names problem-1b, which this note's basedOn does not, itself or through its result/u,
							);
							assert.match(
								events.find((event) => event.startsWith("review-history:")) ?? "",
								/00000000-0000-4000-8000-000000000010, which is not one of the NOT_MET results or problems this review may rest on/u,
							);
							assert.match(
								events.find((event) => event.startsWith("review-stored:")) ?? "",
								/Stored the review: a summary resting on 1 result\(s\) and problem\(s\), 1 line note\(s\), 0 withholding decision\(s\)[\s\S]*"terminate":true/u,
							);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							// Private composition sees the person's history and the stored review.
							assert.ok(
								events.includes("private-turn history=true review=true"),
								events.join("\n"),
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-historical:")) ?? "",
								/does not name an admitted result or problem from this run/u,
								`${events.join("\n")}\n${child.stderr}`,
							);
							const reply = events.find((event) => event.startsWith("feedback:")) ?? "";
							// The composition turn goes on to its summary; only the retry ends on its last decision.
							assert.match(reply, /"terminate":false/u, `${events.join("\n")}\n${child.stderr}`);
							assert.match(
								reply,
								/#1: stored a PRACTICE_PAGE piece for test-practice \(NEW\); 1\/1 used/u,
							);
							assert.match(reply, /#2: basedOn is required/u);
							assert.match(reply, /#3: piece: Unrecognized key: .*verdict/u);
							assert.match(
								reply,
								/#4: body: Aim for at most 600 characters[\s\S]*Corrected text example/u,
							);
							assert.match(reply, /#5: withholdReason: Invalid option/u);
							assert.match(
								reply,
								/#6: practiceSlug 'other-practice' is not a practice with an admitted result in this run \(those are: test-practice\)/u,
							);
							assert.match(reply, /#7: destination: Invalid option/u);
							assert.match(reply, /#8: piece: Invalid input/u);
							assert.match(
								events.find((event) => event.startsWith("feedback-bare:")) ?? "",
								/#1: CONVERSATION is not a destination this run may write for/u,
							);
							const second = readFileSync(nodePath.join(cwd, "prompt-3.md"), "utf8");
							// Private composition receives stable context anew, without measurement criteria or tool examples.
							assert.match(second, /### `evidence\/metadata\.json`/u);
							assert.doesNotMatch(second, /### Practice `test-practice`|How submit_result takes/u);
							assert.equal(
								readFileSync(nodePath.join(cwd, "private-system-prompt.md"), "utf8"),
								"Write clear, useful feedback.\n\nCompose from admitted problems.",
							);
							assert.doesNotMatch(
								second,
								/Compose from admitted problems|How submit_result takes/u,
							);
							assert.match(second, /"resultId": "00000000-0000-4000-8000-000000000001"/u);
							assert.match(second, /"problemId": "problem-1"/u);
							// The quoted lines and the verification records stay on disk, where the composer
							// can read them if needed; the search it recorded is still shown.
							assert.ok(second.includes("## This turn"));
							const composerTurn = second.slice(second.indexOf("## This turn"));
							// The NOT_MET practice's criteria come whole before the admitted rows, without its
							// measuring leads or a sibling's criteria; the staged files stay readable.
							const criteriaAt = composerTurn.indexOf("# Test practice\nCriteria.");
							assert.ok(criteriaAt !== -1, composerTurn);
							assert.ok(
								criteriaAt <
									composerTurn.indexOf('"resultId": "00000000-0000-4000-8000-000000000001"'),
								composerTurn,
							);
							assert.doesNotMatch(
								composerTurn,
								/Leads from the practice script|insecure call: |Complete second criterion/u,
							);
							// The server's private history keeps every fact it was read with and names where its words are.
							for (const fact of [
								'"id": "00000000-0000-4000-8000-000000000202"',
								'"deliveredAt": "2026-08-15T10:00:00Z"',
								'"recordedClaimCurrentness": "STALE"',
								'"withdrawn": true',
								'"threadKey": "00000000-0000-4000-8000-000000000101"',
							]) {
								assert.ok(composerTurn.includes(fact), fact);
							}
							assert.ok(!composerTurn.includes("An earlier card about the insecure call."));
							assert.ok(!composerTurn.includes("Earlier prepared mentor wording."));
							assert.ok(!composerTurn.includes(REVIEW_SUMMARY));
							assert.match(composerTurn, /feedback\[0\]\.body/u);
							assert.doesNotMatch(composerTurn, /feedback\[1\]\.body/u);
							assert.match(composerTurn, /prepared\[0\]\.body/u);
							// Supplied history is shown as supplied; only the problem history, which no read supplies, is unknown.
							assert.doesNotMatch(
								composerTurn,
								/(?:feedback|prepared)\.json` is not staged|staged with no entries/u,
							);
							assert.match(composerTurn, /history\/problems\.json` is not staged for this review/u);
							assert.match(composerTurn, /review\.summary\.body/u);
							assert.match(composerTurn, /review\.inline\[0\]\.body/u);
							// The native evidence reader can retrieve the original source at a displayed locator.
							const { createReadTool } = await import("@earendil-works/pi-coding-agent");
							const reader = createReadTool(cwd);
							for (const [path, expected] of [
								[
									"work/composition/history/feedback.json",
									"An earlier card about the insecure call.",
								],
								["work/composition/history/prepared.json", "Earlier prepared mentor wording."],
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

							// The runner stages each part of the read in its own file; the projection keeps every root
							// and row fact, a body is replaced by its locator in the unchanged file, and a row without
							// one (withdrawn, stale) names none.
							const projections = [
								...composerTurn.matchAll(/```json\n(?<json>[\s\S]*?)\n```/gu),
							].map((match): unknown => JSON.parse(match.groups?.json ?? "null"));
							const { readAt, coverage: historyCoverage } = PRIVATE_HISTORY;
							for (const [role, key, file, rowsRead, bodyRoles] of [
								[
									"RECORDED_DELIVERY",
									"feedback",
									"work/composition/history/feedback.json",
									PRIVATE_HISTORY.delivered,
									["PRACTICE_PAGE_CARD", null],
								],
								[
									"PREPARED_NOT_DELIVERED",
									"prepared",
									"work/composition/history/prepared.json",
									PRIVATE_HISTORY.queued,
									["MENTOR_NOTES"],
								],
							] as const) {
								const staged = readFileSync(nodePath.join(cwd, file), "utf8");
								assert.deepEqual(JSON.parse(staged), {
									readAt,
									coverage: historyCoverage,
									recordRole: role,
									[key]: rowsRead,
								});
								const view = projections.find(
									(projection) => isRecord(projection) && projection.recordRole === role,
								);
								assert.ok(isRecord(view), role);
								assert.deepEqual(view.coverage, historyCoverage);
								const rows = view[key];
								assert.ok(Array.isArray(rows));
								assert.deepEqual(
									rows.map((row: unknown) => (isRecord(row) ? row.bodyRole : undefined)),
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
								readFileSync(nodePath.join(cwd, "work/composition/results.json"), "utf8"),
								/"quote": "\+ insecure\(\);"/u,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							assert.equal(
								Reflect.get(feedback, "admissionDigest"),
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.equal(Reflect.get(feedback, "contractVersion"), 3);
							assert.equal(Reflect.get(feedback, "lead"), undefined);
							const pieces: unknown = Reflect.get(feedback, "feedback");
							assert.ok(Array.isArray(pieces) && pieces.length === 1, JSON.stringify(feedback));
							assert.ok(isRecord(pieces[0]) && pieces[0].destination === "PRACTICE_PAGE");
							// The review as stored: the summary verbatim, the note on its cited line.
							assert.deepEqual(Reflect.get(feedback, "review"), {
								summary: {
									body: REVIEW_SUMMARY,
									basedOn: [{ resultId: "00000000-0000-4000-8000-000000000001" }],
								},
								inline: [
									{
										body: "`insecure()` runs here before the token is checked; call the checked helper instead.",
										basedOn: [
											{ resultId: "00000000-0000-4000-8000-000000000001", problemId: "problem-1" },
										],
										anchor: { problemId: "problem-1", citationIndex: 0 },
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
							assert.doesNotMatch(composition, /## Recorded so far|How submit_result takes/u);
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
								/## Undecided[\s\S]*each with a NOT_MET result: test-practice/u,
							);
							assert.match(
								readFileSync(nodePath.join(cwd, "prompt-4.md"), "utf8"),
								/Room left for new items: PRACTICE_PAGE 1 of 1/u,
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
								/#1: basedOn is required: [^;]*\(test-practice's results: 00000000-0000-4000-8000-000000000001\); skipped\./u,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-finish:")) ?? "",
								/#2: stored a PRACTICE_PAGE piece for test-practice \(WITHHOLD\)/u,
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
							const pieces: unknown = Reflect.get(feedback, "feedback");
							assert.ok(Array.isArray(pieces) && pieces.length === 1);
							const piece: unknown = pieces[0];
							assert.ok(typeof piece === "object" && piece !== null);
							assert.equal(Reflect.get(piece, "withholdReason"), "BELOW_BAR");
							break;
						}
						case "compose-empty": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							assert.ok(
								!events.some((event) => event.endsWith("tools=report_review")),
								events.join("\n"),
							);
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.equal(feedback.review, null);
							assert.deepEqual(
								readFeedbackResults(nodePath.join(cwd, "out/feedback.json")).map(
									(item) => item.outcome,
								),
								["MET"],
							);
							break;
						}
						case "compose-invalid-select": {
							assert.equal(child.status, 0, child.stderr);
							const said = (label: string) =>
								events.find((event) => event.startsWith(`${label}:`)) ?? "";
							for (const label of ["invalid-report", "invalid-again"]) {
								assert.match(
									said(label),
									/review refused, nothing was stored:[\s\S]*00000000-0000-4000-8000-000000000001 has no decision/u,
								);
							}
							assert.equal(
								said("invalid-nudge"),
								`invalid-nudge:${JSON.stringify([["report_review"]])}`,
							);
							assert.match(said("invalid-after-final"), /The review on this work is final/u);
							assert.match(
								child.stderr,
								/the same report_review call 2 times — nudging to record/u,
							);
							assert.doesNotMatch(child.stderr, /the same report_review call 3 times/u);
							assert.match(said("invalid-final"), /Stored the review[\s\S]*"terminate":true/u);
							assert.ok(!events.includes("review-retry"), events.join("\n"));
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.deepEqual(feedback.review, {
								summary: {
									body: REVIEW_SUMMARY,
									basedOn: [{ resultId: "00000000-0000-4000-8000-000000000001" }],
								},
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
							assert.ok(turn.indexOf(staged) < turn.indexOf('"problems": ['), turn);
							assert.ok(!refused.includes(JSON.stringify(staged).slice(1, -1)), refused);
							// The retry holds only what is owed and how to finish.
							const retry = prompts.find((prompt) => prompt.startsWith("## Undecided"));
							assert.ok(retry !== undefined, events.join("\n"));
							assert.ok(retry.includes("00000000-0000-4000-8000-000000000001"), retry);
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
								const [concern = ""] = standards;
								assert.ok(reviewTurn.includes(concern), reviewTurn);
								assert.ok(
									!reviewTurn.includes(
										"The helper path is present in the source; its runtime behavior was not executed.",
									),
									reviewTurn,
								);
								assert.ok(
									!reviewTurn.includes('"resultId": "00000000-0000-4000-8000-000000000002"'),
									reviewTurn,
								);
								assert.match(
									events.find((event) => event.startsWith("mixed-met:")) ?? "",
									/review refused, nothing was stored:[\s\S]*basedOn names 00000000-0000-4000-8000-000000000002, which is not one of the NOT_MET results or problems this review may rest on/u,
								);
								const refused = readToolText(nodePath.join(cwd, "compacted-refused.txt"));
								assert.match(
									refused,
									/review refused, nothing was stored: the session's context was compacted/u,
								);
								assert.ok(refused.includes(reviewTurn), refused);
								assert.ok(refused.includes(`"itemVersion": "${"a".repeat(40)}"`), refused);
								assert.match(
									readFileSync(nodePath.join(cwd, "restore-batch-refused.txt"), "utf8"),
									/review refused, nothing was stored/u,
								);
								assert.match(
									events.find((event) => event.startsWith("compacted-stored:")) ?? "",
									/Stored the review: a summary resting on 1 result\(s\) and problem\(s\)/u,
								);
								const stored: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(stored));
								assert.deepEqual(stored.review, {
									summary: {
										body: REVIEW_SUMMARY,
										basedOn: [{ resultId: "00000000-0000-4000-8000-000000000001" }],
									},
									inline: [],
									withheld: [],
								});
								reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
								break;
							}
							for (const standard of standards) {
								assert.ok(reviewTurn.includes(standard), reviewTurn);
							}
							const refused = readFileSync(nodePath.join(cwd, "qualification-refused.txt"), "utf8");
							assert.match(
								refused,
								/review refused, nothing was stored:[\s\S]*00000000-0000-4000-8000-000000000002 has no decision/u,
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
							assert.equal(
								feedback.admissionDigest,
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.deepEqual(feedback.review, {
								summary: {
									body: REVIEW_SUMMARY,
									basedOn: [{ resultId: "00000000-0000-4000-8000-000000000001" }],
								},
								inline: [],
								withheld: [
									{
										basedOn: [{ resultId: "00000000-0000-4000-8000-000000000002" }],
										reason: "BELOW_BAR",
										witnessIds: [],
									},
								],
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
								/review refused, nothing was stored:[\s\S]*00000000-0000-4000-8000-000000000001 has no decision/u,
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
								/Stored the review: a summary resting on 1 result\(s\) and problem\(s\)[\s\S]*"terminate":true/u,
							);
							assert.ok(events.includes('reselect-terminal:{"action":"end"}'), events.join("\n"));
							assert.ok(events.includes("reselect-drained:0"), events.join("\n"));
							assert.equal(events.filter((event) => event === "native-finish-turn").length, 2);
							assert.match(
								said("reselect-met"),
								/review refused, nothing was stored:[\s\S]*basedOn names 00000000-0000-4000-8000-000000000002, which is not one of the NOT_MET results or problems this review may rest on/u,
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
								practices: [{ slug: "test-practice", name: "Test practice", knownLimitations: [] }],
							});
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.deepEqual(feedback.review, {
								summary: {
									body: REVIEW_SUMMARY,
									basedOn: [{ resultId: "00000000-0000-4000-8000-000000000001" }],
								},
								inline: [],
								withheld: [],
							});
							break;
						}
						case "compose-already-said": {
							assert.equal(child.status, 0, child.stderr);
							const first = events.find((event) => event.startsWith("said-first:")) ?? "";
							assert.match(
								first,
								/#1: NO_MATERIAL_CHANGE rests on feedback recorded as delivered on CONVERSATION for test-practice in/u,
							);
							assert.match(
								first,
								/#2: ALREADY_SAID rests on feedback recorded as delivered on CONVERSATION for test-practice in/u,
							);
							assert.match(
								first,
								/#3: stored a PRACTICE_PAGE piece for test-practice \(WITHHOLD\); 0\/1 used on that destination\./u,
							);
							const second = events.find((event) => event.startsWith("said-second:")) ?? "";
							assert.match(
								second,
								/#1: NO_MATERIAL_CHANGE rests on feedback recorded as delivered on CONVERSATION for test-practice in/u,
							);
							// The refused grouping left the pair open; MET counterevidence owes no history.
							assert.match(
								second,
								/#2: stored a CONVERSATION piece for second-practice \(WITHHOLD\); 0\/1 used on that destination\./u,
							);
							// test-practice's problem is on the diff: the open destination refuses even a withholding for it.
							const inline = fixture === "compose-already-said-inline";
							assert.match(
								second,
								inline
									? /#3: CONVERSATION cannot carry feedback about test-practice this run \(HAS_INLINE_ANCHOR\); write no piece for it there, not even a WITHHOLD\. Skipped\./u
									: /#3: stored a CONVERSATION piece for test-practice \(WITHHOLD\); 0\/1 used on that destination\./u,
							);
							const payload: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(payload) && Array.isArray(payload.feedback));
							assert.deepEqual(
								payload.feedback.map((piece: unknown) => {
									assert.ok(isRecord(piece));
									assert.ok(Array.isArray(piece.basedOn));
									const refs = piece.basedOn.map((ref: unknown) => {
										assert.ok(isRecord(ref) && ref.problemId === undefined);
										return String(ref.resultId);
									});
									return `${String(piece.destination)}:${String(piece.practiceSlug)}:${String(piece.withholdReason)}:${refs.join("+")}`;
								}),
								[
									"PRACTICE_PAGE:test-practice:ALREADY_SAID:00000000-0000-4000-8000-000000000001",
									"CONVERSATION:second-practice:NO_MATERIAL_CHANGE:00000000-0000-4000-8000-000000000002",
									...(inline
										? []
										: [
												"CONVERSATION:test-practice:BELOW_BAR:00000000-0000-4000-8000-000000000001",
											]),
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
								// A server read that found nothing is staged empty, over the file the session planted, and
								// history no read supplied is unknown: different facts.
								assert.match(
									privateTurn,
									/### Recorded delivered feedback — staged with no entries\n```json\n[^`]*"feedback": \[\]/u,
								);
								assert.match(
									privateTurn,
									/### Prepared feedback \(not delivered; supersession targets\) — staged with no entries\n```json\n[^`]*"prepared": \[\]/u,
								);
								assert.match(
									privateTurn,
									/### What earlier reviews recorded about this person — `[^`]*history\/problems\.json` is not staged for this review; what it would record is unknown here, not empty/u,
								);
								assert.doesNotMatch(privateTurn, /(?:feedback|prepared)\.json` is not staged/u);
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
							// Unstaged history locations are reported unknown instead of requiring a failed read.
							for (const label of [
								"What earlier reviews recorded about this person — `[^`]*history/problems\\.json`",
								"Recorded delivered feedback — `[^`]*history/feedback\\.json`",
								"Prepared feedback \\(not delivered; supersession targets\\) — `[^`]*history/prepared\\.json`",
							]) {
								assert.match(
									privateTurn,
									new RegExp(
										`### ${label} is not staged for this review; what it would record is unknown here, not empty\n`,
										"u",
									),
								);
							}
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
								assert.ok(!publicTurn.includes("00000000-0000-4000-8000-000000000011"));
								assert.ok(
									privateTurn.includes('"resultId": "00000000-0000-4000-8000-000000000011"'),
								);
								assert.doesNotMatch(privateTurn, /"quote"|"verification"/u);
								assert.match(
									events.find((event) => event.startsWith("qualification-support:")) ?? "",
									/00000000-0000-4000-8000-000000000011, which is not one of the NOT_MET results or problems this review may rest on/u,
								);
								const restored =
									events.find((event) => event.startsWith("qualification-restored:")) ?? "";
								assert.ok(restored.includes("Which current request governs?"));
								assert.ok(restored.includes("The captured interface is unchanged."));
								const feedback: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(feedback) && Array.isArray(feedback.feedback));
								const piece: unknown = feedback.feedback[0];
								assert.ok(isRecord(piece));
								assert.deepEqual(
									feedback.feedback.map((entry) => recordOf(entry).basedOn),
									[
										[{ resultId: "00000000-0000-4000-8000-000000000001" }],
										[{ resultId: "00000000-0000-4000-8000-000000000002" }],
									],
								);
							}
							break;
						}
						case "compose-abstention": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.ok(
								!events.some((event) => event.endsWith("tools=report_review")),
								events.join("\n"),
							);
							assert.deepEqual(
								[...candidatesIn(nodePath.join(cwd, "out/result.json")).values()].map(
									(item) => item.outcome,
								),
								["DOES_NOT_APPLY", "MET", "COULD_NOT_DETERMINE"],
							);
							const echoed: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(echoed));
							assert.equal(
								echoed.admissionDigest,
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.deepEqual(
								readFeedbackResults(nodePath.join(cwd, "out/feedback.json")).map(
									(item) => item.outcome,
								),
								["DOES_NOT_APPLY", "MET", "COULD_NOT_DETERMINE"],
							);
							assert.equal(echoed.review, null);
							assert.deepEqual(echoed.compositionFailures, []);
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
							assert.match(child.stderr, /outcome/u);
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
									Reflect.get(turn, "phase") === "public-review",
							);
							assert.ok(typeof composition === "object" && composition !== null);
							assert.equal(Reflect.get(composition, "toolErrors"), 24);
							assert.equal(Reflect.get(composition, "recordingCalls"), 24);
							break;
						}
						case "compose-continuation": {
							assert.equal(
								child.status,
								fixture === "compose-continuation-private-safety" ? 77 : 0,
								child.stderr,
							);
							if (fixture === "compose-continuation-public-safety") {
								// The continuation had the whole run for composition: its own ceiling ends the review on
								// the work for good, with no further attempt and no private feedback started.
								assert.equal(
									events.filter((event) => event.startsWith("create:session")).length,
									1,
									events.join("\n"),
								);
								assert.ok(
									!events.some((event) => event.startsWith("private-turn")),
									events.join("\n"),
								);
								assert.ok(!events.includes("prepare-public:true"), events.join("\n"));
								assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
								const stopped: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(stopped));
								assert.equal(stopped.review ?? null, null);
								assert.deepEqual(stopped.compositionFailures, [
									{ phase: "PUBLIC_REVIEW", reason: "SAFETY" },
									{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
								]);
								break;
							}
							if (fixture === "compose-continuation-private-safety") {
								// The review composed and accepted in this attempt stays; the run's ceiling stopped the
								// private composer, so the private feedback is owed to another attempt.
								const created = events.filter((event) => event.startsWith("create:session"));
								assert.equal(created.length, 2, events.join("\n"));
								assert.equal(created[0], "create:session tools=report_review");
								assert.ok((created[1] ?? "").includes("report_feedback"), events.join("\n"));
								assert.deepEqual(
									events.filter((event) => event.startsWith("prepare-public:")),
									["prepare-public:true"],
								);
								assert.ok(
									events.indexOf(created[1] ?? "") > events.indexOf("prepare-public:true"),
									events.join("\n"),
								);
								assert.match(child.stderr, /preparation: PREPARED/u);
								assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
								assert.ok(!existsSync(nodePath.join(cwd, "out/practice-scripts.json")));
								assert.ok(!events.includes("derive-change"));
								const owed: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(owed));
								assert.ok(owed.review !== null);
								assert.ok(isRecord(owed.preparation), JSON.stringify(owed.preparation));
								assert.deepEqual(owed.compositionFailures, [
									{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
								]);
								break;
							}
							if (fixture === "compose-continuation-reviewed") {
								assert.deepEqual(
									events.filter((event) => event.startsWith("create:session")),
									[],
								);
								assert.ok(!events.some((event) => event.startsWith("public-completion:")));
								assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
								break;
							}
							if (fixture === "compose-continuation-private") {
								// Private feedback only: no review composer, no public preparation, no new admission.
								const created = events.filter((event) => event.startsWith("create:session"));
								assert.ok(
									!created.some((event) => event.includes("report_review")),
									events.join("\n"),
								);
								assert.equal(
									created.filter((event) => event.includes("report_feedback")).length,
									1,
									events.join("\n"),
								);
								assert.ok(
									events.some((event) => event.startsWith("private-turn")),
									events.join("\n"),
								);
								assert.ok(
									events.includes("prepare-public:false"),
									`${events.join("\n")}\n${child.stderr}`,
								);
								assert.ok(!events.includes("prepare-public:true"), events.join("\n"));
								assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
								const continued: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(continued));
								assert.equal(continued.review, null);
								assert.ok(Array.isArray(continued.feedback));
								assert.ok(
									continued.feedback.some(
										(piece: unknown) => isRecord(piece) && piece.destination === "PRACTICE_PAGE",
									),
								);
								assert.ok(Array.isArray(continued.compositionFailures));
								assert.ok(
									!continued.compositionFailures.some(
										(failure: unknown) => isRecord(failure) && failure.phase === "PUBLIC_REVIEW",
									),
									JSON.stringify(continued.compositionFailures),
								);
								break;
							}
							if (fixture === "compose-continuation-unresolved") {
								// An unknown public delivery is recorded once and never composed again; it owes nothing.
								// Private composition still runs, as in the measured attempt.
								const sessions = events.filter((event) => event.startsWith("create:session"));
								assert.equal(
									sessions.filter((event) => event.includes("report_review")).length,
									1,
									events.join("\n"),
								);
								assert.equal(
									sessions.filter((event) => event.includes("report_feedback")).length,
									1,
									events.join("\n"),
								);
								assert.equal(
									events.filter((event) => event === "prepare-public:true").length,
									1,
									events.join("\n"),
								);
								assert.ok(events.includes("prepare-public:false"), events.join("\n"));
								const unresolved: unknown = JSON.parse(
									readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
								);
								assert.ok(isRecord(unresolved));
								assert.equal(unresolved.review, null);
								assert.deepEqual(unresolved.compositionFailures, [
									{ phase: "PUBLIC_REVIEW", reason: "PUBLIC_DELIVERY_UNRESOLVED" },
								]);
								assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
								break;
							}
							const created = events.filter((event) => event.startsWith("create:session"));
							if (fixture === "compose-continuation-private-prepared") {
								// One session, the review composer's: the earlier card already decides the only private practice.
								assert.deepEqual(created, ["create:session tools=report_review"]);
								assert.equal(
									readFileSync(nodePath.join(cwd, "system-prompt.md"), "utf8"),
									"Write clear, useful feedback.\n\nWrite the review on the work.",
								);
							} else if (fixture === "compose-continuation-stale") {
								// Changed history earns one fresh review session over the new read, then the private
								// composer sees the accepted review. No submission reuses the refused context.
								assert.deepEqual(created.slice(0, 2), [
									"create:session tools=report_review",
									"create:session tools=report_review",
								]);
								assert.equal(created.length, 3, events.join("\n"));
								assert.ok((created[2] ?? "").includes("report_feedback"), events.join("\n"));
								assert.deepEqual(
									events.filter((event) => event.startsWith("public-context:")),
									[
										`public-context:v1:7:${"c".repeat(64)}`,
										`public-context:v1:7:${"d".repeat(64)}`,
									],
								);
								assert.equal(events.filter((event) => event === "prepare-public:true").length, 2);
								assert.ok(
									events.lastIndexOf("prepare-public:true") <
										events.indexOf("prepare-public:false"),
									events.join("\n"),
								);
								assert.ok(
									events.some(
										(event) => event.startsWith("private-turn") && event.endsWith("review=true"),
									),
								);
							} else {
								// The review composer, then the private composer that sees the review just composed.
								assert.equal(created.length, 2, events.join("\n"));
								assert.equal(created[0], "create:session tools=report_review");
								assert.ok((created[1] ?? "").includes("report_feedback"), events.join("\n"));
								assert.ok(
									events.some(
										(event) => event.startsWith("private-turn") && event.endsWith("review=true"),
									),
								);
								const publicPrepared = events.indexOf("prepare-public:true");
								assert.ok(publicPrepared !== -1, events.join("\n"));
								assert.ok(
									publicPrepared < events.indexOf("prepare-public:false"),
									events.join("\n"),
								);
								assert.equal(events.filter((event) => event === "prepare-public:true").length, 1);
							}
							const result: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/result.json"), "utf8"),
							);
							assert.partialDeepStrictEqual(result, { contractVersion: 3, results: [] });
							assert.ok(!existsSync(nodePath.join(cwd, "out/practice-scripts.json")));
							assert.ok(!existsSync(nodePath.join(cwd, "admission.json")));
							assert.ok(!events.includes("derive-change"));
							const feedback: unknown = JSON.parse(
								readFileSync(nodePath.join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(isRecord(feedback));
							assert.equal(
								feedback.admissionDigest,
								"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							);
							assert.ok(feedback.review !== null);
							if (fixture === "compose-continuation-private-prepared") {
								// Nothing private is left to compose, so nothing private failed.
								assert.deepEqual(feedback.feedback, []);
								assert.deepEqual(feedback.compositionFailures, []);
								break;
							}
							// The private feedback the earlier attempt never composed, from the server's current reread.
							assert.ok(Array.isArray(feedback.feedback));
							assert.ok(
								feedback.feedback.some(
									(piece: unknown) => isRecord(piece) && piece.destination === "PRACTICE_PAGE",
								),
								JSON.stringify(feedback.feedback),
							);
							assert.deepEqual(feedback.compositionFailures, []);
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
