import type { ArtifactTrace, ObservationDetail, ProfileReviewRun } from "@/api/types.gen";

import { detailRun } from "./practice-detail-story-mock-data";
import { issue, pullRequest } from "./practice-profile-story-mock-data";
import { daysBefore } from "./story-clock";

/** No count yet: what a run still going reports before it has recorded anything. */
const nothingCounted = {
	observations: { strengths: 0, problems: 0, notApplicable: 0, undetermined: 0 },
	feedbackDelivered: 0,
	slippedPractices: [],
} satisfies Pick<ProfileReviewRun, "observations" | "feedbackDelivered" | "slippedPractices">;

/**
 * Five reviews of one developer's own work, newest first, across the states the list has to read:
 * the newest one a person asked for, a second run on the same pull request so a row can say which
 * run of its work it is, a run that found nothing to say, one that stopped before it finished with
 * an observation and a piece of feedback already recorded, and one still going.
 */
const newestRun: ProfileReviewRun = {
	reviewId: detailRun.reviewId,
	reviewedAt: daysBefore(2),
	reviewedWork: pullRequest(902),
	status: "COMPLETED",
	triggerMode: "MANUAL",
	lead: "This change moves the workspace model and documents why; one linked page did not follow it.",
	// The four assessments are the practices this run reached, so they add up to
	// `practicesEvaluated`; its activity below lists one more, because a practice the run never got
	// to is listed and not reached.
	practicesEvaluated: 4,
	practicesEligible: 5,
	durationSeconds: 41,
	observations: { strengths: 2, problems: 1, notApplicable: 1, undetermined: 0 },
	feedbackDelivered: 1,
	feedbackUrl: "https://github.com/acme/api/pull/902#issuecomment-2481902",
	slippedPractices: [
		{ practiceSlug: "reviewable-diff-size", practiceName: "Keep the diff reviewable" },
	],
	mayRequest: true,
};

export const profileReviewRuns: ProfileReviewRun[] = [
	newestRun,
	{
		reviewId: "00000000-0000-0000-0000-0000000002a2",
		reviewedAt: daysBefore(4),
		reviewedWork: pullRequest(902),
		status: "COMPLETED",
		triggerMode: "AUTO",
		lead: "The first pass on this pull request: the rename and the caching change arrived together.",
		practicesEvaluated: 9,
		practicesEligible: 9,
		durationSeconds: 38,
		observations: { strengths: 1, problems: 3, notApplicable: 5, undetermined: 0 },
		feedbackDelivered: 2,
		feedbackUrl: "https://github.com/acme/api/pull/902#issuecomment-2478114",
		// Three practices slipped, which is every one the wire carries: the row names two and counts
		// the rest.
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
		practicesEligible: 4,
		durationSeconds: 12,
		// Somebody else's issue this reader was observed on: they may read the run and not re-run it.
		mayRequest: false,
		...nothingCounted,
		// It reached every practice and none of them had a subject in this issue.
		observations: { strengths: 0, problems: 0, notApplicable: 4, undetermined: 0 },
	},
	{
		reviewId: "00000000-0000-0000-0000-0000000002a4",
		reviewedAt: daysBefore(12),
		reviewedWork: pullRequest(890),
		status: "FAILED",
		triggerMode: "AUTO",
		mayRequest: true,
		// The run timed out after one practice had written its observation and the feedback from it
		// had gone out: what stands is reported, and only the stop is said about the run itself.
		observations: { strengths: 1, problems: 0, notApplicable: 0, undetermined: 0 },
		feedbackDelivered: 1,
		// The comment its summary landed in carries a node id, which is no address: the row says how
		// much feedback reached the reader and links nowhere.
		slippedPractices: [],
	},
	{
		reviewId: "00000000-0000-0000-0000-0000000002a5",
		reviewedAt: daysBefore(40),
		reviewedWork: pullRequest(871),
		status: "COMPLETED",
		triggerMode: "AUTO",
		lead: "The tests arrived with the change.",
		practicesEvaluated: 9,
		practicesEligible: 9,
		durationSeconds: 55,
		observations: { strengths: 3, problems: 0, notApplicable: 6, undetermined: 0 },
		feedbackDelivered: 0,
		slippedPractices: [],
		mayRequest: false,
	},
];

/** The run the levels open: the newest one, whose two observations the run view groups. */
export const openProfileReviewRun: ProfileReviewRun = newestRun;

/** A review that is being run now, for the chip's and the list's running state. */
export const runningProfileReviewRun: ProfileReviewRun = {
	reviewId: "00000000-0000-0000-0000-0000000002a6",
	reviewedAt: daysBefore(0),
	reviewedWork: pullRequest(905),
	status: "IN_PROGRESS",
	triggerMode: "MANUAL",
	mayRequest: true,
	...nothingCounted,
};

/** What the open run observed about the reader, as the run level groups it by practice group. */
export const openProfileRunObservations: ObservationDetail[] = detailRun.observations;

/** The earlier of the two runs on pull request 902, as the fixtures above spell it. */
const OLDER_RUN = "00000000-0000-0000-0000-0000000002a2";

/** A run of somebody else's this reader was only observed on; every row shows the same run id. */
const OTHER_RUN = "00000000-0000-0000-0000-0000000002b9";

/**
 * This work's review activity as the run level reads it, asked for the newest run: the two
 * practices it reached and recorded an observation on, three it answered without saying anything,
 * and one row carrying another run's id, which the level drops because a run's table is that run's
 * answers.
 */
export const profileRunTrace: ArtifactTrace = {
	artifactKind: "scm.pull_request",
	artifactId: 902,
	number: 902,
	title: "Move the workspace model behind its own port",
	container: "acme/api",
	url: "https://github.com/acme/api/pull/902",
	signals: [
		{
			id: "sig-ready",
			signal: "scm.pull_request.ready",
			displayName: "Marked ready for review",
			revision: "27f4e88c",
			occurredAt: daysBefore(2),
			discoveredVia: "EVENT",
			state: "TRIGGERED",
			reviewId: detailRun.reviewId,
		},
		{
			id: "sig-sync",
			signal: "scm.pull_request.synchronized",
			displayName: "New commits pushed",
			revision: "9ab3c410",
			occurredAt: daysBefore(1),
			discoveredVia: "SYNC",
			state: "SUPPRESSED",
			stateReason: "COOLDOWN_ACTIVE",
		},
	],
	practices: [
		{
			practiceSlug: "scope-one-reviewable-change",
			practiceName: "Scope the change to one concern",
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			autonomy: "AUTOMATIC",
			outcome: "REVIEWED",
			explanation: "Reviewed on the commits that were ready at 10:15, and one point was raised.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			decidedAt: daysBefore(2),
			reviewId: detailRun.reviewId,
			observationCount: 2,
			deliveredCount: 1,
			withheldReasons: [],
		},
		{
			practiceSlug: "describe-what-and-why",
			practiceName: "Describe what changed and why",
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			autonomy: "HUMAN_APPROVAL",
			outcome: "REVIEWED",
			explanation: "Reviewed, and one measurement was recorded.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			decidedAt: daysBefore(2),
			reviewId: detailRun.reviewId,
			observationCount: 1,
			deliveredCount: 0,
			withheldReasons: ["PRACTICE_REQUIRES_APPROVAL"],
		},
		{
			practiceSlug: "small-changes",
			practiceName: "Small, reviewable changes",
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			autonomy: "AUTOMATIC",
			outcome: "SKIPPED",
			explanation:
				"Skipped because this pull request was reviewed 40 minutes ago and the workspace's cooldown is one hour.",
			watches: ["scm.pull_request.synchronized"],
			occasionedBy: "scm.pull_request.synchronized",
			occasionedById: "sig-sync",
			decidedAt: daysBefore(1),
			reviewId: detailRun.reviewId,
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
		{
			practiceSlug: "clear-ownership",
			practiceName: "Clear ownership",
			groupSlug: "owning-the-change",
			groupName: "Owning the change",
			autonomy: "AUTOMATIC",
			outcome: "NOT_REACHED",
			explanation: "The review ended before reaching this practice.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			reviewId: detailRun.reviewId,
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
		{
			practiceSlug: "migration-safety",
			practiceName: "Migration safety",
			groupSlug: "owning-the-change",
			groupName: "Owning the change",
			autonomy: "AUTOMATIC",
			outcome: "NOT_ASSESSABLE",
			explanation:
				"The diff for these commits could not be read, so this practice could not be judged either way.",
			watches: ["scm.pull_request.ready"],
			reviewId: detailRun.reviewId,
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
		{
			practiceSlug: "meaningful-commits",
			practiceName: "Meaningful commit history",
			groupSlug: "owning-the-change",
			groupName: "Owning the change",
			autonomy: "AUTOMATIC",
			outcome: "REVIEWED",
			explanation: "Reviewed by the earlier run on this pull request.",
			watches: ["scm.pull_request.synchronized"],
			reviewId: OTHER_RUN,
			observationCount: 1,
			deliveredCount: 0,
			withheldReasons: [],
		},
	],
};

/**
 * The same pull request's activity asked for the earlier of its two runs: that run's own practices,
 * with what it made of them. Every entry names that run, which is what makes the older run's table
 * its own rather than empty.
 */
export const profileOlderRunTrace: ArtifactTrace = {
	...profileRunTrace,
	practices: [
		{
			practiceSlug: "meaningful-commits",
			practiceName: "Meaningful commit history",
			groupSlug: "owning-the-change",
			groupName: "Owning the change",
			autonomy: "AUTOMATIC",
			outcome: "REVIEWED",
			explanation: "Assessed on this artifact.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			decidedAt: daysBefore(4),
			reviewId: OLDER_RUN,
			observationCount: 1,
			deliveredCount: 1,
			withheldReasons: [],
		},
		{
			practiceSlug: "scope-one-reviewable-change",
			practiceName: "Scope the change to one concern",
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			autonomy: "AUTOMATIC",
			outcome: "REVIEWED",
			explanation: "Assessed on this artifact; nothing to report.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			decidedAt: daysBefore(4),
			reviewId: OLDER_RUN,
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
		{
			practiceSlug: "migration-safety",
			practiceName: "Migration safety",
			groupSlug: "owning-the-change",
			groupName: "Owning the change",
			autonomy: "AUTOMATIC",
			outcome: "SKIPPED",
			explanation: "This practice does not apply here: the change touches no migration.",
			watches: ["scm.pull_request.ready"],
			occasionedBy: "scm.pull_request.ready",
			occasionedById: "sig-ready",
			decidedAt: daysBefore(4),
			reviewId: OLDER_RUN,
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
	],
};
