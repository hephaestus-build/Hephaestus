import { describe, expect, it } from "vitest";

import type { PrecomputeModelUse, PrecomputeRun } from "@/api/types.gen";

import { precomputeModelsToCheck, precomputeNotRated } from "./precompute-not-rated-defs";
import {
	PRECOMPUTE_RUN_STATUS_DEFS,
	precomputeFix,
	precomputeRunSentence,
} from "./precompute-run-status-defs";

const run = (over: Partial<PrecomputeRun>): PrecomputeRun => ({
	status: "OK",
	leads: 0,
	models: [],
	...over,
});

const model = (over: Partial<PrecomputeModelUse>): PrecomputeModelUse => ({
	purpose: "PRACTICE_DECISION",
	need: "REQUIRED",
	bound: true,
	notRated: [],
	...over,
});

const sentence = precomputeRunSentence;
const result = (value: PrecomputeRun) => PRECOMPUTE_RUN_STATUS_DEFS[value.status].result(value);

describe("how a precompute run ended", () => {
	it.each([
		[run({ leads: 1 }), "Precompute script found 1 place to check."],
		[run({ leads: 4 }), "Precompute script found 4 places to check."],
		[run({ leads: 0 }), "Precompute script found no places to check."],
		[run({ status: "FAILED" }), "Precompute script failed."],
		[run({ status: "TIMED_OUT" }), "Precompute script ran out of time."],
		[
			run({ status: "TIMED_OUT", leads: 2 }),
			"Precompute script ran out of time but found 2 places to check.",
		],
		[run({ status: "NOT_FINISHED" }), "Precompute script did not finish."],
	])("says %o as %s", (value, expected) => {
		expect(sentence(value)).toBe(expected);
	});

	it.each([
		[run({ leads: 1 }), "Found 1 place"],
		[run({ leads: 3 }), "Found 3 places"],
		[run({ leads: 0 }), "Found no places"],
		[run({ status: "SKIPPED", leads: 0 }), "Did not run"],
		[run({ status: "FAILED" }), "Failed"],
		[run({ status: "TIMED_OUT" }), "Ran out of time"],
		[run({ status: "TIMED_OUT", leads: 2 }), "Ran out of time, found 2 places"],
		[run({ status: "NOT_FINISHED" }), "Did not finish"],
	])("gives %o the result %s", (value, expected) => {
		expect(result(value)).toBe(expected);
	});

	it("names the required models a skipped script had none for, and never counts places", () => {
		const skipped = run({
			status: "SKIPPED",
			models: [
				model({ bound: false }),
				model({ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", bound: false }),
				model({ purpose: "PRACTICE_RERANKING", bound: false }),
			],
		});
		expect(sentence(skipped)).toBe(
			"Precompute script did not run: no decision model or reranking model was set for this review.",
		);
		expect(sentence(run({ status: "SKIPPED" }))).toBe("Precompute script did not run.");
	});
});

describe("what a precompute run did not rate", () => {
	it("counts calls, and says nothing when every call was rated", () => {
		expect(sentence(run({ leads: 2, models: [model({})] }))).toBe(
			"Precompute script found 2 places to check.",
		);
		expect(
			sentence(
				run({
					leads: 2,
					models: [
						model({ notRated: [{ reason: "DEADLINE", count: 1 }] }),
						model({
							purpose: "PRACTICE_EMBEDDING",
							notRated: [{ reason: "UNAVAILABLE", count: 3 }],
						}),
					],
				}),
			),
		).toBe("Precompute script found 2 places to check. 4 calls were not rated.");
	});

	it("never says the work held no places when calls went unrated, in the line or the result", () => {
		const unrated = model({ notRated: [{ reason: "DEADLINE", count: 1 }] });
		expect(sentence(run({ leads: 0, models: [unrated] }))).toBe(
			"Precompute script handed the review no places. 1 call was not rated.",
		);
		expect(result(run({ leads: 0, models: [unrated] }))).toBe("Handed the review no places");
		expect(result(run({ leads: 0, models: [model({})] }))).toBe("Found no places");
		expect(result(run({ leads: 2, models: [unrated] }))).toBe("Found 2 places");
		expect(sentence(run({ status: "FAILED", leads: 0, models: [unrated] }))).toBe(
			"Precompute script failed. 1 call was not rated.",
		);
	});

	it("does not count what a skipped script never asked", () => {
		expect(
			sentence(
				run({
					status: "SKIPPED",
					models: [model({ bound: false, notRated: [{ reason: "UNAVAILABLE", count: 2 }] })],
				}),
			),
		).toBe("Precompute script did not run: no decision model was set for this review.");
	});

	it("sums each reason across models, in registry order, and skips zero counts", () => {
		expect(
			precomputeNotRated([
				model({
					notRated: [
						{ reason: "OFF_FORMAT", count: 1 },
						{ reason: "DEADLINE", count: 2 },
					],
				}),
				model({
					purpose: "PRACTICE_RERANKING",
					notRated: [
						{ reason: "DEADLINE", count: 3 },
						{ reason: "BUDGET", count: 0 },
					],
				}),
			]),
		).toStrictEqual({
			count: 6,
			reasons: ["5 too slow", "1 wrong format"],
			outsideTheModel: true,
		});
		expect(precomputeNotRated([model({})])).toStrictEqual({
			count: 0,
			reasons: [],
			outsideTheModel: false,
		});
	});
});

describe("the one fix a run offers", () => {
	it("assigns the first required model a skipped script had none for", () => {
		expect(
			precomputeFix(
				run({
					status: "SKIPPED",
					models: [
						model({ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", bound: false }),
						model({ purpose: "PRACTICE_RERANKING", bound: false }),
					],
				}),
			),
		).toStrictEqual({ kind: "ASSIGN", purpose: "PRACTICE_RERANKING" });
		expect(precomputeFix(run({ status: "SKIPPED" }))).toBeUndefined();
	});

	it("edits a script that failed, even when its models also went unrated", () => {
		expect(
			precomputeFix(
				run({ status: "FAILED", models: [model({ notRated: [{ reason: "REFUSED", count: 1 }] })] }),
			),
		).toStrictEqual({ kind: "EDIT" });
	});

	it.each([
		["UNAVAILABLE", ["PRACTICE_DECISION"]],
		["BUDGET", []],
		["DEADLINE", []],
		["TOO_LARGE", []],
		["OFF_FORMAT", ["PRACTICE_DECISION"]],
		["REFUSED", ["PRACTICE_DECISION"]],
		["ERROR", ["PRACTICE_DECISION"]],
	] as const)("offers to check the model for %s: %o", (reason, offered) => {
		const models = [model({ notRated: [{ reason, count: 1 }] })];
		expect(precomputeModelsToCheck(models)).toStrictEqual(offered);
	});

	it("checks a model only where the model is the way out", () => {
		const models = [
			model({ notRated: [{ reason: "DEADLINE", count: 5 }] }),
			model({ purpose: "PRACTICE_EMBEDDING", notRated: [{ reason: "REFUSED", count: 1 }] }),
			model({ purpose: "PRACTICE_RERANKING", notRated: [{ reason: "BUDGET", count: 2 }] }),
		];
		expect(precomputeModelsToCheck(models)).toStrictEqual(["PRACTICE_EMBEDDING"]);
		expect(precomputeFix(run({ models }))).toStrictEqual({
			kind: "CHECK",
			purpose: "PRACTICE_EMBEDDING",
		});
		expect(precomputeFix(run({ leads: 3 }))).toBeUndefined();
	});

	it.each(["BUDGET", "DEADLINE", "TOO_LARGE"] as const)(
		"sends %s, which no model change fixes, to the guide that says who can",
		(reason) => {
			const models = [model({ notRated: [{ reason, count: 2 }] })];
			expect(precomputeFix(run({ models }))).toStrictEqual({
				kind: "GUIDE",
				guide: "UNRATED_CALLS",
			});
		},
	);

	it.each(["TIMED_OUT", "NOT_FINISHED"] as const)(
		"sends a script that %s to the guide on scripts that stop early, before any model to check",
		(status) => {
			const refused = model({ notRated: [{ reason: "REFUSED", count: 1 }] });
			const stoppedEarly = { kind: "GUIDE", guide: "STOPPED_EARLY" };
			expect(precomputeFix(run({ status }))).toStrictEqual(stoppedEarly);
			expect(precomputeFix(run({ status, leads: 2, models: [refused] }))).toStrictEqual(
				stoppedEarly,
			);
		},
	);
});
