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
	minimumOthers: 5,
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

	it("gives the two parts of a collapsed split and why it collapsed", () => {
		expect(
			splitDescription({ shape: "COLLAPSED", hasStanding: 22, noneYet: 6 }, "STRENGTH", context),
		).toBe(
			"28 developers observed in this workspace so far: 22 have a standing, 6 none yet. The split is held back while one standing would cover 5 developers or fewer. You: Going well.",
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
			splitDescription({ shape: "COLLAPSED", hasStanding: 22, noneYet: 6 }, "MIXED", {
				...context,
				observedDevelopers: undefined,
			}),
		).toMatch(/^Developers observed in this workspace so far: /u);
	});
});

describe("the hints", () => {
	it("names who the band is of once, with the count and window the response gives", () => {
		expect(tilesHint(5, "DAYS_90", 41)).toBe(
			"The typical range is the middle half of 41 developers observed in the last 90 days; your marker shows you. A tile compares you once at least 10 other developers have reviewed work in this window; until then it shows only your own value.",
		);
	});

	it("names no count the server held back", () => {
		expect(tilesHint(5, "DAYS_30")).toMatch(
			/^The typical range is the middle half of the developers here; /u,
		);
	});

	it("takes the part size from K", () => {
		expect(groupsHint(5)).toContain("A part shows only when it holds at least 5 other developers");
	});
});
