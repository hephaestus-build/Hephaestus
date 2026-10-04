import { PulseIcon } from "@primer/octicons-react";
import type { ReactNode } from "react";

import type { PracticeGroupReviewRun } from "@/api/types.gen";
import { InfiniteListEnd } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { rendersContent } from "@/lib/react-node";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import type { ReviewRunFeedState } from "./review-runs";
import { ReviewRunTimeline, type ReviewRunTimelineProps } from "./ReviewRunTimeline";

export interface ReviewRunFeedProps extends Pick<
	ReviewRunTimelineProps,
	"observations" | "showPracticeName" | "initiallyOpen"
> {
	feed: ReviewRunFeedState;
	/**
	 * The runs this surface shows, when they are not simply the feed's — a practice's own level
	 * narrows them to its observations.
	 */
	runs?: PracticeGroupReviewRun[];
	/** How many run cards the skeleton draws, so nothing jumps when the real ones land. */
	skeletonRows: number;
	/** Why there is nothing here, in this surface's words. */
	emptyTitle: string;
	emptyDescription: string;
	/** A way out of a narrowing that left the feed empty. */
	emptyAction?: ReactNode;
}

/**
 * One practice surface's review-run feed: the runs as a timeline, the earlier ones loaded as its
 * end scrolls into view,
 * and the error, loading and empty states around them, in one home so the rail cannot end one way
 * on one surface and another way on the next.
 */
export function ReviewRunFeed({
	feed,
	runs,
	skeletonRows,
	observations,
	showPracticeName,
	initiallyOpen,
	emptyTitle,
	emptyDescription,
	emptyAction,
}: ReviewRunFeedProps) {
	if (feed.status === "error") {
		return (
			<QueryErrorAlert
				error={feed.error}
				title="We could not load reviews"
				onRetry={feed.onRetry}
			/>
		);
	}
	if (feed.status === "loading") {
		return <ReviewRunFeedSkeleton rows={skeletonRows} />;
	}
	const shown = runs ?? feed.runs;
	// A narrowing can leave the pages read so far empty while earlier ones still hold its runs, so
	// the empty state, which says nothing reached this surface at all, waits for the last page.
	if (shown.length === 0 && feed.hasMore) {
		return (
			<div className="flex flex-col items-start gap-2">
				<p className="text-sm text-muted-foreground">
					The latest reviews have no observations here.
				</p>
				{emptyAction}
				<EarlierReviews {...feed} loadingRow={RUN_CARD_SKELETON} />
			</div>
		);
	}
	if (shown.length === 0) {
		return (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<PulseIcon />
					</EmptyMedia>
					<EmptyTitle>{emptyTitle}</EmptyTitle>
					<EmptyDescription>{emptyDescription}</EmptyDescription>
				</EmptyHeader>
				{rendersContent(emptyAction) && <EmptyContent>{emptyAction}</EmptyContent>}
			</Empty>
		);
	}
	return (
		<>
			<ReviewRunTimeline
				runs={shown}
				observations={observations}
				showPracticeName={showPracticeName}
				initiallyOpen={initiallyOpen}
				continues={feed.hasMore}
			/>
			<EarlierReviews {...feed} loadingRow={RUN_CARD_SKELETON} />
		</>
	);
}

/** One run card's shape, for the feed's skeleton and for the page that loads after it. */
const RUN_CARD_SKELETON = <Skeleton className="h-24 w-full" />;

/**
 * The pages before these, in the words every review feed uses: they load as the end of the list
 * scrolls into view, and a press asks for them too. A failed load keeps what was already read and
 * says so beside the press that asks again.
 */
export function EarlierReviews({ loadingRow, ...more }: MorePages & { loadingRow: ReactNode }) {
	return (
		<InfiniteListEnd
			{...more}
			moreLabel="View earlier reviews"
			failedLabel="We could not load earlier reviews."
			loadingRow={loadingRow}
		/>
	);
}

/** One block per run card the feed will show, so the surface does not jump when they land. */
export function ReviewRunFeedSkeleton({ rows }: { rows: number }) {
	return (
		<div className="flex flex-col gap-2.5" aria-busy="true">
			<span className="sr-only">Loading reviews…</span>
			{Array.from({ length: rows }, (_, index) => (
				<Skeleton key={index} className="h-24 w-full" />
			))}
		</div>
	);
}
