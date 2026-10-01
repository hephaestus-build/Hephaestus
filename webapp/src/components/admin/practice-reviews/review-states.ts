import type { PracticeReviewOverview } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";

export type ReviewSectionState<T> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "pending" }
	| { status: "ready"; items: T[]; total: number };

/**
 * How many rows a list holds, read without the rows: `stale` while the previous range's count stands
 * in for the range just chosen.
 */
export type ReviewCountState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; total: number; stale: boolean };

/** The period of the same length before the range, which a total is set against. */
interface PreviousReviewPeriod {
	overview: PracticeReviewOverview;
	/** "the previous 30 days": the period after "than" or "as". */
	name: string;
}

/**
 * The overview once it is in: current, or stale — the previous range's, standing in while the range
 * just chosen loads.
 */
export type PracticeReviewOverviewState = PanelState<{
	overview: PracticeReviewOverview;
	stale: boolean;
}>;

/** The period before, which the totals are set against; failed, they go without a comparison. */
export type PreviousReviewPeriodState = PanelState<{ period: PreviousReviewPeriod }>;

/** The overview as a region under the page draws it: the page reports a failure once, for all of them. */
export type OverviewRegionState = Exclude<PracticeReviewOverviewState, { status: "error" }>;

/** The shape of a paged query this module reads, spelled out rather than taken as a
 * `UseQueryResult` so the element type is inferred from the page's own `content`. */
interface PagedQuery<T> {
	isLoading: boolean;
	isError: boolean;
	error: unknown;
	refetch: () => unknown;
	data?: { content?: T[]; page?: { totalElements?: number } };
}

/**
 * `pending` is the one state a page of results cannot report for itself: an empty answer from a run
 * still in flight means "not yet", and the identical answer from a finished run means "none". The
 * caller knows which, so it passes `stillRunning` in.
 */
export function toSectionState<T>(
	query: PagedQuery<T>,
	stillRunning = false,
): ReviewSectionState<T> {
	if (query.isLoading) {
		return { status: "loading" };
	}
	if (query.isError) {
		return {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	}
	const items = query.data?.content ?? [];
	if (stillRunning && items.length === 0) {
		return { status: "pending" };
	}
	return { status: "ready", items, total: query.data?.page?.totalElements ?? 0 };
}
