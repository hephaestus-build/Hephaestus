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

describe("the tiles' hint holds for every response", () => {
	const OPEN_WITH_A_BAND = { yours: 3, middleLow: 1, middleHigh: 4 };
	const OPEN_ALONE = { yours: 3 };
	const tiles = {
		minimumOthersForMiddleHalf: 6,
		window: "DAYS_30",
		developersWithAStandingInWindow: 41,
	} as const;

	it("counts one developer in the singular", () => {
		const [band] = tilesHint({ ...tiles, developersWithAStandingInWindow: 1 }, OPEN_WITH_A_BAND);
		expect(band).toContain(
			"Hephaestus takes the 1 developer in this workspace who has a standing in the last 30 days",
		);
	});

	it("names no count where the server held the total back, in every window", () => {
		for (const window of ["DAYS_30", "DAYS_90", "ALL_TIME"] as const) {
			const [band] = tilesHint({ minimumOthersForMiddleHalf: 6, window }, OPEN_WITH_A_BAND);
			expect(band).toMatch(
				/Hephaestus takes the developers in this workspace who have a standing /u,
			);
			expect(band).not.toMatch(/\d+ developers? with a standing/u);
		}
	});

	it.each([
		[
			"DAYS_30",
			"the 41 developers in this workspace who have a standing in the last 30 days, and sorts them",
		],
		[
			"DAYS_90",
			"the 41 developers in this workspace who have a standing in the last 90 days, and sorts them",
		],
		["ALL_TIME", "the 41 developers in this workspace who have a standing so far, and sorts them"],
	] as const)("names the %s window as the toggle does", (window, phrase) => {
		const [band] = tilesHint({ ...tiles, window }, OPEN_WITH_A_BAND);
		expect(band).toContain(phrase);
	});

	it("takes the threshold from the server, with its verb in number", () => {
		expect(tilesHint({ ...tiles, minimumOthersForMiddleHalf: 10 }, OPEN_WITH_A_BAND)[0]).toContain(
			"only when at least 10 other developers have a standing.",
		);
		expect(tilesHint({ ...tiles, minimumOthersForMiddleHalf: 1 }, OPEN_WITH_A_BAND)[0]).toContain(
			"only when at least 1 other developer has a standing.",
		);
	});

	it("says open feedback counts every developer and what is open now, with its band", () => {
		expect(tilesHint(tiles, OPEN_WITH_A_BAND)[1]).toBe(
			"Open feedback counts what is open now, for every developer that this page counts.",
		);
		// A band at nought is still a band: the tile says most have none in its place.
		expect(tilesHint(tiles, { yours: 0, middleLow: 0, middleHigh: 0 })[1]).toBe(
			"Open feedback counts what is open now, for every developer that this page counts.",
		);
	});

	it("says when open feedback shows its band, where it has none, by the server's threshold", () => {
		expect(tilesHint(tiles, OPEN_ALONE)[1]).toBe(
			"Open feedback counts what is open now, for every developer that this page counts. Its band shows only when this page counts at least 6 other developers.",
		);
		expect(tilesHint({ ...tiles, minimumOthersForMiddleHalf: 1 }, OPEN_ALONE)[1]).toMatch(
			/at least 1 other developer\.$/u,
		);
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
			"The grey band is the typical range. To find it, Hephaestus takes the 41 developers in this workspace who have a standing in the last 30 days, and sorts them by their value. The band covers the middle half: a quarter of them are below it, and a quarter are above it. Your marker shows your value. A tile shows the band only when at least 6 other developers have a standing.",
			"Open feedback counts what is open now, for every developer that this page counts.",
		]);
	});

	it("names no count the server held back", () => {
		const [band] = tilesHint(
			{ minimumOthersForMiddleHalf: 6, window: "DAYS_30" },
			{ yours: 3, middleLow: 1, middleHigh: 4 },
		);
		expect(band).toContain(
			"To find it, Hephaestus takes the developers in this workspace who have a standing in the last 30 days, and sorts them by their value.",
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
