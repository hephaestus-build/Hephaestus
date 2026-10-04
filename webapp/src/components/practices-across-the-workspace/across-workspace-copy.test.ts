import { describe, expect, it } from "vitest";

import type { WorkspaceSplit } from "@/api/types.gen";

import {
	barsHint,
	PAGE_PURPOSE,
	type SplitContext,
	splitDescription,
	tilesHint,
} from "./across-workspace-copy";

const split = (
	needsAttention: number,
	mixedFeedback: number,
	goingWell: number,
	noneYet: number,
): WorkspaceSplit => ({
	shape: "SPLIT",
	parts: [
		{ standing: "DEVELOPING", developers: needsAttention },
		{ standing: "MIXED", developers: mixedFeedback },
		{ standing: "STRENGTH", developers: goingWell },
	],
	noneYet,
});

const WITHHELD: WorkspaceSplit = { shape: "WITHHELD", parts: [] };

const context: SplitContext = {
	readerCounted: true,
	developersWithAStanding: 28,
	minimumOthers: 3,
};

describe("splitDescription", () => {
	it("names the reference group, every count the bar shows, and the part the marker is on", () => {
		expect(splitDescription(split(6, 7, 7, 8), "MIXED", context)).toBe(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
	});

	it("names no window: a bar counts the current standing whatever the tiles read", () => {
		expect(splitDescription(split(6, 7, 7, 8), "MIXED", context)).not.toMatch(
			/days|so far|All time/u,
		);
	});

	it("marks none yet for a reader counted with no standing in the subject", () => {
		expect(splitDescription(split(6, 7, 7, 8), "NOT_OBSERVED", context)).toMatch(
			/, 8 none yet\. The You marker is on none yet\.$/u,
		);
	});

	it("says nothing of a reader who is not among the developers with a standing", () => {
		expect(
			splitDescription(split(6, 7, 7, 8), "NOT_OBSERVED", { ...context, readerCounted: false }),
		).toMatch(/, 8 none yet\.$/u);
	});
});

describe("a split shown only as its total", () => {
	const totalOnly: WorkspaceSplit = { shape: "TOTAL_ONLY", parts: [], developers: 41 };

	it("names the total and why the parts are held back, and nothing of the reader", () => {
		expect(splitDescription(totalOnly, "MIXED", context)).toBe(
			"41 developers with a current standing in this workspace. The split is held back so no one can be singled out.",
		);
	});
});

describe("a split held back", () => {
	it("gives one short reason and nothing of the reader, never the total", () => {
		expect(splitDescription(WITHHELD, "MIXED", context)).toBe(
			"Held back so no one can be singled out.",
		);
	});

	it("names no total the server held back", () => {
		expect(
			splitDescription(split(4, 5, 6, 4), "MIXED", {
				...context,
				developersWithAStanding: undefined,
			}),
		).toMatch(/^Developers with a current standing in this workspace: /u);
	});
});

describe("the hints", () => {
	it("says how the typical range comes about, with the count and window the response gives", () => {
		expect(
			tilesHint(
				{
					minimumOthersForMiddleHalf: 6,
					window: "DAYS_30",
					developersWithAStandingInWindow: 41,
				},
				{ yours: 3, middleLow: 1, middleHigh: 4 },
			),
		).toStrictEqual([
			"The grey band is the typical range. To find it, Hephaestus sorts the 41 developers with a standing in the last 30 days by their value. The band covers the middle half: a quarter of them are below it, and a quarter are above it. Your marker shows your value. A tile shows the band only when at least 6 other developers have a standing.",
			"Open feedback counts what is open now, for every developer that this page counts.",
		]);
	});

	it("names no count the server held back", () => {
		const [band] = tilesHint(
			{ minimumOthersForMiddleHalf: 6, window: "DAYS_30" },
			{ yours: 3, middleLow: 1, middleHigh: 4 },
		);
		expect(band).toContain(
			"To find it, Hephaestus sorts the developers with a standing in the last 30 days by their value.",
		);
		expect(band).not.toMatch(/\d+ developers? with a standing/u);
	});

	it("takes the part size from K", () => {
		expect(barsHint(3, "group")).toContain(
			"A bar shows its parts only if each part holds at least 3 other developers",
		);
	});

	it("says a bar counts the current standing, as the profile shows it, and names no window", () => {
		for (const hint of [barsHint(3, "group"), barsHint(3, "practice")]) {
			expect(hint).toContain("current standing");
			expect(hint).toContain("as their Practice profile shows it");
			expect(hint).not.toMatch(/days|All time/u);
		}
	});

	it("sends the reader to their profile for their next step", () => {
		expect(PAGE_PURPOSE).toContain("Your next step is in your Practice profile.");
	});

	it("says a bar then shows only its total", () => {
		expect(barsHint(3, "group")).toContain("If not, the bar shows only its total");
		expect(barsHint(3, "practice")).toContain("If not, the bar shows only its total.");
	});

	it("says a practice's parts are also held back beside its group's, with no promise about later", () => {
		expect(barsHint(3, "practice")).toContain(
			"The parts must also single no one out beside the group’s bar.",
		);
		expect(splitDescription(WITHHELD, "MIXED", context)).not.toMatch(/yet/u);
	});
});
