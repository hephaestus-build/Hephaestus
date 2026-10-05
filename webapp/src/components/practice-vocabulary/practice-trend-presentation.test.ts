import { describe, expect, it } from "vitest";

import type { TrendSupport } from "@/api/types.gen";
import { wellSupported } from "@/stories/practice-profile-story-mock-data";

import {
	explainStanding,
	formatGroupStandingBasis,
	formatStandingBasis,
	formatStandingWork,
	formatTrendProvenance,
	shownTrendDirection,
	standingWork,
} from "./practice-trend-presentation";

const support = (overrides: Partial<TrendSupport> = {}): TrendSupport => ({
	...wellSupported,
	...overrides,
});

describe("practice trend copy", () => {
	it("describes a practice comparison the server actually made", () => {
		expect(formatTrendProvenance(support({ calendarSpanDays: 9 }), "IMPROVING", "practice")).toBe(
			"Compared your latest four pieces of reviewed work with the four before them. Evidence spans nine days.",
		);
	});

	it("handles singular provenance and a zero-evidence state", () => {
		expect(
			formatTrendProvenance(
				support({ currentOpportunities: 1, previousOpportunities: 0, calendarSpanDays: 1 }),
				"IMPROVING",
				"practice",
			),
		).toBe("Based on one piece of reviewed work. Evidence spans one day.");
		expect(
			formatTrendProvenance(
				support({ currentOpportunities: 0, previousOpportunities: 0, opportunities: 0 }),
				"INSUFFICIENT_EVIDENCE",
				"practice",
			),
		).toBe("No new work has been reviewed yet.");
	});

	it("claims no comparison when the server formed none", () => {
		const sentence = formatTrendProvenance(
			support({ previousOpportunities: 3, opportunities: 7, opportunitiesUntilComparable: 1 }),
			"INSUFFICIENT_EVIDENCE",
			"practice",
		);

		expect(sentence).not.toContain("Compared");
		expect(sentence).toBe(
			"Based on seven pieces of reviewed work. A direction needs one more piece of reviewed work with something to judge. Evidence spans 12 days.",
		);
	});

	it("says the latest work counts most, or names the review of past work a standing rests on", () => {
		expect(formatStandingBasis(support())).toBe(
			"Your latest work counts most, and older work counts less.",
		);
		expect(formatStandingBasis(support({ currentOpportunities: 2 }))).toBe(
			"Your latest work counts most, and older work counts less.",
		);
		// One piece weighs nothing against another: the early read already names it.
		expect(formatStandingBasis(support({ currentOpportunities: 1 }))).toBeUndefined();
		expect(formatStandingBasis(support({ currentOpportunities: 0 }))).toBe(
			"Read from a review that you asked for or a review of your past work. Neither moves a trend.",
		);
	});

	it("calls a settled standing from fewer than three pieces of work an early read", () => {
		expect(explainStanding("STRENGTH", "practice", support({ currentOpportunities: 1 }))).toBe(
			"An early read from one piece of work.",
		);
		expect(explainStanding("DEVELOPING", "group", support({ currentOpportunities: 2 }))).toBe(
			"An early read from two pieces of work.",
		);
	});

	it("names the work a settled standing is read from after the registry's sentence", () => {
		expect(explainStanding("STRENGTH", "practice", support({ currentOpportunities: 3 }))).toBe(
			"Recent reviews here were almost entirely positive. Read from three pieces of work.",
		);
		expect(explainStanding("MIXED", "practice", support())).toBe(
			"Recent reviews found both strengths and problems here. Read from four pieces of work.",
		);
	});

	it("keeps the registry's sentence where no count of work backs the standing", () => {
		expect(explainStanding("STRENGTH", "practice", undefined)).toBe(
			"Recent reviews here were almost entirely positive.",
		);
		expect(explainStanding("STRENGTH", "practice", support({ currentOpportunities: 0 }))).toBe(
			"Recent reviews here were almost entirely positive.",
		);
		expect(explainStanding("NOT_OBSERVED", "practice", support({ currentOpportunities: 1 }))).toBe(
			"No review has observed this practice in your work yet.",
		);
	});

	it("names how much work a standing rests on beside its badge, in digits", () => {
		expect(formatStandingWork(1)).toBe("from 1 piece of work");
		expect(formatStandingWork(4)).toBe("from 4 pieces of work");
		expect(standingWork(support({ currentOpportunities: 0 }))).toBeUndefined();
		expect(standingWork(undefined)).toBeUndefined();
		expect(standingWork(support())).toBe(4);
	});

	it("counts a group's practices by standing as one sentence, in the registry's order", () => {
		expect(formatGroupStandingBasis({ STRENGTH: 2, DEVELOPING: 2, MIXED: 1 })).toBe(
			"Of five practices, two need attention, one shows mixed feedback and two are going well.",
		);
		expect(formatGroupStandingBasis({ NOT_OBSERVED: 1 })).toBe(
			"Of one practice, one is not observed yet.",
		);
		expect(formatGroupStandingBasis({ STRENGTH: 12 })).toBe("Of 12 practices, 12 are going well.");
		expect(formatGroupStandingBasis({})).toBeUndefined();
	});

	it("writes both counts of one clause as digits once either reaches ten", () => {
		expect(
			formatTrendProvenance(
				support({ currentOpportunities: 12, previousOpportunities: 4, calendarSpanDays: 30 }),
				"IMPROVING",
				"practice",
			),
		).toBe(
			"Compared your latest 12 pieces of reviewed work with the 4 before them. Evidence spans 30 days.",
		);
	});

	it("does not describe a group trend as a comparison of bundles", () => {
		const sentence = formatTrendProvenance(
			support({
				currentOpportunities: 7,
				previousOpportunities: 5,
				opportunities: 12,
				comparablePractices: 3,
				eligiblePractices: 5,
			}),
			"IMPROVING",
			"group",
		);

		expect(sentence).not.toContain("Compared");
		expect(sentence).toBe(
			"Across 12 pieces of reviewed work in this group. Three of five practices here had enough evidence to compare. Evidence spans 12 days.",
		);
	});

	it("counts a piece of work both bundles claim once", () => {
		// A group bundles per practice, so one pull request can be current evidence for one practice and
		// previous evidence for another. The reader is told what the group saw, not what the bundles add up
		// to.
		const shared = {
			currentOpportunities: 7,
			previousOpportunities: 5,
			opportunities: 10,
			comparablePractices: 3,
			eligiblePractices: 5,
		};

		expect(formatTrendProvenance(support(shared), "IMPROVING", "group")).toBe(
			"Across 10 pieces of reviewed work in this group. Three of five practices here had enough evidence to compare. Evidence spans 12 days.",
		);
		expect(
			formatTrendProvenance(
				support({ ...shared, opportunitiesUntilComparable: 2 }),
				"INSUFFICIENT_EVIDENCE",
				"group",
			),
		).toBe(
			"Based on 10 pieces of reviewed work. A direction needs two more pieces of reviewed work with something to judge. Evidence spans 12 days.",
		);
	});
});

describe("shownTrendDirection", () => {
	it("shows a direction only with the evidence behind it", () => {
		expect(shownTrendDirection("IMPROVING", wellSupported)).toBe("IMPROVING");
		expect(shownTrendDirection("IMPROVING", undefined)).toBe("INSUFFICIENT_EVIDENCE");
		expect(shownTrendDirection(undefined, wellSupported)).toBe("INSUFFICIENT_EVIDENCE");
	});
});
