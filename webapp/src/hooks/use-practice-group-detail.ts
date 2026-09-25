import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	deleteFeedbackResponseMutation,
	listPracticeGroupReviewRunsInfiniteOptions,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ObservationDetail, PracticeStanding } from "@/api/types.gen";
import {
	EMPTY_REVIEW_RUN_FEED,
	type FeedbackResponse,
	isEmptyFeedbackResponse,
	nextReviewRunPage,
	type ReviewRunFeedState,
	reviewRunFeedState,
} from "@/components/profile/review-runs";
import {
	FEEDBACK_RESPONSE_WRITE_KEY,
	invalidateFeedbackResponses,
} from "@/hooks/use-in-app-feedback";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

/** Review runs per page of the feed; also the skeleton's row count while the first page loads. */
export const REVIEW_RUN_PAGE_SIZE = 10;

export interface PracticeGroupDetailRequest {
	workspaceSlug: string;
	/** The open group level. Undefined while the drawer is closed, which keeps every query idle. */
	groupSlug?: string;
	/** The practice level open over the group, if any; the feed belongs to it. */
	practiceSlug?: string;
	/** The developer's practice standings; the ones in the open group are the group's practices. */
	practiceStandings: PracticeStanding[];
}

export interface PracticeGroupDetail {
	/**
	 * The open group's practices, with the catalog's words on each and where the developer stands.
	 */
	practices: PracticeStanding[];
	/** The open practice, once the open group is known to review it. */
	practice?: PracticeStanding;
	feed: ReviewRunFeedState;
	respond: (observation: ObservationDetail, response: FeedbackResponse) => void;
	pendingFeedbackId?: string;
}

/**
 * The open detail levels' data: the group level's practices, read off the standings the page
 * already holds, and the practice level's review-run feed and feedback-response mutations. The
 * feed carries every observation in full, so opening one loads nothing. The query is gated on
 * its level actually being open, so a closed drawer costs nothing and the group level never
 * fetches the feed it does not show.
 *
 * Only the practice profile's drawer reads this; it is a file of its own because the drawer is
 * one query and two mutations, and the route stays readable without them.
 */
export function usePracticeGroupDetail({
	workspaceSlug,
	groupSlug,
	practiceSlug,
	practiceStandings,
}: PracticeGroupDetailRequest): PracticeGroupDetail {
	const queryClient = useQueryClient();
	const groupOpen = groupSlug !== undefined;
	const practiceOpen = groupOpen && practiceSlug !== undefined;

	const activityQuery = useInfiniteQuery({
		...listPracticeGroupReviewRunsInfiniteOptions({
			path: { workspaceSlug, groupSlug: groupSlug ?? "" },
			query: { size: REVIEW_RUN_PAGE_SIZE, practiceSlug },
		}),
		initialPageParam: 0,
		getNextPageParam: nextReviewRunPage,
		enabled: practiceOpen,
	});
	const written = () => invalidateFeedbackResponses(queryClient, workspaceSlug, groupSlug);
	const replaceResponseMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, replaceFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not save your feedback response")),
	});
	const deleteResponseMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, deleteFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not withdraw your feedback response")),
	});

	const pendingFeedbackIds = usePendingMutationIds(FEEDBACK_RESPONSE_WRITE_KEY, (variables) =>
		pathString(variables, "feedbackId"),
	);

	const practices = practiceStandings.filter((practice) => practice.groupSlug === groupSlug);
	// With the query idle there is nothing in flight to resolve a skeleton, so the feed is settled
	// and empty rather than pending: the drawer is closed, or the practice level opened without a
	// group whose runs could be read, and that level says so instead of showing a feed.
	const feed: ReviewRunFeedState = practiceOpen
		? reviewRunFeedState(activityQuery)
		: EMPTY_REVIEW_RUN_FEED;
	return {
		practices,
		practice: practiceOpen
			? practices.find((practice) => practice.slug === practiceSlug)
			: undefined,
		feed,
		respond: (observation, response) => {
			const feedbackId = observation.feedbackResponse?.feedbackId;
			if (!hasText(feedbackId)) {
				return;
			}
			if (isEmptyFeedbackResponse(response)) {
				deleteResponseMutation.mutate({ path: { workspaceSlug, feedbackId } });
				return;
			}
			replaceResponseMutation.mutate({
				path: { workspaceSlug, feedbackId },
				body: response,
			});
		},
		// One row's buttons wait at a time: a response is written by a press on one observation.
		pendingFeedbackId: [...pendingFeedbackIds][0],
	};
}
