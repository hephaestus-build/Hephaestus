import type { ReviewOverview } from "@/api/types.gen";

import {
	FINISHED_REVIEW_STATUSES,
	finishedCount,
	type OutcomeScope,
	type ReviewListTarget,
	scopeSearch,
} from "./review-outcomes";
import type { FeedbackSearch } from "./review-search";
import type { ReviewCountState, ReviewOverviewState } from "./review-states";
import type { AttentionGroup } from "./ReviewAttention";

/** A count the overview already holds, in the state of the overview itself. */
export function overviewCount(
	overview: ReviewOverviewState,
	count: (overview: ReviewOverview) => number,
): ReviewCountState {
	return overview.status === "ready"
		? { status: "ready", total: count(overview.overview), stale: overview.stale }
		: overview;
}

export function deliveryFailures(
	scope: OutcomeScope,
	overview: ReviewOverviewState,
): AttentionGroup<ReviewCountState> {
	const search: Partial<FeedbackSearch> = {
		...scopeSearch(scope),
		state: ["FAILED"],
	};
	return {
		state: overviewCount(overview, ({ feedback }) => feedback.failed),
		list: { list: "feedback", search },
		of:
			overview.status === "ready"
				? overview.overview.feedback.delivered +
					overview.overview.feedback.unconfirmed +
					overview.overview.feedback.failed
				: undefined,
	};
}

export function finishedInRange(
	overview: ReviewOverviewState,
	scope: OutcomeScope,
): { state: ReviewCountState; list: ReviewListTarget } {
	const list: ReviewListTarget = {
		list: "reviews",
		search: { ...scopeSearch(scope), status: [...FINISHED_REVIEW_STATUSES] },
	};
	if (overview.status !== "ready") {
		return { state: overview, list };
	}
	const { reviews } = overview.overview;
	return {
		state: {
			status: "ready",
			total: finishedCount(reviews),
			stale: overview.stale,
		},
		list,
	};
}
