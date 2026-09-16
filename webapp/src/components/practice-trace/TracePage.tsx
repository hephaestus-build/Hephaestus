import { Link } from "@tanstack/react-router";
import { ArrowLeftIcon, RadarIcon } from "lucide-react";

import type { GetArtifactTraceResponse, ReviewRequestOutcome } from "@/api/types.gen";
import { MissingRecordEmpty } from "@/components/common/MissingRecordEmpty";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Skeleton } from "@/components/ui/skeleton";

import { TraceHeader } from "./TraceHeader";
import { TracePracticeList } from "./TracePracticeList";
import { TraceRefusalAlert } from "./TraceRefusalAlert";
import { TraceSignalTimeline } from "./TraceSignalTimeline";

export interface TracePageProps {
	workspaceSlug: string;
	/** Resolved by the route from the membership its guard already fetched. */
	canAdminister: boolean;
	trace: GetArtifactTraceResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
	onRequestReview: () => void;
	requestPending: boolean;
	/**
	 * The refused outcome of the last ask, if the last ask was refused. Derived by the route from the
	 * mutation's own result rather than mirrored into state, so a later success clears it by itself.
	 */
	refusal: ReviewRequestOutcome | undefined;
}

/** One piece of work and every practice's answer, the quiet ones included. */
export function TracePage({
	workspaceSlug,
	canAdminister,
	trace,
	isLoading,
	error,
	onRetry,
	onRequestReview,
	requestPending,
	refusal,
}: TracePageProps) {
	const backLink = (
		<Link
			to="/w/$workspaceSlug/reviews"
			params={{ workspaceSlug }}
			className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
		>
			<ArrowLeftIcon className="size-4" aria-hidden />
			Review activity
		</Link>
	);

	if (isLoading) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{backLink}
				<div role="status" className="space-y-3">
					<span className="sr-only">Loading review activity</span>
					<Skeleton className="h-8 w-2/3 max-w-md" />
					<Skeleton className="h-4 w-1/3 max-w-xs" />
					<Skeleton className="h-40 w-full" />
				</div>
			</article>
		);
	}
	if (error) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{backLink}
				<QueryErrorAlert
					error={error}
					title="Couldn't load this work's review activity"
					onRetry={onRetry}
				/>
			</article>
		);
	}
	// Not the alert: no record and no error is a query that never came back, and the alert would read
	// the absent status as a lost connection. See `MissingRecordEmpty`.
	if (!trace) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{backLink}
				<MissingRecordEmpty title="This work's review activity hasn't loaded" onRetry={onRetry} />
			</article>
		);
	}

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			{backLink}

			<header className="min-w-0 space-y-4">
				<TraceHeader
					trace={trace}
					onRequestReview={onRequestReview}
					requestPending={requestPending}
				/>
				{refusal && (
					<TraceRefusalAlert
						refusal={refusal}
						workspaceSlug={workspaceSlug}
						canAdminister={canAdminister}
					/>
				)}
			</header>

			<TraceSignalTimeline
				signals={trace.signals}
				workspaceSlug={workspaceSlug}
				canAdminister={canAdminister}
			/>

			<TracePracticeList
				practices={trace.practices}
				signals={trace.signals}
				artifactKind={trace.artifactKind}
			/>

			<p className="flex items-center gap-1.5 text-xs text-muted-foreground">
				<RadarIcon className="size-3.5 shrink-0" aria-hidden />
				Silence here is always a decision with a reason. If a reason looks wrong, a workspace admin
				can change the practice or its autonomy.
			</p>
		</article>
	);
}
