import { describe, expect, it } from "vitest";

import {
	groupsHint,
	practicesHint,
	type SplitContext,
	splitDescription,
	tilesHint,
} from "./across-workspace-copy";

const context: SplitContext = {
	window: "ALL_TIME",
	readerCounted: true,
	developersWithAStanding: 28,
	minimumOthers: 3,
};

describe("splitDescription", () => {
	it("names the reference group and every count the bar shows, and nothing it does not", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"MIXED",
				context,
			),
		).toBe(
			"28 developers with a standing in this workspace so far: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. You: Mixed feedback.",
		);
	});

	it("says the reader is not counted when they are not among the developers with a standing", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"NOT_OBSERVED",
				{ ...context, window: "DAYS_30", readerCounted: false },
			),
		).toMatch(
			/in the last 30 days: .*, 8 none yet\. You: None yet \(Not observed yet\), not counted in the split\.$/u,
		);
	});
});

describe("a split held back", () => {
	it("gives one short reason and the reader's word, never the total", () => {
		expect(splitDescription({ shape: "WITHHELD" }, "MIXED", context)).toBe(
			"Held back so no one can be singled out. You: Mixed feedback.",
		);
	});

	it("names no total the server held back", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 4, mixedFeedback: 5, goingWell: 6, noneYet: 4 },
				"MIXED",
				{
					...context,
					developersWithAStanding: undefined,
				},
			),
		).toMatch(/^Developers with a standing in this workspace so far: /u);
	});
});

describe("the hints", () => {
	it("names who the band is of once, with the count and window the response gives", () => {
		expect(tilesHint(3, "DAYS_90", 41)).toBe(
			"The typical range is the middle half of 41 developers with a standing in the last 90 days; your marker shows you. A tile compares you once at least 6 other developers have a standing in this window; until then it shows only your own value.",
		);
	});

	it("names no count the server held back", () => {
		expect(tilesHint(3, "DAYS_30")).toMatch(
			/^The typical range is the middle half of the developers here; /u,
		);
	});

	it("takes the part size from K", () => {
		expect(groupsHint(3)).toContain(
			"A bar shows only if each of its parts holds at least 3 other developers",
		);
	});

	it("says a practice's bar is also held back beside its group's, with no promise about later", () => {
		expect(practicesHint(3)).toContain(
			"The bar must also single no one out beside the group's bar.",
		);
		expect(splitDescription({ shape: "WITHHELD" }, "MIXED", context)).not.toMatch(/yet/u);
	});
});
