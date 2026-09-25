import { ArrowRightIcon, LayersIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewRunRef } from "@/api/types.gen";
import { countsTogether } from "@/components/common/feedback-text";
import { InlineLink } from "@/components/common/InlineLink";
import {
	PracticeGroupStandingRing,
	type StandingCounts,
	StandingCountsList,
} from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

export interface PracticeProfilePageHeaderProps {
	/**
	 * The chip beside the title: when the latest review ran and on what; omitted while no review has
	 * run.
	 */
	latestRun?: ReviewRunRef;
	/** How many practices sit at each standing; the ring and the legend are drawn from these. */
	counts: StandingCounts;
	/**
	 * Every workspace practice has a standing, reviewed or not, so zero here is a workspace with no
	 * practices.
	 */
	practiceCount: number;
	groupCount: number;
	/** Opens the practice-groups table; without a handler the panel is not a control. */
	onSeeAllPractices?: () => void;
	isLoading?: boolean;
	className?: string;
}

/** A document title longer than this is cut in the chip, with the full title on its `title`. */
const WORK_LABEL_MAX = 28;

/**
 * What a first visit needs to know, in the page's own voice: what is reviewed, who sees it, and
 * how a piece of feedback ends. Everything below the fold is the record this sentence introduces.
 */
const INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it observes lands here, for you alone, and a piece of feedback resolves once your work comes back clean.";

export function PracticeProfilePageHeader({
	latestRun,
	counts,
	practiceCount,
	groupCount,
	onSeeAllPractices,
	isLoading = false,
	className,
}: PracticeProfilePageHeaderProps) {
	return (
		<header className={cn("flex flex-col gap-3", className)}>
			<div className="flex min-w-0 flex-col gap-2">
				{isLoading ? (
					<>
						<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
							<Skeleton className="h-8 w-48" />
							<Skeleton className="h-5 w-64 rounded-full" />
						</div>
						<Skeleton className="h-4 w-full max-w-2xl" />
						<Skeleton className="h-4 w-3/4 max-w-2xl" />
					</>
				) : (
					<>
						<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
							<h1 className="text-2xl font-semibold tracking-tight">Practice profile</h1>
							{latestRun && <LatestRunChip run={latestRun} />}
						</div>
						<p className="max-w-2xl text-sm text-muted-foreground">{INTRO}</p>
					</>
				)}
			</div>
			<StandingPanel
				counts={counts}
				label={
					practiceCount === 0 ? "No practices set up yet" : countLabel(practiceCount, groupCount)
				}
				onSeeAll={onSeeAllPractices}
				isLoading={isLoading}
			/>
		</header>
	);
}

interface StandingPanelProps {
	counts: StandingCounts;
	label: string;
	onSeeAll?: () => void;
	isLoading: boolean;
}

/**
 * The hairline card under the title: the ring, what it stands for, and where it leads. One
 * control names the destination and covers the card with a pseudo-element, so the whole card is
 * the pointer path while the words stay the keyboard path and the card wears their focus ring.
 * The words sit in the foreground colour and turn mentor blue only while the card is hovered, as
 * every inline link on the practice surfaces does; blue at rest is the one primary action's.
 */
function StandingPanel({ counts, label, onSeeAll, isLoading }: StandingPanelProps) {
	const disabled = isLoading || onSeeAll === undefined;
	return (
		<div
			className={cn(
				"group/panel relative flex w-full flex-wrap items-center gap-x-6 gap-y-3 rounded-xl border bg-background p-4 transition-colors",
				"has-[[data-panel-link]:focus-visible]:border-ring has-[[data-panel-link]:focus-visible]:ring-3 has-[[data-panel-link]:focus-visible]:ring-ring/50",
				!disabled && "hover:bg-sidebar",
			)}
		>
			{isLoading ? (
				<Skeleton className="size-15 shrink-0 rounded-full" />
			) : (
				<PracticeGroupStandingRing counts={counts} />
			)}
			{/* Three columns: the ring, the label with its legend, and the destination centred on the
			    card's height at the right edge; the legend wraps inside its own column. Below `sm` the
			    destination takes the row under the legend. */}
			<div className="flex min-w-0 flex-1 basis-40 flex-col gap-1.5">
				{isLoading ? (
					<Skeleton className="h-4 w-36" />
				) : (
					<span className="text-xs font-medium text-muted-foreground">{label}</span>
				)}
				{isLoading ? (
					<div className="flex flex-wrap gap-x-5 gap-y-1">
						<Skeleton className="h-5 w-24" />
						<Skeleton className="h-5 w-28" />
						<Skeleton className="h-5 w-28" />
					</div>
				) : (
					<StandingCountsList
						counts={counts}
						size="row"
						aria-label="Practices by standing"
						className="flex-row flex-wrap items-center gap-x-4 gap-y-1"
					/>
				)}
			</div>
			<Button
				variant="link"
				size="inline"
				role="link"
				data-panel-link
				disabled={disabled}
				onClick={onSeeAll}
				className="w-full justify-start text-sm font-medium whitespace-nowrap text-foreground decoration-1 underline-offset-3 group-hover/panel:text-mentor group-hover/panel:underline after:absolute after:inset-0 focus-visible:border-transparent focus-visible:ring-0 sm:w-auto sm:shrink-0 sm:self-center"
			>
				See all practice groups
				<ArrowRightIcon
					className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/panel:translate-x-0.5"
					aria-hidden
				/>
			</Button>
		</div>
	);
}

function LatestRunChip({ run }: { run: ReviewRunRef }) {
	const at = asDate(run.at);
	const { label, url } = run.reviewedWork;
	const cut = label.length > WORK_LABEL_MAX;
	const shownLabel = cut ? `${label.slice(0, WORK_LABEL_MAX).trimEnd()}…` : label;
	return (
		<div className="flex max-w-full">
			<Badge variant="outline" className="max-w-full">
				<LayersIcon className="text-muted-foreground" aria-hidden />
				<span className="font-semibold">Latest run</span>
				{at && (
					<time dateTime={at.toISOString()} className="font-normal text-muted-foreground">
						{formatDayTime(at)}
					</time>
				)}
				<span className="text-border" aria-hidden>
					·
				</span>
				<span className="min-w-0 truncate font-normal text-muted-foreground">
					after{" "}
					<InlineLink href={url} external title={cut ? label : undefined}>
						{shownLabel}
					</InlineLink>
				</span>
			</Badge>
		</div>
	);
}

/** "16 practices in 5 groups": one clause, so both counts are digits as soon as either is. */
function countLabel(practiceCount: number, groupCount: number): string {
	return countsTogether([
		{ n: practiceCount, one: "practice", many: "practices" },
		{ n: groupCount, one: "group", many: "groups" },
	]).join(" in ");
}
