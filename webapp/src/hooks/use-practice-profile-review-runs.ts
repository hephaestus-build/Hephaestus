import { skipToken, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";

import {
	getArtifactTraceOptions,
	getPracticeProfileReviewRunOptions,
	listPracticeProfileReviewRunsInfiniteOptions,
	listPracticeProfileReviewRunsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { CreateReviewRequest, ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { ACTIVE_REVIEW_POLL_MS } from "@/components/admin/practice-reviews/review-search";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import {
	PROFILE_REVIEWS_PAGE_SIZE,
	reviewRangeStart,
	type ReviewTimeframe,
} from "@/components/practice-profile/practice-profile-search";
import type { ProfileReviewDetailState } from "@/components/practice-profile/ProfileReviewLevel";
import { runPositionsOnWork } from "@/components/practice-profile/review-run-groups";
import {
	EMPTY_REVIEW_RUN_FEED,
	type ReviewRunFeedState,
	reviewRunFeedState,
} from "@/components/profile/review-runs";
import { PRACTICE_PROFILE_POLL_MS } from "@/hooks/use-practice-profile-overview";
import { useRequestPracticeReview } from "@/hooks/use-request-practice-review";
import { sameReviewedWork } from "@/lib/artifact-kinds";
import { problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { QUERY_RETRIES, sessionRetriesQueries } from "@/runtime/tanstack-query/query-defaults";
import { slicePageParams } from "@/runtime/tanstack-query/spring-page";

interface ProfileReviewRunsRequest {
	workspaceSlug: string;
	/** True while a level that shows the list is open; a closed drawer reads nothing. */
	listOpen: boolean;
	/** The open review level's review id, if any. */
	reviewId?: string;
	kind?: string;
	since?: ReviewTimeframe;
}

/**
 * The two review levels' data: the reviews that recorded something about the reader's own work, and
 * the one open over it with this work's review activity, the only read that names the practices
 * that stayed quiet. The work is taken from the list's row when it is loaded, so the activity does
 * not wait for the review.
 */
export function usePracticeProfileReviewRuns({
	workspaceSlug,
	listOpen,
	reviewId,
	kind,
	since,
}: ProfileReviewRunsRequest) {
	const now = useNow();
	const runsQuery = useInfiniteQuery({
		...listPracticeProfileReviewRunsInfiniteOptions({
			path: { workspaceSlug },
			query: {
				size: PROFILE_REVIEWS_PAGE_SIZE,
				kind,
				since: since === undefined ? undefined : reviewRangeStart(now, since),
			},
		}),
		...slicePageParams,
		enabled: listOpen,
		// A review is listed once it records something about the reader, which no ask here announces.
		refetchInterval: PRACTICE_PROFILE_POLL_MS,
	});
	const runOptions = getPracticeProfileReviewRunOptions({
		path: { workspaceSlug, reviewId: reviewId ?? "" },
	});
	const runQuery = useQuery({
		...runOptions,
		queryFn: reviewId === undefined ? skipToken : runOptions.queryFn,
		// A 404 is not the reader's review, or gone: asking again answers the same.
		retry: (failureCount, error) =>
			sessionRetriesQueries() && problemStatusOf(error) !== 404 && failureCount < QUERY_RETRIES,
		refetchInterval: (query) =>
			query.state.data?.run.status === "IN_PROGRESS" ? ACTIVE_REVIEW_POLL_MS : false,
	});
	const running = runQuery.data?.run.status === "IN_PROGRESS";

	const list: ReviewRunFeedState<ProfileReviewRun> = listOpen
		? reviewRunFeedState(runsQuery)
		: EMPTY_REVIEW_RUN_FEED;
	const work =
		(list.status === "ready"
			? list.runs.find((run) => run.reviewId === reviewId)?.reviewedWork
			: undefined) ?? runQuery.data?.run.reviewedWork;
	const traceOptions = getArtifactTraceOptions({
		path: {
			workspaceSlug,
			artifactKind: work?.kind ?? "",
			artifactId: Number(work?.id ?? 0),
		},
		// This review's answers, not the newest review's of the same work.
		query: { reviewId },
	});
	const traceQuery = useQuery({
		...traceOptions,
		queryFn: work === undefined ? skipToken : traceOptions.queryFn,
		refetchInterval: running ? ACTIVE_REVIEW_POLL_MS : false,
	});
	// The activity polls only while the review runs and the list only every minute, so either can
	// predate the review's final answers; the review stopping is what asks both once more.
	const queryClient = useQueryClient();
	const lastSeen = useRef({ reviewId, running });
	useEffect(() => {
		const before = lastSeen.current;
		lastSeen.current = { reviewId, running };
		if (before.reviewId === reviewId && before.running && !running) {
			void queryClient.invalidateQueries({ queryKey: traceOptions.queryKey });
			void queryClient.invalidateQueries({
				queryKey: listPracticeProfileReviewRunsInfiniteQueryKey({ path: { workspaceSlug } }),
			});
		}
	});

	// The open review's head explains a refusal about its own work; a row anywhere else gets a toast.
	const aboutOpenWork = (target: Pick<ReviewedWorkRef, "kind" | "id">) =>
		reviewId !== undefined && work !== undefined && sameReviewedWork(target, work);
	const review = useRequestPracticeReview(workspaceSlug, {
		showsInline: (asked) => aboutOpenWork(refOf(asked)),
	});
	// A refusal is said on the review it was asked from and on no other; leaving the level forgets
	// it, so it never comes back on a later visit.
	const [visited, setVisited] = useState(reviewId);
	if (visited !== reviewId) {
		setVisited(reviewId);
		review.forgetRefusal();
	}
	const onReviewNow = (target: ReviewedWorkRef) =>
		review.ask({ artifactKind: target.kind, artifactId: Number(target.id) });

	const open: ProfileReviewDetailState = panelState(runQuery, (detail) => ({
		status: "ready" as const,
		run: detail.run,
		observations: detail.observations,
		activity: panelState(traceQuery, (trace) => ({ status: "ready" as const, trace })),
	}));

	return {
		list,
		// Counted only over every review there is; a page or a filter would count short.
		positions:
			list.status === "ready" && !list.hasMore && !hasText(kind) && since === undefined
				? runPositionsOnWork(list.runs)
				: undefined,
		open,
		onReviewNow,
		requesting: review.asking && refOf(review.asking),
		refusal: review.refusal?.outcome,
	};
}

/** An ask's work as the review list names it. */
function refOf(asked: CreateReviewRequest): Pick<ReviewedWorkRef, "kind" | "id"> {
	return { kind: asked.artifactKind, id: String(asked.artifactId) };
}
