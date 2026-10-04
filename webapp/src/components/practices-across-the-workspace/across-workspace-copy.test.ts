import { describe, expect, it } from "vitest";

import {
	groupsHint,
	PAGE_PURPOSE,
	practicesHint,
	type SplitContext,
	splitDescription,
	tilesHint,
} from "./across-workspace-copy";

const context: SplitContext = {
	readerCounted: true,
	developersWithAStanding: 28,
	minimumOthers: 3,
};

describe("splitDescription", () => {
	it("names the reference group, every count the bar shows, and the part the marker is on", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"MIXED",
				context,
			),
		).toBe(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
	});

	it("names no window: a bar counts the current standing whatever the tiles read", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"MIXED",
				context,
			),
		).not.toMatch(/days|so far|All time/u);
	});

	it("marks none yet for a reader counted with no standing in the subject", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"NOT_OBSERVED",
				context,
			),
		).toMatch(/, 8 none yet\. The You marker is on none yet\.$/u);
	});

	it("says nothing of a reader who is not among the developers with a standing", () => {
		expect(
			splitDescription(
				{ shape: "SPLIT", needsAttention: 6, mixedFeedback: 7, goingWell: 7, noneYet: 8 },
				"NOT_OBSERVED",
				{ ...context, readerCounted: false },
			),
		).toMatch(/, 8 none yet\.$/u);
	});
});

describe("a split held back", () => {
	it("gives one short reason and nothing of the reader, never the total", () => {
		expect(splitDescription({ shape: "WITHHELD" }, "MIXED", context)).toBe(
			"Held back so no one can be singled out.",
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
		).toMatch(/^Developers with a current standing in this workspace: /u);
	});
});

describe("the hints", () => {
	it("names who the band is of once, with the count and window the response gives", () => {
		expect(tilesHint(3, "DAYS_90", 41)).toBe(
			"Except for open feedback, the typical range is the middle half of 41 developers with a standing in the last 90 days. Your marker shows you. These tiles compare you when at least 6 other developers have a standing in this range. Until then, they show only your own value. Open feedback counts what is open now, for all developers that this page counts.",
		);
	});

	it("names no count the server held back", () => {
		expect(tilesHint(3, "DAYS_30")).toMatch(
			/^Except for open feedback, the typical range is the middle half of the developers with a standing here\. /u,
		);
	});

	it("takes the part size from K", () => {
		expect(groupsHint(3)).toContain(
			"A bar shows only if each of its parts holds at least 3 other developers",
		);
	});

	it("says a bar counts the current standing, as the profile shows it, and names no window", () => {
		for (const hint of [groupsHint(3), practicesHint(3)]) {
			expect(hint).toContain("current standing");
			expect(hint).toContain("as their Practice profile shows it");
			expect(hint).not.toMatch(/days|All time/u);
		}
	});

	it("sends the reader to their profile for their next step", () => {
		expect(PAGE_PURPOSE).toContain("Your next step is in your Practice profile.");
	});

	it("says a practice's bar is also held back beside its group's, with no promise about later", () => {
		expect(practicesHint(3)).toContain(
			"The bar must also single no one out beside the group's bar.",
		);
		expect(splitDescription({ shape: "WITHHELD" }, "MIXED", context)).not.toMatch(/yet/u);
	});
});
