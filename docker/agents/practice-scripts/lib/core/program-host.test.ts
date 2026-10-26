import assert from "node:assert/strict";
import { once } from "node:events";
import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, describe, test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { chatOptionsSchema } from "./model-options.ts";

import { MockEmbeddingModelV4, MockLanguageModelV4 } from "ai/test";

import { parseDiff } from "../../kits/scm/diff-parser.ts";
import { stagePracticeScripts } from "../../stage.ts";
import { MODEL_CONCURRENCY } from "./concurrency.ts";
import {
	executionEntrySchema,
	NOT_A_DEFINITION_ERROR,
	type Candidate,
	type ReviewOptions,
	type SlotModels,
} from "./contract.ts";
import { EvidenceReader, noReceipts, type EvidenceSources } from "./evidence-reader.ts";
import { MAX_IPC_MESSAGE_BYTES } from "./ipc.ts";
import { ModelBudget, reservationOf } from "./models-host.ts";
import { UnratedError } from "./practice-script.ts";
import {
	notReached,
	programDigest,
	runPracticeProgram,
	type PracticeProgram,
	type ProgramOutcome,
} from "./program-host.ts";
import { InputReader } from "./script-inputs.ts";
import { ProgramSettlementError, RETURN_MS } from "./script-run.ts";

const roots: string[] = [];
after(async () =>
	Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true }))),
);

const FIXTURES = path.join(import.meta.dirname, "../..", "fixtures");
const HEAD = "0".repeat(40);
const BASE = "b".repeat(40);
const CHANGE = parseDiff(
	[
		"diff --git a/src/cart.ts b/src/cart.ts",
		"--- a/src/cart.ts",
		"+++ b/src/cart.ts",
		"@@ -1,1 +1,8 @@",
		" export class Cart {",
		"+  // increment the item count by one",
		"+  count += 1;",
		"+  // retry once: the payment API drops the first call after an idle period",
		"+  await pay(order).catch(() => pay(order));",
		"+  // TODO clean this up",
		"+  const legacy = true;",
		"+}",
	].join("\n"),
);

const REVIEWED: Candidate = {
	outcome: "COULD_NOT_DETERMINE",
	summary: "The review leaves the practice open",
	explanation: "The fake review answers every review with this result.",
	citations: [
		{
			sourceKind: "scm.pull-request.core",
			sourcePath: "context/metadata.json",
			path: "context/metadata.json",
			startLine: 36,
			endLine: 36,
			quote: '  "changed_files": 1,',
		},
	],
	evidence: {
		openQuestions: [
			{ openQuestion: "What would the review decide?", wouldSettleIt: "A review session." },
		],
	},
	coverage: {
		source: "host",
		reads: 3,
		truncatedReads: 0,
		failedReads: 0,
		receipts: [],
		complete: true,
	},
};

const fixture = async (name: string) => readFile(path.join(FIXTURES, `${name}.ts`), "utf8");

const program = (imports: string, body: string) =>
	`import {scm} from "../kits/scm/index.ts";
import { ${imports} } from "../lib/core/practice-script.ts";\nexport default definePracticeScript(${body});\n`;

interface Workspace {
	practices: string;
	repo: string;
	sources: EvidenceSources;
	inputs: InputReader;
}

async function workspace(scripts: Record<string, string | null>): Promise<Workspace> {
	const { root, practices } = await stagePracticeScripts(scripts);
	await copyFile(path.join(FIXTURES, "comment-kinds.ts"), path.join(practices, "comment-kinds.ts"));
	roots.push(root);
	const repo = path.join(root, "repo");
	await mkdir(path.join(repo, "src"), { recursive: true });
	await writeFile(path.join(repo, "notes.txt"), "hay\nneedle\n");
	const context = path.join(root, "context");
	await mkdir(context);
	await copyFile(
		path.join(FIXTURES, "pull-request-metadata.json"),
		path.join(context, "metadata.json"),
	);
	return {
		practices,
		repo,
		sources: {
			repo,
			headRevision: HEAD,
			treeSourceFile: "repos/reviewed/.git/HEAD",
			contextDir: context,
			contextReference: "context",
			change: { base: BASE, head: HEAD, files: CHANGE },
			sourceKindOf: (sourceFile) =>
				sourceFile === "context/metadata.json" ? "scm.pull-request.core" : undefined,
		},
		inputs: new InputReader({
			practicesDir: practices,
		}),
	};
}

interface RunOptions {
	review?: (options: ReviewOptions, signal: AbortSignal) => Promise<Candidate>;
	models?: Partial<SlotModels>;
	budget?: ModelBudget;
	signal?: AbortSignal;
	deadlineMs?: number;
}

async function run(ws: Workspace, slug: string, options: RunOptions = {}) {
	const read = await ws.inputs.read(slug);
	assert.ok("inputs" in read, "error" in read ? read.error : "");
	const file = path.join(ws.practices, `${slug}.ts`);
	const staged: PracticeProgram = existsSync(file)
		? { script: { path: file, source: await readFile(file) } }
		: { script: null };
	const reviews: ReviewOptions[] = [];
	const logs: string[] = [];
	const receipts = noReceipts();
	const outcome = await runPracticeProgram(
		staged,
		{
			slug,
			inputs: read.inputs,
			files: {
				head: { commit: HEAD },
				change: { base: BASE, head: HEAD, files: new Map(CHANGE) },
			},
			now: "2026-01-01T00:00:00Z",
			deadline: Date.now() + (options.deadlineMs ?? 30_000),
		},
		{
			reader: new EvidenceReader(ws.sources, receipts),
			models: options.models ?? {},
			budget: options.budget ?? new ModelBudget(10_000, MODEL_CONCURRENCY),
			review: async (reviewOptions, signal) => {
				reviews.push(reviewOptions);
				return options.review === undefined ? REVIEWED : options.review(reviewOptions, signal);
			},
			signal: options.signal ?? new AbortController().signal,
			log: (line) => {
				logs.push(line.replace(/^\s*\[[^\]]*\] /u, ""));
			},
			receipts,
		},
	);
	return { outcome, reviews, logs, staged };
}

function reportEntry(slug: string, outcome: ProgramOutcome) {
	return executionEntrySchema.parse({
		practiceSlug: slug,
		program: outcome.program,
		models: outcome.models,
		status: outcome.status,
		stopReason: outcome.stopReason,
		...(outcome.error === undefined ? {} : { error: outcome.error.slice(0, 500) }),
		calls: outcome.calls,
		coverageReached: outcome.coverageReached,
		durationMs: outcome.durationMs,
	});
}

const calls = (
	review: number,
	models: Partial<Record<"chat" | "decision" | "embedding" | "reranking", number>> = {},
) => ({
	review,
	chat: 0,
	decision: 0,
	embedding: 0,
	reranking: 0,
	...models,
});

const slowReview = (ms: number) => async () => {
	await sleep(ms);
	return REVIEWED;
};

void describe("a practice's result", () => {
	void test("a custom program that decides in code returns its own result and asks for no review", async () => {
		const ws = await workspace({ direct: await fixture("direct") });
		const { outcome, reviews, staged } = await run(ws, "direct");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.equal(outcome.stopReason, null);
		assert.deepEqual(reviews, []);
		assert.deepEqual(outcome.calls, calls(0));
		assert.deepEqual(outcome.program, {
			kind: "custom",
			digest: programDigest(staged.script?.source ?? Buffer.alloc(0)),
		});
		assert.deepEqual(outcome.models, []);
		assert.partialDeepStrictEqual(outcome.candidate, {
			outcome: "DOES_NOT_APPLY",
			citations: [
				{
					sourceKind: "scm.pull-request.core",
					sourcePath: "context/metadata.json",
					path: "context/metadata.json",
					startLine: 36,
					endLine: 36,
				},
			],
			coverage: { source: "host", truncatedReads: 0, failedReads: 0, complete: true },
		});
		reportEntry("direct", outcome);
	});

	void test("a program that claims complete coverage after a failed read gets no MET", async () => {
		const ws = await workspace({
			hidden: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => {
					await ctx.evidence.record("..").catch(() => undefined);
					return {
						outcome: "MET",
						summary: "Met",
						explanation: "The program swallowed its failed read.",
						citations: [await ctx.evidence.citeRecord("metadata.json", 36)],
						evidence: {},
						coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true },
					};
				} }`,
			),
		});
		const { outcome } = await run(ws, "hidden");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-candidate");
		assert.match(outcome.error ?? "", /^met_coverage_incomplete:/u);
		assert.equal(outcome.candidate, undefined);
	});

	void test("the built-in asks one review with no prepared input and returns its result", async () => {
		const ws = await workspace({ "built-in": null });
		const { outcome, reviews } = await run(ws, "built-in");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(reviews, [{}]);
		assert.deepEqual(outcome.candidate, REVIEWED);
		assert.deepEqual(outcome.calls, calls(1));
		assert.deepEqual(outcome.program, { kind: "builtin", digest: null });
		assert.deepEqual(outcome.models, []);
		reportEntry("built-in", outcome);
	});

	void test("a built-in whose review gives no result fails with the review's reason", async () => {
		const ws = await workspace({ "built-in": null });
		const { outcome } = await run(ws, "built-in", {
			review: async () => {
				throw new UnratedError("off-format", "the session ended without a submitted result");
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "off-format");
		assert.equal(outcome.error, "the session ended without a submitted result");
		assert.equal(outcome.candidate, undefined);
		reportEntry("built-in", outcome);
	});

	void test("a custom program's prepared input reaches the review parsed, and the review's result passes", async () => {
		const ws = await workspace({ "builtin-like": await fixture("builtin-like") });
		const { outcome, reviews } = await run(ws, "builtin-like");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(reviews, [
			{
				leads: [
					{ at: { change: "src/cart.ts", line: 6, side: "NEW" }, label: "An added TODO comment." },
				],
				facts: { comments: 3 },
				directions: ["Check each added TODO against the criteria."],
				limitations: [{ what: "comments in generated files", count: 1, reason: "selection" }],
			},
		]);
		assert.deepEqual(outcome.candidate, REVIEWED);
		assert.deepEqual(outcome.calls, calls(1));
	});

	void test("review input that breaks the contract fails the program, and no review starts", async () => {
		const ws = await workspace({
			invalid: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => ctx.review({ leads: [{ at: { change: "src/cart.ts", line: 0 }, label: "A lead." }] }) }`,
			),
		});
		const { outcome, reviews } = await run(ws, "invalid");
		assert.deepEqual(reviews, []);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.match(outcome.error ?? "", /^review options: leads\.0\.at/u);
		assert.deepEqual(outcome.calls, calls(0));
	});
});

void describe("one review at a time", () => {
	void test("a second review through the context while one runs is refused", async () => {
		const ws = await workspace({ "concurrent-review": await fixture("concurrent-review") });
		const { outcome, reviews, logs } = await run(ws, "concurrent-review", {
			review: slowReview(100),
		});
		assert.ok(logs.includes("second review: refused"), logs.join("\n"));
		assert.deepEqual(reviews, [{ directions: ["The first review."] }]);
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(outcome.calls, calls(1));
	});

	void test("a review request sent past the context while one runs is refused by the host", async () => {
		const ws = await workspace({ "nested-review": await fixture("nested-review") });
		const { outcome, reviews, logs } = await run(ws, "nested-review", { review: slowReview(100) });
		assert.ok(logs.includes("nested review: refused"), logs.join("\n"));
		assert.equal(reviews.length, 1);
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(outcome.calls, calls(1));
	});
});

void describe("what a program's process may do", () => {
	void test("it inherits no host environment, reads only through the context, and cannot write or start a program", async () => {
		const ws = await workspace({ probe: null });
		const escape = path.join(tmpdir(), "practice-program-escape");
		await writeFile(
			path.join(ws.practices, "probe.ts"),
			`import { execFileSync } from "node:child_process";
import {readFileSync,writeFileSync} from "node:fs";
import {definePracticeScript} from "../lib/core/practice-script.ts";
import {scm} from "../kits/scm/index.ts";
const attempt = (action) => { try { action(); return "allowed"; } catch (error) { return error.code ?? "denied"; } };
export default definePracticeScript({
	meta: {},
	async run(ctx) {
		ctx.log("environment " + JSON.stringify(Object.keys(process.env).sort()));
		ctx.log("repository " + attempt(() => readFileSync(${JSON.stringify(path.join(ws.repo, "notes.txt"))})));
		ctx.log("system " + attempt(() => readFileSync("/etc/hostname")));
		ctx.log("write " + attempt(() => writeFileSync(${JSON.stringify(escape)}, "x")));
		ctx.log("program " + attempt(() => execFileSync("cat", ["/proc/1/environ"])));
		ctx.log("context " + (await scm(ctx).files.head.read("notes.txt")).status);
		return ${JSON.stringify(REVIEWED)};
	},
});
`,
		);
		const { outcome, logs, reviews } = await run(ws, "probe");
		// macOS can initialize this CoreFoundation marker even when fork receives an empty environment.
		const allowedEnvironments =
			process.platform === "darwin" ? [[], ["__CF_USER_TEXT_ENCODING"]] : [[]];
		assert.ok(
			allowedEnvironments.some((keys) => logs.includes(`environment ${JSON.stringify(keys)}`)),
			`expected only platform environment keys in:\n${logs.join("\n")}`,
		);
		for (const line of [
			"repository ERR_ACCESS_DENIED",
			"system ERR_ACCESS_DENIED",
			"write ERR_ACCESS_DENIED",
			"program ERR_ACCESS_DENIED",
			"context FOUND",
		]) {
			assert.ok(logs.includes(line), `${line} in:\n${logs.join("\n")}`);
		}
		assert.equal(existsSync(escape), false);
		assert.deepEqual(outcome.coverageReached, { reads: 1, truncatedReads: 0, failedReads: 0 });
		assert.deepEqual(reviews, []);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-candidate");
		assert.equal(
			outcome.error,
			"the result carries host coverage that no review of this practice returned",
		);
	});

	void test("a program may return its review's result with its own words, but never with other host coverage", async () => {
		const ws = await workspace({
			reworded: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { const reviewed = await ctx.review(); return { ...reviewed, summary: "The program's own summary" }; } }`,
			),
			recounted: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { const reviewed = await ctx.review(); return { ...reviewed, coverage: { ...reviewed.coverage, reads: reviewed.coverage.reads + 1 } }; } }`,
			),
		});
		const reworded = await run(ws, "reworded");
		assert.equal(reworded.outcome.status, "ok", reworded.outcome.error);
		assert.equal(reworded.outcome.candidate?.summary, "The program's own summary");
		const recounted = await run(ws, "recounted");
		assert.equal(recounted.outcome.status, "failed");
		assert.equal(recounted.outcome.stopReason, "invalid-candidate");
	});
});

void describe("the host's bounds on a program's messages", () => {
	void test("child shutdown drains model calls and returns a stable report", async () => {
		let active = 0;
		const embedding = new MockEmbeddingModelV4({
			doEmbed: async ({ abortSignal }) => {
				active += 1;
				try {
					assert.ok(abortSignal !== undefined);
					await once(abortSignal, "abort");
					await sleep(40);
					throw new Error("stopped");
				} finally {
					active -= 1;
				}
			},
		});
		const ws = await workspace({ flood: await fixture("flood") });
		const budget = new ModelBudget(200_000, 4);
		const { outcome } = await run(ws, "flood", { models: { embedding }, budget });
		assert.equal(active, 0);
		assert.equal(outcome.calls.embedding, 4);
		assert.equal(outcome.models[0]?.failures.deadline, 32);
		const snapshot = JSON.stringify(outcome);
		await sleep(50);
		assert.equal(JSON.stringify(outcome), snapshot);
		assert.ok(
			"value" in
				(await budget.run(1, new AbortController().signal, async () => ({
					value: "next practice",
					tokens: 1,
				}))),
		);
	});

	void test("a pending review settles before the next practice starts", async () => {
		const candidate = {
			...REVIEWED,
			coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true },
		};
		const ws = await workspace({
			first: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { void ctx.review(); return ${JSON.stringify(candidate)}; } }`,
			),
			next: null,
		});
		let active = false;
		const review = async (_options: ReviewOptions, signal: AbortSignal) => {
			assert.equal(active, false);
			active = true;
			try {
				await once(signal, "abort");
				await sleep(40);
				return REVIEWED;
			} finally {
				active = false;
			}
		};
		const first = await run(ws, "first", { review });
		assert.equal(first.outcome.status, "ok", first.outcome.error);
		assert.equal(active, false);
		const next = await run(ws, "next", {
			review: async () => {
				assert.equal(active, false);
				return REVIEWED;
			},
		});
		assert.equal(next.outcome.status, "ok", next.outcome.error);
	});

	void test("a request that ignores cancellation ends the attempt within the settlement bound", async () => {
		const pending = Promise.withResolvers<never>();
		const ws = await workspace({
			stuck: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { void ctx.review(); throw new Error("stop"); } }`,
			),
		});
		try {
			await assert.rejects(
				run(ws, "stuck", { review: async () => pending.promise }),
				ProgramSettlementError,
			);
		} finally {
			pending.reject(new Error("cleanup"));
		}
	});
	void test("a shared-reference object is refused without JSON expansion in the host", async () => {
		const ws = await workspace({ graph: await fixture("shared-reference-ipc") });
		const { outcome } = await run(ws, "graph");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(outcome.error, "the program's process must send JSON text");
		assert.equal(outcome.candidate, undefined);
	});

	void test("malformed JSON text is refused before a request runs", async () => {
		const ws = await workspace({
			malformed: program(
				"definePracticeScript",
				`{ meta: {}, async run() { process.send("{"); return Promise.withResolvers().promise; } }`,
			),
		});
		const { outcome, reviews } = await run(ws, "malformed");
		assert.equal(outcome.error, "the program sent a message that is not JSON");
		assert.deepEqual(reviews, []);
	});
	void test("more requests in flight than the host answers stop the program", async () => {
		const embedding = new MockEmbeddingModelV4({
			doEmbed: async ({ abortSignal }) => {
				if (abortSignal !== undefined) {
					await once(abortSignal, "abort");
				}
				throw new Error("aborted");
			},
		});
		const ws = await workspace({ flood: await fixture("flood") });
		const { outcome } = await run(ws, "flood", { models: { embedding } });
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(outcome.error, "more than 32 requests at once");
		reportEntry("flood", outcome);
	});

	void test("a message larger than the bound stops the program", async () => {
		const ws = await workspace({
			large: program(
				"definePracticeScript",
				`{ meta: {}, run: async () => ({ outcome: "x".repeat(${MAX_IPC_MESSAGE_BYTES}) }) }`,
			),
		});
		const { outcome } = await run(ws, "large");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(
			outcome.error,
			`the program sent a message larger than ${MAX_IPC_MESSAGE_BYTES} bytes`,
		);
	});

	void test("a request id used again while its request is open stops the program", async () => {
		const ws = await workspace({
			reused: program(
				"definePracticeScript",
				`{ meta: {}, async run() {
					process.send(JSON.stringify({ kind: "review", id: 7, options: {} }));
					process.send(JSON.stringify({ kind: "read", id: 7, request: { op: "record", name: "metadata.json" } }));
					return Promise.withResolvers().promise;
				} }`,
			),
		});
		const { outcome } = await run(ws, "reused", { review: slowReview(300) });
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(outcome.error, "the program reused the id of request 7");
	});

	void test("a second final message stops the program", async () => {
		const ws = await workspace({ "two-final-results": await fixture("two-final-results") });
		const { outcome } = await run(ws, "two-final-results");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(outcome.candidate, undefined);
	});
});

void describe("a program that does not stop when the host stops it", { concurrency: true }, () => {
	void test("a custom program cannot replace its cancelled review with a final candidate", async () => {
		const cancel = new AbortController();
		const candidate = {
			...REVIEWED,
			coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true },
		};
		const ws = await workspace({
			late: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { try { await ctx.review(); } catch {} return ${JSON.stringify(candidate)}; } }`,
			),
		});
		const { outcome } = await run(ws, "late", {
			signal: cancel.signal,
			review: async () => {
				cancel.abort(new UnratedError("cancelled", "the attempt was cancelled"));
				return REVIEWED;
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "cancelled");
		assert.equal(outcome.candidate, undefined);
	});

	void test("a built-in review that answers after its deadline supplies no candidate", async () => {
		const ws = await workspace({ builtin: null });
		const { outcome } = await run(ws, "builtin", {
			deadlineMs: 100,
			review: async (_options, signal) => {
				await once(signal, "abort");
				return REVIEWED;
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "deadline");
		assert.equal(outcome.candidate, undefined);
	});
	void test("a built-in review that answers after cancellation supplies no candidate", async () => {
		const cancel = new AbortController();
		const ws = await workspace({ builtin: null });
		const { outcome } = await run(ws, "builtin", {
			signal: cancel.signal,
			review: async () => {
				cancel.abort(new UnratedError("cancelled", "the attempt was cancelled"));
				return REVIEWED;
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "cancelled");
		assert.equal(outcome.candidate, undefined);
	});
	void test("on cancellation, its open review fails, a later reply is not delivered, and the host ends it", async () => {
		const cancel = new AbortController();
		const ws = await workspace({ cancellation: await fixture("cancellation") });
		const { outcome, logs } = await run(ws, "cancellation", {
			signal: cancel.signal,
			review: async () => {
				cancel.abort(new UnratedError("cancelled", "the attempt was cancelled"));
				await sleep(100);
				return REVIEWED;
			},
		});
		assert.ok(logs.includes("review: cancelled"), logs.join("\n"));
		assert.ok(
			!logs.some((line) => line.startsWith("late reply") || line === "review: answered"),
			logs.join("\n"),
		);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "cancelled");
		assert.equal(outcome.error, "the host stopped this program: cancelled");
		assert.ok(
			(outcome.durationMs ?? 0) >= RETURN_MS,
			`ended after ${String(outcome.durationMs)}ms`,
		);
		reportEntry("cancellation", outcome);
	});

	void test("at its deadline, its open review fails, a later reply is not delivered, and the host ends it", async () => {
		const ws = await workspace({ cancellation: await fixture("cancellation") });
		const { outcome, logs } = await run(ws, "cancellation", {
			deadlineMs: 1000,
			review: async (_options, signal) => {
				await once(signal, "abort");
				await sleep(100);
				return REVIEWED;
			},
		});
		assert.ok(logs.includes("review: deadline"), logs.join("\n"));
		assert.ok(
			!logs.some((line) => line.startsWith("late reply") || line === "review: answered"),
			logs.join("\n"),
		);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "deadline");
		assert.ok(
			(outcome.durationMs ?? 0) >= 1000 + RETURN_MS - 100,
			`ended after ${String(outcome.durationMs)}ms`,
		);
	});
});

void test("direct model calls share the attempt's budget: a later practice sees what is left, and a call above it never reaches the model", async () => {
	const call = {
		prompt: [{ role: "user", content: [{ type: "text", text: "Say hi." }] }],
		maxOutputTokens: 1000,
	};
	const reserve = reservationOf("chat", chatOptionsSchema.parse(call));
	const chat = new MockLanguageModelV4({
		doGenerate: {
			content: [{ type: "text", text: "hi" }],
			finishReason: { unified: "stop", raw: "stop" },
			usage: {
				inputTokens: { total: 100, noCache: 100, cacheRead: 0, cacheWrite: 0 },
				outputTokens: { total: 50, text: 50, reasoning: 0 },
			},
			warnings: [],
		},
	});
	const spend = program(
		"definePracticeScript, reasonOf",
		`{
			meta: { models: { chat: "required" } },
			async run(ctx) {
				const before = ctx.budget.remaining().tokens;
				const chat = await ctx.models.chat.doGenerate(${JSON.stringify(call)}).then(() => "rated", (error) => reasonOf(error));
				return ctx.review({ facts: { before, after: ctx.budget.remaining().tokens, chat } });
			},
		}`,
	);
	const ws = await workspace({ first: spend, second: spend });
	const budget = new ModelBudget(reserve + 100, MODEL_CONCURRENCY);
	const first = await run(ws, "first", { models: { chat }, budget });
	assert.equal(first.outcome.status, "ok", first.outcome.error);
	assert.deepEqual(first.reviews[0]?.facts, {
		before: reserve + 100,
		after: reserve - 50,
		chat: "rated",
	});
	assert.equal(budget.left, reserve - 50);
	assert.deepEqual(first.outcome.calls, calls(1, { chat: 1 }));

	const second = await run(ws, "second", { models: { chat }, budget });
	assert.equal(second.outcome.status, "ok", second.outcome.error);
	assert.deepEqual(second.reviews[0]?.facts, {
		before: reserve - 50,
		after: reserve - 50,
		chat: "budget",
	});
	assert.equal(chat.doGenerateCalls.length, 1);
	assert.deepEqual(second.outcome.models, [
		{ slot: "chat", need: "required", bound: true, failures: { budget: 1 } },
	]);
});

void describe("a program that gives no result", () => {
	void test("one that throws an unrated error fails with its reason", async () => {
		const ws = await workspace({
			unavailable: program(
				"definePracticeScript, UnratedError",
				`{ meta: {}, run: async () => { throw new UnratedError("unavailable", "the change was not captured"); } }`,
			),
		});
		const { outcome } = await run(ws, "unavailable");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "unavailable");
		assert.equal(outcome.error, "the change was not captured");
	});

	void test("one that returns a value the contract refuses fails as an invalid result, with its refusal code", async () => {
		const ws = await workspace({
			uncited: program(
				"definePracticeScript",
				`{ meta: {}, run: async () => (${JSON.stringify({ ...REVIEWED, citations: [] })}) }`,
			),
		});
		const { outcome } = await run(ws, "uncited");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-candidate");
		assert.match(
			outcome.error ?? "",
			/^the program returned no valid result: result_citations_required: /u,
		);
	});

	void test("one whose default export is not a practice script fails as an invalid program", async () => {
		const ws = await workspace({ positional: "export default () => ({ outcome: 'MET' });\n" });
		const { outcome } = await run(ws, "positional");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.error, NOT_A_DEFINITION_ERROR);
		assert.equal(outcome.stopReason, "invalid-program");
	});
});

void test("a program whose required direct model is not bound is skipped", async () => {
	const ws = await workspace({ "requires-decision": await fixture("requires-decision") });
	const { outcome, reviews } = await run(ws, "requires-decision");
	assert.equal(outcome.status, "withheld");
	assert.equal(outcome.stopReason, "unavailable");
	assert.equal(outcome.error, undefined);
	assert.deepEqual(outcome.models, [
		{ slot: "decision", need: "required", bound: false, failures: {} },
	]);
	assert.deepEqual(reviews, []);
	reportEntry("requires-decision", outcome);
});

void test("a practice the host did not reach has no duration", async () => {
	const source = Buffer.from(await fixture("direct"));
	const outcome = notReached({ script: { path: "direct.ts", source } }, "deadline");
	assert.deepEqual(outcome, {
		program: { kind: "custom", digest: programDigest(source) },
		models: [],
		status: "not-reached",
		stopReason: "deadline",
		calls: calls(0),
		coverageReached: { reads: 0, truncatedReads: 0, failedReads: 0 },
		durationMs: null,
	});
	reportEntry("direct", outcome);
	assert.deepEqual(notReached({ script: null }, "cancelled").program, {
		kind: "builtin",
		digest: null,
	});
});
