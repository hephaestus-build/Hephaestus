import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import {
	getPracticeGuidanceOptions,
	listOwnGroupReviewsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeStanding, ReviewedPractice } from "@/api/types.gen";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import type { PracticeGuidanceState } from "@/components/practice-guidance/PracticeIntro";
import {
	reviewRangeStart,
	type ReviewTimeframe,
} from "@/components/practice-profile/practice-profile-search";
import {
	EMPTY_REVIEW_FEED,
	type FeedbackResponseControls,
	type ReviewFeedState,
	reviewFeedState,
} from "@/components/profile/reviews";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";

export const REVIEW_PAGE_SIZE = 10;

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
	reviewedPractices: ReviewedPractice[];
	/** How far back the open practice's observations reach; absent for every one. */
	since?: ReviewTimeframe;
}

export interface PracticeGroupDetail {
	/**
	 * The open group's practices, with the catalog's words on each and where the developer stands.
	 */
	practices: PracticeStanding[];
	/**
	 * The open practice, whether or not a group level is open under it. Absent for a practice with
	 * no standing, such as one that offers guidance without automated review.
	 */
	practice?: PracticeStanding;
	/** The open practice's name and words when it has no standing to carry them. */
	described?: ReviewedPractice;
	feed: ReviewFeedState;
	/** The open practice's guidance; loading while no practice is open. */
	guidance: PracticeGuidanceState;
	/** Absent when this reader may not respond, which leaves no response controls. */
	responses?: FeedbackResponseControls;
}

export function usePracticeGroupDetail({
	workspaceSlug,
	groupSlug,
	practiceSlug,
	practiceStandings,
	reviewedPractices,
	since,
}: PracticeGroupDetailRequest): PracticeGroupDetail {
	const now = useNow();
	const practice =
		practiceSlug === undefined
			? undefined
			: practiceStandings.find((candidate) => candidate.slug === practiceSlug);
	const feedGroupSlug = groupSlug ?? practice?.groupSlug ?? undefined;
	const practiceOpen = practiceSlug !== undefined && feedGroupSlug !== undefined;

	const activityOptions = listOwnGroupReviewsInfiniteOptions({
		path: { workspaceSlug, groupSlug: feedGroupSlug ?? "" },
		query: {
			size: REVIEW_PAGE_SIZE,
			// The server narrows the pages, so the window holds across every page, not only the loaded.
			since: since === undefined ? undefined : reviewRangeStart(now, since),
		},
	});
	const activityQuery = useInfiniteQuery({
		...activityOptions,
		...springPageParams,
		enabled: practiceOpen,
	});
	useQueryReadRefusal(activityOptions.queryKey, activityQuery.isError, activityQuery.error);
	// Its own request, so the words the standing carries show while the picture is on its way. It
	// keeps the app's staleTime: the server answers a refetch of an unchanged guidance with a 304.
	const guidanceQuery = useQuery({
		...getPracticeGuidanceOptions({
			path: { workspaceSlug, practiceSlug: practiceSlug ?? "" },
		}),
		enabled: practiceSlug !== undefined,
	});
	const responses = useFeedbackResponseWrite(workspaceSlug);

	const practices = practiceStandings.filter((candidate) => candidate.groupSlug === groupSlug);
	// With the query idle there is nothing in flight to resolve a skeleton, so the feed is settled
	// and empty rather than pending: the drawer is closed, or the practice belongs to no group.
	const feed = practiceOpen ? reviewFeedState(activityQuery) : EMPTY_REVIEW_FEED;
	return {
		practices,
		practice,
		described:
			practice === undefined && practiceSlug !== undefined
				? reviewedPractices.find((candidate) => candidate.slug === practiceSlug)
				: undefined,
		feed,
		guidance: panelState(guidanceQuery, (data) => ({ status: "ready" as const, ...data })),
		responses,
	};
}
