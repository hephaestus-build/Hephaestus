import type { ReactNode } from "react";

import type { ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { ACTIVITY_RANGE_DEFS } from "@/components/activity/activity-range";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SelectFilter } from "@/components/common/SelectFilter";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { TraceKindFilter } from "@/components/practice-trace/TraceKindFilter";
import type { ReviewRunFeedState } from "@/components/profile/review-runs";
import { EarlierReviews } from "@/components/profile/ReviewRunFeed";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import {
	PROFILE_REVIEWS_PAGE_SIZE,
	REVIEW_TIMEFRAMES,
	REVIEWS_OF_YOUR_WORK,
	type ReviewTimeframe,
} from "./practice-profile-search";
import { ReviewRunsTable, ReviewRunsTableSkeleton } from "./ReviewRunsTable";

const TIMEFRAME_OPTIONS = REVIEW_TIMEFRAMES.map((value) => ({
	value,
	label: ACTIVITY_RANGE_DEFS[value].label,
}));

export interface ProfileReviewsLevelProps {
	nested?: boolean;
	path: LevelPath;
	feed: ReviewRunFeedState<ProfileReviewRun>;
	/**
	 * Each review's position among the reviews of its work, by review id; absent while the feed does
	 * not hold every review, so no row claims a count it cannot see.
	 */
	positions?: Map<string, number>;
	onOpenReview: (reviewId: string) => void;
	/** The review whose level is open over this one. */
	openReviewId?: string;
	/** The kind of work the list is narrowed to, or `undefined` for all of it. */
	kind?: string;
	onKindChange: (kind: string | undefined) => void;
	/** How far back the list reaches, or `undefined` for every review. */
	since?: ReviewTimeframe;
	onSinceChange: (since: ReviewTimeframe | undefined) => void;
	/** Offered only on the rows whose `mayRequest` allows it. */
	onReviewNow?: (work: ReviewedWorkRef) => void;
	/** The work an ask is in flight about. */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
}

/**
 * The reviews that recorded something about the reader's own work, newest first, as a level over
 * the practice profile. The server lists a review once it records something about the reader, so
 * one still running may not be here yet.
 */
export function ProfileReviewsLevel({
	nested,
	path,
	feed,
	positions,
	onOpenReview,
	openReviewId,
	kind,
	onKindChange,
	since,
	onSinceChange,
	onReviewNow,
	requesting,
}: ProfileReviewsLevelProps) {
	const runs = feed.status === "ready" ? feed.runs : [];
	const filtered = hasText(kind) || since !== undefined;

	let body: ReactNode;
	switch (feed.status) {
		case "error": {
			body = (
				<QueryErrorAlert
					error={feed.error}
					title="We could not load reviews of your work"
					onRetry={feed.onRetry}
				/>
			);
			break;
		}
		case "loading": {
			body = <ReviewRunsTableSkeleton rows={PROFILE_REVIEWS_PAGE_SIZE} />;
			break;
		}
		case "ready": {
			body = (
				<>
					<ReviewRunsTable
						runs={feed.runs}
						filtered={filtered}
						onOpenReview={onOpenReview}
						openReviewId={openReviewId}
						onReviewNow={onReviewNow}
						requesting={requesting}
						positions={positions}
					/>
					<EarlierReviews {...feed} loadingRow={<Skeleton className="h-12 w-full" />} />
				</>
			);
			break;
		}
	}

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<DetailPath {...path} current="Reviews" />
					<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
						{REVIEWS_OF_YOUR_WORK}
					</DrawerTitle>
					<DrawerDescription className="max-w-2xl">
						Reviews that recorded something about your work, newest first. A review that is still
						running appears once it records something.
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-3 pt-2">
				<FilterToolbar
					hasFilter={filtered}
					onReset={() => {
						onKindChange(undefined);
						onSinceChange(undefined);
					}}
				>
					<TraceKindFilter
						seen={runs.map((run) => run.reviewedWork.kind)}
						value={kind}
						onChange={onKindChange}
					/>
					<SelectFilter
						label="Timeframe"
						allLabel="All time"
						options={TIMEFRAME_OPTIONS}
						value={since}
						onChange={onSinceChange}
					/>
				</FilterToolbar>
				{body}
			</DrawerBody>
		</>
	);
}
