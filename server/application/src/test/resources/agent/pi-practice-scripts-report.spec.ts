import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import {
	executionReportSchema,
	NOT_A_DEFINITION_ERROR,
	parseCandidate,
	REASONS,
} from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import type { ProgramOutcome } from "../../../../../../docker/agents/practice-scripts/lib/core/program-host.ts";
import {
	executionReport,
	PRACTICE_SCRIPT_ERROR_MAX_CHARS,
	PRACTICE_SCRIPT_REPORT_MAX_BYTES,
	PRACTICE_SCRIPT_REPORT_MAX_PRACTICES,
} from "../../../main/resources/agent/pi-practice-scripts-report.ts";

function parsed(text: string) {
	assert.ok(
		Buffer.byteLength(text) <= PRACTICE_SCRIPT_REPORT_MAX_BYTES,
		`${Buffer.byteLength(text)} bytes`,
	);
	const report = executionReportSchema.safeParse(JSON.parse(text));
	assert.ok(report.success, report.error?.message);
	return report.data;
}

const noCalls = { review: 0 };

function outcome(changes: Partial<ProgramOutcome> = {}): ProgramOutcome {
	return {
		program: { kind: "builtin", digest: null },
		status: "ok",
		stopReason: null,
		calls: { review: 1 },
		coverageReached: { reads: 4, truncatedReads: 0, failedReads: 0 },
		durationMs: 40,
		...changes,
	};
}

const failed = (error: string | undefined) =>
	outcome({ status: "failed", stopReason: "error", ...(error === undefined ? {} : { error }) });

const slugOf = (i: number) => `p-${String(i).padStart(4, "0")}`;

const sharedFixture = (name: string): unknown =>
	JSON.parse(readFileSync(path.join(import.meta.dirname, "../practice-results", name), "utf8"));

void test("the report of the shared fixture's practices is the fixture: every status, program kind and stop", () => {
	const fixture = executionReportSchema.safeParse(sharedFixture("execution-report.json"));
	assert.ok(fixture.success, fixture.error?.message);
	const passed = parseCandidate(sharedFixture("candidate-met-with-example.json"));
	assert.ok(passed.ok);
	const outcomes = fixture.data.practices.toReversed().map(({ practiceSlug, ...entry }) => {
		const ran: ProgramOutcome =
			entry.status === "ok" ? { ...entry, candidate: passed.candidate } : { ...entry };
		return [practiceSlug, ran] as const;
	});
	assert.deepEqual(parsed(executionReport(outcomes)), fixture.data);
});

void test("one entry for each admitted practice, sorted by slug as Java compares strings", () => {
	const slugs = ["b-2", "a-10", "a-1", "b", "0-x"];
	const report = parsed(executionReport(slugs.map((slug) => [slug, outcome()])));
	assert.deepEqual(
		report.practices.map((entry) => entry.practiceSlug),
		["0-x", "a-1", "a-10", "b", "b-2"],
	);
	assert.equal(report.truncated, false);
});

void test("a custom program is reported with the digest of its staged bytes", () => {
	const digest = createHash("sha256").update("export default {};\n").digest("hex");
	const report = parsed(
		executionReport([
			["decides", outcome({ program: { kind: "custom", digest }, calls: noCalls })],
		]),
	);
	assert.deepEqual(report.practices, [
		{
			practiceSlug: "decides",
			program: { kind: "custom", digest },
			status: "ok",
			stopReason: null,
			calls: noCalls,
			coverageReached: { reads: 4, truncatedReads: 0, failedReads: 0 },
			durationMs: 40,
		},
	]);
});

void test("an error is reported only for a failed practice", () => {
	const report = parsed(
		executionReport([
			["ok", outcome({ error: "a note the host kept" })],
			["withheld", outcome({ status: "withheld", stopReason: "unavailable", error: "no context" })],
			[
				"not-reached",
				outcome({ status: "not-reached", stopReason: "deadline", durationMs: null, error: "late" }),
			],
			["failed", failed("Cannot read x")],
		]),
	);
	assert.deepEqual(
		report.practices.map((entry) => [entry.practiceSlug, entry.error]),
		[
			["failed", "Cannot read x"],
			["not-reached", undefined],
			["ok", undefined],
			["withheld", undefined],
		],
	);
});

void test("every stop reason of a failed program is reported as it is", () => {
	const stops = [
		...REASONS,
		"cancelled",
		"invalid-program",
		"invalid-candidate",
		"unread-evidence",
	] as const;
	const report = parsed(
		executionReport(
			stops.map((stopReason) => [stopReason, outcome({ status: "failed", stopReason })]),
		),
	);
	assert.deepEqual(
		report.practices.map((entry) => [entry.practiceSlug, entry.status, entry.stopReason]),
		stops.toSorted().map((stop) => [stop, "failed", stop]),
	);
});

interface ErrorLineCase {
	case: string;
	error: string;
	line: string | null;
}

function errorLineCases(): ErrorLineCase[] {
	const cases: unknown = JSON.parse(
		readFileSync(path.join(import.meta.dirname, "practice-script-error-lines.json"), "utf8"),
	);
	assert.ok(Array.isArray(cases));
	return cases.map((value: unknown) => {
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

void test("a failed program's error is reported as the line that the server keeps", () => {
	const cases = errorLineCases();
	const report = parsed(
		executionReport([
			...cases.map(({ error }, index) => [slugOf(index), failed(error)] as const),
			["unnamed", failed(undefined)],
		]),
	);
	const errors = new Map(report.practices.map((entry) => [entry.practiceSlug, entry.error]));
	for (const [index, { case: name, line }] of cases.entries()) {
		assert.equal(errors.get(slugOf(index)), line ?? undefined, name);
	}
	assert.equal(errors.get("unnamed"), undefined);
	assert.equal(errors.size, cases.length + 1);
});

void test("a program that is not a practice script is reported with its whole error", () => {
	assert.ok(NOT_A_DEFINITION_ERROR.length <= PRACTICE_SCRIPT_ERROR_MAX_CHARS);
	const report = parsed(
		executionReport([
			[
				"not-a-script",
				outcome({
					program: { kind: "custom", digest: "0".repeat(64) },
					status: "failed",
					stopReason: "invalid-program",
					error: NOT_A_DEFINITION_ERROR,
					calls: noCalls,
				}),
			],
		]),
	);
	assert.partialDeepStrictEqual(report.practices, [
		{ status: "failed", stopReason: "invalid-program", error: NOT_A_DEFINITION_ERROR },
	]);
});

void test("the report keeps the first practices by slug within its bounds, and says it left the rest out", () => {
	const slugs = Array.from({ length: PRACTICE_SCRIPT_REPORT_MAX_PRACTICES + 44 }, (_, i) =>
		slugOf(i),
	);
	const report = parsed(executionReport(slugs.toReversed().map((slug) => [slug, outcome()])));
	assert.ok(report.practices.length <= PRACTICE_SCRIPT_REPORT_MAX_PRACTICES);
	assert.deepEqual(
		report.practices.map((entry) => entry.practiceSlug),
		slugs.slice(0, report.practices.length),
	);
	assert.equal(report.truncated, true);
});

/** The largest entry without an error: every field at its longest value. */
const large = outcome({
	program: { kind: "custom", digest: "f".repeat(64) },
	status: "not-finished",
	stopReason: "invalid-candidate",
	calls: { review: 1_000_000 },
	reviewSessions: { modelCalls: 1_000_000, evidenceRefusals: 1_000_000, formatRefusals: 1_000_000 },
	coverageReached: { reads: 1_000_000, truncatedReads: 1_000_000, failedReads: 1_000_000 },
	durationMs: 2_147_483_647,
});

function entryBytes(slug: string): number {
	const text = executionReport([[slug, large]]);
	return Buffer.byteLength(text) - Buffer.byteLength(executionReport([]));
}

void test("the report keeps whole entries within its byte bound, and says it left the rest out", async (t) => {
	const entry = entryBytes(slugOf(0));
	const envelope = Buffer.byteLength(executionReport([]));
	// Exercise the exact report boundary, including the comma needed by one additional entry.
	for (const slack of [0, entry]) {
		await t.test(`${slack} bytes short of the bound`, () => {
			const room = PRACTICE_SCRIPT_REPORT_MAX_BYTES - slack - envelope + 1;
			const kept = Math.floor(room / (entry + 1));
			assert.ok(kept < PRACTICE_SCRIPT_REPORT_MAX_PRACTICES, "the byte bound is reached first");
			let padding = room - kept * (entry + 1);
			const slugs = Array.from({ length: kept + 10 }, (_, i) => {
				const extra = Math.min(padding, 64 - slugOf(i).length);
				padding -= extra;
				return `${slugOf(i)}${"x".repeat(extra)}`;
			});
			assert.equal(padding, 0);
			const text = executionReport(slugs.toReversed().map((slug) => [slug, large]));
			const report = parsed(text);
			assert.deepEqual(
				report.practices.map((item) => item.practiceSlug),
				slugs.slice(0, kept),
			);
			assert.equal(report.truncated, true);
			const complete = Buffer.byteLength(text.replace('"truncated":true', '"truncated":false'));
			assert.equal(complete, PRACTICE_SCRIPT_REPORT_MAX_BYTES - slack);
			assert.ok(complete + 1 + entryBytes(slugs[kept] ?? "") > PRACTICE_SCRIPT_REPORT_MAX_BYTES);
		});
	}
});

void test("the report keeps every practice within its bounds and leaves out the errors that do not fit", () => {
	const slugs = Array.from({ length: 2000 }, (_, i) => slugOf(i));
	const error = "\u{1F642}".repeat(PRACTICE_SCRIPT_ERROR_MAX_CHARS);
	const report = parsed(executionReport(slugs.map((slug) => [slug, failed(error)])));
	assert.equal(report.truncated, false);
	assert.deepEqual(
		report.practices.map((entry) => [entry.practiceSlug, entry.status]),
		slugs.map((slug) => [slug, "failed"]),
	);
	const withError = report.practices.filter((entry) => entry.error !== undefined);
	assert.ok(withError.length > 0 && withError.length < slugs.length, `${withError.length} errors`);
	assert.deepEqual(
		withError.map((entry) => [entry.practiceSlug, entry.error]),
		slugs.slice(0, withError.length).map((slug) => [slug, error]),
	);
});

void test("an error that does not fit leaves room for a shorter error after it", () => {
	const long = "\u{1F642}".repeat(PRACTICE_SCRIPT_ERROR_MAX_CHARS);
	const slugs = Array.from({ length: 600 }, (_, i) => slugOf(i));
	const report = parsed(
		executionReport(
			slugs.map((slug, i) => [slug, failed(i === slugs.length - 1 ? "Cannot read x" : long)]),
		),
	);
	assert.equal(report.practices.length, slugs.length);
	assert.deepEqual(
		report.practices.slice(-2).map((entry) => entry.error),
		[undefined, "Cannot read x"],
	);
});
