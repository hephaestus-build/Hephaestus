import { describe, expect, it } from "vitest";

import type { PracticeScriptRun } from "@/api/types.gen";

import {
	PRACTICE_SCRIPT_RUN_STATUS_DEFS,
	practiceScriptFix,
	practiceScriptRunSentence,
} from "./practice-script-run-status-defs";

const run = (over: Partial<PracticeScriptRun>): PracticeScriptRun => ({
	attempt: 1,
	durationMs: 1200,
	error: null,
	finishedAt: new Date("2026-10-01T10:00:00Z"),
	jobId: "0b6f3c3e-5f1d-4f4a-9b55-2b7f6c9d1e01",
	models: [],
	practiceId: "12",
	practiceVersionId: "34",
	scriptKind: "CUSTOM",
	status: "OK",
	stopReason: null,
	noResultCause: null,
	reviewModelCalls: null,
	...over,
});

const result = (value: PracticeScriptRun) =>
	PRACTICE_SCRIPT_RUN_STATUS_DEFS[value.status].result(value);

describe("how a practice script run ended", () => {
	it.each([
		[run({}), "Practice script returned a candidate observation."],
		[run({ scriptKind: "BUILTIN" }), "Built-in review returned a candidate observation."],
		[run({ status: "WITHHELD" }), "Practice script did not run: the practice was withheld."],
		[run({ status: "FAILED" }), "Practice script stopped without a candidate observation."],
		[
			run({ status: "FAILED", stopReason: "DEADLINE" }),
			"Practice script stopped without a candidate observation: it ran out of time.",
		],
		[
			run({ status: "FAILED", stopReason: "INVALID_SCRIPT" }),
			"Practice script stopped without a candidate observation: it is not a script the review can run.",
		],
		[
			run({ status: "FAILED", stopReason: "OFF_FORMAT" }),
			"Practice script stopped without a candidate observation: a model answered in the wrong format.",
		],
		[
			run({
				status: "FAILED",
				scriptKind: "BUILTIN",
				stopReason: "OFF_FORMAT",
				noResultCause: "EVIDENCE_NOT_READ",
			}),
			"Built-in review stopped without a candidate observation: its candidate observation was refused because it had not read the evidence it needed.",
		],
		[
			run({
				status: "FAILED",
				scriptKind: "BUILTIN",
				stopReason: "OFF_FORMAT",
				noResultCause: "SESSION_ENDED",
			}),
			"Built-in review stopped without a candidate observation: its review session ended without an accepted candidate observation.",
		],
		[run({ status: "NOT_REACHED" }), "The review ended before the practice script started."],
		[
			run({ status: "NOT_REACHED", scriptKind: "BUILTIN" }),
			"The review ended before the built-in review started.",
		],
		[run({ status: "NOT_FINISHED" }), "Practice script did not finish."],
	])("says %o as %s", (value, expected) => {
		expect(practiceScriptRunSentence(value)).toBe(expected);
	});

	it.each([
		[run({}), "Returned a candidate observation"],
		[run({ status: "WITHHELD" }), "Withheld"],
		[run({ status: "FAILED" }), "Failed"],
		[run({ status: "FAILED", stopReason: "BUDGET" }), "Failed: out of budget"],
		[run({ status: "FAILED", stopReason: "OFF_FORMAT" }), "Failed: wrong format"],
		[
			run({
				status: "FAILED",
				scriptKind: "BUILTIN",
				stopReason: "OFF_FORMAT",
				noResultCause: "EVIDENCE_NOT_READ",
			}),
			"Failed: evidence not read",
		],
		[run({ status: "NOT_REACHED" }), "Not reached"],
		[run({ status: "NOT_FINISHED" }), "Did not finish"],
	])("puts %o in the result cell as %s", (value, expected) => {
		expect(result(value)).toBe(expected);
	});
});

describe("the one fix a run offers", () => {
	it("edits a custom script that failed", () => {
		expect(practiceScriptFix(run({ status: "FAILED", stopReason: "ERROR" }))).toStrictEqual({
			kind: "EDIT",
		});
	});

	it("offers no edit for the built-in review or a cancelled review", () => {
		expect(
			practiceScriptFix(run({ status: "FAILED", stopReason: "ERROR", scriptKind: "BUILTIN" })),
		).toBeUndefined();
		expect(practiceScriptFix(run({ status: "FAILED", stopReason: "CANCELLED" }))).toBeUndefined();
	});

	it("opens the guide where time or budget stopped the script", () => {
		for (const value of [
			run({ status: "FAILED", stopReason: "DEADLINE" }),
			run({ status: "FAILED", stopReason: "BUDGET" }),
			run({ status: "NOT_REACHED" }),
			run({ status: "NOT_FINISHED" }),
		]) {
			expect(practiceScriptFix(value)).toStrictEqual({ kind: "GUIDE", guide: "STOPPED_EARLY" });
		}
	});

	it("offers nothing for a result or a withheld practice", () => {
		expect(practiceScriptFix(run({}))).toBeUndefined();
		expect(practiceScriptFix(run({ status: "WITHHELD" }))).toBeUndefined();
	});
});
