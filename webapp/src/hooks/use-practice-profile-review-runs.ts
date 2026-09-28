import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getArtifactTraceOptions,
	getPracticeProfileOverviewQueryKey,
	getPracticeProfileReviewRunOptions,
	listPracticeProfileReviewRunsInfiniteOptions,
	requestPracticeReviewMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	GetArtifactTraceResponse,
	ObservationDetail,
	ProfileReviewRun,
	ProfileReviewRunDetail,
	ReviewRequestOutcome,
	ReviewedWorkRef,
} from "@/api/types.gen";
import { type LoadState, type PanelState, queryLoadState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import {
	type RunTimeframe,
	timeframeSince,
} from "@/components/practice-profile/review-run-timeframes";
import {
	EMPTY_REVIEW_RUN_FEED,
	nextReviewRunPage,
	type ReviewRunFeedState,
	reviewRunFeedState,
} from "@/components/profile/review-runs";
import { sameReviewedWork } from "@/lib/artifact-kinds";
import { problemDetailOf } from "@/lib/problem-detail";

/** Runs per page of the list; also the skeleton's row count while the first page loads. */
export const PROFILE_REVIEW_RUN_PAGE_SIZE = 10;

/** The list of the reader's own review runs, with its paging while earlier ones exist. */
export type ProfileReviewRunsState = ReviewRunFeedState<ProfileReviewRun>;

/**
 * One open run: the row the list already holds, the observations it made about the reader, and this
 * work's whole review activity, which is what names every practice the run could have reached,
 * including the ones that stayed quiet. The activity is a second read that lands after the run, so
 * it carries its own state: the level draws the run as soon as it has it and waits for the rest.
 */
export type ProfileReviewRunState = PanelState<{
	run?: ProfileReviewRun;
	observations: ObservationDetail[];
	trace?: GetArtifactTraceResponse;
	traceState: LoadState;
}>;

/** Asking for a review of one piece of work, and what came of the last ask. */
export interface ProfileReviewRequest {
	onReviewNow: (work: ReviewedWorkRef) => void;
	/** The work the ask in flight is about, so only its own control says so. */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	/** The refused outcome of the last ask, while the open run is on the work it was about. */
	refusal?: ReviewRequestOutcome;
}

export interface ProfileReviewRuns {
	list: ProfileReviewRunsState;
	open: ProfileReviewRunState;
	request: ProfileReviewRequest;
}

export interface ProfileReviewRunsRequest {
	workspaceSlug: string;
	/** True while a level that shows the list is open; a closed drawer costs nothing. */
	listOpen: boolean;
	/** The open run level's review id, if any. */
	reviewId?: string;
	/** The kind of work the list is narrowed to, from the level's own search param. */
	kind?: string;
	/**
	 * How far back the list reaches, as the level's own search param spells it; the hook turns it
	 * into the moment the wire takes. Undefined is every run there is.
	 */
	since?: RunTimeframe;
}

/**
 * The two run levels' data: every review of the reader's own work, paged and narrowed to one kind
 * of work when they ask for one, and the one run open over it. Both are gated on their level
 * actually being open, and the run level reads its own row out of the loaded pages when the list is
 * there — a run opened from a shared link has none, and the detail's own copy of the row answers
 * for it.
 *
 * The run level's practice table needs the answer of every practice, not just the ones that
 * observed something, so the open run also reads this work's review activity — the one endpoint
 * that lists the quiet practices with their recorded reason, narrowed to the open run so that what
 * it lists is what that run decided rather than what the newest review of the work did.
 */
export function usePracticeProfileReviewRuns({
	workspaceSlug,
	listOpen,
	reviewId,
	kind,
	since,
}: ProfileReviewRunsRequest): ProfileReviewRuns {
	const queryClient = useQueryClient();
	// Counted back from midnight of the reader's own day, so the bound the query key carries is the
	// same all day and the shared clock's tick does not refetch the list under them.
	const sinceAt = timeframeSince(since, useNow());
	const listOptions = listPracticeProfileReviewRunsInfiniteOptions({
		path: { workspaceSlug },
		query: { size: PROFILE_REVIEW_RUN_PAGE_SIZE, kind, since: sinceAt },
	});
	const runsQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: 0,
		getNextPageParam: nextReviewRunPage,
		enabled: listOpen,
	});
	const runQuery = useQuery({
		...getPracticeProfileReviewRunOptions({
			path: { workspaceSlug, reviewId: reviewId ?? "" },
		}),
		enabled: reviewId !== undefined,
	});
	const work = runQuery.data?.run.reviewedWork;
	// Asked for this review alone: the answers a work carries are per review, so a second review of
	// the same pull request would otherwise speak for this one and leave the older run with none.
	const traceOptions = getArtifactTraceOptions({
		path: {
			workspaceSlug,
			artifactKind: work?.kind ?? "",
			artifactId: Number(work?.id ?? 0),
		},
		query: { reviewId },
	});
	const traceQuery = useQuery({ ...traceOptions, enabled: work !== undefined });
	const requestReview = useMutation({
		...requestPracticeReviewMutation(),
		onSuccess: (outcome) => {
			if (outcome.status !== "SUBMITTED") {
				return;
			}
			// The run this level shows is unchanged by a new one; what moves is the list it sits in,
			// which now has a run at its head, this work's review activity, which has a new
			// occurrence on it, and the overview's chip.
			void queryClient.invalidateQueries({ queryKey: listOptions.queryKey });
			void queryClient.invalidateQueries({ queryKey: traceOptions.queryKey });
			// And the overview under it, whose chip names the latest run: the run just asked for is
			// about to be it.
			void queryClient.invalidateQueries({
				queryKey: getPracticeProfileOverviewQueryKey({ path: { workspaceSlug } }),
			});
			toast.success("Review started");
		},
		onError: (error) =>
			toast.error("Couldn't ask for a review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});

	const askFor = (target: ReviewedWorkRef) => {
		requestReview.mutate({
			path: { workspaceSlug },
			body: { artifactKind: target.kind, artifactId: Number(target.id) },
		});
	};
	// The work the last ask was about, read off the mutation's own variables: the wire takes the id
	// as a number and the reference carries it as a string, so it is spelled back the way the
	// reference spells it.
	const asked = requestReview.variables?.body;
	const askedWork = asked && { kind: asked.artifactKind, id: String(asked.artifactId) };

	return {
		// With the level closed nothing is in flight to resolve a skeleton, so it reports a settled
		// empty list rather than a pending one.
		list: listOpen ? reviewRunFeedState(runsQuery) : EMPTY_REVIEW_RUN_FEED,
		open: openOf(reviewId, runQuery, traceQuery),
		request: {
			onReviewNow: askFor,
			requesting: requestReview.isPending ? askedWork : undefined,
			// Read off the mutation rather than mirrored into state, and only while the open run is
			// on the work that was asked about: the next accepted ask replaces the result, so the
			// alert clears itself, and a run opened on other work never shows a refusal about this
			// one.
			refusal:
				requestReview.data?.status === "REFUSED" &&
				askedWork &&
				work &&
				sameReviewedWork(askedWork, work)
					? requestReview.data
					: undefined,
		},
	};
}

/**
 * The open run as one state; with no level open there is nothing to read and nothing to wait for.
 * The work's review activity is a second request that starts only once the run has named the work,
 * so it resolves after the run does: the level shows the run as soon as it has it and carries the
 * activity's own state beside it, so the practice table can wait for it rather than claim the run
 * reached no practice while the answer is still on its way.
 */
function openOf(
	reviewId: string | undefined,
	query: ReturnType<typeof useQuery<ProfileReviewRunDetail>>,
	traceQuery: Parameters<typeof queryLoadState>[0] & { data?: GetArtifactTraceResponse },
): ProfileReviewRunState {
	if (reviewId === undefined) {
		return { status: "ready", observations: [], traceState: { status: "ready" } };
	}
	const state = queryLoadState(query);
	if (state.status !== "ready") {
		return state;
	}
	return {
		status: "ready",
		run: query.data?.run,
		observations: query.data?.observations ?? [],
		trace: traceQuery.data,
		traceState: queryLoadState(traceQuery),
	};
}
