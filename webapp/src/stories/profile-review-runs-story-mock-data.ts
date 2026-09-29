import type { ObservationDetail, ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";

import { detailObservation } from "./practice-detail-story-mock-data";
import { issue, pullRequest } from "./practice-profile-story-mock-data";
import { daysBefore } from "./story-clock";

/** The review `artifactTrace`'s practices answered for, in the trace fixtures. */
const TRACED_REVIEW = "11111111-1111-1111-1111-111111111111";

/** GitLab, since only a GitLab note can be linked to from a review's row. */
const tracedMergeRequest: ReviewedWorkRef = {
	...pullRequest(1423),
	provider: "GITLAB",
	label: "!1423",
	url: "https://gitlab.example.com/acme/api/-/merge_requests/1423",
};

const nothingCounted = {
	practices: { toImprove: 0, held: 0, notApplicable: 0, undecided: 0 },
	feedbackDelivered: 0,
	slippedPractices: [],
} satisfies Pick<ProfileReviewRun, "practices" | "feedbackDelivered" | "slippedPractices">;

/**
 * The newest review, on the traced merge request: Thin controllers slipped, Product language held,
 * Migration safety was undecided, and Clear ownership was never reached.
 */
export const openProfileReviewRun: ProfileReviewRun = {
	reviewId: TRACED_REVIEW,
	reviewedAt: daysBefore(2),
	reviewedWork: tracedMergeRequest,
	status: "COMPLETED",
	triggerMode: "MANUAL",
	practicesEvaluated: 3,
	practices: { toImprove: 1, held: 1, notApplicable: 0, undecided: 1 },
	feedbackDelivered: 1,
	feedbackUrl: "https://gitlab.example.com/acme/api/-/merge_requests/1423#note_2481902",
	slippedPractices: [{ practiceSlug: "thin-controllers", practiceName: "Thin controllers" }],
	mayRequest: true,
};

/** Newest first: a second review of one merge request, a quiet issue, a stop, and an older pass. */
export const profileReviewRuns: ProfileReviewRun[] = [
	openProfileReviewRun,
	{
		reviewId: "00000000-0000-0000-0000-0000000002a2",
		reviewedAt: daysBefore(4),
		reviewedWork: tracedMergeRequest,
		status: "COMPLETED",
		triggerMode: "AUTO",
		practicesEvaluated: 9,
		practices: { toImprove: 3, held: 1, notApplicable: 5, undecided: 0 },
		feedbackDelivered: 2,
		slippedPractices: [
			{ practiceSlug: "explain-changes", practiceName: "Explain each change" },
			{ practiceSlug: "reviewable-diff-size", practiceName: "Keep the diff reviewable" },
			{ practiceSlug: "meaningful-commits", practiceName: "Meaningful commit history" },
		],
		mayRequest: true,
	},
	{
		reviewId: "00000000-0000-0000-0000-0000000002a3",
		reviewedAt: daysBefore(9),
		reviewedWork: issue(31),
		status: "COMPLETED",
		triggerMode: "AUTO",
		practicesEvaluated: 4,
		mayRequest: false,
		...nothingCounted,
		practices: { toImprove: 0, held: 0, notApplicable: 4, undecided: 0 },
	},
	{
		reviewId: "00000000-0000-0000-0000-0000000002a4",
		reviewedAt: daysBefore(12),
		reviewedWork: pullRequest(890),
		status: "FAILED",
		triggerMode: "AUTO",
		mayRequest: true,
		practices: { toImprove: 0, held: 1, notApplicable: 0, undecided: 0 },
		feedbackDelivered: 1,
		slippedPractices: [],
	},
	{
		reviewId: "00000000-0000-0000-0000-0000000002a5",
		reviewedAt: daysBefore(40),
		reviewedWork: pullRequest(871),
		status: "COMPLETED",
		triggerMode: "AUTO",
		practicesEvaluated: 9,
		practices: { toImprove: 0, held: 3, notApplicable: 6, undecided: 0 },
		feedbackDelivered: 0,
		slippedPractices: [],
		mayRequest: false,
	},
];

export const runningProfileReviewRun: ProfileReviewRun = {
	reviewId: "00000000-0000-0000-0000-0000000002a6",
	reviewedAt: daysBefore(0),
	reviewedWork: pullRequest(905),
	status: "IN_PROGRESS",
	triggerMode: "MANUAL",
	mayRequest: true,
	...nothingCounted,
};

/** What the open review observed about the reader: two observations, both on Thin controllers. */
export const openProfileRunObservations: ObservationDetail[] = [
	{ ...detailObservation, practiceSlug: "thin-controllers", practiceName: "Thin controllers" },
	{
		...detailObservation,
		id: "00000000-0000-0000-0000-0000000001aa",
		practiceSlug: "thin-controllers",
		practiceName: "Thin controllers",
		summary: "A dependency bump rode along with the behaviour change",
	},
];
