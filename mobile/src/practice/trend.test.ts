import { describe, expect, it } from "vitest";

import type { TrendSupport } from "@/api/types.gen";

import { trendProvenance } from "./trend";

// The web's own cases (`webapp/src/components/practice-vocabulary/practice-trend-presentation.test.ts`), so both
// clients describe the same evidence; only what is still needed is worded as relevant reviewed work.
const support = (overrides: Partial<TrendSupport> = {}): TrendSupport => ({
	currentOpportunities: 4,
	opportunities: 8,
	previousOpportunities: 4,
	opportunitiesUntilComparable: 0,
	bundleSize: 4,
	ropeHalfWidth: 0.15,
	credibilityThreshold: 0.9,
	...overrides,
});

describe("trendProvenance", () => {
	it("describes a practice comparison the server actually made", () => {
		expect(trendProvenance(support({ calendarSpanDays: 9 }), "IMPROVING", "practice")).toBe(
			"Compared your latest 4 pieces of reviewed work with the 4 before them. Evidence spans 9 days.",
		);
	});

	it("handles singular provenance and a zero-evidence state", () => {
		expect(
			trendProvenance(
				support({
					currentOpportunities: 1,
					previousOpportunities: 0,
					opportunities: 1,
					calendarSpanDays: 1,
				}),
				"IMPROVING",
				"practice",
			),
		).toBe("Based on 1 piece of reviewed work. Evidence spans 1 day.");
		expect(
			trendProvenance(
				support({ currentOpportunities: 0, previousOpportunities: 0, opportunities: 0 }),
				"INSUFFICIENT_EVIDENCE",
				"practice",
			),
		).toBe("No reviewed work is available yet.");
	});

	it("claims no comparison when the server formed none", () => {
		const sentence = trendProvenance(
			support({ previousOpportunities: 3, opportunities: 7, opportunitiesUntilComparable: 1 }),
			"INSUFFICIENT_EVIDENCE",
			"practice",
		);
		expect(sentence).not.toContain("Compared");
		expect(sentence).toBe(
			"Based on 7 pieces of reviewed work. 1 more piece of relevant reviewed work is needed before a direction can be shown.",
		);
	});

	it("uses distinct reviewed work for a group instead of summing overlapping bundles", () => {
		const sentence = trendProvenance(
			support({
				currentOpportunities: 7,
				previousOpportunities: 5,
				opportunities: 10,
				comparablePractices: 3,
				eligiblePractices: 5,
			}),
			"IMPROVING",
			"group",
		);
		expect(sentence).not.toContain("Compared");
		expect(sentence).toBe(
			"Across 10 pieces of reviewed work in this group. 3 of 5 practices here had enough evidence to compare.",
		);
	});
});
