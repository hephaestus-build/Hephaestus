import { cn } from "cn";
import { ChevronDownIcon, ChevronRightIcon } from "lucide-react";
import { useState } from "react";
import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import { getGroupVisual } from "@/components/admin/practice-catalog/group-visuals";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { statusToneClass, statusValues } from "@/components/practice-vocabulary/status-def";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { artifactKindCountLabel, artifactKindIcon } from "@/lib/artifact-kinds";
import {
	PracticeGroupStandingRing,
	STANDING_LEGEND,
	summarizePracticeStandings,
} from "./PracticeGroupStandingRing";
import { PracticeTrendChip } from "./PracticeTrendChip";

const COLLAPSED_GROUP_COUNT = 3;
const STANDING_ORDER = statusValues(PRACTICE_GROUP_STANDING_DEFS);

export interface PracticeGroupStandingSectionProps {
	groups: PracticeGroup[];
	standings: Record<string, PracticeGroupStanding | undefined>;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	onOpenDetails?: (group: PracticeGroup) => void;
	practicesByGroup?: Record<string, PracticeStanding[] | undefined>;
}

export function PracticeGroupStandingCard({
	groups,
	standings,
	isLoading,
	error,
	onRetry,
	onOpenDetails,
	practicesByGroup,
}: PracticeGroupStandingSectionProps) {
	const [showAll, setShowAll] = useState(false);

	if (isLoading) {
		return <Skeleton className="h-40 w-full" data-testid="practice-group-standing-loading" />;
	}
	if (error) {
		return (
			<QueryErrorAlert
				error={error}
				title="Could not load your practice-group standings"
				onRetry={onRetry}
			/>
		);
	}
	if (groups.length === 0) {
		return <p className="text-sm text-muted-foreground">No practice groups are configured yet.</p>;
	}

	const orderedGroups = [...groups].sort((left, right) => {
		const leftStanding = standings[left.slug]?.standing ?? "NOT_OBSERVED";
		const rightStanding = standings[right.slug]?.standing ?? "NOT_OBSERVED";
		return STANDING_ORDER.indexOf(leftStanding) - STANDING_ORDER.indexOf(rightStanding);
	});
	const collapsible = orderedGroups.length > COLLAPSED_GROUP_COUNT;
	const visibleGroups = showAll ? orderedGroups : orderedGroups.slice(0, COLLAPSED_GROUP_COUNT);
	const showsRing = Object.values(practicesByGroup ?? {}).some((practices) => practices?.length);

	return (
		<section className="flex flex-col gap-3" aria-labelledby="practice-groups-heading">
			<div className="grid gap-2">
				<h2 id="practice-groups-heading" className="text-lg font-semibold">
					Practice groups
				</h2>
				<p className="text-sm text-muted-foreground">
					Related practices reviewed across your day-to-day work.
				</p>
				{showsRing && (
					<ul
						aria-label="Practice standing colours"
						className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground"
					>
						{STANDING_LEGEND.map((segment) => (
							<li key={segment.standing} className="flex items-center gap-1.5">
								<span
									className={cn("size-2 rounded-full bg-current", segment.colorClass)}
									aria-hidden
								/>
								{segment.label}
							</li>
						))}
					</ul>
				)}
			</div>

			<div id="practice-group-grid" className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
				{visibleGroups.map((group) => {
					const groupStanding = standings[group.slug];
					const presentation =
						PRACTICE_GROUP_STANDING_DEFS[groupStanding?.standing ?? "NOT_OBSERVED"];
					const practices = practicesByGroup?.[group.slug] ?? [];
					const breakdown = summarizePracticeStandings(practices);
					const { Icon, pill } = getGroupVisual(group.icon, group.color);
					return (
						<Card key={group.slug} className="relative flex h-full flex-col overflow-hidden pt-0">
							<CardHeader className="gap-2 border-b bg-muted/40 pt-4">
								<div className="flex items-center gap-3">
									<span
										className={cn(
											"flex size-10 shrink-0 items-center justify-center rounded-lg",
											pill,
										)}
									>
										<Icon className="size-5" aria-hidden />
									</span>
									<h3 className="min-w-0 flex-1 text-lg font-medium leading-snug">{group.name}</h3>
									{onOpenDetails && (
										<ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />
									)}
								</div>
							</CardHeader>
							<CardContent className="flex flex-1 flex-col gap-3">
								<div className="flex flex-1 items-center gap-4">
									<div className="grid min-w-0 flex-1 gap-1.5">
										<StatusBadge def={presentation} className="w-fit" />
										<p className="text-sm text-muted-foreground">{presentation.description}</p>
										{groupStanding?.direction && groupStanding.trendSupport && (
											<PracticeTrendChip
												direction={groupStanding.direction}
												support={groupStanding.trendSupport}
												scope="group"
												className="relative z-10"
											/>
										)}
									</div>
									{practices.length > 0 && <PracticeGroupStandingRing practices={practices} />}
								</div>
								{breakdown.length > 0 && (
									<ul className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
										{breakdown.map(({ standing, count, label }) => {
											const StandingIcon = PRACTICE_GROUP_STANDING_DEFS[standing].icon;
											return (
												<li key={standing} className="flex items-center gap-1">
													<StandingIcon
														className={cn(
															"size-3.5 shrink-0",
															statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant),
														)}
														aria-hidden
													/>
													{count} {label.toLowerCase()}
												</li>
											);
										})}
									</ul>
								)}
								{groupStanding && groupStanding.sources.length > 0 && (
									<div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
										<span>Based on</span>
										<ul className="flex flex-wrap items-center gap-x-3 gap-y-1">
											{groupStanding.sources.map(({ workKind, count }) => {
												const SourceIcon = artifactKindIcon(workKind);
												return (
													<li key={workKind} className="flex items-center gap-1">
														<SourceIcon className="size-3.5 shrink-0" aria-hidden />
														{artifactKindCountLabel(workKind, count)}
													</li>
												);
											})}
										</ul>
									</div>
								)}
							</CardContent>
							{onOpenDetails && (
								<button
									type="button"
									aria-label={`See details about ${group.name}`}
									onClick={() => onOpenDetails(group)}
									className="absolute inset-0 rounded-[inherit] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
								/>
							)}
						</Card>
					);
				})}
			</div>

			{collapsible && (
				<Button
					type="button"
					variant="outline"
					size="sm"
					className="w-fit self-center"
					aria-expanded={showAll}
					aria-controls="practice-group-grid"
					onClick={() => setShowAll((value) => !value)}
				>
					{showAll ? "Show fewer groups" : `Show all ${orderedGroups.length} practice groups`}
					<ChevronDownIcon className={cn("size-3.5", showAll && "rotate-180")} aria-hidden />
				</Button>
			)}
		</section>
	);
}
