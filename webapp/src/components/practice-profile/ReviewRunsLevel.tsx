import type { ReactNode } from "react";

import type { ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { ALL_OPTION, FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { TraceKindFilter } from "@/components/practice-trace/TraceKindFilter";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { ARTIFACT_KIND_VALUES } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { REVIEWS_OF_YOUR_WORK } from "./practice-profile-search";
import { holdsEveryRun, runPositionsOnWork } from "./review-run-groups";
import {
	ALL_TIME_LABEL,
	RUN_TIMEFRAME_LABELS,
	RUN_TIMEFRAMES,
	type RunTimeframe,
} from "./review-run-timeframes";
import { ReviewRunsTable, ReviewRunsTableSkeleton } from "./ReviewRunsTable";

export interface ReviewRunsLevelProps {
	/** True when a level sits under this one; the practice profile opens it from the header's chip. */
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	/** The runs loaded so far, newest first. */
	runs: ProfileReviewRun[];
	/** Opens one run as the level over this one. */
	onOpenRun?: (reviewId: string) => void;
	/** The run whose level is open over this one; its row keeps a bar on its leading edge. */
	openReviewId?: string;
	/** The kind of work the list is narrowed to, or `undefined` for all of it. */
	kind?: string;
	onKindChange?: (kind: string | undefined) => void;
	/** How far back the list reaches, or `undefined` for every run there is. */
	since?: RunTimeframe;
	onSinceChange?: (since: RunTimeframe | undefined) => void;
	/** Asks for a review of one piece of work now, from the row that offers it. */
	onReviewNow?: (run: ProfileReviewRun) => void;
	/** The work an ask is in flight about, so only its own rows say so. */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	/** Earlier runs exist beyond the pages loaded so far. */
	hasMore?: boolean;
	isLoadingMore?: boolean;
	onLoadMore?: () => void;
	/** Why the last page of earlier runs did not arrive; the ones already loaded stand. */
	loadMoreError?: unknown;
	/** How many rows the table draws while its first page loads. */
	skeletonRows: number;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

/**
 * Every review run on the reader's own work as a level over the practice profile, newest first,
 * under a heading per day. A row opens the run as the next level, so dismissing the run returns
 * here, and each row also opens the work itself and — where the reader may ask — starts another
 * review of it. Reading to the end of the list loads the runs before it.
 */
export function ReviewRunsLevel({
	nested,
	path,
	runs,
	onOpenRun,
	openReviewId,
	kind,
	onKindChange,
	since,
	onSinceChange,
	onReviewNow,
	requesting,
	hasMore = false,
	isLoadingMore = false,
	onLoadMore,
	loadMoreError,
	skeletonRows,
	isLoading,
	error,
	onRetry,
}: ReviewRunsLevelProps) {
	// No endpoint enumerates the kinds, so the choices are the ones this build knows plus any the
	// list shows — and always the active filter, so a filter arriving by link can be seen and cleared.
	const kinds = [
		...new Set([
			...ARTIFACT_KIND_VALUES,
			...runs.map((run) => run.reviewedWork.kind),
			...(hasText(kind) ? [kind] : []),
		]),
	];

	let body: ReactNode;
	if (error != null) {
		body = (
			<QueryErrorAlert
				error={error}
				title="Could not load reviews of your work"
				onRetry={onRetry}
			/>
		);
	} else if (isLoading) {
		body = <ReviewRunsTableSkeleton rows={skeletonRows} />;
	} else {
		body = (
			<ReviewRunsTable
				runs={runs}
				onOpenRun={onOpenRun}
				openReviewId={openReviewId}
				onReviewNow={onReviewNow}
				requesting={requesting}
				// A row says which run of its work it is only where the list holds every run of
				// that work; a page or a filter would make the total a claim.
				positions={
					holdsEveryRun({ isLoading, error, hasMore, kind, since })
						? runPositionsOnWork(runs)
						: undefined
				}
				earlier={
					onLoadMore && {
						hasMore,
						isLoading: isLoadingMore,
						error: loadMoreError,
						onLoadMore,
					}
				}
			/>
		);
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
						Every review run on your work, newest first. Only you can see this.
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-3 pt-2">
				{(onKindChange !== undefined || onSinceChange !== undefined) && (
					<FilterToolbar
						hasFilter={hasText(kind) || since !== undefined}
						onReset={() => {
							onKindChange?.(undefined);
							onSinceChange?.(undefined);
						}}
					>
						{onKindChange && <TraceKindFilter kinds={kinds} value={kind} onChange={onKindChange} />}
						{onSinceChange && <RunTimeframeFilter value={since} onChange={onSinceChange} />}
					</FilterToolbar>
				)}
				{body}
			</DrawerBody>
		</>
	);
}

/**
 * How far back the list reaches. "All time" is the resting choice, so the control never withholds a
 * run the reader could reach by scrolling on once.
 */
function RunTimeframeFilter({
	value,
	onChange,
}: {
	value: RunTimeframe | undefined;
	onChange: (since: RunTimeframe | undefined) => void;
}) {
	const items = [
		{ value: ALL_OPTION, label: ALL_TIME_LABEL },
		...RUN_TIMEFRAMES.map((timeframe) => ({
			value: timeframe,
			label: RUN_TIMEFRAME_LABELS[timeframe],
		})),
	];
	return (
		<div className="flex min-w-0 items-center gap-2">
			<Label
				id="run-timeframe-label"
				htmlFor="run-timeframe"
				className="shrink-0 text-muted-foreground"
			>
				Timeframe
			</Label>
			<Select
				items={items}
				value={value ?? ALL_OPTION}
				onValueChange={(next) => {
					const chosen = RUN_TIMEFRAMES.find((candidate) => candidate === next);
					onChange(chosen);
				}}
			>
				<SelectTrigger id="run-timeframe" className="w-44 max-w-full">
					<SelectValue placeholder={ALL_TIME_LABEL} />
				</SelectTrigger>
				<SelectContent aria-labelledby="run-timeframe-label">
					{items.map((item) => (
						<SelectItem key={item.value} value={item.value}>
							{item.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</div>
	);
}
