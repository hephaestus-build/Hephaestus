import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	getPracticeGuidanceOptions,
	listPracticeGroupReviewRunsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeStanding } from "@/api/types.gen";
import { panelState } from "@/components/common/panel-state";
import type { PracticeGuidanceState } from "@/components/practice-guidance/PracticeIntro";
import {
	EMPTY_REVIEW_RUN_FEED,
	type ReviewRunFeedState,
	reviewRunFeedState,
} from "@/components/profile/review-runs";
import {
	type FeedbackResponseWrite,
	useFeedbackResponseWrite,
} from "@/hooks/use-feedback-response-write";
import { slicePageParams } from "@/runtime/tanstack-query/spring-page";

/** Review runs per page of the feed; also the skeleton's row count while the first page loads. */
export const REVIEW_RUN_PAGE_SIZE = 10;

export interface PracticeGroupDetailRequest {
	workspaceSlug: string;
	/** The open group level. Undefined while the drawer is closed, which keeps every query idle. */
	groupSlug?: string;
	/** The open practice level, if any; the feed belongs to it. */
	practiceSlug?: string;
	/**
	 * The developer's practice standings: the ones in the open group are the group's practices, and
	 * the open practice is found among all of them.
	 */
	practiceStandings: PracticeStanding[];
}

export interface PracticeGroupDetail {
	/**
	 * The open group's practices, with the catalog's words on each and where the developer stands.
	 */
	practices: PracticeStanding[];
	/** The open practice, whether or not a group level is open under it. */
	practice?: PracticeStanding;
	feed: ReviewRunFeedState;
	/** The open practice's picture and "Read more" guide; loading while no practice is open. */
	guidance: PracticeGuidanceState;
	/** Absent when this reader may not respond, which leaves no response controls. */
	respond?: FeedbackResponseWrite["respond"];
	pendingResponses: FeedbackResponseWrite["pendingResponses"];
}

/**
 * The open detail levels' data: the group level's practices, read off the standings the page
 * already holds, and the practice level's picture and guide, its review-run feed and the responses
 * written on it. The feed carries every observation in full, so opening one loads nothing. Each
 * query is gated on its level actually being open, so a closed drawer costs nothing and the group
 * level never fetches what it does not show.
 */
export function usePracticeGroupDetail({
	workspaceSlug,
	groupSlug,
	practiceSlug,
	practiceStandings,
}: PracticeGroupDetailRequest): PracticeGroupDetail {
	const practice =
		practiceSlug === undefined
			? undefined
			: practiceStandings.find((candidate) => candidate.slug === practiceSlug);
	// Runs are listed per group, so a practice opened on its own reads them through its own group.
	const feedGroupSlug = groupSlug ?? practice?.groupSlug;
	const practiceOpen = practiceSlug !== undefined && feedGroupSlug !== undefined;

	const activityQuery = useInfiniteQuery({
		...listPracticeGroupReviewRunsInfiniteOptions({
			path: { workspaceSlug, groupSlug: feedGroupSlug ?? "" },
			query: { size: REVIEW_RUN_PAGE_SIZE, practiceSlug },
		}),
		...slicePageParams,
		enabled: practiceOpen,
	});
	// Its own request, so the words the standing carries show while the picture is on its way. It
	// keeps the app's staleTime: the server answers a refetch of an unchanged guidance with a 304.
	const guidanceQuery = useQuery({
		...getPracticeGuidanceOptions({
			path: { workspaceSlug, practiceSlug: practiceSlug ?? "" },
		}),
		enabled: practiceSlug !== undefined,
	});
	const { respond, pendingResponses } = useFeedbackResponseWrite(
		workspaceSlug,
		() => feedGroupSlug,
	);

	const practices = practiceStandings.filter((candidate) => candidate.groupSlug === groupSlug);
	// With the query idle there is nothing in flight to resolve a skeleton, so the feed is settled
	// and empty rather than pending: the drawer is closed, or the practice belongs to no group.
	const feed: ReviewRunFeedState = practiceOpen
		? reviewRunFeedState(activityQuery)
		: EMPTY_REVIEW_RUN_FEED;
	return {
		practices,
		practice,
		feed,
		guidance: panelState(guidanceQuery, (data) => ({
			status: "ready" as const,
			visual: data.visual,
			guide: data.guide,
		})),
		respond,
		pendingResponses,
	};
}
