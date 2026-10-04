import { describe, expect, it } from "vitest";

import type { WorkspaceSplit } from "@/api/types.gen";

import { barsHint, shownSplit, splitDescription, tilesHint } from "./across-workspace-copy";

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
	developers: needsAttention + mixedFeedback + goingWell + noneYet,
});

describe("splitDescription", () => {
	it("names the whole, every count in the server's order, and the part the marker is on", () => {
		expect(splitDescription(split(6, 7, 7, 8), "MIXED", true)).toBe(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
	});

	it.each(["NOT_OBSERVED", "NO_OPPORTUNITY"] as const)(
		"puts a counted reader whose standing is %s on none yet",
		(standing) => {
			expect(splitDescription(split(6, 7, 7, 8), standing, true)).toMatch(
				/The You marker is on none yet\.$/u,
			);
		},
	);

	it("marks no one when the reader is not counted", () => {
		expect(splitDescription(split(6, 7, 7, 8), "MIXED", false)).toMatch(/, 8 none yet\.$/u);
	});

	it("marks no one when the server sends no marker", () => {
		expect(splitDescription(split(6, 7, 7, 8), undefined, true)).toMatch(/, 8 none yet\.$/u);
	});

	it("gives a total-only split its total and reason, and nothing of the reader", () => {
		expect(
			splitDescription({ shape: "TOTAL_ONLY", parts: [], developers: 1 }, undefined, true),
		).toBe(
			"1 developer with a current standing in this workspace. The split is held back so no one can be singled out.",
		);
	});

	it("gives a withheld split its reason alone, with no total and no promise about later", () => {
		expect(splitDescription({ shape: "WITHHELD", parts: [] }, undefined, true)).toBe(
			"Held back so no one can be singled out.",
		);
	});
});

describe("shownSplit", () => {
	const incomplete: [string, WorkspaceSplit][] = [
		["a split with no total", { ...split(6, 7, 7, 8), developers: undefined }],
		["a split with no none yet", { ...split(6, 7, 7, 8), noneYet: undefined }],
		["a total-only split with no total", { shape: "TOTAL_ONLY", parts: [] }],
	];
	it.each(incomplete)("holds back %s rather than drawing a 0", (_, wire) => {
		expect(shownSplit(wire)).toBeUndefined();
		expect(splitDescription(wire, undefined, true)).toBe("Held back so no one can be singled out.");
	});
});

describe("tilesHint", () => {
	const tiles = {
		minimumOthersForMiddleHalf: 6,
		window: "DAYS_30",
		developersWithAStandingInWindow: 41,
	} as const;
	const WITH_A_BAND = { yours: 3, middle: { low: 1, high: 4 } };

	it.each([
		["DAYS_30", "the 41 developers in this workspace who have a standing in the last 30 days,"],
		["DAYS_90", "the 41 developers in this workspace who have a standing in the last 90 days,"],
		["ALL_TIME", "the 41 developers in this workspace who have a standing so far,"],
	] as const)("names the %s window in the toggle's words", (window, phrase) => {
		expect(tilesHint({ ...tiles, window }, WITH_A_BAND)[0]).toContain(phrase);
	});

	it("names no count where the server held the total back", () => {
		const [band] = tilesHint({ minimumOthersForMiddleHalf: 6, window: "DAYS_30" }, WITH_A_BAND);
		expect(band).toContain("takes the developers in this workspace who have a standing");
	});

	it("agrees count, noun and verb in the singular", () => {
		const [band] = tilesHint(
			{ ...tiles, developersWithAStandingInWindow: 1, minimumOthersForMiddleHalf: 1 },
			WITH_A_BAND,
		);
		expect(band).toContain("the 1 developer in this workspace who has a standing");
		expect(band).toContain("only when at least 1 other developer has a standing.");
	});

	it("says when open feedback shows its band only while it has none", () => {
		const open =
			"Open feedback counts what is open now, for every developer that this page counts.";
		expect(tilesHint(tiles, WITH_A_BAND)[1]).toBe(open);
		// A band at nought is still a band: the tile says most have none in its place.
		expect(tilesHint(tiles, { yours: 0, middle: { low: 0, high: 0 } })[1]).toBe(open);
		expect(tilesHint(tiles, { yours: 3 })[1]).toBe(
			`${open} Its band shows only when this page counts at least 6 other developers.`,
		);
	});
});

describe("barsHint", () => {
	// The server shows a part only above K with the reader counted (CohortPrivacyPolicy), so K = 3
	// means a floor of 4 and 3 others whoever reads the bar.
	it("names the part floor as K + 1 and the others it leaves as K", () => {
		const hint = barsHint(3, "group");
		expect(hint).toContain("only when each part holds at least 4 developers.");
		expect(hint).toContain("So each part stands for at least 3 developers other than you.");
	});

	it("adds the practice-only differencing rule, and only for a practice", () => {
		const rule = "beside its group’s bar, it would single out fewer than 3 developers.";
		expect(barsHint(3, "practice")).toContain(rule);
		expect(barsHint(3, "group")).not.toContain(rule);
	});
});
