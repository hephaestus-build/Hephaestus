import { PulseIcon } from "@primer/octicons-react";
import type { ReactNode } from "react";

import type { PracticeGroupReviewRun } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
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
 * One practice surface's review-run feed: the runs as a timeline, the earlier ones a press away,
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
				title="Could not load review runs"
				onRetry={feed.onRetry}
			/>
		);
	}
	if (feed.status === "loading") {
		return <ReviewRunFeedSkeleton rows={skeletonRows} />;
	}
	const shown = runs ?? feed.runs;
	const loadMore = feed.hasMore && (
		<Button
			type="button"
			variant="link"
			size="inline"
			className="w-fit text-sm"
			onClick={feed.onLoadMore}
			disabled={feed.isLoadingMore}
		>
			{feed.isLoadingMore ? "Loading…" : "View earlier reviews"}
		</Button>
	);
	// A narrowing can leave the pages read so far empty while earlier ones still hold its runs, so
	// the empty state, which says nothing reached this surface at all, waits for the last page.
	if (shown.length === 0 && feed.hasMore) {
		return (
			<div className="flex flex-col items-start gap-2">
				<p className="text-sm text-muted-foreground">Nothing here in the latest reviews.</p>
				<div className="flex flex-wrap items-center gap-3">
					{emptyAction}
					{loadMore}
				</div>
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
			{loadMore}
		</>
	);
}

/** One block per run card the feed will show, so the surface does not jump when they land. */
export function ReviewRunFeedSkeleton({ rows }: { rows: number }) {
	return (
		<div className="flex flex-col gap-2.5" aria-busy="true">
			<span className="sr-only">Loading review runs</span>
			{Array.from({ length: rows }, (_, index) => (
				<Skeleton key={index} className="h-24 w-full" />
			))}
		</div>
	);
}
