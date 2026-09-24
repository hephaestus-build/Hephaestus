import { describe, expect, it } from "vitest";

import type {
	PracticeGroupTrend,
	PracticeStanding,
	PracticeStandingObservation,
	PracticeTrend,
	ReviewedPractice,
} from "@/api/types.gen";

import { contributingPractices, nextStepOf } from "./practice-standing";

function observation(overrides: Partial<PracticeStandingObservation>): PracticeStandingObservation {
	return {
		observationId: "obs-1",
		kind: "OMISSION_GAP",
		origin: "LIVE",
		reviewedWorkId: 1,
		workKind: "scm.pull_request",
		title: "Small pull requests",
		...overrides,
	};
}

function standing(
	toWorkOn: PracticeStandingObservation[],
	overrides: Partial<PracticeStanding> = {},
): PracticeStanding {
	return {
		slug: "small-pull-requests",
		name: "Small pull requests",
		standing: "DEVELOPING",
		strengths: [],
		toWorkOn,
		...overrides,
	};
}

describe("nextStepOf", () => {
	it("is nothing without a standing", () => {
		expect(nextStepOf(undefined)).toBeUndefined();
	});

	it("is nothing when there is nothing to work on", () => {
		expect(nextStepOf(standing([]))).toBeUndefined();
	});

	it("prefers the delivered feedback of the first item to work on", () => {
		const next = nextStepOf(
			standing([
				observation({ deliveredFeedback: "  Split the migration out.  ", title: "Mixed concerns" }),
				observation({ deliveredFeedback: "Later item" }),
			]),
		);
		expect(next).toBe("Split the migration out.");
	});

	it("falls back to the observation title when nothing was delivered", () => {
		expect(nextStepOf(standing([observation({ title: " Mixed concerns " })]))).toBe(
			"Mixed concerns",
		);
	});

	it("withholds a title that only repeats the practice name", () => {
		expect(nextStepOf(standing([observation({ title: "Small pull requests " })]))).toBeUndefined();
	});

	it("withholds empty delivered feedback as well as a repeated title", () => {
		expect(
			nextStepOf(standing([observation({ deliveredFeedback: "", title: "Small pull requests" })])),
		).toBeUndefined();
	});
});

describe("contributingPractices", () => {
	const practices: ReviewedPractice[] = [
		{ slug: "small-pull-requests", name: "Small pull requests", groupSlug: "flow" },
		{ slug: "clear-titles", name: "Clear titles", groupSlug: "flow" },
		{ slug: "test-first", name: "Test first", groupSlug: "quality" },
	];
	const standings = [
		standing([observation({ deliveredFeedback: "Split the migration out." })], {
			groupSlug: "flow",
		}),
		standing([], { slug: "test-first", name: "Test first", groupSlug: "quality" }),
	];
	const support: PracticeTrend["support"] = {
		bundleSize: 5,
		credibilityThreshold: 0.8,
		currentOpportunities: 5,
		previousOpportunities: 5,
		opportunitiesUntilComparable: 0,
		ropeHalfWidth: 0.1,
	};
	const trend: PracticeGroupTrend = {
		group: { slug: "flow", scope: "GROUP", direction: "IMPROVING", support, opportunities: [] },
		practices: [
			{
				slug: "clear-titles",
				scope: "PRACTICE",
				direction: "DECLINING",
				support,
				opportunities: [],
			},
		],
	};

	it("keeps only the practices of the requested group", () => {
		expect(contributingPractices("flow", practices, standings).map((p) => p.slug)).toStrictEqual([
			"small-pull-requests",
			"clear-titles",
		]);
	});

	it("joins each practice with its standing and next step", () => {
		const [first] = contributingPractices("flow", practices, standings);
		expect(first?.standing).toBe("DEVELOPING");
		expect(first?.nextStep).toBe("Split the migration out.");
	});

	it("leaves a practice without a standing unmeasured", () => {
		const [, second] = contributingPractices("flow", practices, standings);
		expect(second?.standing).toBeUndefined();
		expect(second?.nextStep).toBeUndefined();
	});

	it("attaches the trend of a practice once the group trend is known", () => {
		const [, second] = contributingPractices("flow", practices, standings, trend);
		expect(second?.trend?.direction).toBe("DECLINING");
	});

	it("carries no trend while the group trend is still loading", () => {
		expect(
			contributingPractices("flow", practices, standings).every((p) => p.trend === undefined),
		).toBe(true);
	});
});
