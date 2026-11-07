import { describe, expect, it } from "vitest";

import type { TrendSupport } from "@/api/types.gen";
import { wellSupported } from "@/stories/practice-profile-story-mock-data";

import {
	explainStanding,
	shownTrendDirection,
	standingWork,
	TREND_EXPLAINED,
	trendComparison,
} from "./practice-trend-presentation";

const support = (overrides: Partial<TrendSupport> = {}): TrendSupport => ({
	...wellSupported,
	...overrides,
});

describe("trend comparison sentence", () => {
	it("describes the comparison without assuming a bundle size", () => {
		expect(TREND_EXPLAINED).toBe("Compares your recent reviewed work with the work before it.");
		expect(trendComparison("IMPROVING", "practice", undefined)).toBe(TREND_EXPLAINED);
		expect(trendComparison("IMPROVING", "practice", support())).toBe(
			"Compares your last four reviewed pieces of work with the four before them.",
		);
	});

	it("names the practice's own counts when they differ from the default", () => {
		expect(
			trendComparison(
				"DECLINING",
				"practice",
				support({ currentOpportunities: 12, previousOpportunities: 4 }),
			),
		).toBe("Compares your last 12 reviewed pieces of work with the 4 before them.");
	});

	it("says what is missing where there is no direction yet", () => {
		expect(
			trendComparison(
				"INSUFFICIENT_EVIDENCE",
				"practice",
				support({ opportunitiesUntilComparable: 1 }),
			),
		).toBe("Needs one more reviewed piece of work.");
		expect(
			trendComparison(
				"INSUFFICIENT_EVIDENCE",
				"group",
				support({ opportunitiesUntilComparable: 3 }),
			),
		).toBe("Needs three more reviewed pieces of work.");
	});

	it("adds nothing where no direction is at hand and the gap is unknown", () => {
		expect(trendComparison("INSUFFICIENT_EVIDENCE", "practice", undefined)).toBe("");
		expect(trendComparison("INSUFFICIENT_EVIDENCE", "group", undefined)).toBe("");
	});

	it("gives a group no number", () => {
		expect(trendComparison("UNCERTAIN", "group", support())).not.toMatch(/four|eight|\d/u);
	});
});

describe("practice trend copy", () => {
	it("calls a settled standing from fewer than three pieces of work an early read", () => {
		expect(
			explainStanding(
				"STRENGTH",
				"practice",
				support({ standingWork: 1, reviewedWork: 1 }),
				"GITHUB",
			),
		).toBe(
			"An early read from the only pull request reviewed for this practice in the last 90 days.",
		);
		expect(
			explainStanding(
				"DEVELOPING",
				"group",
				support({ standingWork: 2, reviewedWork: 2 }),
				"GITLAB",
			),
		).toBe(
			"An early read from the two merge requests reviewed for this group in the last 90 days.",
		);
	});

	it("says how much more work was reviewed than an early read rests on", () => {
		expect(
			explainStanding("MIXED", "practice", support({ standingWork: 2, reviewedWork: 3 }), "GITHUB"),
		).toBe(
			"An early read from your last two reviewed pull requests. In the last 90 days, three pull requests were reviewed for this practice.",
		);
	});

	it("names the newest work a standing is read from and all the work reviewed for the practice", () => {
		expect(explainStanding("MIXED", "practice", support(), "GITHUB")).toBe(
			"Recent reviews found both strengths and problems here. Read from your last 4 reviewed pull requests. In the last 90 days, 11 pull requests were reviewed for this practice.",
		);
		expect(
			explainStanding(
				"STRENGTH",
				"practice",
				support({ standingWork: 3, reviewedWork: 3 }),
				"GITHUB",
			),
		).toBe(
			"Recent reviews here were almost entirely positive. Read from all three pull requests reviewed for this practice in the last 90 days.",
		);
	});

	it("counts a group's work once across its practices, in the same sentence shape", () => {
		expect(
			explainStanding(
				"DEVELOPING",
				"group",
				support({ standingWork: 6, reviewedWork: 15 }),
				"GITHUB",
			),
		).toBe(
			"Recent reviews here mostly found problems. Read from the last reviewed pull requests of each practice here, 6 in all. In the last 90 days, 15 pull requests were reviewed for this group.",
		);
	});

	it("says pull or merge requests without a provider, and pieces of work for mixed kinds", () => {
		expect(explainStanding("MIXED", "practice", support({ reviewedWork: 5 }))).toBe(
			"Recent reviews found both strengths and problems here. Read from your last four reviewed pull or merge requests. In the last 90 days, five pull or merge requests were reviewed for this practice.",
		);
		expect(
			explainStanding("MIXED", "practice", support({ reviewedWork: 5, reviewedWorkKind: null })),
		).toBe(
			"Recent reviews found both strengths and problems here. Read from your last four reviewed pieces of work. In the last 90 days, five pieces of work were reviewed for this practice.",
		);
		expect(
			explainStanding(
				"MIXED",
				"practice",
				support({ standingWork: 1, reviewedWork: 1, reviewedWorkKind: "scm.issue" }),
			),
		).toBe("An early read from the only issue reviewed for this practice in the last 90 days.");
	});

	it("keeps the registry's sentence where no count of work backs the standing", () => {
		expect(explainStanding("STRENGTH", "practice", undefined)).toBe(
			"Recent reviews here were almost entirely positive.",
		);
		expect(
			explainStanding("STRENGTH", "practice", support({ standingWork: 0, reviewedWork: 0 })),
		).toBe("Recent reviews here were almost entirely positive.");
		expect(explainStanding("NOT_OBSERVED", "practice", support({ standingWork: 1 }))).toBe(
			"No review has looked at this practice in your work yet.",
		);
	});

	it("reads how much work a standing rests on from the server", () => {
		expect(standingWork(support({ standingWork: 0 }))).toBeUndefined();
		expect(standingWork(undefined)).toBeUndefined();
		expect(standingWork(support())).toBe(4);
	});
});

describe("shownTrendDirection", () => {
	it("shows a direction only with the evidence behind it", () => {
		expect(shownTrendDirection("IMPROVING", wellSupported)).toBe("IMPROVING");
		expect(shownTrendDirection("IMPROVING", undefined)).toBe("INSUFFICIENT_EVIDENCE");
		expect(shownTrendDirection(undefined, wellSupported)).toBe("INSUFFICIENT_EVIDENCE");
	});
});
