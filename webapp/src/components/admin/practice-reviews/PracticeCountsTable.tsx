import { ScanSearchIcon } from "lucide-react";

import { cn } from "cn";
import type { PracticeReviewCounts } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
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

import { OutcomeBar, OutcomeLegend } from "./OutcomeMix";
import { practiceLevel } from "./review-levels";
import { feedbackSlots, observationSlots, slotsTotal } from "./review-outcomes";
import type { OverviewRegionState } from "./review-states";

export interface PracticeCountsTableProps {
	workspaceSlug: string;
	state: OverviewRegionState;
}

const SKELETON_ROWS = 4;

/**
 * Each practice the reviews checked in the range, busiest first, with how its observations turned
 * out and what feedback cited it: where a noisy or a quiet practice shows. A practice opens its own
 * level, where the counts link to their rows and the practice to Practice setup. Practices are
 * ranked by volume; people never are (ADR 0045).
 */
export function PracticeCountsTable({ workspaceSlug, state }: PracticeCountsTableProps) {
	if (state.status === "ready" && state.overview.practices.length === 0) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScanSearchIcon />
					</EmptyMedia>
					<EmptyTitle>No practice was checked in this range</EmptyTitle>
					<EmptyDescription>
						Practices appear here once a review records an observation or feedback about them.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<Table
			aria-busy={state.status === "loading" || state.stale || undefined}
			className={cn(state.status === "ready" && state.stale && STALE)}
		>
			<TableHeader>
				<TableRow>
					<TableHead>Practice</TableHead>
					<TableHead className="w-1/3">Observations</TableHead>
					<TableHead className="text-right">Feedback</TableHead>
					<TableHead className="text-right">Delivered</TableHead>
					<TableHead className="text-right">Marked incorrect</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{state.status === "ready"
					? state.overview.practices.map((counts) => (
							<PracticeCountsRow
								key={counts.practiceSlug}
								workspaceSlug={workspaceSlug}
								counts={counts}
							/>
						))
					: Array.from({ length: SKELETON_ROWS }, (_, index) => (
							<TableRow key={index} aria-hidden>
								<TableCell>
									<Skeleton className="h-4 w-48" />
								</TableCell>
								<TableCell>
									<Skeleton className="h-2 w-full" />
								</TableCell>
								{Array.from({ length: 3 }, (__, cell) => (
									<TableCell key={cell}>
										<Skeleton className="ml-auto h-4 w-8" />
									</TableCell>
								))}
							</TableRow>
						))}
			</TableBody>
		</Table>
	);
}

function PracticeCountsRow({
	workspaceSlug,
	counts,
}: {
	workspaceSlug: string;
	counts: PracticeReviewCounts;
}) {
	const slots = observationSlots(counts.observations);
	return (
		// `relative` anchors the name link's stretched hit area, so the whole row opens the practice.
		<TableRow className="relative">
			<TableCell className="max-w-72">
				<span className="flex min-w-0 items-center gap-2">
					{counts.group && (
						<GroupPill
							size="sm"
							slug={counts.group.slug}
							name={counts.group.name}
							icon={counts.group.icon}
							color={counts.group.color}
							srLabel
						/>
					)}
					<InlineLink
						className="min-w-0 truncate font-medium after:absolute after:inset-0"
						render={<DetailStackLink entry={practiceLevel(counts.practiceSlug)} />}
					>
						{counts.practiceName}
					</InlineLink>
				</span>
			</TableCell>
			<TableCell>
				<div className="flex flex-col gap-1.5">
					<OutcomeBar slots={slots} className="min-w-24" />
					{/* Words only: the row opens the practice, whose counts open their rows. */}
					<OutcomeLegend
						workspaceSlug={workspaceSlug}
						slots={slots}
						size="sm"
						aria-label={`Observations of ${counts.practiceName}`}
					/>
				</div>
			</TableCell>
			<TableCell className="text-right tabular-nums">
				{slotsTotal(feedbackSlots(counts.feedback))}
			</TableCell>
			<TableCell className="text-right tabular-nums">{counts.feedback.delivered}</TableCell>
			<TableCell
				className={cn(
					"text-right tabular-nums",
					counts.observationsInvalidated === 0 && "text-muted-foreground",
				)}
			>
				{counts.observationsInvalidated}
			</TableCell>
		</TableRow>
	);
}
