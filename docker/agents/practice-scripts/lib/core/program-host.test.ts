import assert from "node:assert/strict";
import { once } from "node:events";
import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, describe, test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { pathToFileURL } from "node:url";

import { parseDiff } from "../../kits/scm/diff-parser.ts";
import { stagePracticeScripts } from "../../stage.ts";
import {
	executionEntrySchema,
	NOT_A_DEFINITION_ERROR,
	type Candidate,
	type ReviewOptions,
} from "./contract.ts";
import { EvidenceReader, noReceipts, type EvidenceSources } from "./evidence-reader.ts";
import { MAX_IPC_MESSAGE_BYTES, type ReadRequest } from "./ipc.ts";
import { resolveLeads } from "./leads.ts";
import { UnratedError } from "./practice-script.ts";
import {
	notReached,
	programDigest,
	runPracticeProgram,
	type PracticeProgram,
	type ProgramOutcome,
	type RequiredSource,
} from "./program-host.ts";
import { InputReader } from "./script-inputs.ts";
import { forkedProgram, ProgramSettlementError, RETURN_MS } from "./script-run.ts";

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
	`import {scm} from "@hephaestus/practice-scripts/scm";
import { ${imports} } from "@hephaestus/practice-scripts";\nexport default definePracticeScript(${body});\n`;

const cited = `await ctx.evidence.citeRecord("metadata.json", 36)`;
const readThen = (before: string, result: string) =>
	program(
		"definePracticeScript",
		`{ meta: {}, run: async (ctx) => {
			${before}
			await ctx.evidence.record("metadata.json");
			return ${result};
		} }`,
	);
const FAILED_READ = `await ctx.evidence.record("..").catch(() => undefined);`;
const NOT_CAPTURED = `await ctx.evidence.record("review_threads.json");`;
const FAILED_CITE = `await ctx.evidence.citeRecord("review_threads.json", 1).catch(() => undefined);`;
const ABSENT_FILE = `await scm(ctx, "scm.pull_request").head?.read("no-such-file.txt");`;
const NOT_MET = `{ outcome: "NOT_MET", summary: "The change is too large",
	explanation: "The record counts the changed files.", citations: [], evidence: {},
	problems: [{ severity: "MINOR", summary: "Too many changed files",
		explanation: "The record states the count.", citations: [${cited}],
		basis: { type: "state", explanation: "The pull request has this state." } }] }`;
const OPEN = `{ outcome: "COULD_NOT_DETERMINE", summary: "The read failed",
	explanation: "The program could not read a source.", citations: [${cited}],
	evidence: { openQuestions: [{ openQuestion: "What does it say?", wouldSettleIt: "A read." }] } }`;
const MET = `{ outcome: "MET", summary: "Met", explanation: "The record shows it.",
	citations: [${cited}], evidence: {} }`;
const DOES_NOT_APPLY = `{ outcome: "DOES_NOT_APPLY", summary: "No view changed",
	explanation: "No changed file is a view.", citations: [${cited}],
	evidence: { inapplicability: { consulted: ["scm.pull-request.core"],
		prerequisite: "user interface files", ruledOutBy: "No changed file is a view." } } }`;
const KIT_FAILURE = `await scm(ctx, "scm.pull_request").reviewRecord().catch(() => undefined);`;
const kitProgram = (result: string) => readThen(KIT_FAILURE, result);

interface Workspace {
	practices: string;
	repo: string;
	sources: EvidenceSources;
	inputs: InputReader;
}

const malformed = async (ws: Workspace) => {
	await writeFile(path.join(ws.sources.contextDir ?? "", "review_threads.json"), "{ not json");
	ws.sources.sourceKindOf = (sourceFile) =>
		sourceFile === "context/metadata.json" ? "scm.pull-request.core" : "scm.review-threads";
};

/** Stored scripts, as production stages them. */
async function workspace(scripts: Record<string, string | null>): Promise<Workspace> {
	const { root, practices } = await stagePracticeScripts(scripts);
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
	/** Answers the program's reads in place of the captured sources. */
	answer?: EvidenceReader["answer"];
	signal?: AbortSignal;
	deadlineMs?: number;
	requiredSources?: RequiredSource[];
	log?: (line: string) => void;
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
			reader: Object.assign(
				new EvidenceReader(ws.sources, receipts),
				options.answer === undefined ? {} : { answer: options.answer },
			),
			start: forkedProgram,
			review: async (reviewOptions, signal) => {
				reviews.push(reviewOptions);
				return options.review === undefined ? REVIEWED : options.review(reviewOptions, signal);
			},
			signal: options.signal ?? new AbortController().signal,
			log: (line) => {
				logs.push(line.replace(/^\s*\[[^\]]*\] /u, ""));
				options.log?.(line);
			},
			receipts,
			requiredSources: options.requiredSources ?? [],
		},
	);
	return { outcome, reviews, logs, staged };
}

function reportEntry(slug: string, outcome: ProgramOutcome) {
	return executionEntrySchema.parse({
		practiceSlug: slug,
		program: outcome.program,
		status: outcome.status,
		stopReason: outcome.stopReason,
		...(outcome.error === undefined ? {} : { error: outcome.error.slice(0, 500) }),
		calls: outcome.calls,
		coverageReached: outcome.coverageReached,
		durationMs: outcome.durationMs,
	});
}

const calls = (review: number) => ({ review });

const slowReview = (ms: number) => async () => {
	await sleep(ms);
	return REVIEWED;
};

/** A program that cites the change, and reads the core record first when `read` is set. */
const decidesFromTheChange = (read: boolean) =>
	program(
		"definePracticeScript",
		`{ meta: {}, run: async (ctx) => {
				${read ? 'await ctx.evidence.record("metadata.json");' : ""}
				const found = await scm(ctx, "scm.pull_request").change?.cite("src/cart.ts", "NEW", 2);
				return {
					outcome: "MET",
					summary: "Met",
					explanation: "The change shows the practice.",
					citations: [found],
					evidence: {},
				};
			} }`,
	);

/** A search of the whole checkout for `pattern` that ran to the end. */
const searched = (pattern: string, matches = 0) => ({
	sourceKind: "scm.repository.tree",
	sourcePath: "repos/reviewed/.git/HEAD",
	path: ".",
	version: HEAD,
	status: "SEARCHED" as const,
	pattern,
	matches,
});

const searchesOf = (outcome: ProgramOutcome) =>
	(outcome.readReceipts ?? []).filter((receipt) => receipt.status === "SEARCHED");

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

	void test("a program result that types its own coverage is refused", async () => {
		const ws = await workspace({
			typed: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => ({
					outcome: "MET",
					summary: "Met",
					explanation: "The program typed its coverage.",
					citations: [await ctx.evidence.citeRecord("metadata.json", 36)],
					evidence: {},
					coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true },
				}) }`,
			),
		});
		const { outcome } = await run(ws, "typed");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-candidate");
		assert.match(outcome.error ?? "", /coverage/u);
	});

	void test("code that cites a source it did not read gets no MET", async () => {
		const ws = await workspace({
			unread: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => ({
					outcome: "MET",
					summary: "Met",
					explanation: "The program quoted one line and read nothing.",
					citations: [await ctx.evidence.citeRecord("metadata.json", 36)],
					evidence: {},
				}) }`,
			),
		});
		const { outcome } = await run(ws, "unread");
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-candidate");
		assert.match(outcome.error ?? "", /^met_coverage_incomplete:/u);
	});

	void test("code gets no MET until it reads each source that its practice requires", async () => {
		const ws = await workspace({
			unread: decidesFromTheChange(false),
			read: decidesFromTheChange(true),
		});
		const requiredSources = [
			{ sourceKind: "scm.pull-request.core", sourcePath: "context/metadata.json" },
		];
		const unread = await run(ws, "unread", { requiredSources });
		assert.equal(unread.outcome.status, "failed");
		assert.match(unread.outcome.error ?? "", /^met_coverage_incomplete:/u);
		const read = await run(ws, "read", { requiredSources });
		assert.equal(read.outcome.status, "ok", read.outcome.error);
		assert.partialDeepStrictEqual(read.outcome.candidate, {
			outcome: "MET",
			coverage: { source: "host", complete: true },
		});
	});

	void test("a search proves an absence only for the decision that ran it", async () => {
		const session = searched("ControllerAdvice");
		const lead = searched("needle", 1);
		const ws = await workspace({
			kept: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => {
						await scm(ctx, "scm.pull_request").head?.grep("needle");
						return ctx.review({});
					} }`,
			),
			decided: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => {
						await scm(ctx, "scm.pull_request").head?.grep("needle");
						await ctx.evidence.record("metadata.json");
						const found = await scm(ctx, "scm.pull_request").change?.cite("src/cart.ts", "NEW", 2);
						return { outcome: "MET", summary: "Met", explanation: "The change shows the practice.",
							citations: [found], evidence: {} };
					} }`,
			),
		});
		const review = async () => ({
			...REVIEWED,
			coverage: { ...REVIEWED.coverage, receipts: [session] },
		});
		// The review's unchanged result keeps its own search, never the script's lead search.
		const kept = await run(ws, "kept", { review });
		assert.equal(kept.outcome.status, "ok", kept.outcome.error);
		assert.deepEqual(searchesOf(kept.outcome), [session]);

		// A result that code decided keeps the code's search, never a review's.
		const decided = await run(ws, "decided", { review });
		assert.equal(decided.outcome.status, "ok", decided.outcome.error);
		assert.deepEqual(searchesOf(decided.outcome), [lead]);
	});

	for (const [outcome, result] of Object.entries({ NOT_MET, OPEN, MET, DOES_NOT_APPLY })) {
		for (const [cause, before, reason] of [
			["a failed read", FAILED_READ, /1 of its reads failed/u],
			["a failed citation", FAILED_CITE, /1 of its reads failed/u],
			["a record that was not captured", NOT_CAPTURED, /review_threads\.json/u],
		] as const) {
			void test(`code that decides ${outcome} after ${cause} is refused with its own reason`, async () => {
				const ws = await workspace({ decides: readThen(before, result) });
				const { outcome: ran } = await run(ws, "decides");
				assert.equal(ran.status, "failed");
				assert.equal(ran.stopReason, "unread-evidence");
				assert.match(ran.error ?? "", reason);
				assert.equal(ran.candidate, undefined);
			});
		}
	}

	for (const [outcome, result] of Object.entries({ NOT_MET, OPEN, MET, DOES_NOT_APPLY })) {
		void test(`code that decides ${outcome} after it catches a failure of the kit is refused`, async () => {
			const ws = await workspace({ decides: kitProgram(result) });
			await malformed(ws);
			const { outcome: ran } = await run(ws, "decides");
			assert.equal(ran.status, "failed");
			assert.equal(ran.stopReason, "unread-evidence");
			assert.match(ran.error ?? "", /review_threads\.json is not valid JSON/u);
			assert.equal(ran.candidate, undefined);
			assert.equal(ran.coverageReached.failedReads, 1);
		});
	}

	void test("a review after a caught failure of the kit names it in its coverage", async () => {
		const ws = await workspace({ reviews: kitProgram("ctx.review({})") });
		await malformed(ws);
		const { outcome } = await run(ws, "reviews", {
			review: async () => REVIEWED,
		});
		assert.equal(outcome.status, "ok", outcome.error);
		assert.partialDeepStrictEqual(outcome.candidate, {
			coverage: {
				failedReads: 1,
				complete: false,
				omitted: [{ what: "script reads that failed", count: 1, reason: "unavailable" }],
			},
		});
	});

	const CUT_LISTING = `await scm(ctx, "scm.pull_request").head?.grep("a|e", { maxResults: 1 });`;
	const CUT_READ = `await scm(ctx, "scm.pull_request").head?.read("notes.txt", { maxLines: 1 });`;
	for (const [cause, before] of [
		["a cut search", CUT_LISTING],
		["a cut read", CUT_READ],
	] as const) {
		void test(`code gets no Met or Does not apply after ${cause}: a partial read proves no absence`, async () => {
			const ws = await workspace({
				met: readThen(before, MET),
				inapplicable: readThen(before, DOES_NOT_APPLY),
			});
			const met = await run(ws, "met");
			assert.equal(met.outcome.status, "failed");
			assert.match(met.outcome.error ?? "", /^met_coverage_incomplete:/u);
			const inapplicable = await run(ws, "inapplicable");
			assert.equal(inapplicable.outcome.status, "failed");
			assert.match(inapplicable.outcome.error ?? "", /complete coverage/u);
		});

		void test(`code keeps a Not met on evidence it read after ${cause}`, async () => {
			const ws = await workspace({ "not-met": readThen(before, NOT_MET) });
			const { outcome } = await run(ws, "not-met");
			assert.equal(outcome.status, "ok", outcome.error);
			assert.partialDeepStrictEqual(outcome.candidate, {
				outcome: "NOT_MET",
				coverage: { complete: false },
			});
		});
	}

	void test("code that read what it decides from keeps its result", async () => {
		const ws = await workspace({ "not-met": readThen("", NOT_MET) });
		const { outcome } = await run(ws, "not-met");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.partialDeepStrictEqual(outcome.candidate, { outcome: "NOT_MET" });
	});

	void test("a file that the captured tree does not hold is a fact that code can decide from", async () => {
		const ws = await workspace({ absent: readThen(ABSENT_FILE, DOES_NOT_APPLY) });
		const { outcome } = await run(ws, "absent");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.partialDeepStrictEqual(outcome.candidate, {
			outcome: "DOES_NOT_APPLY",
			coverage: { complete: true },
		});
	});

	void test("a review after a failed read keeps its Not met, and its coverage says what failed", async () => {
		const ws = await workspace({
			reviews: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => {
					${FAILED_READ}
					return ctx.review({});
				} }`,
			),
		});
		const { outcome } = await run(ws, "reviews", {
			review: async () => ({
				...REVIEWED,
				outcome: "NOT_MET",
				citations: [],
				evidence: {},
				problems: [
					{
						severity: "MINOR",
						summary: "Too many changed files",
						explanation: "The review read the record.",
						citations: REVIEWED.citations,
						basis: { type: "state", explanation: "The pull request has this state." },
					},
				],
			}),
		});
		assert.equal(outcome.status, "ok", outcome.error);
		assert.partialDeepStrictEqual(outcome.candidate, {
			outcome: "NOT_MET",
			coverage: {
				failedReads: 1,
				complete: false,
				omitted: [{ what: "script reads that failed", count: 1, reason: "unavailable" }],
			},
		});
	});

	void test("a review after a failed read gets no Met", async () => {
		const ws = await workspace({
			reviews: program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => {
					${FAILED_READ}
					return ctx.review({});
				} }`,
			),
		});
		const { outcome } = await run(ws, "reviews", {
			review: async () => ({
				outcome: "MET",
				summary: REVIEWED.summary,
				explanation: REVIEWED.explanation,
				citations: REVIEWED.citations,
				example: undefined,
				evidence: {},
				coverage: {
					source: "host",
					reads: 0,
					truncatedReads: 0,
					failedReads: 0,
					receipts: [],
					complete: true,
				},
			}),
		});
		assert.equal(outcome.status, "failed");
		assert.match(outcome.error ?? "", /^met_coverage_incomplete:/u);
	});

	for (const [form, line] of [
		["a relative path", `import "../lib/core/contract.ts";`],
		["a path through the install", `import "../kits/scm/index.ts";`],
		[
			"an absolute path",
			`import ${JSON.stringify(path.resolve(import.meta.dirname, "contract.ts"))};`,
		],
		[
			"a file URL",
			`import ${JSON.stringify(pathToFileURL(path.resolve(import.meta.dirname, "contract.ts")).href)};`,
		],
		["a deep name", `import "@hephaestus/practice-scripts/lib/core/contract.ts";`],
		["a Node module", `import "node:fs";`],
		["a package", `import "zod";`],
		["the retired models entry", `import "@hephaestus/practice-scripts/models";`],
		["the private chat schema", `import "@hephaestus/practice-scripts/chat/records";`],
		["the private document schema", `import "@hephaestus/practice-scripts/docs/records";`],
		["a data URL", `import "data:text/javascript,export default 1";`],
		["a dynamic import", `await import("../lib/core/contract.ts");`],
	] as const) {
		void test(`a script that imports ${form} stops as an invalid program, and names the import`, async () => {
			const ws = await workspace({
				importer: `${line}
import { definePracticeScript } from "@hephaestus/practice-scripts";
export default definePracticeScript({ meta: {}, run: async (ctx) => ctx.review({}) });
`,
			});
			const { outcome, reviews } = await run(ws, "importer");
			assert.equal(outcome.status, "failed");
			assert.equal(outcome.stopReason, "invalid-program");
			assert.match(
				outcome.error ?? "",
				/A practice script can import only @hephaestus\/practice-scripts/u,
			);
			assert.equal(outcome.candidate, undefined);
			assert.deepEqual(reviews, []);
		});
	}

	for (const meta of [
		{ models: { chat: "required" } },
		{ models: { chat: "optional" } },
		{ models: {} },
		{ limits: {} },
	]) {
		void test(`a stored script with nonempty metadata ${JSON.stringify(meta)} is invalid`, async () => {
			const ws = await workspace({
				importer: `import { definePracticeScript } from "@hephaestus/practice-scripts";
export default definePracticeScript({ meta: ${JSON.stringify(meta)}, run: async (ctx) => ctx.review() });`,
			});
			const { outcome, reviews } = await run(ws, "importer");
			assert.equal(outcome.status, "failed");
			assert.equal(outcome.stopReason, "invalid-program");
			assert.equal(
				outcome.error,
				"A practice script must declare empty meta. Rewrite the script for this API.",
			);
			assert.deepEqual(outcome.calls, calls(0));
			assert.deepEqual(reviews, []);
		});
	}

	void test("a script with a name in the export map runs", async () => {
		const ws = await workspace({
			importer: `import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import { chat } from "@hephaestus/practice-scripts/chat";
import { docs } from "@hephaestus/practice-scripts/docs";
import { issueReferences } from "@hephaestus/practice-scripts/scm";
void [chat, docs, listWithin, issueReferences];
export default definePracticeScript({ meta: {}, run: async (ctx) => ctx.review({}) });
`,
		});
		const { outcome } = await run(ws, "importer");
		assert.equal(outcome.status, "ok", outcome.error);
	});

	void test("the built-in asks one review with no prepared input and returns its result", async () => {
		const ws = await workspace({ "built-in": null });
		const { outcome, reviews } = await run(ws, "built-in");
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(reviews, [{}]);
		assert.deepEqual(outcome.candidate, REVIEWED);
		assert.deepEqual(outcome.calls, calls(1));
		assert.deepEqual(outcome.program, { kind: "builtin", digest: null });
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

	void test("a lead its source does not hold is an error of the program, never dropped", async () => {
		const ws = await workspace({
			"unheld-lead": program(
				"definePracticeScript",
				`{ meta: {}, run: async (ctx) => ctx.review({ leads: [{ at: { change: "src/cart.ts", line: 400 }, label: "A lead." }] }) }`,
			),
		});
		const { outcome } = await run(ws, "unheld-lead", {
			review: async (options) => {
				await resolveLeads(options.leads ?? [], ws.sources);
				return REVIEWED;
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.match(
			outcome.error ?? "",
			/^lead 1 \(A lead\.\) points at .*, which its source does not hold$/u,
		);
		assert.equal(outcome.candidate, undefined);
	});
});

void describe("one review at a time", () => {
	void test("a second review through the context while one runs never starts", async () => {
		const ws = await workspace({ "concurrent-review": await fixture("concurrent-review") });
		const { outcome, reviews } = await run(ws, "concurrent-review", {
			review: slowReview(100),
		});
		assert.deepEqual(reviews, [{ directions: ["The first review."] }]);
		assert.equal(outcome.status, "ok", outcome.error);
		assert.deepEqual(outcome.calls, calls(1));
	});

	void test("a review request sent past the context while one runs never starts", async () => {
		const ws = await workspace({ "nested-review": await fixture("nested-review") });
		const { outcome, reviews } = await run(ws, "nested-review", { review: slowReview(100) });
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
			`import {definePracticeScript} from "@hephaestus/practice-scripts";
import {scm} from "@hephaestus/practice-scripts/scm";
const { execFileSync } = process.getBuiltinModule("node:child_process");
const { readFileSync, writeFileSync } = process.getBuiltinModule("node:fs");
const attempt = (action) => { try { action(); return "allowed"; } catch (error) { return error.code ?? "denied"; } };
export default definePracticeScript({
	meta: {},
	async run(ctx) {
		ctx.log("environment " + JSON.stringify(Object.keys(process.env).sort()));
		ctx.log("repository " + attempt(() => readFileSync(${JSON.stringify(path.join(ws.repo, "notes.txt"))})));
		ctx.log("system " + attempt(() => readFileSync("/etc/hostname")));
		ctx.log("write " + attempt(() => writeFileSync(${JSON.stringify(escape)}, "x")));
		ctx.log("program " + attempt(() => execFileSync("cat", ["/proc/1/environ"])));
		ctx.log("context " + (await scm(ctx, "scm.pull_request").head.read("notes.txt")).status);
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

	void test("a program ends at its review: its process is gone while the model works, and code after it does not run", async () => {
		const ws = await workspace({
			after: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) {
					ctx.log("pid " + process.pid);
					const reviewed = await ctx.review({});
					ctx.log("after the review");
					return { ...reviewed, summary: "The program's own summary" };
				} }`,
			),
		});
		let alive: boolean | undefined;
		const seen: string[] = [];
		const { outcome, logs } = await run(ws, "after", {
			log: (line) => {
				seen.push(line);
			},
			review: async () => {
				const pid = /pid (?<pid>\d+)/u.exec(seen.join("\n"))?.groups?.pid;
				assert.ok(pid !== undefined, seen.join("\n"));
				const running = () => {
					try {
						process.kill(Number(pid), 0);
						return true;
					} catch {
						return false;
					}
				};
				// The host ends the program while the model works: wait for that exit, not a fixed time.
				const deadline = Date.now() + 10_000;
				alive = running();
				while (alive && Date.now() < deadline) {
					await sleep(10);
					alive = running();
				}
				return REVIEWED;
			},
		});
		assert.equal(alive, false);
		assert.ok(!logs.some((line) => line.includes("after the review")), logs.join("\n"));
		assert.equal(outcome.status, "ok", outcome.error);
		assert.equal(outcome.candidate?.summary, REVIEWED.summary);
	});

	void test("requests open at its review end with the program and do not lose the review", async () => {
		const ws = await workspace({
			busy: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) {
					for (let i = 0; i < 28; i += 1) void ctx.evidence.record("metadata.json").catch(() => undefined);
					return ctx.review({});
				} }`,
			),
		});
		for (let round = 0; round < 12; round += 1) {
			const { outcome } = await run(ws, "busy", {
				review: async () => {
					await sleep(20);
					return REVIEWED;
				},
			});
			assert.equal(outcome.status, "ok", outcome.error);
			assert.deepEqual(outcome.calls, calls(1));
		}
	});

	void test("a review that gives no result ends the program with the review's reason", async () => {
		const ws = await workspace({
			caught: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) {
					try { return await ctx.review({}); }
					catch { return { outcome: "MET", summary: "Met", explanation: "Code replaced it.", citations: [], evidence: {} }; }
				} }`,
			),
		});
		const { outcome } = await run(ws, "caught", {
			review: async () => {
				throw new UnratedError("off-format", "the session ended without a result");
			},
		});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "off-format");
		assert.equal(outcome.candidate, undefined);
	});
});

void describe("the host's bounds on a program's messages", () => {
	void test("a pending review settles before the next practice starts", async () => {
		const candidate = {
			...REVIEWED,
			coverage: undefined,
		};
		const ws = await workspace({
			first: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) { void ctx.review(); return ${JSON.stringify(candidate)}; } }`,
			),
			next: null,
		});
		let active = false;
		const review = async () => {
			assert.equal(active, false);
			active = true;
			try {
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
				`{ meta: {}, async run(ctx) { return ctx.review(); } }`,
			),
		});
		try {
			await assert.rejects(
				run(ws, "stuck", { deadlineMs: 2000, review: async () => pending.promise }),
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
	void test("more requests in flight than the host answers stop the program, and the host drains them", async () => {
		let active = 0;
		/** Holds each read until the host stops it. */
		const answer = async (_request: ReadRequest, signal?: AbortSignal): Promise<never> => {
			active += 1;
			try {
				assert.ok(signal !== undefined);
				await once(signal, "abort");
				await sleep(40);
				throw new UnratedError("deadline", "stopped");
			} finally {
				active -= 1;
			}
		};
		const ws = await workspace({
			flood: program(
				"definePracticeScript",
				`{ meta: {}, async run(ctx) {
					await Promise.all(Array.from({ length: 33 }, async () => ctx.evidence.record("metadata.json")));
					return ctx.review();
				} }`,
			),
		});
		const { outcome, reviews } = await run(ws, "flood", { answer });
		assert.equal(active, 0);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.equal(outcome.error, "more than 32 requests at once");
		assert.deepEqual(reviews, []);
		const snapshot = JSON.stringify(outcome);
		await sleep(50);
		assert.equal(JSON.stringify(outcome), snapshot);
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

	for (const { when, pause } of [
		{ when: "while its request is open", pause: "" },
		{
			when: "after its request was answered",
			pause: "await new Promise((resolve) => setTimeout(resolve, 500));",
		},
	]) {
		void test(`a request id used again ${when} stops the program`, async () => {
			const ws = await workspace({
				reused: program(
					"definePracticeScript",
					`{ meta: {}, async run() {
						process.send(JSON.stringify({ kind: "read", id: 7, request: { op: "record", name: "metadata.json" } }));
						${pause}
						process.send(JSON.stringify({ kind: "read", id: 7, request: { op: "record", name: "metadata.json" } }));
						return Promise.withResolvers().promise;
					} }`,
				),
			});
			const { outcome } = await run(ws, "reused");
			assert.equal(outcome.status, "failed");
			assert.equal(outcome.stopReason, "error");
			assert.equal(outcome.error, "the program reused the id of request 7");
		});
	}

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
			coverage: undefined,
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

	void test("a program that answers its abort keeps the host's reason for a lost side", async () => {
		const cancel = new AbortController();
		const ws = await workspace({
			rethrows: program(
				"definePracticeScript",
				'{ meta: {}, async run() { await new Promise((end) => setTimeout(end, 1500)); throw new Error("stopped"); } }',
			),
		});
		setTimeout(() => {
			cancel.abort(new UnratedError("unavailable", "the tools side stopped"));
		}, 700);
		const { outcome } = await run(ws, "rethrows", { signal: cancel.signal });
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "unavailable");
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
	void test("on cancellation, its open review stops and the program is already gone", async () => {
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
		assert.ok(
			!logs.some((line) => line.startsWith("review:") || line.startsWith("late reply")),
			logs.join("\n"),
		);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "cancelled");
		assert.equal(outcome.error, "the host stopped this program: cancelled");
		assert.ok((outcome.durationMs ?? 0) < RETURN_MS, `ended after ${String(outcome.durationMs)}ms`);
		reportEntry("cancellation", outcome);
	});

	void test("at its deadline, its open review stops and the program is already gone", async () => {
		const ws = await workspace({ cancellation: await fixture("cancellation") });
		const { outcome, logs } = await run(ws, "cancellation", {
			deadlineMs: 1000,
			review: async (_options, signal) => {
				await once(signal, "abort");
				await sleep(100);
				return REVIEWED;
			},
		});
		assert.ok(
			!logs.some((line) => line.startsWith("review:") || line.startsWith("late reply")),
			logs.join("\n"),
		);
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "deadline");
		assert.ok(
			(outcome.durationMs ?? 0) < 1000 + RETURN_MS,
			`ended after ${String(outcome.durationMs)}ms`,
		);
	});
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

void test("a practice the host did not reach has no duration", async () => {
	const source = Buffer.from(await fixture("direct"));
	const outcome = notReached({ script: { path: "direct.ts", source } }, "deadline");
	assert.deepEqual(outcome, {
		program: { kind: "custom", digest: programDigest(source) },
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
