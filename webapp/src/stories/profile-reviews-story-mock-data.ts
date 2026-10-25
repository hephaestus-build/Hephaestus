import type {
	ItemRef,
	ProfileReview,
	ProfileReviewDetail,
	ResultDetail,
	ReviewRef,
} from "@/api/types.gen";

import {
	detailResult,
	listedResult,
	metWithExample,
	ownFeedback,
	reviewRef,
	searchedAndFoundNothing,
	undeterminedResult,
} from "./practice-detail-story-mock-data";
import { issue, pullRequest } from "./practice-profile-story-mock-data";
import { daysBefore } from "./story-clock";

const TRACED_REVIEW = "11111111-1111-1111-1111-111111111111";

/** GitLab, since only a GitLab note can be linked to from a review's row. */
const tracedMergeRequest: ItemRef = {
	...pullRequest(1423),
	provider: "GITLAB",
	label: "!1423",
	url: "https://gitlab.example.com/acme/api/-/merge_requests/1423",
};

function onReview(
	result: ResultDetail,
	review: ReviewRef,
	practice: Pick<ResultDetail, "id" | "practiceSlug" | "practiceName">,
	fields: Partial<ResultDetail> = {},
) {
	return listedResult({
		...result,
		reviewId: review.id,
		item: review.item,
		recordedAt: review.createdAt,
		...practice,
		...fields,
	});
}

const openReview = reviewRef(TRACED_REVIEW, tracedMergeRequest, daysBefore(2), {
	origin: "REQUEST",
});
const secondReview = reviewRef(
	"00000000-0000-4000-8000-0000000002a2",
	tracedMergeRequest,
	daysBefore(4),
);
const issueReview = reviewRef("00000000-0000-4000-8000-0000000002a3", issue(31), daysBefore(9));
const olderReview = reviewRef(
	"00000000-0000-4000-8000-0000000002a5",
	pullRequest(871),
	daysBefore(40),
);

export const openProfileReview: ProfileReview = {
	review: openReview,
	positionOnWork: 2,
	totalOnWork: 2,
	results: [
		onReview(detailResult, openReview, {
			id: "00000000-0000-4000-8000-000000000301",
			practiceSlug: "thin-controllers",
			practiceName: "Thin controllers",
		}),
		onReview(metWithExample, openReview, {
			id: "00000000-0000-4000-8000-000000000302",
			practiceSlug: "product-language",
			practiceName: "Product language",
		}),
		onReview(undeterminedResult, openReview, {
			id: "00000000-0000-4000-8000-000000000303",
			practiceSlug: "migration-safety",
			practiceName: "Migration safety",
		}),
	],
	feedback: [
		ownFeedback("00000000-0000-4000-8000-0000000002f1", {
			copies: [
				{
					id: "00000000-0000-4000-8000-0000000002f2",
					kind: "SUMMARY",
					externalUrl: "https://gitlab.example.com/acme/api/-/merge_requests/1423#note_2481902",
					postedAt: daysBefore(2),
				},
			],
		}),
	],
};

export const openProfileReviewDetail: ProfileReviewDetail = {
	...openProfileReview,
	practiceOutcomes: { met: 1, notMet: 1, doesNotApply: 0, couldNotDetermine: 1 },
	trace: {
		item: tracedMergeRequest,
		review: openReview,
		events: [
			{ name: "Review requested", displayName: "Activity recorded", occurredAt: daysBefore(2) },
		],
		results: openProfileReview.results,
	},
};

export const profileReviews: ProfileReview[] = [
	openProfileReview,
	{
		review: secondReview,
		positionOnWork: 1,
		totalOnWork: 2,
		results: [
			onReview(detailResult, secondReview, {
				id: "00000000-0000-4000-8000-000000000304",
				practiceSlug: "explain-changes",
				practiceName: "Explain each change",
			}),
			onReview(searchedAndFoundNothing, secondReview, {
				id: "00000000-0000-4000-8000-000000000305",
				practiceSlug: "reviewable-diff-size",
				practiceName: "Keep the diff reviewable",
			}),
		],
		feedback: [
			ownFeedback("00000000-0000-4000-8000-0000000002e1", { deliveredAt: daysBefore(4) }),
			ownFeedback("00000000-0000-4000-8000-0000000002e2", {
				destination: "PRACTICE_PAGE",
				deliveredAt: daysBefore(3),
			}),
		],
	},
	{
		review: issueReview,
		positionOnWork: 1,
		totalOnWork: 1,
		results: [
			onReview(
				metWithExample,
				issueReview,
				{
					id: "00000000-0000-4000-8000-000000000306",
					practiceSlug: "ready-and-traceable-handoff",
					practiceName: "Ready and traceable handoff",
				},
				{ outcome: "DOES_NOT_APPLY", summary: null },
			),
		],
		feedback: [],
	},
	{
		review: reviewRef("00000000-0000-4000-8000-0000000002a4", pullRequest(890), daysBefore(12), {
			status: "FAILED",
		}),
		positionOnWork: 1,
		totalOnWork: 1,
		results: [],
		feedback: [],
	},
	{
		review: olderReview,
		positionOnWork: 1,
		totalOnWork: 1,
		results: [
			onReview(metWithExample, olderReview, {
				id: "00000000-0000-4000-8000-000000000307",
				practiceSlug: "product-language",
				practiceName: "Product language",
			}),
		],
		feedback: [],
	},
];

export const runningProfileReview: ProfileReview = {
	review: reviewRef("00000000-0000-4000-8000-0000000002a6", pullRequest(905), daysBefore(0), {
		origin: "REQUEST",
		status: "RUNNING",
		completedAt: null,
	}),
	positionOnWork: 1,
	totalOnWork: 1,
	results: [],
	feedback: [],
};
