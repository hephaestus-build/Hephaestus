import { HttpResponse, http } from "msw";

import type { ReviewOverview, ReviewQueue } from "@/api/types.gen";
import {
	feedbackCounts,
	idleReviewQueue,
	practiceReviewOverview,
	practiceReviewTiming,
} from "@/components/admin/practice-reviews/fixtures";
import { reviewRangeStart } from "@/components/admin/practice-reviews/review-range";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";

export const OVERVIEW = "/w/acme/admin/practices/reviews/overview";

/** The range: nothing queued or running, so nothing polls. */
export const rangeOverview: ReviewOverview = {
	...practiceReviewOverview,
	reviews: { complete: 80, partial: 0, failed: 5, cancelled: 0, running: 0, queued: 0 },
	feedback: {
		...feedbackCounts([]),
		delivered: 60,
		ready: 5,
		failed: 2,
		withheld: 30,
		rejected: 3,
		replaced: 20,
	},
};

/** Fourteen waiting, the oldest for 42 minutes. */
function longWaitQueue(): ReviewQueue {
	return {
		...idleReviewQueue,
		waiting: 14,
		oldestWaitingSince: new Date(idleReviewQueue.asOf.getTime() - 42 * 60_000),
	};
}

/**
 * `week`, given, answers the read of the fixed week, so a test can tell it from the range's read;
 * without it, every read gets the range.
 */
export function serveAttention(week?: ReviewOverview) {
	const overviewReads: string[] = [];
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/reviews/overview", ({ request }) => {
			const from = new URL(request.url).searchParams.get("from");
			overviewReads.push(from ?? "");
			const weekStart = reviewRangeStart(
				// oxlint-disable-next-line no-restricted-properties -- the route reads its week against the wall clock too.
				Date.now(),
				"7d",
			).getTime();
			const isWeek = from !== null && Date.parse(from) === weekStart;
			return HttpResponse.json(isWeek && week !== undefined ? week : rangeOverview);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/timing", () =>
			HttpResponse.json({
				...practiceReviewTiming,
				waitingLong: 9,
				feedbackWithheldAsMerged: 21,
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/queue", () =>
			HttpResponse.json(longWaitQueue()),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", () =>
			HttpResponse.json({
				content: [],
				size: 1,
				page: 0,
				totalElements: 0,
				totalPages: 1,
			}),
		),
		...reviewHandlers(),
	);
	return { overviewReads };
}
