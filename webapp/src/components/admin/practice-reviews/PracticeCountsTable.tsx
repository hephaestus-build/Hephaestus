import { Link } from "@tanstack/react-router";
import { ScanSearchIcon } from "lucide-react";
import { useId } from "react";

import { cn } from "cn";
import type { Practice, PracticeReviewCounts } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { reviewableByHephaestus } from "@/components/admin/practices/practice-autonomy/practice-autonomy-model";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { OUTCOME_COUNT_NOUNS } from "@/components/practice-vocabulary/outcome-defs";
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
import { capitalise } from "@/lib/text";

import { practiceLevel } from "./review-levels";
import { familyCount, feedbackSlots, observationSlots, slotsTotal } from "./review-outcomes";
import type { OverviewRegionState } from "./review-states";

export interface PracticeCountsTableProps {
	workspaceSlug: string;
	state: OverviewRegionState;
	/**
	 * The workspace's practices, to say how many that could have been reviewed recorded nothing;
	 * absent while they load, when the table says nothing about them.
	 */
	practices:
		| readonly Pick<
				Practice,
				"slug" | "autonomy" | "automatedReviewPolicy" | "automatedReviewWithdrawal"
		  >[]
		| undefined;
}

const SKELETON_ROWS = 4;

/** Below the table's `@md` the practice level carries these; the name and the total stay. */
const SECONDARY = "hidden @md:table-cell";

/**
 * Each practice the reviews checked in the range, busiest first, as plain counts with what they are
 * out of: a share without its whole reads as a verdict. A practice opens its own level, where the
 * counts link to their rows. Practices are ranked by volume; people never are (ADR 0045).
 */
export function PracticeCountsTable({ workspaceSlug, state, practices }: PracticeCountsTableProps) {
	const markedIncorrectNote = useId();
	// Another range's empty answer, standing in while this one loads, says nothing about this one:
	// the rows wait in their shape instead of claiming the range is empty.
	const recorded =
		state.status === "ready" && !(state.stale && state.overview.practices.length === 0)
			? state.overview.practices
			: undefined;
	const quiet =
		recorded &&
		practices?.filter(
			(practice) =>
				practice.autonomy.effective !== "OFF" &&
				reviewableByHephaestus(practice) &&
				!recorded.some((counts) => counts.practiceSlug === practice.slug),
		).length;
	if (recorded?.length === 0) {
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
		<div className="@container rounded-xl border bg-card">
			<Table
				aria-busy={state.status === "loading" || state.stale || undefined}
				className={cn(state.status === "ready" && state.stale && STALE)}
			>
				<TableHeader>
					<TableRow variant="static">
						<TableHead>Practice</TableHead>
						<TableHead numeric className="text-right">
							Observations
						</TableHead>
						<TableHead numeric className={cn(SECONDARY, "text-right")}>
							{capitalise(OUTCOME_COUNT_NOUNS.NEGATIVE.other)}
						</TableHead>
						<TableHead
							numeric
							className={cn(SECONDARY, "text-right")}
							aria-describedby={markedIncorrectNote}
						>
							{MARKED_INCORRECT_DEF.label}
						</TableHead>
						<TableHead numeric className={cn(SECONDARY, "text-right")}>
							Feedback delivered
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{recorded
						? recorded.map((counts) => (
								<PracticeCountsRow key={counts.practiceSlug} counts={counts} />
							))
						: Array.from({ length: SKELETON_ROWS }, (_, index) => (
								<TableRow key={index} variant="static" aria-hidden>
									<TableCell>
										<Skeleton className="h-4 w-48" />
									</TableCell>
									<TableCell>
										<Skeleton className="ml-auto h-4 w-8" />
									</TableCell>
									{Array.from({ length: 3 }, (__, cell) => (
										<TableCell key={cell} className={SECONDARY}>
											<Skeleton className="ml-auto h-4 w-12" />
										</TableCell>
									))}
								</TableRow>
							))}
					{quiet !== undefined && quiet > 0 && (
						<TableRow variant="static">
							<TableCell colSpan={5} className="whitespace-normal text-muted-foreground">
								{/* The count opens nothing it counts, so it is words; the way to act on it is its own link. */}
								{quiet} {quiet === 1 ? "practice" : "practices"} recorded nothing in this range.{" "}
								<InlineLink
									className="font-medium"
									render={
										<Link to="/w/$workspaceSlug/admin/practices" params={{ workspaceSlug }} />
									}
								>
									Open Practice setup
								</InlineLink>
							</TableCell>
						</TableRow>
					)}
				</TableBody>
			</Table>
			<p
				id={markedIncorrectNote}
				className="hidden border-t px-3 py-2 text-xs text-muted-foreground @md:block"
			>
				{MARKED_INCORRECT_DEF.label}: {MARKED_INCORRECT_DEF.note}
			</p>
		</div>
	);
}

/** "3 of 12", muted when there is none of it. */
function OutOf({ part, whole }: { part: number; whole: number }) {
	return (
		<span className={cn(part === 0 && "text-muted-foreground")}>
			{part} of {whole}
		</span>
	);
}

function PracticeCountsRow({ counts }: { counts: PracticeReviewCounts }) {
	const observations = slotsTotal(observationSlots(counts.observations));
	const feedback = slotsTotal(feedbackSlots(counts.feedback));
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
			<TableCell numeric className="text-right">
				{observations}
			</TableCell>
			<TableCell numeric className={cn(SECONDARY, "text-right")}>
				<OutOf part={counts.observations.problems} whole={observations} />
			</TableCell>
			<TableCell numeric className={cn(SECONDARY, "text-right")}>
				<OutOf part={counts.observationsInvalidated} whole={observations} />
			</TableCell>
			<TableCell numeric className={cn(SECONDARY, "text-right")}>
				{feedback === 0 ? (
					<span className="text-muted-foreground">None</span>
				) : (
					<OutOf part={familyCount(counts.feedback, "DELIVERED")} whole={feedback} />
				)}
			</TableCell>
		</TableRow>
	);
}
