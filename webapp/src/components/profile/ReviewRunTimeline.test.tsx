import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ObservationDetail, PracticeGroupReviewRun } from "@/api/types.gen";
import { formatDay, formatShortDay } from "@/lib/dates";
import { daysBefore } from "@/stories/story-clock";

import { ReviewRunTimeline } from "./ReviewRunTimeline";

const baseObservation = {
	id: "00000000-0000-0000-0000-000000000102",
	feedbackResponse: {
		feedbackId: "00000000-0000-0000-0000-000000000103",
		usefulness: "HELPFUL",
		resolution: "ADDRESSED",
		comment: "Applied in the next revision.",
	},
	practiceSlug: "records-decisions",
	practiceName: "Record significant decisions and the reasoning",
	summary: "The workspace trade-off is documented",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "GOOD",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	artifactId: 902,
	artifactKind: "scm.pull_request",
	evidenceRationale: "The description says why the trade-off was made, not only what it is.",
} satisfies ObservationDetail;

const run = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		kind: "scm.pull_request",
		id: "902",
		label: "#902",
		repositoryName: "HephaestusTest/practice-validation",
		url: "https://github.com/HephaestusTest/practice-validation/pull/902",
	},
	observations: [baseObservation],
} satisfies PracticeGroupReviewRun;

describe("ReviewRunTimeline", () => {
	it("names the day once, on the run's own card, and on none of its rows", () => {
		const denseRun: PracticeGroupReviewRun = {
			...run,
			observations: [1, 2, 3].map((n) => ({
				...baseObservation,
				id: `00000000-0000-0000-0000-00000000020${n}`,
				summary: `Observation ${n}`,
			})),
		};
		render(<ReviewRunTimeline runs={[denseRun]} />);

		expect(screen.getAllByText(formatShortDay(run.reviewedAt))).toHaveLength(1);
		expect(screen.queryByText(formatDay(baseObservation.observedAt))).toBeNull();
	});
});
