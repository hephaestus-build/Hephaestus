import { MessageSquareTextIcon } from "lucide-react";

import { cn } from "cn";
import type { ReasonCount } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { definitionFor, statusToneClass, statusValues } from "@/components/common/status-def";
import { Section } from "@/components/layout/Section";
import {
	FEEDBACK_LOCATION_DEFS,
	type FeedbackLocation,
} from "@/components/practice-vocabulary/feedback-location-defs";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

import type { ReviewListTarget } from "./review-outcomes";
import { ReviewListLink } from "./ReviewListLink";

export interface FeedbackDeveloperSummaryProps {
	workspaceSlug: string;
	developerId: string;
	developerName: string | undefined;
	state: PanelState<{ counts: ReasonCount[] }>;
}

interface LocationCount {
	location: FeedbackLocation;
	count: number;
}

export function FeedbackDeveloperSummary({
	workspaceSlug,
	developerId,
	developerName,
	state,
}: FeedbackDeveloperSummaryProps) {
	const who = developerName ?? "this developer";
	return (
		<Section
			level={3}
			title={`Feedback for ${who}`}
			description="Everything for them, by where it appears. Each count opens the feedback it counts."
		>
			<SummaryBody
				workspaceSlug={workspaceSlug}
				developerId={developerId}
				who={who}
				state={state}
			/>
		</Section>
	);
}

function SummaryBody({
	workspaceSlug,
	developerId,
	who,
	state,
}: Omit<FeedbackDeveloperSummaryProps, "developerName"> & { who: string }) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title={`We could not count the feedback for ${who}`}
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return <SummarySkeleton />;
	}
	const rows = locationRows(state.counts);
	if (rows.length === 0) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<MessageSquareTextIcon />
					</EmptyMedia>
					<EmptyTitle>No feedback for {who} yet</EmptyTitle>
					<EmptyDescription>Feedback for them appears here after reviews run.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	const total = rows.reduce((sum, row) => sum + row.count, 0);
	return (
		<div className="@container rounded-xl border bg-card">
			<Table aria-label={`Feedback for ${who} by where it goes`}>
				<TableHeader>
					<TableRow variant="static">
						<TableHead>Where</TableHead>
						<TableHead numeric className="text-right">
							Feedback
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{rows.map((row) => (
						<LocationRow
							key={row.location}
							workspaceSlug={workspaceSlug}
							developerId={developerId}
							row={row}
						/>
					))}
					<TableRow variant="static">
						<TableCell className="font-medium">Everywhere</TableCell>
						<TableCell numeric className="text-right font-medium">
							<CountLink
								workspaceSlug={workspaceSlug}
								count={total}
								target={feedbackTarget(developerId, {})}
							/>
						</TableCell>
					</TableRow>
				</TableBody>
			</Table>
		</div>
	);
}

function locationRows(counts: readonly ReasonCount[]): LocationCount[] {
	return statusValues(FEEDBACK_LOCATION_DEFS).flatMap((location) => {
		const count = counts.find((row) => row.reason === location)?.count ?? 0;
		return count > 0 ? [{ location, count }] : [];
	});
}

function LocationRow({
	workspaceSlug,
	developerId,
	row,
}: {
	workspaceSlug: string;
	developerId: string;
	row: LocationCount;
}) {
	const def = definitionFor(FEEDBACK_LOCATION_DEFS, row.location);
	const Icon = def.icon;
	return (
		<TableRow variant="static">
			<TableCell className="whitespace-normal">
				<span className="inline-flex min-w-0 items-center gap-2">
					<Icon
						aria-hidden
						className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))}
					/>
					<span className="min-w-0 break-words">{def.label}</span>
				</span>
			</TableCell>
			<TableCell numeric className="text-right">
				<CountLink
					workspaceSlug={workspaceSlug}
					count={row.count}
					target={feedbackTarget(developerId, { location: row.location })}
				/>
			</TableCell>
		</TableRow>
	);
}

function CountLink({
	workspaceSlug,
	count,
	target,
}: {
	workspaceSlug: string;
	count: number;
	target: ReviewListTarget;
}) {
	if (count === 0) {
		return <span className="text-muted-foreground">0</span>;
	}
	return (
		<InlineLink
			tone="count"
			render={<ReviewListLink workspaceSlug={workspaceSlug} destination={target} />}
		>
			{count}
		</InlineLink>
	);
}

function feedbackTarget(
	developerId: string,
	search: Extract<ReviewListTarget, { list: "feedback" }>["search"],
): ReviewListTarget {
	return { list: "feedback", search: { developerId, ...search } };
}

function SummarySkeleton() {
	return (
		<div className="space-y-2 rounded-xl border bg-card p-3" aria-hidden>
			{Array.from({ length: 3 }, (_, index) => (
				<Skeleton key={index} className="h-5 w-full" />
			))}
		</div>
	);
}
