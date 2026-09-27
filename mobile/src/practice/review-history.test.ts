import { describe, expect, it } from "vitest";

import type { ObservationDetail } from "@/api/types.gen";

import { observationResult, openableUrl, workHeading } from "./review-history";

describe("workHeading", () => {
	it("names the work by its title and its host's own word for it", () => {
		expect(
			workHeading(
				{
					id: "1",
					kind: "scm.pull_request",
					label: "!12",
					title: "Retry uploads",
					repositoryName: "api",
				},
				"GITLAB",
			),
		).toStrictEqual({ title: "Retry uploads", detail: "Merge request !12 · api" });
	});

	it("falls back to the kind and number when the title says nothing more", () => {
		expect(
			workHeading(
				{ id: "1", kind: "scm.pull_request", label: "", title: "Pull request" },
				"GITHUB",
			),
		).toStrictEqual({ title: "Pull request", detail: "Pull request" });
	});
});

function observation(
	facts: Pick<ObservationDetail, "assessmentStatus" | "presence" | "assessment" | "outcome">,
): ObservationDetail {
	return {
		id: "o",
		artifactId: 1,
		artifactKind: "scm.pull_request",
		observedAt: new Date(0),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		practiceName: "Include tests",
		practiceSlug: "tests",
		summary: "Cover the retry",
		...facts,
	};
}

describe("observationResult", () => {
	// The outcome is the server's, derived from presence and assessment together: a good behaviour that
	// is absent is a gap, so "good" alone never means a positive outcome.
	it.each([
		["PRESENT", "GOOD", "POSITIVE", "Positive outcome", true],
		["ABSENT", "GOOD", "NEGATIVE", "Negative outcome", false],
		["PRESENT", "BAD", "NEGATIVE", "Negative outcome", false],
		["ABSENT", "BAD", "POSITIVE", "Positive outcome", true],
	] as const)(
		"reads %s behaviour judged %s as the server's %s outcome",
		(presence, assessment, outcome, label, positive) => {
			expect(
				observationResult(
					observation({ assessmentStatus: "ASSESSED", presence, assessment, outcome }),
				),
			).toStrictEqual({ label, positive });
		},
	);

	it("says why an unassessed observation has no outcome", () => {
		expect(observationResult(observation({ assessmentStatus: "NOT_APPLICABLE" }))).toStrictEqual({
			label: "Not applicable",
			positive: undefined,
		});
		expect(observationResult(observation({ assessmentStatus: "UNDETERMINED" }))).toStrictEqual({
			label: "Undetermined",
			positive: undefined,
		});
	});

	it("claims no outcome the server did not send", () => {
		expect(
			observationResult(
				observation({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "GOOD" }),
			),
		).toStrictEqual({ label: "Assessed", positive: undefined });
	});
});

describe("openableUrl", () => {
	it("opens only https addresses", () => {
		expect(openableUrl("https://github.com/org/repo/pull/1")).toBe(
			"https://github.com/org/repo/pull/1",
		);
		expect(openableUrl("http://example.org")).toBeUndefined();
		expect(openableUrl("data:text/html,hello")).toBeUndefined();
		expect(openableUrl("not a url")).toBeUndefined();
		expect(openableUrl(undefined)).toBeUndefined();
	});
});
