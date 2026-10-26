import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
	existsSync,
	mkdtempSync,
	realpathSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { after, test } from "node:test";

import {
	executionReportSchema,
	REASONS,
	type ExecutionEntry,
} from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { PRACTICES_DIR } from "./golden-review-workspace.ts";
import {
	capturedLines,
	directProgram,
	field,
	readJsonFile,
	runRunner,
	stagePractices,
	type RunnerExit,
	type SessionBehaviour,
	type StagedPractice,
	INVALID_META_PROGRAM,
} from "./program-host-runner.ts";

const roots: string[] = [];
after(() => {
	for (const root of roots) {
		rmSync(root, { recursive: true, force: true });
	}
});

const REVIEWING_PROGRAM = `import { definePracticeScript } from "../lib/core/practice-script.ts";
export default definePracticeScript({
	meta: {},
	async run(ctx) {
		return ctx.review({ directions: ["Check each added authentication call."] });
	},
});
`;

interface Run extends RunnerExit {
	cwd: string;
}

async function run(
	practices: readonly StagedPractice[],
	sessions: Record<string, SessionBehaviour> = {},
	env: Record<string, string> = {},
	stage: (cwd: string) => void = () => undefined,
): Promise<Run> {
	const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-program-host-")));
	roots.push(cwd);
	stagePractices(cwd, practices);
	stage(cwd);
	return { cwd, ...(await runRunner(cwd, sessions, env)) };
}

function executionReport(cwd: string) {
	const parsed = executionReportSchema.safeParse(readJsonFile(cwd, "out/practice-scripts.json"));
	assert.ok(parsed.success, parsed.error?.message);
	return parsed.data;
}

function entryOf(entries: readonly ExecutionEntry[], slug: string): ExecutionEntry {
	const entry = entries.find((item) => item.practiceSlug === slug);
	assert.ok(entry !== undefined, `${slug} is reported`);
	return entry;
}

function admittedSlugs(cwd: string): unknown[] {
	const results: unknown = field(readJsonFile(cwd, "out/result.json"), "results");
	assert.ok(Array.isArray(results));
	return results.map((result: unknown) => field(result, "practiceSlug"));
}

const noCalls = { review: 0, chat: 0, decision: 0, embedding: 0, reranking: 0 };
const digestOf = (cwd: string, slug: string) =>
	createHash("sha256")
		.update(readFileSync(nodePath.join(cwd, PRACTICES_DIR, `${slug}.ts`)))
		.digest("hex");

const main = await run(
	[
		{ slug: "a-direct", program: directProgram() },
		{ slug: "b-builtin" },
		{ slug: "c-invalid-meta", program: INVALID_META_PROGRAM },
		{ slug: "d-silent", program: REVIEWING_PROGRAM },
		{ slug: "e-no-inputs", inputs: false },
	],
	{ "d-silent": "silent" },
);

void test("the runner admits the results that passed the host's checks and exits", () => {
	assert.equal(main.status, 0, main.stderr);
});

void test("a partial run submits completed results and explicit runtime non-results separately", () => {
	const [admission] = capturedLines(main.cwd, "admission");
	const body: unknown = field(admission, "body");
	assert.deepEqual(field(body, "nonResults"), [
		{ practiceSlug: "c-invalid-meta", reason: "EXECUTION_FAILED" },
		{ practiceSlug: "d-silent", reason: "EXECUTION_FAILED" },
		{ practiceSlug: "e-no-inputs", reason: "EXECUTION_INCOMPLETE" },
	]);
	assert.deepEqual(admittedSlugs(main.cwd), ["a-direct", "b-builtin"]);
});

void test("a custom program that returns a result directly opens no session, and its result is admitted", () => {
	const prompted = capturedLines(main.cwd, "prompts").map((line) => field(line, "slug"));
	assert.equal(prompted.includes("a-direct"), false);
	const [admission] = capturedLines(main.cwd, "admission");
	const body: unknown = field(admission, "body");
	assert.partialDeepStrictEqual(body, {
		results: [
			{
				practiceSlug: "a-direct",
				candidate: {
					outcome: "DOES_NOT_APPLY",
					summary: "No user interface changes; note none",
					citations: [
						{
							sourceKind: "scm.pull-request.core",
							sourcePath: "evidence/metadata.json",
							startLine: 1,
							endLine: 1,
						},
					],
					coverage: { source: "host", truncatedReads: 0, failedReads: 0, complete: true },
				},
			},
		],
	});
	const entry = entryOf(executionReport(main.cwd).practices, "a-direct");
	assert.deepEqual(
		{ ...entry, durationMs: typeof entry.durationMs },
		{
			practiceSlug: "a-direct",
			program: { kind: "custom", digest: digestOf(main.cwd, "a-direct") },
			models: [{ slot: "chat", need: "optional", bound: false, failures: {} }],
			status: "ok",
			stopReason: null,
			calls: noCalls,
			reviewSessions: { modelCalls: 0, evidenceRefusals: 0, formatRefusals: 0 },
			coverageReached: { reads: 1, truncatedReads: 0, failedReads: 0 },
			durationMs: "number",
		},
	);
});

void test("the built-in program opens one review session and reports the reads that session made", () => {
	const sessions = capturedLines(main.cwd, "prompts").filter(
		(line) => field(line, "slug") === "b-builtin",
	);
	assert.equal(sessions.length, 1);
	assert.match(
		JSON.stringify(capturedLines(main.cwd, "submitted")),
		/b-builtin: stored \(negative\)/u,
	);
	const entry = entryOf(executionReport(main.cwd).practices, "b-builtin");
	assert.deepEqual(
		{ ...entry, durationMs: typeof entry.durationMs },
		{
			practiceSlug: "b-builtin",
			program: { kind: "builtin", digest: null },
			models: [],
			status: "ok",
			stopReason: null,
			calls: { ...noCalls, review: 1 },
			reviewSessions: { modelCalls: 1, evidenceRefusals: 0, formatRefusals: 0 },
			coverageReached: { reads: 1, truncatedReads: 0, failedReads: 0 },
			durationMs: "number",
		},
	);
});

void test("a program whose meta the contract refuses fails as an invalid program, and only it is left out", () => {
	const entry = entryOf(executionReport(main.cwd).practices, "c-invalid-meta");
	assert.partialDeepStrictEqual(entry, {
		program: { kind: "custom", digest: digestOf(main.cwd, "c-invalid-meta") },
		status: "failed",
		stopReason: "invalid-program",
		calls: noCalls,
	});
	assert.match(String(field(entry, "error")), /^meta: /u);
	assert.deepEqual(admittedSlugs(main.cwd), ["a-direct", "b-builtin"]);
});

void test("a review session that ends without a result fails its program with an unrated reason", () => {
	const prompts = capturedLines(main.cwd, "prompts").filter(
		(line) => field(line, "slug") === "d-silent",
	);
	assert.equal(prompts.length, 2);
	assert.equal(new Set(prompts.map((line) => field(line, "session"))).size, 1);
	assert.ok(prompts.every((line) => field(line, "reviewInput") === true));
	const entry = entryOf(executionReport(main.cwd).practices, "d-silent");
	assert.partialDeepStrictEqual(entry, {
		status: "failed",
		stopReason: "off-format",
		error: "the review session of d-silent ended without a result",
		calls: { ...noCalls, review: 1 },
	});
	assert.ok(REASONS.some((reason) => reason === entry.stopReason));
	assert.equal(admittedSlugs(main.cwd).includes("d-silent"), false);
});

void test("a practice without its staged inputs fails as unavailable and opens no session", () => {
	const entry = entryOf(executionReport(main.cwd).practices, "e-no-inputs");
	assert.partialDeepStrictEqual(entry, {
		program: { kind: "builtin", digest: null },
		status: "failed",
		stopReason: "unavailable",
		calls: noCalls,
		durationMs: 0,
	});
	assert.match(entry.error ?? "", /inputs are not valid/u);
	const prompted = capturedLines(main.cwd, "prompts").map((line) => field(line, "slug"));
	assert.equal(prompted.includes("e-no-inputs"), false);
});

void test("the execution report has one entry for each admitted practice, sorted by slug", () => {
	const report = executionReport(main.cwd);
	assert.equal(report.truncated, false);
	assert.deepEqual(
		report.practices.map((entry) => entry.practiceSlug),
		["a-direct", "b-builtin", "c-invalid-meta", "d-silent", "e-no-inputs"],
	);
});

void test("the admission request is result.json and review-state.json, sorted by slug, sent after the review input is removed", () => {
	const admissions = capturedLines(main.cwd, "admission");
	assert.equal(admissions.length, 1);
	const [admission] = admissions;
	const body: unknown = field(admission, "body");
	assert.deepEqual(readJsonFile(main.cwd, "out/result.json"), body);
	assert.deepEqual(readJsonFile(main.cwd, "out/review-state.json"), body);
	assert.partialDeepStrictEqual(body, {
		contractVersion: 3,
		results: [{ practiceSlug: "a-direct" }, { practiceSlug: "b-builtin" }],
	});
	assert.equal(field(admission, "reviewInputPresent"), false);
	assert.equal(existsSync(nodePath.join(main.cwd, "work/review-input")), false);
	assert.deepEqual(capturedLines(main.cwd, "change-view"), [true]);
});

void test("a practice that the run's safety line stops before it starts is reported not reached", async () => {
	// Leave the program return buffer outside the safety deadline so the first review can finish.
	const stopped = await run(
		[{ slug: "a-first" }, { slug: "b-hangs" }, { slug: "c-never" }],
		{ "b-hangs": "hang" },
		{ AGENT_BUDGET_MS: "7000" },
	);
	assert.equal(stopped.status, 0, stopped.stderr);
	const report = executionReport(stopped.cwd);
	assert.partialDeepStrictEqual(entryOf(report.practices, "a-first"), { status: "ok" });
	assert.partialDeepStrictEqual(entryOf(report.practices, "b-hangs"), {
		status: "failed",
		stopReason: "deadline",
		calls: { ...noCalls, review: 1 },
	});
	assert.deepEqual(entryOf(report.practices, "c-never"), {
		practiceSlug: "c-never",
		program: { kind: "builtin", digest: null },
		models: [],
		status: "not-reached",
		stopReason: "deadline",
		calls: noCalls,
		coverageReached: { reads: 0, truncatedReads: 0, failedReads: 0 },
		durationMs: null,
	});
	const prompted = capturedLines(stopped.cwd, "prompts").map((line) => field(line, "slug"));
	assert.equal(prompted.includes("c-never"), false);
});

void test("a composition continuation derives no change, runs no program and writes no execution report", async () => {
	const continued = await run(
		[
			{ slug: "a-direct", program: directProgram('ctx.log("the program ran");') },
			{ slug: "b-builtin" },
		],
		{},
		{},
		(cwd) => {
			writeFileSync(
				nodePath.join(cwd, "evidence/composition-continuation.json"),
				JSON.stringify({
					contractVersion: 4,
					admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
					publicReviewed: false,
					privateProgress: {
						decidedResultIds: [],
						practicePageDeliveries: 0,
						conversationDeliveries: 0,
					},
					admitted: [],
					withheld: [],
					practicePageSupport: null,
					conversationEligibility: null,
				}),
			);
		},
	);
	assert.equal(continued.status, 0, continued.stderr);
	assert.doesNotMatch(continued.stderr, /the program ran/u);
	assert.deepEqual(capturedLines(continued.cwd, "prompts"), []);
	assert.deepEqual(capturedLines(continued.cwd, "admission"), []);
	assert.deepEqual(capturedLines(continued.cwd, "change-view"), []);
	assert.equal(existsSync(nodePath.join(continued.cwd, "out/practice-scripts.json")), false);
});
