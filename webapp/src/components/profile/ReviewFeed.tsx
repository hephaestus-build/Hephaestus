import { PulseIcon } from "@primer/octicons-react";
import type { ReactNode } from "react";

import { InfiniteListEnd, LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
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

import type { ReviewEntry, ReviewFeedState } from "./reviews";
import { ReviewTimeline, type ReviewTimelineProps } from "./ReviewTimeline";

export interface ReviewFeedProps extends Pick<
	ReviewTimelineProps,
	| "responses"
	| "showPracticeName"
	| "openOnArrival"
	| "selectedResultId"
	| "marksSearchMatches"
	| "renderObservation"
> {
	feed: ReviewFeedState;
	/**
	 * The reviews this surface shows, when they are not simply the feed's — a practice's own level
	 * narrows them to its results.
	 */
	reviews?: ReviewEntry[];
	skeletonRows: number;
	emptyTitle: string;
	emptyDescription: string;
	/** A way out of a narrowing that left the feed empty. */
	emptyAction?: ReactNode;
}

export function ReviewFeed({
	feed,
	reviews,
	skeletonRows,
	responses,
	showPracticeName,
	openOnArrival,
	selectedResultId,
	marksSearchMatches,
	renderObservation,
	emptyTitle,
	emptyDescription,
	emptyAction,
}: ReviewFeedProps) {
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
		return <ReviewFeedSkeleton rows={skeletonRows} />;
	}
	const shown = reviews ?? feed.reviews;
	const loadingMore = feed.isLoadingMore ? LOADING_MORE_ROWS : undefined;
	// Refresh failures must preserve loaded reviews and pending responses, with a stale-data warning.
	const refreshAlert =
		feed.refreshFailure === undefined ? undefined : (
			<QueryErrorAlert
				error={feed.refreshFailure.error}
				title="We could not refresh reviews"
				pending={feed.refreshFailure.pending}
				onRetry={feed.refreshFailure.onRetry}
				className="mb-4"
			/>
		);

	// Filtered loaded pages cannot prove the whole feed empty while earlier pages remain.
	if (shown.length === 0 && feed.hasMore) {
		return (
			<div className="flex flex-col items-start gap-2">
				{refreshAlert}
				<p className="text-sm text-muted-foreground">
					The latest reviews have no observations here.
				</p>
				{emptyAction}
				{/* The earlier reviews will appear here, so their placeholders do. */}
				{loadingMore !== undefined && <ReviewTimeline reviews={[]} loadingMore={loadingMore} />}
				<EarlierReviews {...feed} />
			</div>
		);
	}
	if (shown.length === 0) {
		return (
			<>
				{refreshAlert}
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
			</>
		);
	}
	return (
		<>
			{refreshAlert}
			<ReviewTimeline
				reviews={shown}
				responses={responses}
				showPracticeName={showPracticeName}
				openOnArrival={openOnArrival}
				selectedResultId={selectedResultId}
				marksSearchMatches={marksSearchMatches}
				renderObservation={renderObservation}
				continues={feed.hasMore}
				loadingMore={loadingMore}
			/>
			<EarlierReviews {...feed} />
		</>
	);
}

/** The end of a review list; the list above draws the next page's placeholders itself. */
export function EarlierReviews(more: MorePages) {
	return (
		<InfiniteListEnd
			{...more}
			moreLabel="View earlier reviews"
			failedLabel="We could not load earlier reviews."
		/>
	);
}

export function ReviewFeedSkeleton({ rows }: { rows: number }) {
	return (
		<div className="flex flex-col gap-2.5" aria-busy="true">
			<span className="sr-only">Loading reviews…</span>
			{Array.from({ length: rows }, (_, index) => (
				<Skeleton key={index} className="h-24 w-full" />
			))}
		</div>
	);
}
