import { describe, expect, it } from "vitest";

import {
	groupsHint,
	type SplitContext,
	splitDescription,
	tilesHint,
} from "./across-workspace-copy";

const context: SplitContext = {
	window: "ALL_TIME",
	readerCounted: true,
	observedDevelopers: 28,
	minimumOthers: 3,
};

describe("splitDescription", () => {
	it("names the reference group and every count the bar shows, and nothing it does not", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7 },
				"MIXED",
				context,
			),
		).toBe(
			"28 developers observed in this workspace so far: 6 Needs attention, 7 Mixed feedback, 7 Going well. You: Mixed feedback.",
		);
	});

	it("says the reader is not counted when they are not among the observed developers", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"NOT_OBSERVED",
				{ ...context, window: "DAYS_30", readerCounted: false },
			),
		).toMatch(
			/in the last 30 days: .*, 8 none yet\. You: Not observed yet, not counted in the split\.$/u,
		);
	});
});

describe("a split held back", () => {
	it("gives one short reason and the reader's word, never the total", () => {
		expect(splitDescription({ shape: "WITHHELD" }, "MIXED", context)).toBe(
			"Held back: too few developers to compare yet. You: Mixed feedback.",
		);
	});

	it("names no total the server held back", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 4, mixedFeedback: 5, goingWell: 6, noneYet: 4 },
				"MIXED",
				{
					...context,
					observedDevelopers: undefined,
				},
			),
		).toMatch(/^Developers observed in this workspace so far: /u);
	});
});

describe("the hints", () => {
	it("names who the band is of once, with the count and window the response gives", () => {
		expect(tilesHint(3, "DAYS_90", 41)).toBe(
			"The typical range is the middle half of 41 developers observed in the last 90 days; your marker shows you. A tile compares you once at least 6 other developers have reviewed work in this window; until then it shows only your own value.",
		);
	});

	it("names no count the server held back", () => {
		expect(tilesHint(3, "DAYS_30")).toMatch(
			/^The typical range is the middle half of the developers here; /u,
		);
	});

	it("takes the part size from K", () => {
		expect(groupsHint(3)).toContain("A part shows only when it holds at least 3 other developers");
	});
});
