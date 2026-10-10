import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { RUN_STATUSES } from "../../../../../../docker/agents/precompute/lib/contract.ts";
import {
	PRECOMPUTE_ERROR_MAX_CHARS,
	PRECOMPUTE_REPORT_MAX_BYTES,
	PRECOMPUTE_REPORT_MAX_PRACTICES,
	precomputeReport,
} from "../../../main/resources/agent/pi-precompute-report.ts";

const roots: string[] = [];
after(() => {
	for (const root of roots) {
		rmSync(root, { recursive: true, force: true });
	}
});

/** Stages a script per slug and writes each given result as the precompute runner writes it. */
function staged(slugs: readonly string[], results: Record<string, unknown> = {}) {
	const root = mkdtempSync(path.join(tmpdir(), "pi-precompute-report-"));
	roots.push(root);
	const scripts = path.join(root, "work/precompute/practices");
	const output = path.join(root, "work/precompute-out");
	mkdirSync(scripts, { recursive: true });
	mkdirSync(output, { recursive: true });
	for (const slug of slugs) {
		writeFileSync(path.join(scripts, `${slug}.ts`), "export default () => ({});\n");
	}
	for (const [slug, result] of Object.entries(results)) {
		writeFileSync(
			path.join(output, `${slug}.json`),
			typeof result === "string" ? result : JSON.stringify(result),
		);
	}
	return { scripts, output };
}

function report(scripts: string, output: string): unknown {
	const text = precomputeReport(scripts, output);
	assert.ok(text !== undefined, "a report");
	return JSON.parse(text);
}

const definition = (status: string, extra: object = {}) => ({
	practice: "x",
	contract: "definition",
	status,
	models: {},
	leads: [],
	facts: {},
	unrated: [],
	directions: [],
	kinds: {},
	dropped: 0,
	durationMs: 40,
	...extra,
});

const positional = (status: string, extra: object = {}) => ({
	practice: "x",
	contract: "positional",
	status,
	hints: [],
	metrics: {},
	directions: [],
	dropped: 0,
	durationMs: 40,
	...extra,
});

/**
 * `PiResultParserPrecomputeReportTest` parses the same file, so a status word or a field that the
 * writer and the server spell differently fails one of the two tests.
 */
void test("the report of every status, slot, need and reason is the one the server parses", () => {
	const { scripts, output } = staged(
		["broken", "decides", "needs-a-model", "never-ran", "scans", "slow"],
		{
			broken: definition("error", {
				models: { chat: { need: "optional", bound: true, notRated: {} } },
				error: "result: leads must be an array\n    at run (practices/broken.ts:4:9)",
			}),
			decides: definition("ok", {
				leads: [{}, {}],
				models: {
					chat: { need: "optional", bound: true, notRated: {} },
					decision: {
						need: "required",
						bound: true,
						notRated: { unavailable: 1, budget: 2, deadline: 3, "too-large": 4 },
					},
					embedding: {
						need: "optional",
						bound: true,
						notRated: { "off-format": 5, refused: 6, error: 7 },
					},
					reranking: { need: "optional", bound: false, notRated: {} },
				},
			}),
			"needs-a-model": definition("skipped", {
				models: { decision: { need: "required", bound: false, notRated: {} } },
			}),
			scans: positional("ok", { hints: [{}, {}, {}] }),
			slow: { ...positional("timeout"), contract: "unknown", error: "Timeout after 15003ms" },
		},
	);
	const fixture: unknown = JSON.parse(
		readFileSync(path.join(import.meta.dirname, "precompute-report.json"), "utf8"),
	);
	assert.deepEqual(report(scripts, output), fixture);
});

void test("a script whose models are not known is reported without models, never as one that uses none", () => {
	const { scripts, output } = staged(["earlier-runner", "late", "never-ran", "scans"], {
		// A runner from before the contract field says nothing about the models.
		"earlier-runner": { practice: "earlier-runner", status: "error", hints: [], metrics: {} },
		late: { ...positional("timeout"), contract: "unknown" },
		scans: positional("ok"),
	});
	assert.deepEqual(report(scripts, output), {
		practices: [
			{ slug: "earlier-runner", status: "error", leads: 0 },
			{ slug: "late", status: "timeout", leads: 0, durationMs: 40 },
			{ slug: "never-ran", status: "not-finished", leads: 0 },
			{ slug: "scans", status: "ok", leads: 0, models: [], durationMs: 40 },
		],
		truncated: false,
	});
});

void test("only the runner's own counts reach the server, and a zero count is no fact", () => {
	const { scripts, output } = staged(["decides"], {
		decides: definition("ok", {
			models: {
				decision: {
					need: "required",
					bound: true,
					notRated: { deadline: 5, budget: 0 },
					// An older runner's counts never reach the server: the proxy owns them.
					calls: 3,
					tokens: 900,
				},
			},
		}),
	});
	assert.deepEqual(report(scripts, output), {
		practices: [
			{
				slug: "decides",
				status: "ok",
				leads: 0,
				models: [{ slot: "decision", need: "required", bound: true, notRated: { deadline: 5 } }],
				durationMs: 40,
			},
		],
		truncated: false,
	});
});

void test("a result that does not parse or carries an unknown status counts as not finished", () => {
	const { scripts, output } = staged(["cut", "odd"], {
		cut: '{"practice": "cut", "status": "o',
		odd: definition("constructor"),
	});
	assert.deepEqual(report(scripts, output), {
		practices: ["cut", "odd"].map((slug) => ({ slug, status: "not-finished", leads: 0 })),
		truncated: false,
	});
});

void test("every status that the precompute runner writes is reported as it is", () => {
	const { scripts, output } = staged(
		RUN_STATUSES,
		Object.fromEntries(RUN_STATUSES.map((status) => [status, definition(status)])),
	);
	assert.deepEqual(report(scripts, output), {
		practices: RUN_STATUSES.toSorted().map((status) => ({
			slug: status,
			status,
			leads: 0,
			models: [],
			durationMs: 40,
		})),
		truncated: false,
	});
});

interface ErrorLineCase {
	case: string;
	error: string;
	line: string | null;
}

function errorLineCases(): ErrorLineCase[] {
	const parsed: unknown = JSON.parse(
		readFileSync(path.join(import.meta.dirname, "precompute-error-lines.json"), "utf8"),
	);
	assert.ok(Array.isArray(parsed));
	return parsed.map((value: unknown) => {
		assert.ok(
			typeof value === "object" &&
				value !== null &&
				"case" in value &&
				typeof value.case === "string" &&
				"error" in value &&
				typeof value.error === "string" &&
				"line" in value &&
				(value.line === null || typeof value.line === "string"),
		);
		return { case: value.case, error: value.error, line: value.line };
	});
}

const caseSlug = (index: number) => `case-${String(index).padStart(2, "0")}`;

/**
 * `PiResultParserPrecomputeReportTest` reads the same cases, so the writer and the server keep one
 * rule for the error line.
 */
void test("a failed script's error is reported as the line that the server keeps", () => {
	const cases = errorLineCases();
	const { scripts, output } = staged(
		[...cases.map((_, index) => caseSlug(index)), "odd", "unnamed"],
		{
			...Object.fromEntries(
				cases.map(({ error }, index) => [caseSlug(index), positional("error", { error })]),
			),
			odd: positional("error", { error: 42 }),
			unnamed: positional("error"),
		},
	);
	const reported = report(scripts, output);
	assert.ok(typeof reported === "object" && reported !== null && "practices" in reported);
	assert.ok(Array.isArray(reported.practices));
	const errors = new Map(
		reported.practices.map((entry: { slug: string; error?: string }) => [entry.slug, entry.error]),
	);
	for (const [index, { case: name, line }] of cases.entries()) {
		assert.equal(errors.get(caseSlug(index)), line ?? undefined, name);
	}
	assert.deepEqual([errors.get("odd"), errors.get("unnamed")], [undefined, undefined]);
});

void test("how long a script ran is reported only when the runner wrote a count", () => {
	const { scripts, output } = staged(["negative", "text"], {
		negative: positional("ok", { durationMs: -1 }),
		text: positional("ok", { durationMs: "40" }),
	});
	assert.deepEqual(report(scripts, output), {
		practices: [
			{ slug: "negative", status: "ok", leads: 0, models: [] },
			{ slug: "text", status: "ok", leads: 0, models: [] },
		],
		truncated: false,
	});
});

void test("no report when no practice was staged", () => {
	const { scripts, output } = staged([]);
	assert.equal(precomputeReport(scripts, output), undefined);
	assert.equal(precomputeReport(path.join(scripts, "absent"), output), undefined);
});

void test("the report keeps the first practices by slug within its practice bound, and says it left the rest out", () => {
	const slugs = Array.from({ length: 300 }, (_, i) => `p-${String(i).padStart(3, "0")}`);
	const { scripts, output } = staged(slugs.toReversed());
	const parsed = report(scripts, output);
	assert.ok(typeof parsed === "object" && parsed !== null && "practices" in parsed);
	assert.ok(Array.isArray(parsed.practices));
	assert.equal(parsed.practices.length, PRECOMPUTE_REPORT_MAX_PRACTICES);
	assert.deepEqual(
		parsed.practices.map((entry: { slug: string }) => entry.slug),
		slugs.slice(0, PRECOMPUTE_REPORT_MAX_PRACTICES),
	);
	assert.partialDeepStrictEqual(parsed, { truncated: true });
});

/** Slugs of one length that sort as their numbers do. */
const slugOf = (i: number) => `p-${String(i).padStart(3, "0")}`;

void test("the report keeps whole entries within its byte bound, and says it left the rest out", async (t) => {
	const reasons = [
		"unavailable",
		"budget",
		"deadline",
		"too-large",
		"off-format",
		"refused",
		"error",
	];
	const notRated = Object.fromEntries(reasons.map((reason) => [reason, 1_000_000]));
	const models = Object.fromEntries(
		["chat", "decision", "embedding", "reranking"].map((slot) => [
			slot,
			{ need: "optional", bound: true, notRated },
		]),
	);
	const result = definition("ok", { models });
	const probe = staged([slugOf(0)], { [slugOf(0)]: result });
	const probed: unknown = report(probe.scripts, probe.output);
	assert.ok(typeof probed === "object" && probed !== null && "practices" in probed);
	assert.ok(Array.isArray(probed.practices));
	/** The bytes of one entry with a slug of the length that `slugOf` gives. */
	const entryBytes = Buffer.byteLength(JSON.stringify(probed.practices[0]));
	// The report as if it were complete: `truncated` is false.
	const envelopeBytes = Buffer.byteLength('{"practices":[],"truncated":false}');

	// Each case fills the bound to `slack` bytes short. The next entry, with its comma, needs
	// `entryBytes + 1`: at slack 0 the last entry fits exactly, and at slack `entryBytes` the next
	// entry is one byte too large.
	for (const slack of [0, entryBytes]) {
		await t.test(`${slack} bytes short of the bound`, () => {
			const room = PRECOMPUTE_REPORT_MAX_BYTES - slack - envelopeBytes + 1;
			const kept = Math.floor(room / (entryBytes + 1));
			// The first slugs get longer by the bytes that whole entries do not fill. A slug is a file
			// name, so no slug gets more than 200 of them.
			let padding = room - kept * (entryBytes + 1);
			const slugs = Array.from({ length: kept + 10 }, (_, i) => {
				const extra = Math.min(padding, 200);
				padding -= extra;
				return `${slugOf(i)}${"x".repeat(extra)}`;
			});
			assert.equal(padding, 0);
			const { scripts, output } = staged(
				slugs.toReversed(),
				Object.fromEntries(slugs.map((slug) => [slug, result])),
			);
			const text = precomputeReport(scripts, output);
			assert.ok(text !== undefined);
			const parsed: unknown = JSON.parse(text);
			assert.ok(typeof parsed === "object" && parsed !== null && "practices" in parsed);
			assert.ok(Array.isArray(parsed.practices));
			assert.deepEqual(
				parsed.practices.map((entry: { slug: string }) => entry.slug),
				slugs.slice(0, kept),
			);
			assert.partialDeepStrictEqual(parsed, { truncated: true });
			// The kept entries are the most that fit: the complete report is `slack` bytes short of
			// the bound, and the next entry does not fit in it.
			const complete = Buffer.byteLength(text.replace('"truncated":true', '"truncated":false'));
			assert.equal(complete, PRECOMPUTE_REPORT_MAX_BYTES - slack);
			assert.ok(complete + 1 + entryBytes > PRECOMPUTE_REPORT_MAX_BYTES);
		});
	}
});

void test("the report keeps every practice within its bounds and leaves out the errors that do not fit", () => {
	const slugs = Array.from(
		{ length: PRECOMPUTE_REPORT_MAX_PRACTICES },
		(_, i) => `p-${String(i).padStart(3, "0")}`,
	);
	// Each error is 500 characters of 4 UTF-8 bytes, so the errors of 256 practices cannot all fit.
	const error = "\u{1F642}".repeat(PRECOMPUTE_ERROR_MAX_CHARS);
	const { scripts, output } = staged(
		slugs,
		Object.fromEntries(slugs.map((slug) => [slug, positional("error", { error })])),
	);
	const text = precomputeReport(scripts, output);
	assert.ok(text !== undefined);
	assert.ok(
		Buffer.byteLength(text) <= PRECOMPUTE_REPORT_MAX_BYTES,
		`${Buffer.byteLength(text)} bytes`,
	);
	const parsed: unknown = JSON.parse(text);
	assert.ok(typeof parsed === "object" && parsed !== null && "practices" in parsed);
	assert.ok(Array.isArray(parsed.practices));
	assert.partialDeepStrictEqual(parsed, { truncated: false });
	assert.deepEqual(
		parsed.practices.map((entry: { slug: string; status: string }) => [entry.slug, entry.status]),
		slugs.map((slug) => [slug, "error"]),
	);
	const withError = parsed.practices.filter(
		(entry: { error?: string }) => entry.error !== undefined,
	);
	assert.ok(withError.length > 0 && withError.length < slugs.length, `${withError.length} errors`);
	assert.deepEqual(
		withError.map((entry: { slug: string; error?: string }) => [entry.slug, entry.error]),
		slugs.slice(0, withError.length).map((slug) => [slug, error]),
	);
});

void test("an error that does not fit leaves room for a shorter error after it", () => {
	const long = "\u{1F642}".repeat(PRECOMPUTE_ERROR_MAX_CHARS);
	const slugs = Array.from({ length: 40 }, (_, i) => `p-${String(i).padStart(2, "0")}`);
	const { scripts, output } = staged(slugs, {
		...Object.fromEntries(slugs.map((slug) => [slug, positional("error", { error: long })])),
		"p-39": positional("error", { error: "Cannot read x" }),
	});
	const parsed: unknown = JSON.parse(precomputeReport(scripts, output) ?? "");
	assert.ok(typeof parsed === "object" && parsed !== null && "practices" in parsed);
	assert.ok(Array.isArray(parsed.practices));
	assert.equal(parsed.practices.length, slugs.length);
	const errors = parsed.practices.map((entry: { error?: string }) => entry.error);
	assert.deepEqual(errors.slice(-2), [undefined, "Cannot read x"]);
});
