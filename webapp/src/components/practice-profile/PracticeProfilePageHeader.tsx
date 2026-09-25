import { ArrowRightIcon, LayersIcon } from "lucide-react";

import type { ReviewRunRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import type { StandingCounts } from "@/components/practice-vocabulary/standing-counts";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

import { ALL_PRACTICE_GROUPS_LEVEL } from "./practice-profile-search";

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
	isLoading?: boolean;
}

/**
 * What a first visit needs to know, in the page's own voice: what is reviewed and how a piece of
 * feedback ends. Everything below the fold is the record this sentence introduces.
 */
const INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it observes lands here, and a piece of feedback resolves once your work comes back clean.";

export function PracticeProfilePageHeader({
	latestRun,
	counts,
	practiceCount,
	groupCount,
	isLoading = false,
}: PracticeProfilePageHeaderProps) {
	return (
		<header className="flex flex-col gap-3">
			<div className="flex min-w-0 flex-col gap-2">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
					<h1 className="text-2xl font-semibold tracking-tight">Practice profile</h1>
					{isLoading ? (
						<Skeleton className="h-5 w-64 rounded-full" />
					) : (
						latestRun && <LatestRunChip run={latestRun} />
					)}
				</div>
				<p className="max-w-2xl text-sm text-muted-foreground">{INTRO}</p>
			</div>
			{/* One link names the destination and covers the card with a pseudo-element, so the whole
			    card is the pointer path while the words stay the keyboard path. The words turn mentor
			    blue while the card is hovered, as every inline link on the practice surfaces does on
			    its own hover. */}
			<StandingSummaryBox
				layout="fill"
				label={
					practiceCount === 0 ? "No practices set up yet" : countLabel(practiceCount, groupCount)
				}
				counts={counts}
				isLoading={isLoading}
				className="group/panel relative transition-colors hover:bg-sidebar"
			>
				<InlineLink
					render={<DetailStackLink entry={ALL_PRACTICE_GROUPS_LEVEL} />}
					className="inline-flex w-full items-center gap-1.5 text-sm font-medium whitespace-nowrap group-hover/panel:text-mentor group-hover/panel:underline after:absolute after:inset-0 sm:w-auto sm:shrink-0 sm:self-center"
				>
					See all practice groups
					<ArrowRightIcon
						className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/panel:translate-x-0.5"
						aria-hidden
					/>
				</InlineLink>
			</StandingSummaryBox>
		</header>
	);
}

function LatestRunChip({ run }: { run: ReviewRunRef }) {
	const at = asDate(run.at);
	const { label, url } = run.reviewedWork;
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
					<InlineLink href={url} external>
						{label}
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
