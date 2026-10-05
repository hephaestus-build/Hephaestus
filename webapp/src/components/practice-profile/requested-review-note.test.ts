import { describe, expect, it } from "vitest";

import type { ObservationDetail } from "@/api/types.gen";

import { requestedReviewNote } from "./requested-review-note";

type Practice = Parameters<typeof requestedReviewNote>[0];

const developing = {
	standing: "DEVELOPING",
	trendSupport: { opportunities: 4 },
} satisfies Practice;

function run(origin: ObservationDetail["origin"], ...outcomes: ObservationDetail["outcome"][]) {
	return { observations: outcomes.map((outcome) => ({ origin, outcome })) };
}

describe("requestedReviewNote", () => {
	it("says that a requested review does not move the standing when the newest one found no problem", () => {
		expect(requestedReviewNote(developing, [run("MANUAL", "MET"), run("LIVE", "NOT_MET")])).toBe(
			"A review that you requested is evidence only and does not change your standing.",
		);
	});

	it.each<[string, Practice, ReturnType<typeof run>[]]>([
		["there is no review", developing, []],
		["the newest review is live", developing, [run("LIVE", "MET"), run("MANUAL", "MET")]],
		["the newest review is a backfill", developing, [run("BACKFILL", "MET")]],
		["the requested review found a problem", developing, [run("MANUAL", "MET", "NOT_MET")]],
		["the requested review reached no verdict", developing, [run("MANUAL", "UNDETERMINED")]],
		[
			"the standing is not Needs attention",
			{ ...developing, standing: "MIXED" },
			[run("MANUAL", "MET")],
		],
		[
			"only requested reviews judged the practice",
			{ standing: "DEVELOPING", trendSupport: { opportunities: 0 } },
			[run("MANUAL", "MET")],
		],
		["the standing has no count of work", { standing: "DEVELOPING" }, [run("MANUAL", "MET")]],
	])("says nothing when %s", (_case, practice, runs) => {
		expect(requestedReviewNote(practice, runs)).toBeUndefined();
	});
});
