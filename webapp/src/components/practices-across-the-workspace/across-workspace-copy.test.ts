import { describe, expect, it } from "vitest";

import type { WorkspaceSplit } from "@/api/types.gen";

import { barsHint, splitDescription, tilesHint } from "./across-workspace-copy";

const split = (
	needsAttention: number,
	mixedFeedback: number,
	goingWell: number,
	noneYet: number,
): WorkspaceSplit => ({
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
		expect(splitDescription(split(6, 7, 7, 8), "MIXED")).toBe(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
	});

	it.each(["NOT_OBSERVED", "NO_OPPORTUNITY"] as const)(
		"puts a reader whose standing is %s on none yet",
		(standing) => {
			expect(splitDescription(split(6, 7, 7, 8), standing)).toMatch(
				/The You marker is on none yet\.$/u,
			);
		},
	);

	it("marks no one when the server sends no marker", () => {
		expect(splitDescription(split(6, 7, 7, 8), undefined)).toMatch(/, 8 none yet\.$/u);
	});

	it("draws parts of one and none like any other", () => {
		expect(splitDescription(split(1, 0, 2, 0), "STRENGTH")).toBe(
			"3 developers with a current standing in this workspace: 1 Needs attention, 0 Mixed feedback, 2 Going well, 0 none yet. The You marker is on Going well.",
		);
	});

	it("says that nobody has a standing when the split counts nobody", () => {
		expect(splitDescription(split(0, 0, 0, 0), undefined)).toBe("No developer has a standing yet.");
	});
});

describe("tilesHint", () => {
	const tiles = { window: "DAYS_30", developersWithAStandingInWindow: 41 } as const;

	it.each([
		["DAYS_30", "the 41 developers in this workspace who have a standing in the last 30 days by"],
		["DAYS_90", "the 41 developers in this workspace who have a standing in the last 90 days by"],
		["ALL_TIME", "the 41 developers in this workspace who have a standing so far by"],
	] as const)("names the %s window in the toggle's words", (window, phrase) => {
		expect(tilesHint({ ...tiles, window })[0]).toContain(phrase);
	});

	it("agrees count, noun and verb in the singular", () => {
		const [band] = tilesHint({ ...tiles, developersWithAStandingInWindow: 1 });
		expect(band).toContain("the 1 developer in this workspace who has a standing");
	});

	it("says plainly that a band over few developers can show one person's value", () => {
		expect(tilesHint(tiles)[0]).toMatch(
			/When only a few developers are counted, the band can show the value of one person\.$/u,
		);
	});

	it("says why there is no band when nobody is counted", () => {
		expect(tilesHint({ ...tiles, developersWithAStandingInWindow: 0 })[0]).toBe(
			"No developer in this workspace has a standing in the last 30 days, so these figures have no typical range.",
		);
	});
});

describe("barsHint", () => {
	it.each(["group", "practice"] as const)(
		"tells a reader of a %s what a small count can show",
		(scope) => {
			expect(barsHint(scope)).toContain(`current standing in the ${scope},`);
			expect(barsHint(scope)).toMatch(/So a small count can let others tell where you stand\.$/u);
		},
	);
});
